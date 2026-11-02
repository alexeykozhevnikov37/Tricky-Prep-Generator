package com.ser_databus.tpg.lichess;

import com.ser_databus.tpg.elite.EliteIndexStore;
import com.ser_databus.tpg.elite.PositionAgg;
import com.ser_databus.tpg.elite.PositionKey;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Counterpart of {@link LichessClient} that reads positions from the local Lichess Elite index
 * (built by {@link com.ser_databus.tpg.elite.EliteIndexBuilder}) instead of the Lichess explorer API.
 * There is no rating selector: the index contains only the elite games.
 * <p>
 * The returned {@link LichessPosition} has the same shape as the API one: all moves played in the position sorted by
 * number of games, position totals equal to the sum over the moves. {@code averageRating} isn't stored and is 0.
 * Positions are indexed up to move 25; positions not in the index return an empty position (no games, no moves),
 * the same way the Lichess explorer answers for an unknown position.
 * <p>
 * The FEN may come from any source: the en passant square is normalized the same way as in the index
 * (see {@link PositionKey}). The client is thread-safe.
 */
@Slf4j
public class LocalLichessClient implements AutoCloseable {
    public static final String DEFAULT_INDEX_DIR = "data/eliteIndex";

    private final EliteIndexStore store;
    private volatile boolean closed;

    public LocalLichessClient() {
        this(Paths.get(DEFAULT_INDEX_DIR));
    }

    public LocalLichessClient(Path indexDir) {
        try {
            store = new EliteIndexStore(indexDir, true);
        } catch (IOException | RocksDBException e) {
            throw new IllegalStateException("Couldn't open the local Lichess index at " + indexDir.toAbsolutePath(), e);
        }
        try {
            store.checkKeyFormat(false);
        } catch (RocksDBException | RuntimeException e) {
            store.close();
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeQuietly));
    }

    /** Same contract as {@link LichessClient#getPosition}, without the rating selector. */
    public LichessPosition getPosition(String fen) {
        if (closed) {
            throw new IllegalStateException("LocalLichessClient is closed");
        }
        String key = PositionKey.of(fen);
        try {
            PositionAgg agg = store.getPosition(key);
            log.trace("Local Lichess position {}: {}", key, agg == null ? "not found" : "found");
            return toLichessPosition(agg);
        } catch (RocksDBException e) {
            throw new IllegalStateException("Couldn't read position " + key + " from the local index", e);
        }
    }

    static LichessPosition toLichessPosition(PositionAgg agg) {
        List<LichessMoveStats> moves = new ArrayList<>();
        long white = 0, draws = 0, black = 0;
        if (agg != null) {
            for (PositionAgg.MoveAgg m : agg.getMoves()) {
                LichessMoveStats stats = new LichessMoveStats();
                stats.uci = m.uci;
                stats.san = m.san;
                stats.white = m.white;
                stats.draws = m.draws;
                stats.black = m.black;
                moves.add(stats);
                white += m.white;
                draws += m.draws;
                black += m.black;
            }
        }
        moves.sort(Comparator.comparingLong(LichessMoveStats::getGames).reversed());
        return new LichessPosition(white, draws, black, moves);
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
