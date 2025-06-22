package org.sosy_lab.cpachecker.util.greyboxsymex;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;

import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicExpression;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.NumeralFormula.*;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

import org.sosy_lab.java_smt.api.SolverException;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;

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
@Options(prefix = "daikonChecker")
public final class DaikonChecker implements ExternalChecker {

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

  @Option(secure = true,
          description = "Greybox object file(s) defining the unknown functions")
  private List<String> greyboxObjectFileStrings = List.of();

  @Option(secure = true,
          description = "Number of SMT samples for Daikon sampling")
  private int numSamples = 200;

  // ---------- internal fields ----------
  private final Path outDir;
  private final LogManager logger;
  private final ShutdownNotifier shutdown;
  private final SolverContext ctx;
  private final FormulaManager fmgr;
  private final BooleanFormulaManager bmgr;
  private final IntegerFormulaManager ifmgr;
  private final RationalFormulaManager rfMgr;

  private List<Path> greyboxObjectFiles;

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
    ifmgr= fmgr.getIntegerFormulaManager();
    rfMgr= fmgr.getRationalFormulaManager();

    greyboxObjectFiles = new ArrayList<>();
    for (String raw : greyboxObjectFileStrings) {
      String s = raw.trim();
      if (s.startsWith("\"") && s.endsWith("\"") && s.length()>1) {
        s = s.substring(1, s.length()-1);
      }
      greyboxObjectFiles.add(Paths.get(s));
    }
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

    /* 1. ─ write harness file and compile it ───────────────────────── */
    logger.log(Level.INFO, "writing harness!");
    Path cFile = emitHarness(preInfo, id);
    String exe = null;
    try{
      exe = compileHarness(cFile);
    } catch (IOException | InterruptedException e) {
      logger.log(Level.WARNING,
          "Harness compilation failed (possibly missing implementation), assuming reachable", e);
      return true;
    }



    /* 2. ─ prepare SMT variables and precondition formula ──────────── */
    Map<String, Formula> varFormulas = new LinkedHashMap<>();
    
    // Pattern to find each SymEx[SymbolicIdentifier[n]]
    Pattern stripSym = Pattern.compile("SymEx\\[SymbolicIdentifier\\[(\\d+)\\]\\]");
    Pattern stripNum = Pattern.compile("SymEx\\[NumericValue\\[number=([^\\]]+)\\]\\]");

    // 2.1 First pass: collect all IDs and determine their C/Solver type to make variables
    for (int i = 0; i < preInfo.getArgumentValues().size(); i++) {
      String argVal = preInfo.getArgumentValues().get(i).toString();
      String argType = preInfo.getArgumentTypes().get(i).toString();
      Matcher m = stripSym.matcher(argVal);
      while (m.find()) {
        String var = "s" + m.group(1) + "_";
        logger.log(Level.INFO, "[+] Found symbol " + var + " in argument " + argVal);
        if (!varFormulas.containsKey(var)) {
          // decide solver‐side type based on argType
          if ("double".equals(argType) || "float".equals(argType)) {
            // floating‐point in C -> rational in JavaSMT
            varFormulas.put(var, rfMgr.makeVariable(var));
          } else {
            // otherwise assume integer
            varFormulas.put(var, ifmgr.makeVariable(var));
          }
        }
      }
    }

    // 2.2 Translate each precondition Constraint into a BooleanFormula
    BooleanFormula preFormula = bmgr.makeTrue();
    for (Constraint c : preInfo.getConstraints()) {
      // Render and strip
      String raw = c.toString();
      logger.log(Level.INFO, "before:", raw);
      raw = stripNum.matcher(raw).replaceAll("$1");
      raw = stripSym.matcher(raw).replaceAll("s$1_");
      logger.log(Level.INFO, "after:", raw);
      // Build a BooleanFormula
      BooleanFormula f = buildConstraintFormula(raw, varFormulas);
      preFormula = bmgr.and(preFormula, f);
    }

    System.out.println("ENTERING STAGE 3");

    /* 3. ─ enumerating models and running kvasir ──────────── */
    //List<Path> decls   = new ArrayList<>();
    //List<Path> dtraces = new ArrayList<>();
    String base = cFile.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    Path decls   = outDir.resolve(base + ".decls");
    Path dtrace  = outDir.resolve(base + ".dtrace");
    //Path dt = outDir.resolve(String.format("grey_%s_%d.dtrace", preInfo.getFunctionName(), id));
    //Path dc = outDir.resolve(String.format("grey_%s_%d.decls", preInfo.getFunctionName(), id));

