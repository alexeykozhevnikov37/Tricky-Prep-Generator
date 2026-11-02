package com.ser_databus.tpg.system;

import com.ser_databus.tpg.system.Rule.SetupRule;
import com.ser_databus.tpg.system.SystemGrower.Result;
import com.ser_databus.tpg.system.SystemPath.Step;

import java.io.PrintStream;
import java.util.List;

import static com.ser_databus.tpg.system.Arena.pct;

/** Prints the systems found. Percentages are normalized scores / evals from my point of view. */
public final class SystemReport {
    private final PrintStream out;
    private final Arena arena;
    private final SystemSettings settings;

    public SystemReport(PrintStream out, Arena arena, SystemSettings settings) {
        this.out = out;
        this.arena = arena;
        this.settings = settings;
    }

    public void printHeader(String fen) {
        out.println();
        out.println("=== Opening systems ===");
        out.println("Root: " + fen + ", I play " + arena.getSide());
        out.println("Root eval " + pct(arena.getRoot().normEval) + "%, eval floor " + pct(arena.getEvalFloor())
                + "% (maxEvalSetback " + settings.getMaxEvalSetback() + ")");
        out.println("Arena: " + arena.size() + " positions, " + arena.getExpandedCount() + " explored, depth "
                + arena.getDepth() + " plies");
    }

    /** Short intermediate result: one line per system. */
    public void printBrief(Result result) {
        out.println();
        out.println("--- Depth " + arena.getDepth() + " plies, " + arena.size() + " positions ---");
        out.println("Crowd baseline: score " + pct(result.baseline().score) + "%");
        for (SystemPath system : result.systems()) {
            Step last = system.last();
            out.printf("%-20s %2d steps, %4.1f things to remember, score %s%%, eval %s%%, %s%n", system.name,
                    system.steps.size() - 1, last.effort(), pct(last.evaluation().score),
                    pct(last.evaluation().eval), system.status);
        }
    }

    public void print(Result result) {
        Evaluation baseline = result.baseline();
        out.println();
        out.println("Crowd baseline (no rules, play like the Elite players): score " + pct(baseline.score)
                + "%, eval " + pct(baseline.eval) + "%, worst " + Math.round(settings.getTailFraction() * 100)
                + "%: " + pct(baseline.lowTail) + "%");
        for (SystemPath system : result.systems()) {
            printSystem(system);
        }
    }

    private void printSystem(SystemPath system) {
        out.println();
        out.println("##### " + system.name + "  [" + system.status + "]");
        List<Step> steps = system.steps;
        int previousTierStep = -1;
        for (int t = 0; t < system.tiers.size(); t++) {
            int tierStep = system.tiers.get(t);
            Step step = steps.get(tierStep);
            Evaluation ev = step.evaluation();
            String gain = "";
            if (previousTierStep >= 0) {
                double g = ev.score - steps.get(previousTierStep).evaluation().score;
                double sd = system.tierGainSd.get(t);
                gain = Double.isNaN(sd) ? String.format(" (%+.1f)", g * 100.)
                        : String.format(" (%+.1f +-%.1f)", g * 100., sd * 100.);
            }
            out.printf("%s %d: %.1f things to remember, score %s%%%s, eval %s%% %s, worst %d%%: %s%%, "
                            + "rules decide %s%% of my moves%n",
                    t == 0 ? "Start" : "Tier", t, step.effort(), pct(ev.score), gain, pct(ev.eval),
                    ev.eval >= arena.getEvalFloor() ? "OK" : "BELOW FLOOR",
                    Math.round(settings.getTailFraction() * 100), pct(ev.lowTail), pct(ev.coverage));
            for (int i = previousTierStep + 1; i <= tierStep; i++) {
                Step s = steps.get(i);
                if (s.change() != null) {
                    out.printf("      %s  (%+.1f)%n", s.change(), s.gain() * 100.);
                }
            }
            previousTierStep = tierStep;
        }
        int extra = steps.size() - 1 - previousTierStep;
        if (extra > 0) {
            double g = system.last().evaluation().score - steps.get(previousTierStep).evaluation().score;
            out.printf("   Not significant: %d more step(s) after the last tier, %+.1f in total%n", extra, g * 100.);
        }
        printRules(system.finalStep());
    }

    private void printRules(Step step) {
        Policy policy = step.policy();
        Evaluation ev = step.evaluation();
        List<Rule> rules = policy.rules();
        if (rules.isEmpty()) {
            return;
        }
        out.println("   Rules of the final tier:");
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            out.printf("    - %s%n", rule.describe());
            out.printf("        decides %.2f moves per game, crowd plays the same %s%%, effort %.1f%n",
                    ev.ruleFires[i], pct(ev.ruleNaturalness(i)), step.ruleEffort()[i]);
            if (rule instanceof SetupRule setup) {
                int setupIndex = i - policy.setupRuleIndex(0);
                StringBuilder sb = new StringBuilder("        moves:");
                for (int t = 0; t < setup.tokens().size(); t++) {
                    sb.append(' ').append(setup.tokens().get(t)).append(" (");
                    if (ev.tokenFires[setupIndex][t] > 0.) {
                        sb.append(String.format("%.2f/game, natural %s%%", ev.tokenFires[setupIndex][t],
                                pct(ev.tokenNaturalness(setupIndex, t))));
                    } else {
                        sb.append("never decides");
                    }
                    sb.append(')');
                }
                out.println(sb);
            }
        }
    }
}
