package com.ser_databus.tpg.system;

import com.ser_databus.tpg.system.Rule.ExactRule;
import com.ser_databus.tpg.system.Rule.ReactionRule;
import com.ser_databus.tpg.system.Rule.SetupRule;

import java.util.List;

/**
 * How much a human has to remember to play a system, in "things to remember". A move the crowd plays anyway where
 * the rule plays it costs almost nothing; a move nobody plays there costs {@link SystemSettings#getMoveCost()}.
 * Recognizing an exact position or noticing a trigger costs extra. Rules that never decide a move cost nothing.
 */
public final class EffortModel {
    private final SystemSettings settings;

    public EffortModel(SystemSettings settings) {
        this.settings = settings;
    }

    /** Effort per rule, in the order of {@link Policy#rules()}. */
    public double[] perRule(Policy policy, Evaluation ev) {
        List<Rule> rules = policy.rules();
        double[] effort = new double[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (ev.ruleFires[i] <= 0.) {
                continue;
            }
            double unnatural = 1. - ev.ruleNaturalness(i);
            if (rule instanceof ExactRule) {
                effort[i] = settings.getPositionCost() + settings.getMoveCost() * unnatural;
            } else if (rule instanceof ReactionRule) {
                effort[i] = settings.getTriggerCost() + settings.getMoveCost() * unnatural;
            } else if (rule instanceof SetupRule setup) {
                int setupIndex = i - policy.setupRuleIndex(0);
                double e = setup.isGuarded() ? settings.getTriggerCost() : 0.;
                for (int t = 0; t < setup.tokens().size(); t++) {
                    if (ev.tokenFires[setupIndex][t] > 0.) {
                        e += settings.getMoveCost() * (1. - ev.tokenNaturalness(setupIndex, t));
                    }
                }
                effort[i] = e;
            }
        }
        return effort;
    }

    public double total(Policy policy, Evaluation ev) {
        double sum = 0.;
        for (double e : perRule(policy, ev)) {
            sum += e;
        }
        return sum;
    }
}
