package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.system.ArenaNode.Edge;
import com.ser_databus.tpg.system.CandidateMiner.Candidate;
import com.ser_databus.tpg.system.Rule.ExactRule;
import com.ser_databus.tpg.system.SystemPath.Status;
import com.ser_databus.tpg.system.SystemPath.Step;
import com.ser_databus.tpg.util.MoveUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Grows opening systems rule by rule.
 * <ol>
 *     <li>Seeds: if the root is my move, one system per good first move (its eval may not be below the floor);
 *     otherwise one system that starts empty.</li>
 *     <li>Each step mines candidate rules ({@link CandidateMiner}), evaluates each candidate system exactly and takes
 *     the one with the best score gain per unit of effort. A candidate may not push the system's expected eval below
 *     the floor (unless it raises it).</li>
 *     <li>Systems grow in turns. A system stops when nothing gains enough, or when another system already scores
 *     clearly better with no more effort (pruning).</li>
 *     <li>Tiers: with bootstrap replicates of the game statistics, a step starts a new tier when the gain since the
 *     previous tier is clearly bigger than the noise.</li>
 * </ol>
 */
@Slf4j
public final class SystemGrower {
    public record Result(Evaluation baseline, List<SystemPath> systems) {
    }

    private final Arena arena;
    private final SystemSettings settings;
    private final Side side;
    private final StatsModel baseModel;
    private final CandidateMiner miner;
    private final EffortModel effortModel;

    public SystemGrower(Arena arena, SystemSettings settings) {
        this.arena = arena;
        this.settings = settings;
        this.side = arena.getSide();
        this.baseModel = StatsModel.base(side, settings.getScorePriorGames());
        this.miner = new CandidateMiner(settings, side);
        this.effortModel = new EffortModel(settings);
    }

    /** @param withTiers false for quick intermediate results: no bootstrap, every step counts as a tier */
    public Result run(boolean withTiers) {
        Evaluation baseline = evaluate(Policy.empty());
        baseline.visits = null;
        List<SystemPath> systems = seed();
        int round = 0;
        while (round < settings.getMaxSteps() && systems.stream().anyMatch(s -> s.status == Status.GROWING)) {
            round++;
            for (SystemPath system : systems) {
                if (system.status != Status.GROWING) {
                    continue;
                }
                Step step = grow(system.last());
                if (step == null) {
                    system.status = Status.COMPLETE;
                } else {
                    system.steps.add(step);
                    log.info("{} step {}: {} -> score {}%, effort {}", system.name, system.steps.size() - 1,
                            step.change(), Arena.pct(step.evaluation().score), String.format("%.1f", step.effort()));
                }
            }
            prune(systems);
        }
        for (SystemPath system : systems) {
            if (system.status == Status.GROWING) {
                system.status = Status.COMPLETE;
            }
            system.last().evaluation().visits = null;
            if (withTiers) {
                detectTiers(system);
            } else {
                for (int i = 0; i < system.steps.size(); i++) {
                    system.tiers.add(i);
                    system.tierGainSd.add(Double.NaN);
                }
            }
        }
        systems.sort(Comparator.comparingDouble((SystemPath s) -> s.finalStep().evaluation().score).reversed());
        return new Result(baseline, systems);
    }

    private List<SystemPath> seed() {
        List<SystemPath> systems = new ArrayList<>();
        ArenaNode root = arena.getRoot();
        if (!root.myTurn || !root.isExpanded()) {
            SystemPath system = new SystemPath("System");
            system.steps.add(seedStep(Policy.empty(), null));
            systems.add(system);
            return systems;
        }
        List<SystemPath> all = new ArrayList<>();
        for (Edge edge : root.edges) {
            if (edge.child.normEval < arena.getEvalFloor()) {
                continue;
            }
            String san = MoveUtil.moveToSan(root.board(), edge.move);
            ExactRule first = new ExactRule(root.positionKey, edge.move, san, root.line.toString());
            SystemPath system = new SystemPath("System " + san);
            system.steps.add(seedStep(Policy.empty().with(first), first.describe()));
            all.add(system);
        }
        all.sort(Comparator.comparingDouble((SystemPath s) -> s.last().evaluation().score).reversed());
        for (int i = 0; i < all.size(); i++) {
            if (i < settings.getMaxSystems()) {
                systems.add(all.get(i));
            } else {
                all.get(i).last().evaluation().visits = null;
            }
        }
        if (systems.isEmpty()) {
            log.warn("No first move keeps the eval above the floor");
        }
        return systems;
    }

