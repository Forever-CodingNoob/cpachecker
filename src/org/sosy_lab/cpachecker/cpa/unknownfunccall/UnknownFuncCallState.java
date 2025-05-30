package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import com.google.common.base.Joiner;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Collections;
import org.sosy_lab.cpachecker.core.interfaces.AbstractState;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;

/**
 * Holds the per-path stack of UnknownFuncCallState snapshots.
 */
public class UnknownFuncCallState implements AbstractState {

  private final Deque<UnknownFuncCallPrecondition> snapshots;

  public UnknownFuncCallState() {
    this.snapshots = new ArrayDeque<>();
  }

  public UnknownFuncCallState(final Deque<UnknownFuncCallPrecondition> pSnapshots) {
    this.snapshots = new ArrayDeque<>(pSnapshots);
  }

  private UnknownFuncCallState(UnknownFuncCallState pOther) {
    this(pOther.snapshots);
  }

  /** Create a copy for merges/precision changes. */
  public UnknownFuncCallState copy() {
    return new UnknownFuncCallState(this);
  }

  /** Push a new snapshot. */
  public void push(UnknownFuncCallPrecondition s) {
    snapshots.addFirst(s);
  }

  /** Pop the most recent snapshot, or null if none. */
  public UnknownFuncCallPrecondition pop() {
    return snapshots.isEmpty() ? null : snapshots.removeFirst();
  }

  /** View all snapshots on this path. */
  public List<UnknownFuncCallPrecondition> asList() {
    return Collections.unmodifiableList(List.copyOf(snapshots));
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof UnknownFuncCallState)
        && snapshots.equals(((UnknownFuncCallState)other).snapshots);
  }

  @Override
  public int hashCode() {
    return snapshots.hashCode();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("++++++++++++++++++\n");
    Joiner.on(",\n").appendTo(sb, snapshots);
    sb.append("\n++++++++++++++++++");

    return sb.toString();
  }
}
