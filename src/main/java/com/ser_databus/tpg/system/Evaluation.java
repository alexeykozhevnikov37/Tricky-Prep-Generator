package com.ser_databus.tpg.system;

import com.ser_databus.tpg.system.Policy.Decision;

import java.util.List;

/**
 * What a system achieves against the Elite opponents, and how its rules are used.
 */
public final class Evaluation {
    /** One of my positions reached with the system, with the probability of reaching it in a game. */
    public record Visit(ArenaNode node, long[] masks, double prob, Decision decision) {
    }

    /** Expected game score (results blended with engine evals at the leaves). */
    public double score;
    /** Expected engine eval at the leaves. */
    public double eval;
    /** Average score of the worst {@link SystemSettings#getTailFraction()} of the probability mass. */
    public double lowTail;
    /** Share of my decisions (weighted by probability) that the rules made. */
    public double coverage;
    /** Per rule: expected number of times per game it decides my move. */
    public double[] ruleFires;
    /** Per rule: sum of prob * (1 - how often the crowd plays the rule's move there). */
    public double[] ruleUnnatural;
    /** Per setup, per token: the same as above for each move of the setup. */
    public double[][] tokenFires;
    public double[][] tokenUnnatural;
    /** My positions reached with the system; only kept while the system is grown, then dropped. */
    public List<Visit> visits;

    /** How often the crowd plays the rule's move where the rule decides (1 = always). */
    public double ruleNaturalness(int ruleIndex) {
        return ruleFires[ruleIndex] > 0. ? 1. - ruleUnnatural[ruleIndex] / ruleFires[ruleIndex] : 1.;
    }

    public double tokenNaturalness(int setupIndex, int tokenIndex) {
        double fires = tokenFires[setupIndex][tokenIndex];
        return fires > 0. ? 1. - tokenUnnatural[setupIndex][tokenIndex] / fires : 1.;
    }
}
