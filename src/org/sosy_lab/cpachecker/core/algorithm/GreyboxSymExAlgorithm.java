package org.sosy_lab.cpachecker.core.algorithm;

import java.io.*;
import java.nio.file.*;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import com.google.common.collect.ImmutableSet;

import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.log.LogManager;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.configuration.Option;
import org.sosy_lab.common.configuration.Options;

import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.core.reachedset.ReachedSet;
import org.sosy_lab.cpachecker.util.AbstractStates;

import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.constraints.domain.ConstraintsState;
import org.sosy_lab.cpachecker.cpa.value.GreyboxValueAnalysisCPA;
import org.sosy_lab.cpachecker.cpa.value.GreyboxValueAnalysisTransferRelation;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallState;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallTransferRelation;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallCPA;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;
import org.sosy_lab.cpachecker.cpa.arg.ARGState;
import org.sosy_lab.cpachecker.cpa.location.LocationState;

import org.sosy_lab.cpachecker.exceptions.CPAException;

import org.sosy_lab.cpachecker.util.greyboxsymex.*;


@Options(prefix = "greyboxSymExAlgorithm")
public class GreyboxSymExAlgorithm implements Algorithm {
  @Option(secure = true,
          name = "output.path",
          description = "path to output dir")
  private Path outDir = Paths.get("output").resolve("greybox");

  private final ConfigurableProgramAnalysis cpa;
  private final Configuration config;
  private final LogManager logger;
  private final ShutdownNotifier shutdownNotifier;

  private final ExternalChecker checker;
  private int harnessCounter = 0;

  public GreyboxSymExAlgorithm(
      ConfigurableProgramAnalysis pCpa,
      Configuration pConfig,
      LogManager pLogger,
      ShutdownNotifier pShutdownNotifier) throws InvalidConfigurationException{
    pConfig.inject(this);
    this.cpa              = pCpa;
    this.config           = pConfig;
    this.logger           = pLogger;
    this.shutdownNotifier = pShutdownNotifier;

    try{
      checker = new DaikonChecker.Factory(config, logger, shutdownNotifier).create(outDir);
    }catch(IOException e){
      throw new InvalidConfigurationException("Failed to create DaikonChecker: " + e);
    }
  }

  @Override
  public AlgorithmStatus run(ReachedSet reached) throws CPAException, InterruptedException {
    //logger.log(Level.INFO, "CPAs:", ((CompositeCPA) ((ARGCPA) cpa).getWrappedCPAs().get(0)).getWrappedCPAs());

    CPAAlgorithm baseAlg;
    AlgorithmStatus status;

    try{
      baseAlg = CPAAlgorithm.create(cpa, logger, config, shutdownNotifier);
    } catch (InvalidConfigurationException e) {
      // wrap in CPAException so the signature matches
      throw new CPAException("Could not create base CPAAlgorithm", e);
    }

    while(true){
      status = baseAlg.run(reached);
      AbstractState errorState = reached.getLastState();

      /*AbstractState eee = null;
      for (AbstractState s : reached.asCollection()) {
        if (AbstractStates.isTargetState(s)) { eee = s; break; }
      }*/

      if (!AbstractStates.isTargetState(errorState)){
        /* no error state */
        logger.log(Level.INFO, "No error state found, yay");
        return status;
      }
      
      LocationState locState = AbstractStates.extractStateByType(errorState, LocationState.class);
      ConstraintsState errorConstraintsState = AbstractStates.extractStateByType(errorState, ConstraintsState.class);
      Set<Constraint> postConstraints = ImmutableSet.copyOf(errorConstraintsState);

      UnknownFuncCallState ufcState = AbstractStates.extractStateByType(errorState, UnknownFuncCallState.class);
      List<UnknownFuncCallPrecondition> preConstraints = ufcState.asList();
      logger.log(Level.FINE,
        "Pre-return constraints: ", ufcState, "\n",
        "Post-error constraints: ", postConstraints
      );

      //UnknownFuncCallPrecondition preConstraint = ufcState.popLast();
      boolean feasible = true;
      try{
        for(UnknownFuncCallPrecondition p: preConstraints){
          feasible = checker.isPathReachable(p, postConstraints, ++harnessCounter);
          if (!feasible){
            break;
          }
        }  
      } catch (IOException e){
        throw new CPAException("Failed to analyze greybox functions", e);
      }
      if (!feasible) {
        // spurious counterexample
        logger.log(Level.INFO, "Spurious error detected at " + locState + ", pruning and continuing.");
        if (errorState instanceof ARGState) {
          ((ARGState) errorState).removeFromARG();
        }
        reached.remove(errorState);
        // continue loop to resume analysis
        // (possibly instantiate a new baseAlg for the next iteration)
        try{
          baseAlg = CPAAlgorithm.create(cpa, logger, config, shutdownNotifier);
        } catch (InvalidConfigurationException e) {
          // wrap in CPAException so the signature matches
          throw new CPAException("Could not create base CPAAlgorithm", e);
        }
      } else {
        // real counterexample
        logger.log(Level.INFO, "Real error confirmed at " + locState + ", stopping analysis.");
        break;
      }
    }
    return status;
  }
}

