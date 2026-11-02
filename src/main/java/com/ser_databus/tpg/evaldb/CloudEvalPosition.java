package com.ser_databus.tpg.evaldb;

import com.github.bhlangonijr.chesslib.Side;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * All cloud evaluations of one position. A position usually has one to a few evaluations that differ in MultiPV and
 * depth (typically a deep single-line search and shallower multi-line ones).
 *
 * @param key   the position key ({@link com.ser_databus.tpg.elite.PositionKey}): pieces, side to move, castling,
 *              en passant
 * @param evals in the order of the dump; never empty
 */
public record CloudEvalPosition(String key, List<CloudEval> evals) {
    private static final Comparator<CloudEval> DEPTH_ORDER = Comparator.comparingInt(CloudEval::depth)
            .thenComparingInt(e -> e.pvs().size())
            .thenComparingLong(CloudEval::knodes);

    public CloudEvalPosition {
        evals = List.copyOf(evals);
    }

    public Side sideToMove() {
        return key.charAt(key.indexOf(' ') + 1) == 'w' ? Side.WHITE : Side.BLACK;
    }

    /** The deepest evaluation; on equal depth the one with more lines, then with more nodes. */
    public CloudEval deepest() {
        return evals.stream().max(DEPTH_ORDER).orElseThrow();
    }

    /** The deepest evaluation that has at least {@code minPvs} lines (e.g. for a MultiPV request). */
    public Optional<CloudEval> deepest(int minPvs) {
        return evals.stream().filter(e -> e.pvs().size() >= minPvs).max(DEPTH_ORDER);
    }

    /** Best line of the deepest evaluation: what Lichess recommends to use as "the" evaluation of the position. */
    public CloudPv bestPv() {
        return deepest().best();
    }

    public int maxDepth() {
        return deepest().depth();
    }

    /** Evaluations of both objects, for positions that occur in the dump under several FENs. */
    public CloudEvalPosition plus(CloudEvalPosition other) {
        List<CloudEval> all = new ArrayList<>(evals);
        all.addAll(other.evals);
        return new CloudEvalPosition(key, all);
    }
}
