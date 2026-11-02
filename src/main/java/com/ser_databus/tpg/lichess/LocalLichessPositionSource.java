package com.ser_databus.tpg.lichess;

import java.util.concurrent.CompletableFuture;

/**
 * {@link LichessPositionSource} backed by the local Lichess Elite index. The rating selector is ignored: the index
 * contains only elite games. Lookups are local and fast, so the async method returns an already completed future.
 */
public class LocalLichessPositionSource implements LichessPositionSource {
    private final LocalLichessClient client = new LocalLichessClient();

    @Override
    public LichessPosition getPosition(String fen, LichessRatingSelector ratingSelector) {
        return client.getPosition(fen);
    }

    @Override
    public CompletableFuture<LichessPosition> getPositionAsync(String fen, LichessRatingSelector ratingSelector) {
        try {
            return CompletableFuture.completedFuture(client.getPosition(fen));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public void flushCache() {
        // Nothing is cached: the local index is read-only
    }

    @Override
    public void close() {
        client.close();
    }
}
