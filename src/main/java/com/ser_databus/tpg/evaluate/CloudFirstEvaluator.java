package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.ser_databus.tpg.evaldb.CloudEval;
import com.ser_databus.tpg.evaldb.CloudEvalPosition;
import com.ser_databus.tpg.evaldb.CloudPv;
import com.ser_databus.tpg.evaldb.LocalCloudEvalClient;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.ser_databus.tpg.util.Const.VICTORY_SCORE;
import static com.ser_databus.tpg.util.MoveUtil.lichessUciToMove;

/**
 * Answers from the local Lichess cloud evaluations when they have the position searched at least as deep as
 * requested, and asks Stockfish otherwise. Cloud evaluations take precedence over Stockfish's own eval cache.
 * <p>
 * The cloud answer is the best line of the deepest cloud evaluation (whether it's a single-line or a multi-line
 * one), converted to the {@link StockfishClient} conventions: score from the side to move's point of view, standard
 * castling moves. Only that best line is used: the other lines of a multi-line evaluation aren't reliable. The
 * decision is made per position, so sibling moves may get evaluations of different depths and Stockfish versions.
 * Cloud lookups take microseconds and run on the calling thread; the returned future is already completed.
 */
@Slf4j
public class CloudFirstEvaluator implements PositionEvaluator {
    private final LocalCloudEvalClient cloud;
    private final StockfishClient stockfish;

    private final AtomicLong cloudHits = new AtomicLong();
    private final AtomicLong notInCloud = new AtomicLong();
    private final AtomicLong tooShallow = new AtomicLong();
    private final AtomicLong unusable = new AtomicLong();

    public CloudFirstEvaluator(LocalCloudEvalClient cloud, StockfishClient stockfish) {
        this.cloud = cloud;
        this.stockfish = stockfish;
        log.info("Engine evaluations: Lichess cloud evals ({}), Stockfish for the rest",
                cloud.getBuildSettings().describe());
    }

    @Override
    public CompletableFuture<Optional<LineEval>> getBestLineAsync(Board board, int depth) {
        LineEval cloudLine = lookup(board, depth);
        if (cloudLine != null) {
            return CompletableFuture.completedFuture(Optional.of(cloudLine));
        }
        return stockfish.getBestLineAsync(board, depth);
    }

    /**
     * Evaluates the position after each move separately with a single line, cloud first ({@link #getBestLineAsync}):
     * the eval of a move is the negated eval of the best reply. The other lines of multi-line cloud evaluations of the
     * position itself aren't used, as they aren't reliable. A local MultiPV search of the whole move set is avoided, too: the
     * positions after the moves usually are in the cloud, and one MultiPV search of N moves runs on a single engine
     * about N times as long as a single search, while the other engines wait.
     */
    @Override
    public CompletableFuture<Map<Move, LineEval>> evaluateMovesAsync(Board board, List<Move> moves, int depth) {
        Map<Move, CompletableFuture<LineEval>> futures = new LinkedHashMap<>();
        for (Move move : moves) {
            Board after = board.clone();
            after.doMove(move);
            int rank = futures.size() + 1;
            futures.put(move, getBestLineAsync(after, depth).thenApply(reply -> new LineEval(rank,
                    // No reply: the move mates or stalemates
                    reply.map(le -> -le.eval).orElse(after.isKingAttacked() ? VICTORY_SCORE : 0.),
                    move, reply.map(le -> le.depth).orElse(depth))));
        }
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            Map<Move, LineEval> result = new LinkedHashMap<>();
            futures.forEach((move, future) -> result.put(move, future.join()));
            return result;
        });
    }

    /** @return the cloud line, or null if Stockfish must evaluate the position */
    private LineEval lookup(Board original, int depth) {
        // A private copy: move generation shouldn't touch a board that the caller may share
        Board board = original.clone();
        Optional<CloudEvalPosition> position = cloud.getEvals(board);
        if (position.isEmpty()) {
            notInCloud.incrementAndGet();
            return null;
        }
        CloudEval deepest = position.get().deepest();
        if (deepest.depth() < depth) {
            tooShallow.incrementAndGet();
            return null;
        }
        CloudPv pv = deepest.best();
        try {
            if (pv.firstMove() != null) {
                Move move = lichessUciToMove(board, pv.firstMove());
                if (MoveGenerator.generateLegalMoves(board).contains(move)) {
                    cloudHits.incrementAndGet();
                    log.trace("{}: cloud eval depth {}, best move {} gives {}", board.getFen(), deepest.depth(),
                            move, pv.scoreText());
                    return new LineEval(1, pv.score(board.getSideToMove()), move, deepest.depth());
                }
            }
        } catch (RuntimeException e) {
            log.trace("{}: unusable cloud line {}: {}", board.getFen(), pv.moves(), e.toString());
        }
        unusable.incrementAndGet();
        return null;
    }

    @Override
    public void abandonPendingRequests() {
        stockfish.abandonPendingRequests();
    }

    @Override
    public void flushCache() {
        // Only Stockfish results are cached; the cloud index is read-only
        stockfish.flushCache();
    }

    @Override
    public void printStats() {
        long hits = cloudHits.get();
        long total = hits + notInCloud.get() + tooShallow.get() + unusable.get();
        log.debug("Cloud eval stats: {} requests, {} answered from the cloud ({}%), {} not in the index, "
                        + "{} too shallow, {} unusable",
                total, hits, total == 0 ? 0 : Math.round(100. * hits / total), notInCloud.get(), tooShallow.get(),
                unusable.get());
        stockfish.printStats();
    }

    @Override
    public void close() {
        stockfish.close();
        cloud.close();
    }
}
