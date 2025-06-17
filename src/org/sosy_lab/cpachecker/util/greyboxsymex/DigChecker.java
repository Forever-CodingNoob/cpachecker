package org.sosy_lab.cpachecker.util.greyboxsymex;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;

import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

import org.sosy_lab.java_smt.api.SolverException;
import org.sosy_lab.common.configuration.InvalidConfigurationException;

/**
 *  Very small “grey-box” checker:
 *    – writes one harness C file per unknown-function call,
 *    – runs  kvasir-dtrace + Daikon  to infer likely invariants,
 *    – conjoins invariants with postconditions using JavaSMT,
 *    – returns UNSAT -> spurious error state.
 *
 *  Requires:
 *    – daikon.jar  on $DAIKON_JAR or default “daikon.jar” in working dir
 *    – kvasir-dtrace  on PATH
 *    – a C compiler (gcc) on PATH
 */
@Options(prefix = "digChecker")
public final class DigChecker implements ExternalChecker {

  // ---------- configurable options ----------
  @Option(secure = true,
          description = "Path to daikon.jar")
  private Path daikonJar = Paths.get(System.getenv().getOrDefault("DAIKON_JAR", "daikon.jar")).toAbsolutePath();

  @Option(secure = true,
          description = "command for kvasir-dtrace (front-end to Daikon)")
  private String kvasirCmd = "kvasir-dtrace";

  @Option(secure = true,
          description = "C compiler for the harness")
  private String cc = "gcc";

  // ---------- internal fields ----------
  private final Path outDir;
  private final LogManager logger;
  private final ShutdownNotifier shutdown;
  private final SolverContext ctx;
  private final FormulaManager fmgr;
  private final BooleanFormulaManager bmgr;

  public static final class Factory implements ExternalChecker.Factory {
    private final Configuration config;
    private final LogManager log;
    private final ShutdownNotifier sn;
    public Factory(Configuration c, LogManager l, ShutdownNotifier s) {
      config = c; log = l; sn = s;
    }
    @Override
    public ExternalChecker create(Path dir) throws IOException, InvalidConfigurationException {
      return new DaikonChecker(dir, config, log, sn);
    }
  }

  private DaikonChecker(Path dir, Configuration cfg, LogManager log, ShutdownNotifier sn) throws IOException, InvalidConfigurationException {

    cfg.inject(this);
    outDir   = Files.createDirectories(dir);
    logger   = log;
    shutdown = sn;

    ctx  = SolverContextFactory.createSolverContext(
            cfg, logger, shutdown, SolverContextFactory.Solvers.SMTINTERPOL);
    fmgr = ctx.getFormulaManager();
    bmgr = fmgr.getBooleanFormulaManager();
  }

  /* =================================================================== */
  /*  Main API                                                           */
  /* =================================================================== */

  @Override
  public boolean isPathReachable(
      UnknownFuncCallPrecondition preInfo,
      Set<Constraint>            postConstraints,
      int                        id)
      throws IOException, InterruptedException {

    /* 1. ─ write harness file ───────────────────────────────────────── */
    Path cFile = emitHarness(preInfo, id);

    /* 2. ─ compile & trace with kvasir ─────────────────────────────── */
    String exe = buildAndTrace(cFile, preInfo.getFunctionName(), id);

    /* 3. ─ run Daikon and collect invariants on :::EXIT  ───────────── */
    List<String> invLines = runDaikon(exe);

    /* 4. ─ translate invariants -> BooleanFormula  ─────────────────── */
    BooleanFormula invFormula = extractInvFormula(invLines);

    /* 5. ─ build postcondition formula from Constraints  ───────────── */
    BooleanFormula postFormula =
        bmgr.and(postConstraints.stream()
                  .map(c -> fmgr.parse(c.toString()))   // simplistic printer-parser pair
                  .collect(Collectors.toList()));

    /* 6. ─ SMT check (inv \land post)  ->  SAT? ───────────────────── */
    BooleanFormula combined = bmgr.and(invFormula, postFormula);
    try (ProverEnvironment pe =
        ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      pe.addConstraint(combined);
      boolean unsat = pe.isUnsat();
      logger.log(Level.INFO,
          "DaikonChecker: combined formula is " + (unsat ? "UNSAT" : "SAT"));
      return !unsat;           // SAT -> reachable (keep error)
    } catch (SolverException e) {
      logger.logUserException(Level.WARNING, e, "SMT solver failure, assume reachable");
      return true;             // conservative
    }
  }

  /* =================================================================== */
  /*  Helper: emit C harness                                             */
  /* =================================================================== */

