package com.ser_databus.tpg.util;

public class Const {
    public static final double VICTORY_SCORE = 327.;
    public static final Long MS_IN_NS = 1_000_000L;
    /**
     * Lichess Win% = 50 + 50 * (2 / (1 + e^(-LICHESS_WIN_SLOPE * centipawns)) - 1), with the centipawns limited to
     * +-{@link #LICHESS_MAX_CENTIPAWNS} (<a href="https://lichess.org/page/accuracy">...</a>)
     */
    public static final double LICHESS_WIN_SLOPE = 0.00368208 * 2;
    public static final double LICHESS_MAX_CENTIPAWNS = 1000;
    public static final double TYPICAL_INACCURACY_EVAL_LOSS = 0.3;
    /**
     * Scale of the normalized eval loss (Lichess Win% / 100) in the soft count of my viable moves: a move that loses
     * this much counts as e^-1 (about 0.37) of a viable move, one that loses twice as much as 0.14. 0.05 is about
     * what {@link #TYPICAL_INACCURACY_EVAL_LOSS} (0.3 pawns) loses near equality.
     */
    public static final double TYPICAL_INACCURACY_NORM_EVAL_LOSS = 0.05;
    public static final int MIN_GAMES_FOR_OP_MOVE = 2;
    public static final int MIN_GAMES_FOR_VALID_SCORE = 25;
    /**
     * Weight (in games) of the engine's expectation when a Lichess score of my move is shrunk toward it: the score of
     * a move played n times is (n * lichessScore + SCORE_PRIOR_GAMES * normEval) / (n + SCORE_PRIOR_GAMES), so
     * small samples count less than big ones.
     */
    public static final double SCORE_PRIOR_GAMES = 50.;
}
