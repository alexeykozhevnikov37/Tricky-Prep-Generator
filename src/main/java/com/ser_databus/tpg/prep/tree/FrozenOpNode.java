package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FrozenOpNode extends FrozenNode {
    public double branchingFactor;

    public FrozenOpNode(Map<Move, FrozenNode> moveToNode, Board board, String fen, double chance, int depth,
                        MoveHistory moveHistory, double normEval, boolean isPruned, PruneReason pruneReason, double vp,
                        double lichessScore, double cumulativeVariety, int leaves, int branches,
                        double branchingFactor) {
        super(moveToNode, board, fen, chance, depth, moveHistory, normEval, isPruned, pruneReason, vp,
                lichessScore, cumulativeVariety, leaves, branches);
        this.branchingFactor = branchingFactor;
    }

    /**
     * The moves are sorted in the order of descending chance
     */
    public List<Map.Entry<Move, FrozenNode>> getSortedMoves() {
        return moveToNode.entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isPruned)
                .sorted(Comparator.comparingDouble((Map.Entry<Move, FrozenNode> e) -> e.getValue().chance).reversed())
                .collect(Collectors.toList());
    }
}
