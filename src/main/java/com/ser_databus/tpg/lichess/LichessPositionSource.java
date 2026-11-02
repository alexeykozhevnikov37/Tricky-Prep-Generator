package com.ser_databus.tpg.lichess;

import java.util.concurrent.CompletableFuture;

/** Where {@link com.ser_databus.tpg.prep.PrepEvaluator} gets Lichess position statistics from. */
public interface LichessPositionSource extends AutoCloseable {
    LichessPosition getPosition(String fen, LichessRatingSelector ratingSelector);

    CompletableFuture<LichessPosition> getPositionAsync(String fen, LichessRatingSelector ratingSelector);

    /** Persists whatever was fetched since the last call, if the source caches anything. */
    void flushCache();

    /**
     * Persists what is cached and releases the resources (threads, databases). Called when the user switches to
     * another Lichess data source; the source isn't used afterwards. Must be idempotent.
     */
    @Override
    void close();
}
