package com.ser_databus.tpg.prep;

import com.ser_databus.tpg.util.Const;

/** Setting: what the value (VP) of a node at the end of a prep line, and of the offbeat continuations, is based on. */
public enum NodeEvaluationMethod {
    /** The engine's normalized eval only. */
    STOCKFISH,
    /**
     * The average score of the Lichess players in the position, shrunk toward the engine's normalized eval by
     * {@link Const#NODE_VALUE_PRIOR_GAMES} games: (n * lichessScore + NODE_VALUE_PRIOR_GAMES * normEval) /
     * (n + NODE_VALUE_PRIOR_GAMES) for a position played in n games. Few games: close to the engine's eval;
     * many games: close to the Lichess score.
     */
    LICHESS_THEN_STOCKFISH
}
