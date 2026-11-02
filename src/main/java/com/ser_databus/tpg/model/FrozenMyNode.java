package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Map;

public class FrozenMyNode extends FrozenNode {
    public final boolean isStubLeafNode;
    public Move chosenPrepMove;
    /** See {@link MyNode#myViableMoves}; 0 if not measured */
    public final double myViableMoves;

    public FrozenMyNode(Map<Move, FrozenNode> moveToNode, String fen, double chance, int depth,
                        MoveHistory moveHistory, double normEval, double correctedNormEval,
                        boolean isPruned, PruneReason pruneReason,
                        double vp, double lichessScore, double lichessVp, double thickness,
                        int leaves, int branches, boolean isStubLeafNode, Move chosenPrepMove,
                        double myViableMoves) {
        super(moveToNode, fen, chance, depth, moveHistory, normEval, correctedNormEval, isPruned, pruneReason, vp,
                lichessScore, lichessVp, thickness, leaves, branches);
        this.isStubLeafNode = isStubLeafNode;
        this.chosenPrepMove = chosenPrepMove;
        this.myViableMoves = myViableMoves;
    }
}
