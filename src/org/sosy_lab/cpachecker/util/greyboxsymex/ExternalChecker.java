package org.sosy_lab.cpachecker.util.greyboxsymex;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;

import org.sosy_lab.common.configuration.InvalidConfigurationException;

/**
 *  A pluggable checker that decides whether a counter-example path involving an
 *  unknown (greybox) function is still feasible once dynamic invariants are
 *  taken into account.
 *
 *  Implementations must:
 *    – build a harness for {@link UnknownFuncCallPrecondition},
 *    – call an invariant detector (e.g. Daikon),
 *    – conjoin the invariants with {@code postConstraints},
 *    – use the internal SMT solver to check satisfiability.
 *
 *  The return value is {@code true} -> path still SAT  (keep error)
 *                     {@code false} -> UNSAT          (prune error)
 */
public interface ExternalChecker {

  boolean isPathReachable(
      UnknownFuncCallPrecondition preInfo,
      Set<Constraint>            postConstraints,
      int                        harnessId) throws IOException, InterruptedException;

  /** Simple factory so GreyboxSymExAlgorithm can late-bind a checker. */
  interface Factory {
    ExternalChecker create(Path outputDir) throws IOException, InvalidConfigurationException;
  }
}
