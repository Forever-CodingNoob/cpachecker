package org.sosy_lab.cpachecker.cpa.unknownfunccall;

import org.sosy_lab.cpachecker.core.defaults.AbstractCPA;
import org.sosy_lab.cpachecker.core.defaults.AutomaticCPAFactory;
import org.sosy_lab.cpachecker.core.interfaces.*;
import org.sosy_lab.cpachecker.cfa.model.CFANode;

public class UnknownFuncCallCPA extends AbstractCPA {

  private UnknownFuncCallCPA(){
    super("sep", // merge operator type
          "always", //stop operator type 
          new UnknownFuncCallTransferRelation());
  }

  public static CPAFactory factory() {
    return AutomaticCPAFactory.forType(UnknownFuncCallCPA.class);
  }

  @Override
  public UnknownFuncCallState getInitialState(CFANode node, StateSpacePartition partition) throws InterruptedException{
    return new UnknownFuncCallState();
  }
}
