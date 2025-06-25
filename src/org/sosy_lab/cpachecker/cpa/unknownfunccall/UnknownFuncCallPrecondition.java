package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableList;
import java.util.Set;
import java.util.List;

import static com.google.common.base.Preconditions.checkNotNull;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicExpression;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.ConstantSymbolicExpression;
import org.sosy_lab.cpachecker.cfa.types.c.CType;

/**  
 * A pair of (functionName, preReturnConstraints) for grey-box symbolic execution.  
 */
public final class UnknownFuncCallPrecondition {
  private final String functionName;
  private final ImmutableSet<Constraint> constraints;
  private final ImmutableList<SymbolicExpression> argumentValues;
  private final ImmutableList<CType> argumentTypes;
  private final ConstantSymbolicExpression returnValue;
  private final CType returnType;

  public UnknownFuncCallPrecondition(
      String pFunctionName,
      final Set<Constraint> pConstraints,
      final List<SymbolicExpression> pArgumentValues,
      final List<CType> pArgumentTypes,
      final ConstantSymbolicExpression pReturnValue,
      final CType pReturnType) {
    checkNotNull(pFunctionName);
    checkNotNull(pConstraints);
    checkNotNull(pArgumentValues);
    checkNotNull(pArgumentTypes);
    checkNotNull(pReturnValue);
    checkNotNull(pReturnType);
    this.functionName = pFunctionName;
    this.constraints = ImmutableSet.copyOf(pConstraints);
    this.argumentValues = ImmutableList.copyOf(pArgumentValues);
    this.argumentTypes = ImmutableList.copyOf(pArgumentTypes);
    this.returnValue = pReturnValue;
    this.returnType = pReturnType;
  }

  public UnknownFuncCallPrecondition(final UnknownFuncCallPrecondition other) {
    this(other.getFunctionName(), other.getConstraints(), other.getArgumentValues(), other.getArgumentTypes(), other.getReturnValue(), other.getReturnType());
  }

  public String getFunctionName() {
    return functionName;
  }
  
  public ImmutableSet<Constraint> getConstraints() {
    return constraints;
  }

  public ImmutableList<SymbolicExpression> getArgumentValues() {
    return argumentValues;
  }

  public ImmutableList<CType> getArgumentTypes() {
    return argumentTypes;
  }

  public ConstantSymbolicExpression getReturnValue(){
    return returnValue;
  }

  public CType getReturnType() {
    return returnType;
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("===============\n");
    sb.append(returnType.toString() + " " + returnValue.toString() + " = " 
        + functionName + "(");
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

