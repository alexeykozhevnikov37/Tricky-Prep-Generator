package com.ser_databus.tpg.evaldb;

import com.github.bhlangonijr.chesslib.Board;
import com.ser_databus.tpg.elite.PositionKey;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Reads Stockfish evaluations from the local copy of the Lichess cloud evaluation dump
 * (built by {@link CloudEvalIndexBuilder}), so positions that Lichess users have already analysed don't need to be
 * calculated again.
 * <p>
 * Things to keep in mind:
 * <ul>
 *     <li>Scores are from White's point of view ({@link CloudPv#score} converts them).</li>
 *     <li>The evaluations come from many Stockfish versions running in users' browsers; the dump doesn't say which.
 *     Use {@link CloudEval#depth()} to decide whether an evaluation is good enough.</li>
 *     <li>Positions that were filtered out at build time (see {@link #getBuildSettings()}) are reported as missing,
 *     the same as positions that Lichess never analysed.</li>
 * </ul>
 * The FEN may come from any source: the en passant square is normalized the same way as in the index
 * (see {@link PositionKey}); move counters are ignored. The client is thread-safe and lookups take microseconds.
 */
@Slf4j
public class LocalCloudEvalClient implements AutoCloseable {
    public static final String DEFAULT_INDEX_DIR = "data/evalIndex";

    private final CloudEvalStore store;
    private final CloudEvalStore.BuildSettings buildSettings;
    private volatile boolean closed;

    public LocalCloudEvalClient() {
        this(Paths.get(DEFAULT_INDEX_DIR));
    }

    public LocalCloudEvalClient(Path indexDir) {
        try {
            store = new CloudEvalStore(indexDir, true);
        } catch (IOException | RocksDBException e) {
            throw new IllegalStateException("Couldn't open the cloud eval index at " + indexDir.toAbsolutePath()
                    + ": " + e.getMessage(), e);
        }
        try {
            store.checkKeyFormat();
            buildSettings = store.getBuildSettings();
        } catch (RocksDBException | RuntimeException e) {
            store.close();
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeQuietly));
    }

    /** All stored evaluations of the position, or empty if the position isn't in the index. */
    public Optional<CloudEvalPosition> getEvals(String fen) {
        return lookup(PositionKey.of(fen));
    }

    /** All stored evaluations of the position, or empty if the position isn't in the index. */
    public Optional<CloudEvalPosition> getEvals(Board board) {
        return lookup(PositionKey.of(board));
    }

    /** Best line of the deepest evaluation, if the position is in the index and was searched at least that deep. */
    public Optional<CloudPv> getBestPv(String fen, int minDepth) {
        return getEvals(fen).map(CloudEvalPosition::deepest).filter(e -> e.depth() >= minDepth).map(CloudEval::best);
    }

    /**
     * The deepest evaluation with at least {@code multiPv} lines and at least {@code minDepth} depth: the cached
     * counterpart of a MultiPV search.
     */
    public Optional<CloudEval> getMultiPv(String fen, int multiPv, int minDepth) {
        return getEvals(fen).flatMap(p -> p.deepest(multiPv)).filter(e -> e.depth() >= minDepth);
    }

    /** What the index keeps from the dump. */
    public CloudEvalStore.BuildSettings getBuildSettings() {
        return buildSettings;
    }

    /** How much of the dump has been indexed, as of opening the client; null if nothing is indexed yet. */
    public CloudEvalProgress getProgress() {
        try {
            return store.getProgress();
        } catch (RocksDBException e) {
            throw new IllegalStateException("Couldn't read the cloud eval index progress", e);
        }
    }

    /** Approximate number of stored positions. */
    public long estimatePositions() {
        return store.estimatePositions();
    }

    private Optional<CloudEvalPosition> lookup(String key) {
        if (closed) {
            throw new IllegalStateException("LocalCloudEvalClient is closed");
        }
        try {
            CloudEvalPosition position = store.get(key);
            log.trace("Cloud eval {}: {}", key, position == null ? "not found" : "depth " + position.maxDepth());
            return Optional.ofNullable(position);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Couldn't read position " + key + " from the cloud eval index", e);
        }
    }

    private void closeQuietly() {
        try {
            close();
        } catch (RuntimeException e) {
            // Shutting down anyway
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            store.close();
        }
    }
}
