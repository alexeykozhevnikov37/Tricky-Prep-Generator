package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class OpNode extends Node {
    public OpNode(Board board, MoveHistory moveHistory, double chance, int depth, MyNode parent) {
        super(board, moveHistory, chance, depth);
        this.parent = parent;
    }

    public final MyNode parent;
    public double branchingFactor;

    public MyNode createChild(Move move, double moveChance) {
        Board newBoard = board();
        MoveHistory newMoveHistory = moveHistory.prolong(newBoard, move);
        newBoard.doMove(move);
        MyNode myNode = new MyNode(newBoard, newMoveHistory, moveChance, depth + 1, this);
        moveToNode.put(move, myNode);
        return myNode;
    }

    @Override
    public FrozenOpNode freeze() {
        Map<Move, FrozenNode> moveToFrozenNode = new HashMap<>();
        for (var entry : moveToNode.entrySet()) {
            FrozenNode frozenNode = entry.getValue().freeze();
            moveToFrozenNode.put(entry.getKey(), frozenNode);
        }
        return new FrozenOpNode(moveToFrozenNode, fen, chance, depth, moveHistory, normEval, correctedNormEval,
                isPruned, pruneReason, vp, lichessScore, lichessVp, getThickness(), leaves, branches,
                branchingFactor);
    }

    /**
     * The moves are sorted in the order of descending chance
     */
    public List<Map.Entry<Move, Node>> getSortedMoves() {
        return moveToNode.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isPruned)
                .sorted(Comparator.comparingDouble((Map.Entry<Move, Node> e) -> e.getValue().chance).reversed())
                .collect(Collectors.toList());
    }
}
