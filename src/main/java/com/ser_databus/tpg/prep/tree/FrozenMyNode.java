package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Map;

public class FrozenMyNode extends FrozenNode {
    public final boolean isStubLeafNode;
    public Move chosenPrepMove;

    public FrozenMyNode(Map<Move, FrozenNode> moveToNode, Board board, String fen, double chance, int depth,
                        MoveHistory moveHistory, double normEval, boolean isPruned, PruneReason pruneReason, double vp,
                        double lichessScore, double cumulativeVariety, int leaves, int branches,
                        boolean isStubLeafNode, Move chosenPrepMove) {
        super(moveToNode, board, fen, chance, depth, moveHistory, normEval, isPruned, pruneReason, vp,
                lichessScore, cumulativeVariety, leaves, branches);
        this.isStubLeafNode = isStubLeafNode;
        this.chosenPrepMove = chosenPrepMove;
    }
}