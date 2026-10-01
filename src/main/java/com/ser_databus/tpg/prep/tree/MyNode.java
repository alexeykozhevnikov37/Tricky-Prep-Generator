package com.ser_databus.tpg.prep.tree;

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

    public OpNode createChild(Move move) {
        MoveHistory newMoveHistory = moveHistory.prolong(move);
        Board newBoard = board.clone();
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
        return new FrozenMyNode(moveToFrozenNode, board, fen, chance, depth, moveHistory, normEval, isPruned,
                pruneReason, vp, lichessScore, cumulativeVariety, leaves, branches, isStubLeafNode, chosenPrepMove);
    }
}