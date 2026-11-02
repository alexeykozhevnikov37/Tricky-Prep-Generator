package com.ser_databus.tpg.system;

import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.util.Const;
import lombok.Builder;
import lombok.Getter;

/**
 * Settings of the opening system miner. All evals and scores are normalized: Lichess Win% / 100 from my side's point
 * of view (see {@link com.ser_databus.tpg.util.CalculationUtil#normalizeEval}).
 */
@Builder
@Getter
public class SystemSettings {
    // ---------- engine ----------
    @Builder.Default
    private int engineSearchDepth = 18;
    @Builder.Default
    private EvalSource evalSource = EvalSource.CLOUD_THEN_STOCKFISH;

    // ---------- arena (the tree of positions reached by human play) ----------
    /**
     * How much lower than the root position's eval a position after my move may be. Positions below this floor are
     * not explored further, and a system whose expected eval falls below it (and doesn't improve it) is rejected.
     */
    @Builder.Default
    private double maxEvalSetback = 0.1;
    /** Opponent moves that lead to a position reached with a lower probability than this aren't explored. */
    @Builder.Default
    private double minReach = 0.005;
    /** Moves played fewer times than this are ignored (they count with the position's own value). */
    @Builder.Default
    private int minGamesForMove = 2;
    /** Positions with fewer games are leaves: valued, but not explored further. */
    @Builder.Default
    private int minGamesToExpand = 20;
    /** My moves played in a smaller share of the games than this aren't explored (the engine's best move always is). */
    @Builder.Default
    private double minCrowdShare = 0.02;
    /** Positions where the eval is at least this (or at most 1 - this) are decided: leaves, not explored further. */
    @Builder.Default
    private double settledNormEval = 0.9;
    /** The Elite index holds positions up to move 25. */
    @Builder.Default
    private int maxMoveNumber = 25;
    /** Weight (in games) of the engine eval when a position's game score is blended with it. */
    @Builder.Default
    private double scorePriorGames = Const.SCORE_PRIOR_GAMES;

    // ---------- growing the systems ----------
    /** Different first moves to start systems from, when the root position is my move. */
    @Builder.Default
    private int maxSystems = 5;
    /** Maximum number of growth steps (added or extended rules) per system. */
    @Builder.Default
    private int maxSteps = 25;
    /** Maximum number of setups of each kind (unguarded / guarded) in a system. */
    @Builder.Default
    private int maxSetups = 3;
    /** Candidates of each kind taken from the mining step and evaluated exactly. */
    @Builder.Default
    private int candidatesPerKind = 8;
    /** A step must gain at least this much score. */
    @Builder.Default
    private double minGain = 0.001;
    /** Effort differences smaller than this count as this much when gain per effort is compared. */
    @Builder.Default
    private double minEffortStep = 0.25;
    /**
     * A system stops growing when another system already scores more than this better with no more effort.
     * This is what limits the run time.
     */
    @Builder.Default
    private double pruneMargin = 0.02;
    /** Steps every system may take before it can be pruned. */
    @Builder.Default
    private int minStepsBeforePrune = 2;

    // ---------- effort ("things to remember") ----------
    /** Cost of recognizing an exact position. */
    @Builder.Default
    private double positionCost = 1.;
    /** Cost of noticing a trigger (an opponent's piece on a square). */
    @Builder.Default
    private double triggerCost = 1.;
    /** Cost of a move nobody would play: a move is charged this times (1 - how often the crowd plays it there). */
    @Builder.Default
    private double moveCost = 1.;

    // ---------- tiers ----------
    /** Bootstrap replicates of the game statistics used to tell real gains from noise. */
    @Builder.Default
    private int bootstrapReplicates = 30;
    /** A new tier needs a gain of at least this many standard deviations of the bootstrap noise... */
    @Builder.Default
    private double tierZ = 2.;
    /** ...and at least this much score. */
    @Builder.Default
    private double minTierGain = 0.003;
    /** Share of the probability mass whose average score is reported as the "worst case". */
    @Builder.Default
    private double tailFraction = 0.1;

    // ---------- output ----------
    /** Print short intermediate results after every full move of depth. */
    @Builder.Default
    private boolean progressive = false;
    /** Intermediate results start at this depth (plies from the root). */
    @Builder.Default
    private int progressiveFromPly = 6;
}
