package org.sosy_lab.cpachecker.core.algorithm;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Collections;
import java.util.logging.Level;
import com.google.common.collect.ImmutableList;

import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.configuration.ClassOption;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;

import org.sosy_lab.cpachecker.core.algorithm.CPAAlgorithm;
import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.core.algorithm.Algorithm.AlgorithmStatus;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.CPAs;

import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.constraints.domain.ConstraintsState;
import org.sosy_lab.cpachecker.cpa.value.GreyboxValueAnalysisCPA;
import org.sosy_lab.cpachecker.cpa.value.GreyboxValueAnalysisTransferRelation;
import org.sosy_lab.cpachecker.cpa.arg.ARGCPA;
import org.sosy_lab.cpachecker.cpa.composite.CompositeCPA;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallState;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;

import org.sosy_lab.cpachecker.exceptions.CPAException;

public class GreyboxSymExAlgorithm implements Algorithm {

  private final ConfigurableProgramAnalysis cpa;
  private final Configuration config;
  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;

  public GreyboxSymExAlgorithm(
      ConfigurableProgramAnalysis pCpa,
      Configuration pConfig,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier) {
    this.cpa              = pCpa;
    this.config           = pConfig;
    this.logger           = pLogger;
    this.shutdownNotifier = pShutdownNotifier;
  }

  @Override
  public AlgorithmStatus run(ReachedSet reached) throws CPAException, InterruptedException {
    //logger.log(Level.INFO, "CPAs:", ((CompositeCPA) ((ARGCPA) cpa).getWrappedCPAs().get(0)).getWrappedCPAs());

    // 1) Delegate to the standard CPAAlgorithm until the first error
    final CPAAlgorithm baseAlg;
    try{
      baseAlg = CPAAlgorithm.create(cpa, logger, config, shutdownNotifier);
    } catch (InvalidConfigurationException e) {
      // wrap in CPAException so the signature matches
      throw new CPAException("Could not create base CPAAlgorithm", e);
    }
    // Note: CPAAlgorithm.create(cpa, logger, config, shutdown) is the standard entry point

    AlgorithmStatus status = baseAlg.run(reached);

    // 2) If we didn’t hit an error, or got interrupted, just return
    if (!status.isSound()) {
      logger.log(Level.INFO, "UNSOUND! QUIT!");
      return status;
    }

    // 3) We hit an ERROR state; extract post‐error constraints
    AbstractState errorState = reached.getLastState();

    ConstraintsState errorConstraintsState = AbstractStates.extractStateByType(errorState, ConstraintsState.class);
    List<Constraint> postConstraints = ImmutableList.copyOf(errorConstraintsState);

    // 4) Retrieve and pop the pre‐return snapshot
    UnknownFuncCallState ufcState = AbstractStates.extractStateByType(errorState, UnknownFuncCallState.class);
    //List<UnknownFuncCallPrecondition> preConstraints = ufcState.asList();

    logger.log(Level.INFO,
        "Pre-return constraints: ", ufcState, "\n",
        "Post-error constraints: ", postConstraints
    );






    // 4) TODO: call your external refiner (SMT / Daikon) on consState.getFormulas()
    //boolean reachable = myExternalRefiner.isReachable(consState.getFormulas());

    //if (!reachable) {
      // 5) Spurious: prune and continue
      //logger.log(Level.INFO, "Spurious error – pruning and resuming");
      //reached.remove(errorState);
      //return run(reached);
    //}

    // 6) Real error: propagate upstream
    //logger.log(Level.INFO, "Confirmed real error; reporting");
    return status;
  }
}

