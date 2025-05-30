package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import java.util.Collection;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.cfa.model.*;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;

/**
 * A no-op transfer relation: it simply propagates the
 * UnknownFuncCallState unchanged along every edge.
 */
public class UnknownFuncCallTransferRelation implements TransferRelation {

  @Override
  public Collection<? extends AbstractState> getAbstractSuccessors(
      AbstractState state, Precision precision)
      throws CPATransferException, InterruptedException {
    // single‐state CPA: successor is itself
    return Collections.singleton(state);
  }

  @Override
  public Collection<? extends AbstractState> getAbstractSuccessorsForEdge(
      AbstractState state, Precision precision, CFAEdge cfaEdge)
      throws CPATransferException, InterruptedException {
    // ignore the edge and just propagate the same state
    return Collections.singleton(state);
  }

  @Override
  public Collection<? extends AbstractState> strengthen(
      AbstractState state,
      Iterable<AbstractState> otherStates,
      @Nullable CFAEdge cfaEdge,
      Precision precision)
      throws CPATransferException, InterruptedException {
    // no actual strengthening needed
    return Collections.singleton(state);
  }
}
