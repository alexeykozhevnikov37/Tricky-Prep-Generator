package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.HashMap;
import java.util.Map;

public class MyNode extends Node {
    public MyNode(Board board, MoveHistory moveHistory, double chance, int depth, OpNode parent) {
        super(board, moveHistory, chance, depth);
        this.parent = parent;
    }

    public OpNode parent;
    public boolean isStubLeafNode;
    public Move chosenPrepMove;
    /**
     * How many good moves I have in this position: the soft count sum(exp(-evalLoss / TYPICAL_INACCURACY_NORM_EVAL_LOSS))
     * over my candidate moves, where evalLoss is the normalized eval lost compared to the best candidate. About 1 if
     * only one move keeps the eval, about 4 if four moves are equally good. 0 if not measured (only one move was
     * considered in this node).
     */
    public double myViableMoves;

    public OpNode createChild(Move move) {
        Board newBoard = board();
        MoveHistory newMoveHistory = moveHistory.prolong(newBoard, move);
        newBoard.doMove(move);
        OpNode opNode = new OpNode(newBoard, newMoveHistory, chance, depth + 1,this);
        moveToNode.put(move, opNode);
        return opNode;
    }

    @Override
    public FrozenMyNode freeze() {
        Map<Move, FrozenNode> moveToFrozenNode = new HashMap<>();
        for (var entry : moveToNode.entrySet()) {
            FrozenNode frozenNode = entry.getValue().freeze();
            moveToFrozenNode.put(entry.getKey(), frozenNode);
        }
        return new FrozenMyNode(moveToFrozenNode, fen, chance, depth, moveHistory, normEval, correctedNormEval,
                isPruned, pruneReason, vp, lichessScore, lichessVp, getThickness(), leaves, branches,
                isStubLeafNode, chosenPrepMove, myViableMoves);
    }
}
