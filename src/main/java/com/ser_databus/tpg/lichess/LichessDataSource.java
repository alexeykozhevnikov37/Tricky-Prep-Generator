package com.ser_databus.tpg.lichess;

/** Setting: which Lichess statistics the prep evaluator uses. */
public enum LichessDataSource {
    /** Lichess opening explorer API, with the rating range of the {@link LichessRatingSelector} (cached locally). */
    PUBLIC_API,
    /**
     * Local index of the Lichess Elite Database ({@link LocalLichessClient}), positions up to move 25.
     * The rating selector is ignored.
     */
    LOCAL_ELITE_DB;

    public LichessPositionSource create() {
        return switch (this) {
            case PUBLIC_API -> new LichessClient();
            case LOCAL_ELITE_DB -> new LocalLichessPositionSource();
        };
    }
}
