package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import com.google.common.collect.ImmutableSet;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;

/**  
 * A pair of (functionName, preReturnConstraints) for grey-box symbolic execution.  
 */
public final class UnknownFuncCallPrecondition {
  private final String functionName;
  private final ImmutableSet<Constraint> constraints;

  public UnknownFuncCallPrecondition(String functionName, final Set<Constraint> pConstraints) {
    checkNotNull(functionName);
    checkNotNull(pConstraints);
    this.functionName = functionName;
    this.constraints  = ImmutableSet.copyOf(pConstraints);
  }

  public UnknownFuncCallPrecondition(final UnknownFuncCallPrecondition other) {
    this(other.getFunctionName(), other.getConstraints());
  }

  public String getFunctionName() {
    return functionName;
  }
  
  public ImmutableSet<Constraint> getConstraints() {
    return constraints;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("===============\n");
    sb.append("func = " + functionName + ",\n");
    sb.append("constraints = [\n");
    for (Constraint currConstraint: constraints) {
      sb.append("<");
      sb.append(currConstraint.toString());
      sb.append(">\n");
    }
    sb.append("]\n===============");

    return sb.toString();
  }
}

