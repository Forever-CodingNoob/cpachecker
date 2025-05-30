package org.sosy_lab.cpachecker.cpa.value;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Optional;
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
import org.sosy_lab.cpachecker.cpa.value.symbolic.ConstraintsStrengthenOperator;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallState;
import org.sosy_lab.cpachecker.cpa.unknownfunccall.UnknownFuncCallPrecondition;

import org.sosy_lab.cpachecker.util.states.MemoryLocation;
import org.sosy_lab.cpachecker.util.states.MemoryLocationValueHandler;
import org.sosy_lab.cpachecker.util.AbstractStates;

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
    
        ValueAnalysisState before = ValueAnalysisState.copyOf(state);
        ValueAnalysisState after  = super.handleFunctionAssignment(pFunctionCallAssignment);

        returnedFuncName = pFunctionCallAssignment.getFunctionCallExpression().getFunctionNameExpression().toASTString();
        System.out.println("Function name: " + returnedFuncName) ;

        if (!before.equals(after)) {
            justReturnedFromUnknownFunction = true;
        }
        return after;
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
            unknownFuncCallState.push(new UnknownFuncCallPrecondition(returnedFuncName, ImmutableSet.copyOf(constraintsState)));
            System.out.println("[+] Saved preconditions!!!");
        }

        // Delegate back to the original strengthen implementation
        return super.strengthen(pElement, pElements, pCfaEdge, pPrecision);
    }
}
