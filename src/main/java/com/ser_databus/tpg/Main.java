package com.ser_databus.tpg;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import com.ser_databus.tpg.prep.GeneratorSettings;
import com.ser_databus.tpg.prep.IterativeDeepeningPrepEvaluator;

public class Main {
    public static void main(String[] args) {
        String fen = "rnbqkb1r/pppppppp/5n2/8/8/5N2/PPPPPPPP/RNBQKB1R w KQkq - 2 2";
        Side side = Side.WHITE;
        LichessRatingSelector lichessRatingSelector = LichessRatingSelector.regular(LichessRating.R2200, LichessRating.R2500);
        GeneratorSettings settings = GeneratorSettings.builder()
                .engineSearchDepth(18)
                .minChance(0.01)
                .maxEvalSetback(0.1)
                .vpMarginForPruning(0.1)
                .lichessScoreMarginForPruning(0.0)
                .deepLineFactor(5.)
                .lichessDataSource(LichessDataSource.LOCAL_ELITE_DB)
                .evalSource(EvalSource.CLOUD_THEN_STOCKFISH)
                .isMinChanceIterationEnabled(false)
                .build();
        IterativeDeepeningPrepEvaluator idpe = new IterativeDeepeningPrepEvaluator(settings);
        idpe.generateTrickyPrep(fen, side, lichessRatingSelector);
    }
}
