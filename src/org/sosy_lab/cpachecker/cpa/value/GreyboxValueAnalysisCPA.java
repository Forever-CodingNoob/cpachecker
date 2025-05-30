package org.sosy_lab.cpachecker.cpa.value;

import java.util.Collection;

import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;

import org.sosy_lab.cpachecker.core.defaults.AbstractCPA;
import org.sosy_lab.cpachecker.core.defaults.AutomaticCPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.core.interfaces.pcc.ProofChecker.ProofCheckerCPA;

import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

import org.sosy_lab.cpachecker.exceptions.CPAException;

import java.io.*;

/**
 * A wrapper CPA that installs GreyboxValueTransferRelation in place
 * of the default transfer relation.
 */
public class GreyboxValueAnalysisCPA extends ValueAnalysisCPA {

  private GreyboxValueAnalysisTransferRelation transferRelation;

  private GreyboxValueAnalysisCPA(
      Configuration config, LogManager logger, ShutdownNotifier pShutdownNotifier, CFA cfa)
      throws InvalidConfigurationException {
    // Build the standard ValueAnalysisCPA:
    super(config, logger, pShutdownNotifier, cfa);
    transferRelation = new GreyboxValueAnalysisTransferRelation(
        logger, cfa, this.transferOptions, this.unknownValueHandler, this.constraintsStrengthenOperator, this.statistics
    );
  }

  /**
   * This static factory method *shadows* (not overrides)
   * ValueAnalysisCPA.factory(), and will be picked up
   * by CPABuilder whenever you set cpa.value=<your class> in the config.
   */
  public static CPAFactory factory() {
    return AutomaticCPAFactory.forType(GreyboxValueAnalysisCPA.class);
  }
  
  @Override
  public ValueAnalysisTransferRelation getTransferRelation() {
    return transferRelation;
  }
}
