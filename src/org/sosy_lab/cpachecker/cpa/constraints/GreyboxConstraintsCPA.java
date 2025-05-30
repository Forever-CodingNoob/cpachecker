package org.sosy_lab.cpachecker.cpa.constraints;

import java.util.Collection;

import org.sosy_lab.common.ShutdownNotifier;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;

import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.model.CFANode;
import org.sosy_lab.cpachecker.core.defaults.AutomaticCPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.*;

import org.sosy_lab.cpachecker.exceptions.CPAException;

/**
 * A wrapper CPA that installs GreyboxConstraintsTransferRelation in place
 * of the default transfer relation.
 */
public class GreyboxConstraintsCPA
    implements ConfigurableProgramAnalysis, StatisticsProvider, AutoCloseable {


  private final ConstraintsCPA delegate;
  private final GreyboxConstraintsTransferRelation transferRelation;

  /** Expose via the same AutomaticCPAFactory mechanism. */
  public static CPAFactory factory() {
    return AutomaticCPAFactory.forType(GreyboxConstraintsCPA.class);
  }

  public GreyboxConstraintsCPA(
      Configuration config,
      LogManager logger,
      ShutdownNotifier shutdownNotifier,
      CFA cfa)
      throws InvalidConfigurationException, CPAException, InterruptedException {
    // Build the standard ConstraintsCPA:
    ConfigurableProgramAnalysis raw =
        ConstraintsCPA.factory()
            .setConfiguration(config)
            .setLogger(logger)
            .set(cfa, CFA.class)
            .set(shutdownNotifier, ShutdownNotifier.class)
            .createInstance();
    if (!(raw instanceof ConstraintsCPA)) {
      throw new InvalidConfigurationException(
          "Expected ConstraintsCPA, got " + raw.getClass());
    }
    delegate = (ConstraintsCPA) raw;
    ConstraintsTransferRelation realRel = (ConstraintsTransferRelation) delegate.getTransferRelation();
    transferRelation = new GreyboxConstraintsTransferRelation(realRel);
  }

  @Override
  public AbstractDomain getAbstractDomain() {
    return delegate.getAbstractDomain();
  }

  @Override
  public TransferRelation getTransferRelation() {
    return transferRelation;
  }

  @Override
  public MergeOperator getMergeOperator() {
    return delegate.getMergeOperator();
  }

  @Override
  public StopOperator getStopOperator() {
    return delegate.getStopOperator();
  }

  @Override
  public PrecisionAdjustment getPrecisionAdjustment() {
    return delegate.getPrecisionAdjustment();
  }

  @Override
  public AbstractState getInitialState(
      CFANode node, StateSpacePartition partition) {
    return delegate.getInitialState(node, partition);
  }

  @Override
  public Precision getInitialPrecision(
      CFANode node, StateSpacePartition partition) {
    return delegate.getInitialPrecision(node, partition);
  }

  @Override
  public void collectStatistics(
      Collection<Statistics> statsCollection) {
    delegate.collectStatistics(statsCollection);
  }

  @Override
  public void close() {
    delegate.close();
  }
}
