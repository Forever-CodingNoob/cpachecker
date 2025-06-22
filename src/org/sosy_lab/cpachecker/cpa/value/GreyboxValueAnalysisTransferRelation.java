package org.sosy_lab.cpachecker.cpa.value;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.base.Preconditions;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.sosy_lab.common.log.LogManager;

import org.sosy_lab.cpachecker.core.interfaces.*;

import org.sosy_lab.cpachecker.cfa.CFA;
import org.sosy_lab.cpachecker.cfa.ast.*;
import org.sosy_lab.cpachecker.cfa.ast.c.*;
import org.sosy_lab.cpachecker.cfa.model.*;
import org.sosy_lab.cpachecker.cfa.model.c.*;
import org.sosy_lab.cpachecker.cfa.types.*;
import org.sosy_lab.cpachecker.cfa.types.c.*;

import org.sosy_lab.cpachecker.cpa.constraints.domain.ConstraintsState;
import org.sosy_lab.cpachecker.cpa.constraints.constraint.Constraint;
import org.sosy_lab.cpachecker.cpa.value.symbolic.ConstraintsStrengthenOperator;
import org.sosy_lab.cpachecker.cpa.value.type.Value;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicValueFactory;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicExpression;
import org.sosy_lab.cpachecker.cpa.value.symbolic.type.SymbolicValue;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallState;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;

import org.sosy_lab.cpachecker.util.states.MemoryLocation;
import org.sosy_lab.cpachecker.util.states.MemoryLocationValueHandler;
import org.sosy_lab.cpachecker.util.AbstractStates;
import org.sosy_lab.cpachecker.util.BuiltinFunctions;

import org.sosy_lab.cpachecker.exceptions.UnrecognizedCodeException;
import org.sosy_lab.cpachecker.exceptions.CPATransferException;

import java.io.*;

/**
 * A custom ValueAnalysisTransferRelation that snapshots the
 * ConstraintsState just before a new symbolic return is introduced
 * for an unknown (no-body) function.
 */
public class GreyboxValueAnalysisTransferRelation extends ValueAnalysisTransferRelation {

    private boolean justReturnedFromUnknownFunction = false;
    private String returnedFuncName = null;
    private CType returnType = null;
    private List<SymbolicExpression> argumentValues = new ArrayList<>();
    private List<CType> argumentTypes = new ArrayList<>();

    public GreyboxValueAnalysisTransferRelation(
        LogManager pLogger,
        CFA pCfa,
        ValueTransferOptions pOptions,
        MemoryLocationValueHandler pUnknownValueHandler,
        ConstraintsStrengthenOperator pConstraintsStrengthenOperator,
        @Nullable ValueAnalysisCPAStatistics pStats) {
        super(pLogger, pCfa, pOptions, pUnknownValueHandler, pConstraintsStrengthenOperator, pStats);
    }


    @Override
    protected ValueAnalysisState handleFunctionAssignment(
        CFunctionCallAssignmentStatement pFunctionCallAssignment) throws UnrecognizedCodeException {
    
        ValueAnalysisState nextState = super.handleFunctionAssignment(pFunctionCallAssignment);

        final CFunctionCallExpression funcCallExp = pFunctionCallAssignment.getFunctionCallExpression();
        CExpression functionNameExp = funcCallExp.getFunctionNameExpression();

        // we only handles normal function calls
        if (! (functionNameExp instanceof CIdExpression)) {
            return nextState;
        }
        String calledFunctionName = ((CIdExpression) functionNameExp).getName();
        
        // we only handles non-builtin functions
        if (BuiltinFunctions.isBuiltinFunction(calledFunctionName)) {
            return nextState;
        }

        System.out.println("[+] Greybox function name: " + calledFunctionName);
        
        List<CExpression> argumentExpressions = funcCallExp.getParameterExpressions();
        argumentValues.clear();
        argumentTypes.clear();
        final ExpressionValueVisitor evv = getVisitor();

        for (CExpression currParamExp : argumentExpressions) {
            Value newValue = currParamExp.accept(evv);
            SymbolicExpression newSymValue = SymbolicValueFactory.getInstance().asConstant(newValue, currParamExp.getExpressionType());
            argumentValues.add(newSymValue);
            argumentTypes.add(currParamExp.getExpressionType());
        }
        returnType = pFunctionCallAssignment.getLeftHandSide().getExpressionType();

        System.out.println("[+] Greybox function parameters: "+argumentValues);
       
        justReturnedFromUnknownFunction = true;
        returnedFuncName = calledFunctionName;
        return nextState;
    }

    @Override
    public Collection<? extends AbstractState> strengthen(
        AbstractState pElement,
        Iterable<AbstractState> pElements,
        CFAEdge pCfaEdge,
        Precision pPrecision)
        throws CPATransferException {

        if (justReturnedFromUnknownFunction){
            justReturnedFromUnknownFunction = false;

            // extract ConstraintsState
            FluentIterable<ConstraintsState> constraintsStates = AbstractStates.projectToType(pElements, ConstraintsState.class);
            assert !constraintsStates.isEmpty();
            ConstraintsState constraintsState = constraintsStates.get(0);

            // extract UnknownFuncCallState
            FluentIterable<UnknownFuncCallState> unknownFuncCallStates = AbstractStates.projectToType(pElements, UnknownFuncCallState.class);
            assert !unknownFuncCallStates.isEmpty();
            UnknownFuncCallState unknownFuncCallState = unknownFuncCallStates.get(0);

            //System.out.println("State before push:\n" + unknownFuncCallState) ;
            
            // push the snapshot
            unknownFuncCallState.push(new UnknownFuncCallPrecondition(returnedFuncName, ImmutableSet.copyOf(constraintsState), argumentValues, argumentTypes, returnType));
            System.out.println("[+] Saved preconditions: " + List.copyOf(constraintsState).stream().map(Object::toString).collect(Collectors.joining(", ")));
        }

        // Delegate back to the original strengthen implementation
        return super.strengthen(pElement, pElements, pCfaEdge, pPrecision);
    }
}
