package org.sosy_lab.cpachecker.util.greyboxsymex;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.math.*;

import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;
import org.sosy_lab.common.rationals.Rational;

import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.SymbolicExpressionToCExpressionTransformer;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicExpression;
import org.sosy_lab.java_smt.SolverContextFactory;
import org.sosy_lab.java_smt.api.*;
import org.sosy_lab.java_smt.api.FormulaType.*;
import org.sosy_lab.java_smt.api.NumeralFormula.*;
import org.sosy_lab.java_smt.api.SolverContext.ProverOptions;

import org.sosy_lab.java_smt.api.SolverException;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import de.uni_freiburg.informatik.ultimate.logic.SMTLIBException;

import org.sosy_lab.cpachecker.cpa.constraints.FormulaCreatorUsingCConverter;
import org.sosy_lab.cpachecker.util.predicates.pathformula.ctoformula.CtoFormulaConverter;
import org.sosy_lab.cpachecker.util.predicates.smt.FormulaManagerView;
import org.sosy_lab.cpachecker.util.predicates.smt.Solver;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.CToFormulaConverterWithPointerAliasing;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.FormulaEncodingWithPointerAliasingOptions;
import org.sosy_lab.cpachecker.util.predicates.pathformula.pointeraliasing.TypeHandlerWithPointerAliasing;
import org.sosy_lab.cpachecker.util.predicates.pathformula.SSAMap;
import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;
import org.sosy_lab.cpachecker.core.AnalysisDirection;
import org.sosy_lab.cpachecker.cfa.types.MachineModel;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import org.sosy_lab.cpachecker.cfa.types.c.CNumericTypes;
import org.sosy_lab.cpachecker.cfa.types.c.CType;
import org.sosy_lab.cpachecker.cfa.ast.*;
import org.sosy_lab.cpachecker.cfa.ast.c.*;
import org.sosy_lab.cpachecker.cfa.types.c.*;
import org.sosy_lab.cpachecker.cpa.value.symbolic.util.SymbolicIdentifierLocator;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.*;
import org.sosy_lab.cpachecker.cpa.smg2.constraint.ConstantSymbolicExpressionLocator;

@Options(prefix = "daikonChecker")
public final class DaikonChecker implements ExternalChecker {

  // ---------- configurable options ----------
  @Option(secure = true,
          description = "Path to daikon.jar")
  private String daikonPath = Paths.get(System.getenv().getOrDefault("DAIKON_JAR", "daikon.jar")).toAbsolutePath().toString();

  @Option(secure = true,
          description = "command for kvasir-dtrace (front-end to Daikon)")
  private String kvasirPath = "kvasir-dtrace";

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
  private final Solver solver;
  private final FormulaManagerView fmgr;
  private final BooleanFormulaManager bmgr;
  private final IntegerFormulaManager ifmgr;
  private final RationalFormulaManager rfMgr;
  private final FloatingPointFormulaManager fpMgr;
  private final CtoFormulaConverter converter;
  private FormulaCreatorUsingCConverter formulaCreator;

  private List<Path> greyboxObjectFiles;
  private List<String> symExprs;

  public static final class Factory implements ExternalChecker.Factory {
    private final Configuration config;
    private final LogManager log;
    private final ShutdownNotifier sn;
    private final CFA cfa;

    public Factory(Configuration c, LogManager l, ShutdownNotifier s, CFA pCfa) {
      config = c; log = l; sn = s; cfa = pCfa;
    }

    @Override
    public ExternalChecker create(Path dir) throws IOException, InvalidConfigurationException {
      return new DaikonChecker(dir, config, log, sn, cfa);
    }
  }



