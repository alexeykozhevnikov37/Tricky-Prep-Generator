package com.ser_databus.tpg.system;

import java.util.ArrayList;
import java.util.List;

/**
 * One opening system and how it grew: step 0 is the seed (empty, or just the first move), every later step adds or
 * extends one rule. Tiers are the steps after which the score improved by more than the noise.
 */
public final class SystemPath {
    /**
     * @param change   what this step changed, for printing; null for the seed
     * @param ruleEffort effort per rule, in the order of the policy's rules
     */
    public record Step(Policy policy, Evaluation evaluation, double effort, double[] ruleEffort, String change,
                       double gain) {
    }

    public enum Status { GROWING, COMPLETE, PRUNED }

    public final String name;
    public final List<Step> steps = new ArrayList<>();
    public Status status = Status.GROWING;
    /** Indexes of the steps that start a new tier (0 = the seed is always included). */
    public final List<Integer> tiers = new ArrayList<>();
    /** Per tier: the bootstrap standard deviation of its gain over the previous tier. */
    public final List<Double> tierGainSd = new ArrayList<>();

    public SystemPath(String name) {
        this.name = name;
    }

    public Step last() {
        return steps.get(steps.size() - 1);
    }

    /** The best score among the steps that need at most this much effort. */
    public double bestScoreWithin(double effort) {
        double best = Double.NEGATIVE_INFINITY;
        for (Step step : steps) {
            if (step.effort() <= effort + 1e-9) {
                best = Math.max(best, step.evaluation().score);
            }
        }
        return best;
    }

    /** The final system: the last tier. */
    public Step finalStep() {
        return tiers.isEmpty() ? last() : steps.get(tiers.get(tiers.size() - 1));
    }
}