  private Path emitHarness(UnknownFuncCallPrecondition info, int id)
      throws IOException {

    String func   = info.getFunctionName();
    String retT   = info.getReturnType().toString();

    List<String> argTypes = info.getArgumentTypes().stream().map(t -> t.toString()).toList();
    List<String> argExprs = info.getArgumentValues().stream().map(Object::toString).toList();

    Path cFile = outDir.resolve("grey_" + func + "_" + id + ".c");
    try (BufferedWriter w = Files.newBufferedWriter(cFile, UTF_8)) {
      w.write("#include <stdlib.h>\n");
      w.write("#include <stdio.h>\n");
      w.write("void __VERIFIER_assume(int cond){ if(!cond) exit(0);} \n\n");

      /* extern declaration */
      w.write("extern " + retT + " " + func + "(");
      for (int i = 0; i < argTypes.size(); i++) {
        if (i > 0) w.write(", ");
        w.write(argTypes.get(i));
      }
      w.write(");\n\n");

      /* stub (empty, linker will resolve if in lib, else unsat paths ok) */
      w.write(retT + " " + func + "(");
      for (int i = 0; i < argTypes.size(); i++) {
        if (i > 0) w.write(", ");
        w.write(argTypes.get(i) + " a" + i);
      }
      w.write(") {\n  /* grey-box stub: nondet */\n");
      w.write("  " + retT + " rv; return rv; }\n\n");

      /* main */
      w.write("int main(){\n");
      for (int i = 0; i < argTypes.size(); i++) {
        w.write("  " + argTypes.get(i) + " v" + i + " = 0;\n");
      }

      /* pre-constraints as assume */
      // TODO: convert the string representation of type Constraint in C language
      // e.g. <SymEx[NumericValue[number=10]] < SymEx[SymbolicIdentifier[7]]> should be converted into 10 < s7
      for (Constraint c : info.getConstraints()) {
        w.write("  __VERIFIER_assume(" + c.toString() + ");\n");
      }

      /* call */
      w.write("  " + retT + " ret = " + func + "(" +
              String.join(", ", argExprs) + ");\n");

      /* print to avoid optimise-away */
      w.write("  fprintf(stderr, \"\", ret);\n");
      w.write("  return 0;}\n");
    }
    logger.log(Level.INFO, "Harness written: {0}", cFile);
    return cFile;
  }

  /* =================================================================== */
  /*  Helper: compile + kvasir-dtrace                                   */
  /* =================================================================== */

  private String buildAndTrace(Path cFile, String func, int id)
      throws IOException, InterruptedException {

    String exe = cFile.getParent().resolve("grey_" + func + "_" + id + ".out").toString();
    run(cFile.getParent(), "gcc", "-O0", "-gdwarf-2", "-no-pie", "-lm",
        cFile.getFileName().toString(), "-o", exe);
    run(cFile.getParent(), "kvasir-dtrace", exe);
    return exe;
  }

  /* =================================================================== */
  /*  Helper: run Daikon + read lines                                    */
  /* =================================================================== */

  private List<String> runDaikon(String exePath)
      throws IOException, InterruptedException {

    Path exeFile = Paths.get(exePath);
    String base = exeFile.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    Path decls   = outDir.resolve(base + ".decls");
    Path dtrace  = outDir.resolve(base + ".dtrace");
    
    run(outDir, "java", "-cp", daikonJar.toString(), "daikon.Daikon",
        decls.toString(), dtrace.toString());   // default output names
    
    // TODO: extract the preconditions in standard output instead
    Path inv = outDir.resolve(base+".inv");
    if (!Files.exists(inv) && Files.exists(outDir.resolve(base+".inv.gz"))) {
      run(outDir, "gunzip", "-f", base+".inv.gz");
    }
    if (Files.notExists(inv)) return List.of();
    return Files.readAllLines(inv, UTF_8);
  }

  /* =================================================================== */
  /*  Helper: build formula from Daikon lines                            */
  /* =================================================================== */

  private BooleanFormula extractInvFormula(List<String> lines) {
    BooleanFormula f = bmgr.makeTrue();
    for (String l : lines) {
      String t = l.trim();
      if (t.isEmpty() || t.startsWith("This") || t.contains(":::")) continue;
      try {
        f = bmgr.and(f, fmgr.parse(t));
      } catch (Exception ignored) {
        // skip unparsable line
      }
    }
    return f;
  }

  /* =================================================================== */
  /*  Helper: run a command with inherited IO                            */
  /* =================================================================== */

  private static void run(Path dir, String... cmd)
      throws IOException, InterruptedException {

    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getInputStream()))) {
      String line;
      while ((line = r.readLine()) != null) {
        System.out.println(String.join(" ", cmd) + " | " + line);
      }
    }
    if (p.waitFor() != 0) {
      throw new IOException("cmd failed: " + String.join(" ", cmd));
    }
  }
}
