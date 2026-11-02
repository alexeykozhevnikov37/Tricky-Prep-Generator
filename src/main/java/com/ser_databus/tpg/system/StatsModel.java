package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.system.ArenaNode.Edge;

/**
 * The game statistics as the policy evaluator sees them: either the real counts, or a bootstrap replicate of them
 * (every count resampled from a Poisson distribution with the real count as its mean). Replicates are deterministic:
 * the same replicate number always gives the same counts, without storing them.
 */
public final class StatsModel {
    private final Side side;
    private final double priorGames;
    /** -1 for the real counts */
    private final int replicate;
    private final long replicateSeed;

    public StatsModel(Side side, double priorGames, int replicate) {
        this.side = side;
        this.priorGames = priorGames;
        this.replicate = replicate;
        this.replicateSeed = mix(0x5EEDL + replicate * 0x9E3779B97F4A7C15L);
    }

    public static StatsModel base(Side side, double priorGames) {
        return new StatsModel(side, priorGames, -1);
    }

    /** Games with the move. */
    public double edgeGames(Edge edge) {
        return replicate < 0 ? edge.games : poisson(edge.games, seed(1, edge.id));
    }

    /** All games in the position: the explored moves plus the rest. */
    public double nodeTotal(ArenaNode node) {
        if (replicate < 0) {
            return node.totalGames();
        }
        double total = 0.;
        for (Edge edge : node.edges) {
            total += edgeGames(edge);
        }
        long rest = Math.max(0, node.totalGames() - node.edgeGames);
        return total + poisson(rest, seed(2, node.id));
    }

    /**
     * Value of a position that isn't explored further: my game score there, blended with the engine eval. With few
     * games the eval dominates, with many games the results do.
     */
    public double staticScore(ArenaNode node) {
        if (node.terminal) {
            return node.terminalScore;
        }
        double white = node.white;
        double draws = node.draws;
        double black = node.black;
        if (replicate >= 0) {
            white = poisson(node.white, seed(3, node.id));
            draws = poisson(node.draws, seed(4, node.id));
            black = poisson(node.black, seed(5, node.id));
        }
        double games = white + draws + black;
        if (games <= 0.) {
            return node.normEval;
        }
        double myWins = side == Side.WHITE ? white : black;
        double score = (myWins + draws / 2.) / games;
        return (games * score + priorGames * node.normEval) / (games + priorGames);
    }

    private long seed(int kind, int id) {
        return mix(replicateSeed ^ mix(kind * 0x632BE59BD9B4E019L + id));
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double uniform(long seed) {
        return ((mix(seed) >>> 11) + 0.5) * 0x1.0p-53;
    }

    private static double poisson(long lambda, long seed) {
        if (lambda <= 0) {
            return 0.;
        }
        if (lambda < 30) {
            double limit = Math.exp(-lambda);
            double p = 1.;
            int k = 0;
            long s = seed;
            do {
                k++;
                p *= uniform(s++);
            } while (p > limit);
            return k - 1;
        }
        double u1 = uniform(seed);
        double u2 = uniform(seed + 0x2545F4914F6CDD1DL);
        double gaussian = Math.sqrt(-2. * Math.log(u1)) * Math.cos(2. * Math.PI * u2);
        return Math.max(0., Math.rint(lambda + Math.sqrt(lambda) * gaussian));
    }
}