    try (ProverEnvironment pe = ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      pe.addConstraint(preFormula);

      for (int sample = 0; sample < numSamples; sample++) {
        if (pe.isUnsat()) break;
        Model model = pe.getModel();

        Map<String,String> vals = new HashMap<>();
        for (Map.Entry<String,Formula> e : varFormulas.entrySet()) {
          String var = e.getKey();
          Object val = model.evaluate(e.getValue());
          vals.put(var, val.toString());
        }

        List<String> args = buildArgs(preInfo.getArgumentValues(), vals);
        List<String> kvasirCmdLine = new ArrayList<>();
        kvasirCmdLine.add(kvasirCmd);
        if(sample == 0){
          kvasirCmdLine.add("--decls-file=" + decls);
        }else{
          kvasirCmdLine.add("--no-dyncomp");
          kvasirCmdLine.add("--dtrace-no-decls");
          kvasirCmdLine.add("--dtrace-append");
        }
        kvasirCmdLine.add("--dtrace-file=" + dtrace);
        kvasirCmdLine.add(exe); kvasirCmdLine.addAll(args);
        System.out.println("RIGHT BEFORE CALL TO KVASIR");
        run(outDir, kvasirCmdLine.toArray(new String[0]));
        //dtraces.add(dt);
        //decls.add(dc);

        // block current model for next iteration
        List<BooleanFormula> eqs = new ArrayList<>();
        for (Map.Entry<String,Formula> e : varFormulas.entrySet()) {
          Formula varF = e.getValue();
          BooleanFormula eq;
          if (varF instanceof IntegerFormula) {
            eq = ifmgr.equal((IntegerFormula)varF,
                 ifmgr.makeNumber(vals.get(e.getKey())));
          } else {
            eq = rfMgr.equal((RationalFormula)varF,
                 rfMgr.makeNumber(vals.get(e.getKey())));
          }
          eqs.add(eq);
        }
        pe.addConstraint(bmgr.not(bmgr.and(eqs)));
      }
    } catch (SolverException e) {
      logger.log(Level.WARNING, e, "SMT solver failure, assume reachable");
      return true;
    }

    /* 4. ─  run Daikon ─────────────────────────────────────────────── */
    List<String> cmd = new ArrayList<>();
    cmd.add("java"); cmd.add("-Xmx3600m");
    cmd.add("-cp"); cmd.add(daikonJar.toString()); cmd.add("daikon.Daikon");
    cmd.add(decls.toString()); cmd.add(dtrace.toString());
    cmd.add("--conf_limit 0");
    run(outDir, cmd.toArray(new String[0]));

    Process p = new ProcessBuilder(cmd)
                  .directory(outDir.toFile())
                  .redirectErrorStream(true)
                  .start();