  private DaikonChecker(Path dir, Configuration cfg, LogManager log, ShutdownNotifier sn, CFA cfa) throws IOException, InvalidConfigurationException {

    cfg.inject(this);
    outDir   = Files.createDirectories(dir);
    logger   = log;
    shutdown = sn;

    solver = Solver.create(cfg, logger, shutdown);
    fmgr = solver.getFormulaManager();
    bmgr = fmgr.getBooleanFormulaManager();
    ifmgr= fmgr.getIntegerFormulaManager();
    rfMgr= fmgr.getRationalFormulaManager();
    fpMgr= fmgr.getFloatingPointFormulaManager();
    
    FormulaEncodingWithPointerAliasingOptions options = 
      new FormulaEncodingWithPointerAliasingOptions(cfg);
    TypeHandlerWithPointerAliasing typeHandler =
      new TypeHandlerWithPointerAliasing(logger, cfa.getMachineModel(), options);
    
    converter =
      new CToFormulaConverterWithPointerAliasing(
        options,
        fmgr,
        cfa.getMachineModel(),
        Optional.empty(),
        logger,
        shutdown,
        typeHandler,
        AnalysisDirection.FORWARD);
    formulaCreator = null;
    
    greyboxObjectFiles = new ArrayList<>();
    for (String raw : greyboxObjectFileStrings) {
      String s = raw.trim();
      if (s.startsWith("\"") && s.endsWith("\"") && s.length()>1) {
        s = s.substring(1, s.length()-1);
      }
      greyboxObjectFiles.add(Paths.get(s));
    }

    symExprs = null;
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

    /* 0. preparation step */
    Map<String, Formula> varFormulas = new LinkedHashMap<>(); // symbolic varaible to SMT formula variable mapping
    SSAMap.SSAMapBuilder ssa = SSAMap.emptySSAMap().builder();
    Map<Long, CType> id2Type = initVarsAndSsa(preInfo, postConstraints, ssa);

    
    // instantiate the creator that reuses the names & SSA
    formulaCreator = new GreyboxFormulaCreator(converter, "__greybox", ssa);

    /* 1. ─ write harness file and compile it ───────────────────────── */
    logger.log(Level.INFO, "writing harness!");
    Path cFile = emitHarness(preInfo, id);
    Path exe = null;
    try{
      exe = compileHarness(cFile);
    } catch (IOException | InterruptedException e) {
      logger.log(Level.WARNING,
          "Harness compilation failed (possibly missing implementation), assuming reachable", e);
      return true;
    }

    /* 2. ─ prepare SMT variables and precondition formula ──────────── */
    // Translate each precondition Constraint into a BooleanFormula
    BooleanFormula preFormula = bmgr.makeTrue();
    for (Constraint c : preInfo.getConstraints()) {
      // Build a BooleanFormula
      BooleanFormula f = buildConstraintFormula(c);
      logger.log(Level.INFO, "[+] built precondition formula:", f);
      preFormula = bmgr.and(preFormula, f);
    }
    for (Map.Entry<String, Formula> e : fmgr.extractVariables(preFormula).entrySet()) {
        varFormulas.put(e.getKey(), e.getValue());
    }

    // postconditions
    BooleanFormula postF = bmgr.makeTrue();
    for (Constraint c : postConstraints) {
      BooleanFormula f = buildConstraintFormula(c);
      logger.log(Level.INFO, "[+] built postcondition formula:", f);
      postF = bmgr.and(postF, f);
    }
    for (Map.Entry<String, Formula> e : fmgr.extractVariables(postF).entrySet()) {
        varFormulas.put(e.getKey(), e.getValue());
    }

    // others
    for (Long id_ : id2Type.keySet()) {
      String base = "s" + id_ + "_";
      String full = base + "@1";
      if(!varFormulas.containsKey(full)){
        logger.log(Level.INFO, "[+] SMT variable " + full + " does not exist, creating a new one...");
        CType  t    = id2Type.get(id_);
        Formula f   = (t instanceof CSimpleType st
                       && (st.getType() == CBasicType.FLOAT
                           || st.getType() == CBasicType.DOUBLE))
                      ? rfMgr.makeVariable(full)
                      : ifmgr.makeVariable(full);
        varFormulas.put(full, f);
      }else{
        logger.log(Level.INFO, "[+] SMT variable " + full + " already exists!");
      }
    }

    logger.log(Level.INFO, "Precondition formula:", preFormula);
    logger.log(Level.INFO, "varFormulas:", varFormulas);


    /* 3. ─ enumerating models and running kvasir ──────────── */
    String base = cFile.getFileName().toString().replaceFirst("\\.[^.]+$", "");
    Path decls   = outDir.resolve(base + ".decls");
    Path dtrace  = outDir.resolve(base + ".dtrace");

    try (ProverEnvironment pe = solver.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      pe.addConstraint(preFormula);

      for (int sample = 0; sample < numSamples; sample++) {
        if (pe.isUnsat()) break;
        Model model = pe.getModel();
        //logger.log(Level.INFO, "model:", model);


        Map<String,String> vals = new HashMap<>();
        for (Map.Entry<String,Formula> e : varFormulas.entrySet()) {
          String ssa_var = e.getKey();
          Object raw = model.evaluate(e.getValue());
          String lit = encodeModelValue(raw);

          //vals.put(ssa_var, lit);
          int at = ssa_var.indexOf('@');
          if (at > 0) {
            vals.put(ssa_var.substring(0, at), lit); //remove trailing "@1"
          }
        }

        //logger.log(Level.INFO, "valuation:", vals);

        List<String> kvasirCmdLine = new ArrayList<>();
        kvasirCmdLine.add(kvasirPath);
        if(sample == 0){
          kvasirCmdLine.add("--decls-file=" + decls.toAbsolutePath());
        }else{
          kvasirCmdLine.add("--no-dyncomp");
          kvasirCmdLine.add("--dtrace-no-decls");
          kvasirCmdLine.add("--dtrace-append");
        }
        kvasirCmdLine.add("--dtrace-file=" + dtrace.toAbsolutePath());
        kvasirCmdLine.add(exe.toAbsolutePath().toString());
        for(String symId: symExprs){
          kvasirCmdLine.add(vals.get(symId));
        }
        run(outDir, kvasirCmdLine.toArray(new String[0]));

        // block current model for next iteration
        List<BooleanFormula> eqs = new ArrayList<>();
        for (String symId : symExprs) {
          String full = symId + "@1";
          Formula varF = varFormulas.get(full);
          BooleanFormula eq;
          FormulaType<?> t = fmgr.getFormulaType(varF);
          if (t.isIntegerType()) {
            BigInteger bi  = (BigInteger) model.evaluate(varF);
            eq = ifmgr.equal((IntegerFormula) varF, ifmgr.makeNumber(bi));

          } else if (t.isRationalType()) {
            Rational rat   = (Rational) model.evaluate(varF);
            eq = rfMgr.equal((RationalFormula) varF, rfMgr.makeNumber(rat));

          } else if (t.isFloatingPointType()) {
            FloatingPointNumber fpVal = (FloatingPointNumber) model.evaluate(varF);
            FloatingPointType fpType  = (FloatingPointType) t;
            FloatingPointFormula constF =
                  fpMgr.makeNumber(fpVal.doubleValue(), fpType);
            eq = fpMgr.equalWithFPSemantics((FloatingPointFormula) varF, constF);

          } else {
            throw new UnsupportedOperationException("Unhandled formula sort: " + t);
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
    cmd.add("-cp"); cmd.add(daikonPath); cmd.add("daikon.Daikon");
    cmd.add(decls.toAbsolutePath().toString()); cmd.add(dtrace.toAbsolutePath().toString());
    cmd.add("--conf_limit=0");
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


    /* 5. ─ Parse Daikon output  (replaces the old loop over invLines) ─ */
    Set<String> interesting = new HashSet<>(symExprs);
    interesting.add("s" + ((SymbolicIdentifier)preInfo.getReturnValue().getValue()).getId() + "_");

    boolean inExitBlock = false;
    BooleanFormula invF = bmgr.makeTrue();

    for (String raw : invLines) {
      String line = raw.trim();
      if (line.startsWith("..main():::EXIT")) {
        inExitBlock = true;
        continue;
      }
      if (!inExitBlock) {
        continue;
      }
      if (line.isEmpty() || line.contains("orig")) {
        continue;
      }
      String cleaned = line.replaceAll("::", "").trim();

      /* keep the invariant only if every variable in it is interesting */
      boolean allRelevant = true;
      Set<String> varsInLine = new HashSet<>();
      Matcher m = Pattern.compile("\\b([0-9a-zA-Z_.+-]+)\\b").matcher(cleaned);
      while (m.find()) {
        //System.out.println("FOUND: "+ m.group(1));
        if (interesting.contains(m.group(1)))
          varsInLine.add(m.group(1));
        else if (Character.isDigit(m.group(1).charAt(0))){
          // numbers
        } else{
          //System.out.println(m.group(1)+" in "+cleaned+" is irrelevant, break...");
          allRelevant = false; 
          break;
        }
      }
      if (!allRelevant) {
        continue;
      }

      logger.log(Level.INFO, "[+] Found a liekly invariant: "+cleaned);

      /* parse the invariants given by Daikon into fomulas */
      // add ssa index
      for (String v : varsInLine) {
        cleaned = cleaned.replaceAll("\\b"+Pattern.quote(v)+"\\b", v+"@1");
      }

      // build smtlib2 formula
      boolean lineHasFP = varsInLine.stream().anyMatch(v -> fmgr.getFormulaType(varFormulas.get(v+"@1")).isFloatingPointType());

      String converted;
      if (!lineHasFP) {
        converted = "(assert " + new Infix2Smtlib().convert(cleaned) + ")";
      } else {
        String tmp = new Infix2Smtlib().convert(cleaned);
        tmp = tmp.replaceAll(">=", "fp.geq")
                  .replaceAll("<=", "fp.leq")
                  .replaceAll(">",  "fp.gt")
                  .replaceAll("<",  "fp.lt")
                  .replaceAll("==", "fp.eq")
                  .replaceAll("!=", "not fp.eq");
        tmp = tmp.replaceAll(
              "(?<=[ (])([0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)\\b",
              "((_ to_fp 11 53) roundNearestTiesToEven $1)");
        tmp = tmp.replaceAll(
              "(?<=[ (])-(\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)\\b",
              "(fp.neg ((_ to_fp 11 53) roundNearestTiesToEven $1))");
        converted = "(assert " + tmp + ")";
      }

      /*
      // convert floating point to real
      for (String v : varsInLine) {
        FormulaType<?> t = fmgr.getFormulaType(varFormulas.get(v + "@1"));
        if (t.isFloatingPointType()) {
          // replace occurrences of v@1 with (fp.to_real v@1)
          converted = converted.replaceAll("\\b" + Pattern.quote(v+"@1") + "\\b", "(fp.to_real " + v + "@1" + ")");
         }
      }
      */



      try {
        invF = bmgr.and(invF, fmgr.parse(converted));
        logger.log(Level.INFO, "[+] Added invariant: "+converted);
      } catch (Exception ex) {
        logger.log(Level.INFO, "[+] Cannot parse invariant '"+converted+"': ", ex);
      }
    }


    /* 6. ─ SMT check ────────────────────────────────────── */
    logger.log(Level.INFO, "postcondition:", postF);
    logger.log(Level.INFO, "invariant:", invF);

    BooleanFormula combined = bmgr.and(invF, postF);
    try (ProverEnvironment pe = solver.newProverEnvironment(ProverOptions.GENERATE_MODELS)) {
      pe.addConstraint(combined);
      boolean unsat = pe.isUnsat();
      logger.log(Level.INFO, "DaikonChecker: combined is " + (unsat? "UNSAT" : "SAT"));
      return !unsat;  // SAT => real error reachable
    } catch (SolverException e) {
      logger.log(Level.WARNING, e, "SMT solver failure, assume reachable");
      return true;
    }
  }

  /*  Helper: emit C harness */
  private Path emitHarness(UnknownFuncCallPrecondition info, int id)
      throws IOException {

    String func   = info.getFunctionName();
    String retVal = "s" + ((SymbolicIdentifier)info.getReturnValue().getValue()).getId() + "_";
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
      
    symExprs = new ArrayList<>();
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
      w.write(retT + " " + retVal + ";\n");
      for(int i=0; i<symExprs.size();i++){
        w.write(exprTypes.get(i)+" "+symExprs.get(i)+";\n");
      }
      w.write("\n");

      /* main */
      w.write("int main(int argc, char **argv){\n");
      // declare & read every symbolic var in argv order
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
      w.write("  " + retVal + " = " + func + "(");
      w.write(String.join(", ", symExprs));
      w.write(");\n");
      
      w.write("}\n");
    }
    logger.log(Level.INFO, "Harness written: {" + cFile +"}");
    return cFile;
  }

  /*  Helper: compile the harness file */
  private Path compileHarness(Path cFile)
      throws IOException, InterruptedException {

    String exe = cFile.getFileName().toString().replace(".c", ".out");
    List<String> cmd = new ArrayList<>();
    cmd.add(cc);
    cmd.add("-O0");
    cmd.add("-gdwarf-2");
    cmd.add("-no-pie");
    // harness source (relative to outDir)
    cmd.add(cFile.getFileName().toString());
    // user-provided object files (absolute paths)
    for (Path obj : greyboxObjectFiles) {
      cmd.add(obj.toString());
    }
    // output executable name (relative)
    cmd.add("-o");
    cmd.add(exe);
    run(outDir, cmd.toArray(new String[0]));
    return outDir.resolve(exe);
  }

  /*  Helper: run a command with inherited IO */
  private void run(Path dir, String... cmd)
      throws IOException, InterruptedException {

    ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile());
    pb.redirectErrorStream(true);
    Process p = pb.start();
    try (BufferedReader r = new BufferedReader(
        new InputStreamReader(p.getInputStream(), UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        logger.log(Level.FINE, String.join(" ", cmd) + " | " + line);
      }
    }
    if (p.waitFor() != 0) {
      throw new IOException("cmd failed: " + String.join(" ", cmd));
    }
  }

  /*  Helper: build SMT fomula? */
  private BooleanFormula buildConstraintFormula(Constraint constraint) {
    try {
      return formulaCreator.createFormula(constraint);

    } catch (UnrecognizedCodeException | InterruptedException e) {
      logger.log(Level.WARNING, "Constraint to Formula failed, fall back to TRUE", e);
      return bmgr.makeTrue();
    }
  }







  private static final class GreyboxIdTransformer
    extends SymbolicExpressionToCExpressionTransformer {

    @Override
    protected CExpression getIdentifierCExpression(SymbolicIdentifier pIdentifier, CType pType) {
      String name = "s" + pIdentifier.getId() + "_";
      CSimpleDeclaration declaration = new CVariableDeclaration(
            FileLocation.DUMMY,
            false,
            CStorageClass.AUTO,
            pType,
            name,
            name,
            name,
            null
          );
      return new CIdExpression(FileLocation.DUMMY, pType, name, declaration);
    }
  }

  private static final class GreyboxFormulaCreator
      extends FormulaCreatorUsingCConverter {

    private final SSAMap.SSAMapBuilder ssa;
    private final GreyboxIdTransformer toExpressionTranformer;

    GreyboxFormulaCreator(
        CtoFormulaConverter conv,
        String fn,
        SSAMap.SSAMapBuilder pSsa) {

      super(conv, fn);
      ssa = pSsa;
      toExpressionTranformer = new GreyboxIdTransformer();
    }

    @Override
    protected SSAMap.SSAMapBuilder getSsaMapBuilder() {
      return ssa;
    }

    @Override
    public BooleanFormula createFormula(final Constraint pConstraint)
        throws UnrecognizedCodeException, InterruptedException {

      CExpression constraintExpression = pConstraint.accept(toExpressionTranformer);
      return toFormulaTransformer.makePredicate(
          constraintExpression, getDummyEdge(), functionName, getSsaMapBuilder());
    }
  }
 





  private Map<Long,CType> initVarsAndSsa(
      UnknownFuncCallPrecondition preInfo,
      Collection<Constraint>      postCons,
      SSAMap.SSAMapBuilder        ssa) {

    Map<Long,CType> id2Type = new HashMap<>();
    ConstantSymbolicExpressionLocator loc =
        ConstantSymbolicExpressionLocator.getInstance();

    /* ------------ helper that handles one SymbolicExpression -------------- */
    Consumer<SymbolicExpression> harvest = expr -> {
      for (ConstantSymbolicExpression cst : expr.accept(loc)) {
        logger.log(Level.INFO, "[+] found symbol " + cst + " in expr " + expr);
        SymbolicIdentifier sid = (SymbolicIdentifier) cst.getValue();
        long   idNum = sid.getId();
        String name  = "s" + idNum + "_";
        CType  cType = (CType) cst.getType();

        id2Type.putIfAbsent(idNum, cType);
        ssa.setIndex(name, cType, 1);
      }
    };

    /* arguments */
    preInfo.getArgumentValues().forEach(harvest);

    /* pre-condition constraints */
    preInfo.getConstraints().forEach(c -> harvest.accept((SymbolicExpression)c));

    /* post-condition constraints */
    postCons.forEach(c -> harvest.accept((SymbolicExpression)c));

    return id2Type;
  }














  private static String encodeModelValue(Object v) {
    /*  Integers */
    if (v instanceof BigInteger bi) {
      return bi.toString();
    }

    /*  Rationals */
    if (v instanceof Rational r) {
      // r = num / den, both BigInteger
      BigDecimal num = new BigDecimal(r.getNum());
      BigDecimal den = new BigDecimal(r.getDen());
      // scale = max(num.precision(), den.precision())
      return num.divide(den, MathContext.DECIMAL128).stripTrailingZeros()
                .toPlainString(); // e.g. 123.125
    }

    /*  IEEE-754 floating-point numbers */
    if (v instanceof FloatingPointNumber fp) {
      double d = fp.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return "0"; // harness can't parse NaN/Inf
      }
      return Double.toString(d); // e.g. 3.141592653589793
    }

    return v.toString(); // fallback (rare)
  }

  




  private static final class Infix2Smtlib {
    String convert(String line) {
      this.s = line.trim();
      this.pos = 0;
      String sexpr = parseComparison();
      skipWS();
      return (pos == s.length()) ? sexpr : "";   // "" → could not parse
    }

    /* grammar --------------------------------------------------------
     *   comparison := sum [ ( "<" | "<=" | ">" | ">=" | "==" | "!=" ) sum ]
     *   sum        := term { ("+"|"-") term }
     *   term       := factor { ("*"|"/") factor }
     *   factor     := number | ident | "-" factor | "(" comparison ")"
     * ----------------------------------------------------------------*/

    private String parseComparison() {
      String left = parseSum();
      skipWS();
      if (match("<=")) return "(<= "  + left + " " + parseSum() + ")";
      if (match("<"))  return "(< "   + left + " " + parseSum() + ")";
      if (match(">=")) return "(>= "  + left + " " + parseSum() + ")";
      if (match(">"))  return "(> "   + left + " " + parseSum() + ")";
      if (match("==")) return "(= "   + left + " " + parseSum() + ")";
      if (match("!=")) return "(distinct " + left + " " + parseSum() + ")";
      return left;
    }

    private String parseSum() {
      String acc = parseTerm();
      while (true) {
        skipWS();
        if (match("+")) acc = "(+ " + acc + " " + parseTerm() + ")";
        else if (match("-")) acc = "(- " + acc + " " + parseTerm() + ")";
        else break;
      }
      return acc;
    }

    private String parseTerm() {
      String acc = parseFactor();
      while (true) {
        skipWS();
        if (match("*")) acc = "(* " + acc + " " + parseFactor() + ")";
        else if (match("/")) acc = "(/ " + acc + " " + parseFactor() + ")";
        else break;
      }
      return acc;
    }

    private String parseFactor() {
      skipWS();
      if (match("-")) {
        if (peekDigit()) {
          return "-" + parseNumber();
        }
        return "(- " + parseFactor() + ")";
      }
      if (match("(")) {
        String inside = parseComparison();
        expect(")");
        return inside;
      }
      if (peekDigit()) return parseNumber();
      return parseIdent();
    }

    private String parseNumber() {
      int start = pos;
      while (pos < s.length()
          && ("0123456789.eE+-".indexOf(s.charAt(pos)) >= 0)) pos++;
      return s.substring(start, pos);
    }

    private String parseIdent() {
      int start = pos;
      while (pos < s.length()
          && (Character.isLetterOrDigit(s.charAt(pos))
              || s.charAt(pos)=='_' || s.charAt(pos)=='@')) pos++;
      return s.substring(start, pos);
    }

    private void skipWS() { 
      while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) 
        pos++; 
    }
    private boolean match(String tok) {
      skipWS();
      if (s.startsWith(tok, pos)) { pos += tok.length(); return true; }
      return false;
    }
    private void expect(String tok) {
      if (!match(tok)) pos = s.length()+1;
    }
    private boolean peekDigit() { 
      skipWS(); 
      return pos < s.length() && Character.isDigit(s.charAt(pos)); 
    }

    private String s;
    private int pos;
  }
}
