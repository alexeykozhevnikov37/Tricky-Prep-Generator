package com.ser_databus.tpg.util;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.lichess.LichessMoveStats;
import com.ser_databus.tpg.lichess.LichessPosition;

import static com.ser_databus.tpg.util.Const.LICHESS_MAX_CENTIPAWNS;
import static com.ser_databus.tpg.util.Const.LICHESS_WIN_SLOPE;

public class CalculationUtil {
    /**
     * The eval (in pawns) as Lichess Win% / 100: 1 / (1 + e^(-0.00368208 * centipawns)), the centipawns limited to
     * +-1000 like on Lichess, so from about 0.025 to 0.975. Mates count as +-10 pawns.
     */
    public static double normalizeEval(double eval) {
        double centipawns = Math.max(-LICHESS_MAX_CENTIPAWNS, Math.min(LICHESS_MAX_CENTIPAWNS, 100 * eval));
        return 1 / (1 + Math.exp(-LICHESS_WIN_SLOPE * centipawns));
    }

    /** The eval in pawns of a normalized eval: the inverse of {@link #normalizeEval}, so at most +-10 pawns. */
    public static double denormalizeEval(double normEval) {
        double max = LICHESS_MAX_CENTIPAWNS / 100;
        if (normEval <= 0.) {
            return -max;
        } else if (normEval >= 1.) {
            return max;
        }
        double eval = -Math.log(1 / normEval - 1) / (100 * LICHESS_WIN_SLOPE);
        return Math.max(-max, Math.min(max, eval));
    }

    public static double getAverageScore(LichessMoveStats lms, Side side) {
        if (lms == null) {
            return 0.5;
        }
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
