package com.ser_databus.tpg;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import com.ser_databus.tpg.prep.GeneratorSettings;
import com.ser_databus.tpg.prep.IterativeDeepeningPrepEvaluator;

public class Main {
    public static void main(String[] args) {
        String fen = "r1bqkb1r/p4ppp/2p2n2/nB2p1N1/8/8/PPPP1PPP/RNBQK2R w KQkq - 0 8";
        Side side = Side.BLACK;
        LichessRatingSelector lichessRatingSelector = LichessRatingSelector.regular(LichessRating.R2200, LichessRating.R2500);
        GeneratorSettings settings = new GeneratorSettings(20, 0.01, 2., 0.01,
                0.16, 0.05, 0.01, 5., true);
        IterativeDeepeningPrepEvaluator idpe = new IterativeDeepeningPrepEvaluator(settings);
        idpe.generateTrickyPrep(fen, side, lichessRatingSelector);
    }
}
