package com.ser_databus.tpg.prep;

import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class GeneratorSettings {
    private int engineSearchDepth;
    private double minChance;
    private double maxEvalSetback;
    private double vpMarginForPruning;
    private double lichessScoreMarginForPruning;
    private double deepLineFactor;
    /** Lichess public API (with the rating selector) or the local Lichess Elite index. */
    private LichessDataSource lichessDataSource;
    /** The rating range (or the masters database) of the Lichess games. Used only with {@link LichessDataSource#PUBLIC_API}. */
    @Builder.Default
    private LichessRatingSelector lichessRatingSelector =
            LichessRatingSelector.regular(LichessRating.R2200, LichessRating.R2500);
    /**
     * Local Stockfish only, or the local Lichess cloud evaluations first (accepted when searched at least
     * {@link #engineSearchDepth} deep) with Stockfish for the rest.
     */
    @Builder.Default
    private EvalSource evalSource = EvalSource.CLOUD_THEN_STOCKFISH;
    /**
     * If true, the prep is built several times with minChance halving each time, down to {@link #minChance}.
     * If false, only the last iteration runs: the prep is built once, directly with {@link #minChance}.
     */
    @Builder.Default
    private boolean isMinChanceIterationEnabled = true;
}
