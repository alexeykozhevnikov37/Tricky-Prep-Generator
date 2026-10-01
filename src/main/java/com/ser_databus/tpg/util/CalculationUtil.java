package com.ser_databus.tpg.util;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.lichess.LichessMoveStats;
import com.ser_databus.tpg.lichess.LichessPosition;

import static com.ser_databus.tpg.util.Const.ADVANTAGE_TO_WIN;
import static com.ser_databus.tpg.util.Const.VICTORY_SCORE;

public class CalculationUtil {
    public static double normalizeEval(double eval) {
        return 1 / (1 + Math.exp(-eval / ADVANTAGE_TO_WIN));
    }

    public static double denormalizeEval(double normEval) {
        if (normEval <= 0.) {
            return -VICTORY_SCORE;
        } else if (normEval >= 1.) {
            return VICTORY_SCORE;
        }
        return -ADVANTAGE_TO_WIN * Math.log (1 / normEval - 1);
    }

    public static double getAverageScore(LichessMoveStats lms, Side side) {
        return getAverageScore(lms.white, lms.draws, lms.getGames(), side);
    }

    public static double getAverageScore(LichessPosition position, Side side) {
        return getAverageScore(position.getWhite(), position.getDraws(), position.getGames(), side);
    }

    private static double getAverageScore(long whiteWins, long draws, long games, Side side) {
        if (games <= 0) {
            return 0.5;
        }
        double averageScore = (whiteWins + draws / 2.) / games;
        return side == Side.WHITE ? averageScore : 1. - averageScore;
    }
}
