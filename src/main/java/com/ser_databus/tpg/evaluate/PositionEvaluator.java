package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * What {@link com.ser_databus.tpg.prep.PrepEvaluator} needs from an engine: the best line of a position.
 * Scores are in pawns from the side to move's point of view; {@code Optional.empty()} means the side to move has
 * no legal moves.
 */
public interface PositionEvaluator extends AutoCloseable {
    CompletableFuture<Optional<LineEval>> getBestLineAsync(Board board, int depth);

    /**
     * Evaluates the given moves of the position, to compare sibling moves. {@link StockfishClient} does it in one
     * search (MultiPV restricted to these moves); {@link CloudFirstEvaluator} evaluates the position after each move
     * separately by its best line (the negated eval of the best reply), as the other lines of multi-line cloud
     * evaluations aren't reliable.
     * <p>
     * Scores are in pawns from the side to move's point of view, i.e. of the side that makes the moves; each
     * {@link LineEval#move} is the evaluated move itself. The result has an entry per move, in the order of the
     * given moves. The moves must be legal and distinct, and there must be at least one.
     */
    CompletableFuture<Map<Move, LineEval>> evaluateMovesAsync(Board board, List<Move> moves, int depth);

    /** Synchronous version of {@link #getBestLineAsync(Board, int)}. */
    default Optional<LineEval> getBestLine(Board board, int depth) {
        try {
            return getBestLineAsync(board, depth).join();
        } catch (CompletionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    /**
     * Drops the queued evaluations that nobody needs any more: those that aren't requested again from now on are
     * skipped instead of calculated, and their futures fail with a CancellationException. Called when a prep
     * generation ends, so the work of a cancelled generation doesn't hold up the next one.
     */
    default void abandonPendingRequests() {
    }

    /** Persists whatever was calculated since the last call, if the evaluator caches anything. */
    void flushCache();

    void printStats();

    @Override
    void close();
}