    // read invariants from stdout directly
    List<String> invLines = new ArrayList<>();
    try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), UTF_8))) {
      String line;
      while ((line = in.readLine()) != null) {
        invLines.add(line);
      }
    }
    int exitCode = p.waitFor();
    if (exitCode != 0) {
      logger.log(Level.WARNING, "Daikon exit code: " + exitCode);
    }
    p.waitFor();

    // 5. parse invariants
    BooleanFormula invF = bmgr.makeTrue();
    for (String l : invLines) {
      String t = l.trim();
      if (t.isEmpty() || t.startsWith("This") || t.contains(":::")) continue;
      try { invF = bmgr.and(invF, fmgr.parse(t)); }
      catch (Exception ignored) { 
        logger.log(Level.INFO, "invariants cannot be parsed somehow: ", ignored);
      }
    }

    // 6. postconditions
    BooleanFormula postF = bmgr.makeTrue();
    for (Constraint c : postConstraints) {
      String raw = c.toString()
                  .replaceAll("SymEx\\[NumericValue\\[number=([^\\]]+)\\]\\]", "$1")
                  .replaceAll("SymEx\\[SymbolicIdentifier\\[(\\d+)\\]\\]", "s$1");
      BooleanFormula f = buildConstraintFormula(raw, varFormulas);
      postF = bmgr.and(postF, f);
    }

    // 7. SMT check
    BooleanFormula combined = bmgr.and(invF, postF);
    try (ProverEnvironment pe = ctx.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      pe.addConstraint(combined);
      boolean unsat = pe.isUnsat();
      logger.log(Level.INFO, "DaikonChecker: combined is " + (unsat? "UNSAT" : "SAT"));
      return !unsat;  // SAT => real error reachable
    } catch (SolverException e) {
      logger.log(Level.WARNING, e, "SMT solver failure, assume reachable");
      return true;
    }
  }

  /* =================================================================== */
  /*  Helper: emit C harness                                             */
  /* =================================================================== */

  private Path emitHarness(UnknownFuncCallPrecondition info, int id)
      throws IOException {

    String func   = info.getFunctionName();
    String retT   = info.getReturnType().toString();

    List<String> argTypes = info.getArgumentTypes().stream().map(Object::toString).toList();
    List<String> argValues = info.getArgumentValues().stream().map(Object::toString).toList();

    // collect all SymEx[SymbolicIdentifier[n]] IDs
    Pattern stripSym = Pattern.compile("SymEx\\[SymbolicIdentifier\\[(\\d+)\\]\\]");
    Pattern stripNum = Pattern.compile("(?:SymEx\\[)?NumericValue\\[number=([^\\]]+)\\]\\]?");
    
    Set<String> allIds = new TreeSet<>(Comparator.comparingInt(Integer::parseInt));
    for (String v : argValues) {
      Matcher m = stripSym.matcher(v);
      while (m.find()) {
        allIds.add(m.group(1));
      }
    }
      
    List<String> symExprs = new ArrayList<>();
    for (String v : argValues) {
      String replaced = v;
      replaced = stripNum.matcher(replaced).replaceAll("$1");
      replaced = stripSym.matcher(replaced).replaceAll("s$1_");
      symExprs.add(replaced);
      logger.log(Level.INFO, "added expression: " + replaced);
    }

    List<String> exprTypes = new ArrayList<>();
    for (String idStr : allIds) { // e.g. allIds = [1,2,3,4]
      String symbol = "s" + idStr + "_"; // e.g. var = s7_
      String type = "int";
      // determine type from first occurrence in argTs/argVs
      for (int i = 0; i < symExprs.size(); i++) {
        if (symExprs.get(i).contains(symbol)) {
          type = argTypes.get(i);
          break;
        }
      }
      exprTypes.add(type);
    }



    Path cFile = outDir.resolve("grey_" + func + "_" + id + ".c");
    try (BufferedWriter w = Files.newBufferedWriter(cFile, UTF_8)) {
      w.write("#include <stdlib.h>\n");
      w.write("#include <stdio.h>\n");

      /* extern declaration */
      w.write("\nextern " + retT + " " + func + "(" + String.join(", ", argTypes) + ");\n\n");

      /* declare globals */
      w.write(retT + " r;\n");
      for(int i=0; i<symExprs.size();i++){
        w.write(exprTypes.get(i)+" "+symExprs.get(i)+";\n");
      }
      w.write("\n");

      /* main */
      w.write("int main(int argc, char **argv){\n");
      // 1) declare & read every symbolic var in argv order
      for (int i=0; i<symExprs.size(); i++){
        String symbol = symExprs.get(i);
        String type = exprTypes.get(i); 
        if ("double".equals(type) || "float".equals(type)) {
          w.write("  " + symbol + " = atof(argv[" + (i+1) + "]);\n");
        } else {
          w.write("  " + symbol + " = atoi(argv[" + (i+1) + "]);\n");
        }
      }
      w.write("\n");

      // function call
      w.write("  r = " + func + "(");
      w.write(String.join(", ", symExprs));
      w.write(");\n");
      
      w.write("}\n");
    }
    logger.log(Level.INFO, "Harness written: {" + cFile +"}");
    return cFile;
  }

  /* =================================================================== */
  /*  Helper: compile the harness file                                   */
  /* =================================================================== */

  private String compileHarness(Path cFile)
      throws IOException, InterruptedException {

    String exe = cFile.getFileName().toString().replace(".c", ".out");
    List<String> cmd = new ArrayList<>();
    cmd.add(cc);
    cmd.add("-O0");
    cmd.add("-gdwarf-2");
    cmd.add("-no-pie");
    // harness source (relative to outDir)
    cmd.add(cFile.getFileName().toString());
    // user‑provided object files (absolute paths)
    for (Path obj : greyboxObjectFiles) {
      cmd.add(obj.toString());
    }
    // output executable name (relative)
    cmd.add("-o");
    cmd.add(exe);
    run(outDir, cmd.toArray(new String[0]));
    return outDir.resolve(exe).toString();
  }

  /* =================================================================== */
  /*  Helper: build a list of arguments with concrete values mapped by 'val' form variable names (e.g. s7) */
  /* =================================================================== */

  private List<String> buildArgs(
      List<SymbolicExpression> argValues, Map<String,String> vals) {
    Pattern pat = Pattern.compile("SymEx\\[SymbolicIdentifier\\[(\\d+)\\]\\]");
    return argValues.stream()
      .map(v -> {
        Matcher m = pat.matcher(v.toString());
        if (m.find()) {
          return vals.get("s" + m.group(1));
        } else {
          return v.toString();
        }
      })
      .toList();
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
        new InputStreamReader(p.getInputStream(), UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        System.out.println(String.join(" ", cmd) + " | " + line);
      }
    }
    if (p.waitFor() != 0) {
      throw new IOException("cmd failed: " + String.join(" ", cmd));
    }
  }

  /* =================================================================== */
  /*  Helper: build SMT fomula?                                          */
  /* =================================================================== */
  private BooleanFormula buildConstraintFormula(
      String expr, Map<String,Formula> varFormulas) {
    logger.log(Level.INFO, "[+] expr:", expr);
    logger.log(Level.INFO, "[+] varFormulas:", varFormulas);
    // 1) find operator
    String op = "!=";
    for (String cand : List.of("<=", ">=", "==", "!=", "<", ">")) {
      if (expr.contains(cand)) {
        op = cand;
        break;
      }
    }
    //if (op == null) {
    //  return bmgr.makeTrue();
    //}

    // 2) split into left/right
    String left, right;
    String[] parts = expr.split(Pattern.quote(op), 2);
    if (parts.length != 2) {
      left  = expr.trim();
      right = "0";
    }else{
      left  = parts[0].trim();
      right = parts[1].trim();
    }

    // 3) map to Formulas, numeric vs symbolic
    Formula fL = varFormulas.containsKey(left)
        ? varFormulas.get(left)
        : tryParseNumber(left, left, varFormulas);
    Formula fR = varFormulas.containsKey(right)
        ? varFormulas.get(right)
        : tryParseNumber(right, right, varFormulas);

    // 4) dispatch based on type
    logger.log(Level.INFO, "[+]", fL, fL instanceof IntegerFormula, fR, fR instanceof IntegerFormula);
    if (fL instanceof IntegerFormula && fR instanceof IntegerFormula) {
      IntegerFormula l = (IntegerFormula) fL;
      IntegerFormula r = (IntegerFormula) fR;
      return switch (op) {
        case "<=" -> ifmgr.lessOrEquals(l, r);
        case "<"  -> ifmgr.lessThan(l, r);
        case ">=" -> ifmgr.greaterOrEquals(l, r);
        case ">"  -> ifmgr.greaterThan(l, r);
        case "==" -> ifmgr.equal(l, r);
        case "!=" -> bmgr.not(ifmgr.equal(l, r));
        default   -> bmgr.makeTrue();
      };
    } else if (fL instanceof RationalFormula && fR instanceof RationalFormula){
      RationalFormula l = (RationalFormula) fL;
      RationalFormula r = (RationalFormula) fR;
      return switch (op) {
        case "<=" -> rfMgr.lessOrEquals(l, r);
        case "<"  -> rfMgr.lessThan(l, r);
        case ">=" -> rfMgr.greaterOrEquals(l, r);
        case ">"  -> rfMgr.greaterThan(l, r);
        case "==" -> rfMgr.equal(l, r);
        case "!=" -> bmgr.not(rfMgr.equal(l, r));
        default   -> bmgr.makeTrue();
      };
    } else{
      // can't compare int <-> real directly in this simple setup
      logger.log(Level.WARNING, "Cannot compare int term and rational term in SMT");
      return bmgr.makeTrue();
    }
  }

  /* 
   * Try to parse the string as a number; if that fails, assume it's a symbolic var 
   * and look it up in varFormulas (falling back to true if absent).
   */
  private Formula tryParseNumber(
      String token,
      String original,
      Map<String,Formula> varFormulas) {
    try {
      return parseNumericLiteral(token);
    } catch (IllegalArgumentException | SMTLIBException e) {
      // not a numeral, then treat as symbolic var
      if (varFormulas.containsKey(original)) {
        return varFormulas.get(original);
      }
      // should never happen if you collected all sN up front
      return bmgr.makeTrue();
    }
  }

  private Formula parseNumericLiteral(String lit) {
    if (lit.contains(".") || lit.contains("e") || lit.contains("E")) {
      return rfMgr.makeNumber(lit);
    } else {
      return ifmgr.makeNumber(lit);
    }
  }

}
