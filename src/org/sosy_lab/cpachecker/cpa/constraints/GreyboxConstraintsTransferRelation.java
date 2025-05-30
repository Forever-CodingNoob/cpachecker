package org.sosy_lab.cpachecker.cpa.constraints;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Optional;

import javax.swing.SpringLayout.Constraints;

import com.google.common.collect.FluentIterable;
import org.checkerframework.checker.nullness.qual.Nullable;

import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.common.configuration.InvalidConfigurationException;
import org.sosy_lab.common.log.LogManager;

import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.core.defaults.ForwardingTransferRelation;
import org.sosy_lab.cpachecker.core.defaults.SingletonPrecision;


import org.sosy_lab.cpachecker.cfa.ast.*;
import org.sosy_lab.cpachecker.cfa.ast.c.*;
import org.sosy_lab.cpachecker.cfa.model.*;
import org.sosy_lab.cpachecker.cfa.model.c.*;
import org.sosy_lab.cpachecker.cfa.types.*;
import org.sosy_lab.cpachecker.cfa.types.c.*;

import org.sosy_lab.cpachecker.cpa.constraints.domain.ConstraintsState;
import org.sosy_lab.cpachecker.cpa.constraints.domain.ConstraintsSolver;

import org.sosy_lab.cpachecker.cpa.value.ValueAnalysisState;
import org.sosy_lab.cpachecker.cpa.value.ValueAnalysisTransferRelation;
import org.sosy_lab.cpachecker.cpa.value.ExpressionValueVisitor;
import org.sosy_lab.cpachecker.cpa.value.type.*;

import org.sosy_lab.cpachecker.util.states.MemoryLocation;
import org.sosy_lab.cpachecker.util.AbstractStates;

import org.sosy_lab.cpachecker.exceptions.CPATransferException;

import java.io.*;

/**
 * A custom ConstraintsTransferRelation that snapshots the
 * ConstraintsState just before a new symbolic return is introduced
 * for an unknown (no-body) function.
 */
public class GreyboxConstraintsTransferRelation implements TransferRelation {

    private final ConstraintsTransferRelation delegate;
    private final Deque<ConstraintsState> snapshots = new ArrayDeque<>();

    public GreyboxConstraintsTransferRelation(ConstraintsTransferRelation pDelegate){ 
        this.delegate = pDelegate;
    }

    @Override
    public Collection<? extends AbstractState> getAbstractSuccessors(
        AbstractState state,
        Precision precision)
        throws CPATransferException, InterruptedException {
        return delegate.getAbstractSuccessors(state, precision);
    }

    @Override
    public Collection<? extends AbstractState> getAbstractSuccessorsForEdge(
        AbstractState state,
        Precision precision,
        CFAEdge edge)
        throws CPATransferException, InterruptedException {
        return delegate.getAbstractSuccessorsForEdge(state, precision, edge);
    }

    @Override
    public Collection<? extends AbstractState> strengthen(
        final AbstractState pStateToStrengthen,
        final Iterable<AbstractState> pStrengtheningStates,
        final CFAEdge pCfaEdge,
        Precision pPrecision)
        throws CPATransferException, InterruptedException {

        System.out.println("Strengthening edge: " + pCfaEdge);

        /* failed attempt
        if (pCfaEdge instanceof CStatementEdge) {
            CStatementEdge stmtEdge = (CStatementEdge) pCfaEdge;
            AStatement stmt = stmtEdge.getStatement();

            // stmt == "x = foo(...)"
            if (stmt instanceof CFunctionCallAssignmentStatement) {
                final CFunctionCallAssignmentStatement fc_stmt = (CFunctionCallAssignmentStatement) stmt;
                final CFunctionCallExpression functionCallExp = fc_stmt.getFunctionCallExpression();
                CLeftHandSide lhs = fc_stmt.getLeftHandSide();

                FluentIterable<ValueAnalysisState> valueStates = AbstractStates.projectToType(pStrengtheningStates, ValueAnalysisState.class);
            }
        }
        */

        // Delegate back to the original strengthen implementation
        return delegate.strengthen(pStateToStrengthen, pStrengtheningStates, pCfaEdge, pPrecision);
    }

    /**  
    * Pop the last snapshot, or null if none.  
    */
    public @Nullable ConstraintsState popSnapshot() {
        return snapshots.isEmpty() ? null : snapshots.pop();
    }
}