    private Step seedStep(Policy policy, String change) {
        Evaluation ev = evaluate(policy);
        return new Step(policy, ev, effortModel.total(policy, ev), effortModel.perRule(policy, ev), change, 0.);
    }

    /** The best next step, or null if no candidate gains enough. */
    private Step grow(Step current) {
        PolicyEvaluator evaluator = new PolicyEvaluator(arena, current.policy(), baseModel, settings.getTailFraction());
        List<Candidate> candidates = miner.mine(evaluator, current.evaluation());
        current.evaluation().visits = null;
        Step best = null;
        double bestRatio = Double.NEGATIVE_INFINITY;
        Evaluation bestEvaluation = null;
        double currentEval = current.evaluation().eval;
        for (Candidate candidate : candidates) {
            Evaluation ev = evaluate(candidate.policy());
            double gain = ev.score - current.evaluation().score;
            if (gain < settings.getMinGain()) {
                continue;
            }
            if (ev.eval < arena.getEvalFloor() && ev.eval < currentEval - 1e-9) {
                continue;
            }
            double effort = effortModel.total(candidate.policy(), ev);
            double ratio = gain / Math.max(effort - current.effort(), settings.getMinEffortStep());
            if (ratio > bestRatio) {
                if (bestEvaluation != null) {
                    bestEvaluation.visits = null;
                }
                bestRatio = ratio;
                best = new Step(candidate.policy(), ev, effort, effortModel.perRule(candidate.policy(), ev),
                        candidate.change(), gain);
                bestEvaluation = ev;
            } else {
                ev.visits = null;
            }
        }
        return best;
    }

    private void prune(List<SystemPath> systems) {
        for (SystemPath system : systems) {
            if (system.status != Status.GROWING || system.steps.size() <= settings.getMinStepsBeforePrune()) {
                continue;
            }
            Step last = system.last();
            for (SystemPath other : systems) {
                if (other == system) {
                    continue;
                }
                if (other.bestScoreWithin(last.effort()) > last.evaluation().score + settings.getPruneMargin()) {
                    log.info("{} pruned: {} scores more with no more effort", system.name, other.name);
                    system.status = Status.PRUNED;
                    last.evaluation().visits = null;
                    break;
                }
            }
        }
    }

    private void detectTiers(SystemPath system) {
        int n = system.steps.size();
        int replicates = settings.getBootstrapReplicates();
        double[][] scores = new double[n][replicates];
        for (int r = 0; r < replicates; r++) {
            StatsModel model = new StatsModel(side, settings.getScorePriorGames(), r);
            for (int i = 0; i < n; i++) {
                PolicyEvaluator ev = new PolicyEvaluator(arena, system.steps.get(i).policy(), model,
                        settings.getTailFraction());
                scores[i][r] = ev.evaluate().score;
            }
        }
        system.tiers.add(0);
        system.tierGainSd.add(0.);
        int lastTier = 0;
        for (int i = 1; i < n; i++) {
            double gain = system.steps.get(i).evaluation().score - system.steps.get(lastTier).evaluation().score;
            double sd = sd(scores[i], scores[lastTier]);
            if (gain >= settings.getMinTierGain() && gain > settings.getTierZ() * sd) {
                system.tiers.add(i);
                system.tierGainSd.add(sd);
                lastTier = i;
            }
        }
    }

    /** Standard deviation of the paired differences a - b. */
    private static double sd(double[] a, double[] b) {
        int n = a.length;
        if (n < 2) {
            return 0.;
        }
        double mean = 0.;
        for (int i = 0; i < n; i++) {
            mean += a[i] - b[i];
        }
        mean /= n;
        double var = 0.;
        for (int i = 0; i < n; i++) {
            double d = a[i] - b[i] - mean;
            var += d * d;
        }
        return Math.sqrt(var / (n - 1));
    }

    private Evaluation evaluate(Policy policy) {
        return new PolicyEvaluator(arena, policy, baseModel, settings.getTailFraction()).evaluate();
    }
}
