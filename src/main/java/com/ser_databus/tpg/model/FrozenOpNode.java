package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.move.Move;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FrozenOpNode extends FrozenNode {
    public double branchingFactor;

    public FrozenOpNode(Map<Move, FrozenNode> moveToNode, String fen, double chance, int depth,
                        MoveHistory moveHistory, double normEval, double correctedNormEval,
                        boolean isPruned, PruneReason pruneReason,
                        double vp, double lichessScore, double lichessVp, double thickness,
                        int leaves, int branches, double branchingFactor) {
        super(moveToNode, fen, chance, depth, moveHistory, normEval, correctedNormEval, isPruned, pruneReason, vp,
                lichessScore, lichessVp, thickness, leaves, branches);
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
