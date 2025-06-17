package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableList;
import java.util.Set;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.cfa.types.c.CType;

/**  
 * A pair of (functionName, preReturnConstraints) for grey-box symbolic execution.  
 */
public final class UnknownFuncCallPrecondition {
  private final String functionName;
  private final ImmutableSet<Constraint> constraints;
  private final ImmutableList<Value> argumentValues;
  private final ImmutableList<CType> argumentTypes;
  private final CType returnType;

  public UnknownFuncCallPrecondition(
      String pFunctionName,
      final Set<Constraint> pConstraints,
      final List<Value> pArgumentValues,
      final List<CType> pArgumentTypes,
      final CType pReturnType) {
    checkNotNull(pFunctionName);
    checkNotNull(pConstraints);
    checkNotNull(pArgumentValues);
    checkNotNull(pArgumentTypes);
    checkNotNull(pReturnType);
    this.functionName = pFunctionName;
    this.constraints  = ImmutableSet.copyOf(pConstraints);
    this.argumentValues = ImmutableList.copyOf(pArgumentValues);
    this.argumentTypes  = ImmutableList.copyOf(pArgumentTypes);
    this.returnType   = pReturnType;
  }

  public UnknownFuncCallPrecondition(final UnknownFuncCallPrecondition other) {
    this(other.getFunctionName(), other.getConstraints(), other.getArgumentValues(), other.getArgumentTypes(), other.getReturnType());
  }

  public String getFunctionName() {
    return functionName;
  }
  
  public ImmutableSet<Constraint> getConstraints() {
    return constraints;
  }

  public ImmutableList<Value> getArgumentValues() {
    return argumentValues;
  }

  public ImmutableList<CType> getArgumentTypes() {
    return argumentTypes;
  }

  public CType getReturnType() {
    return returnType;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("===============\n");
    sb.append(returnType.toString() + " " + functionName + "(");
    for (int i = 0; i < argumentTypes.size(); i++) {
      sb.append(argumentTypes.get(i).toString() + " " + argumentValues.get(i).toString() + ", ");
    }
    sb.append(")\n");
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

