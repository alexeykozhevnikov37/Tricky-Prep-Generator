package com.ser_databus.tpg.evaluate;

import com.ser_databus.tpg.evaldb.LocalCloudEvalClient;

/** Setting: where the prep evaluator gets engine evaluations from. */
public enum EvalSource {
    /** Local Stockfish only ({@link StockfishClient}). */
    STOCKFISH,
    /**
     * The local copy of the Lichess cloud evaluations ({@link LocalCloudEvalClient}) when it has the position at
     * least as deep as requested, local Stockfish otherwise ({@link CloudFirstEvaluator}).
     */
    CLOUD_THEN_STOCKFISH;

    public PositionEvaluator create() {
        return switch (this) {
            case STOCKFISH -> new StockfishClient();
            case CLOUD_THEN_STOCKFISH -> new CloudFirstEvaluator(new LocalCloudEvalClient(), new StockfishClient());
        };
    }
}
