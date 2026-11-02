package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.system.ArenaNode.Edge;
import com.ser_databus.tpg.system.Evaluation.Visit;
import com.ser_databus.tpg.system.Policy.Decision;
import com.ser_databus.tpg.system.Rule.SetupRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plays a system against the Elite opponents over the arena.
 * <ul>
 *     <li>Opponent positions: his moves with their game frequencies; the rarely played rest counts with the position's
 *     own value.</li>
 *     <li>My positions: the move a rule decides, or, if no rule applies, the crowd's moves with their frequencies.</li>
 *     <li>Positions not explored further: their static value (game score blended with the engine eval).</li>
 * </ul>
 * {@link #evaluate()} walks forward from the root ply by ply and returns the totals. {@link #value} is the expected
 * value from any position and setup state, memoized; the candidate miner uses it to compare my alternatives.
 */
public final class PolicyEvaluator {
    public record Val(double score, double eval) {
    }

    private record StateKey(ArenaNode node, long[] masks) {
        @Override
        public boolean equals(Object o) {
            return o instanceof StateKey k && k.node == node && Arrays.equals(k.masks, masks);
        }

        @Override
        public int hashCode() {
            return 31 * node.id + Arrays.hashCode(masks);
        }
    }

    private record Leaf(double prob, double score, double eval) {
    }

    private final Arena arena;
    private final Policy policy;
    private final StatsModel model;
    private final Side side;
    private final double tailFraction;
    private final Map<StateKey, Val> memo = new HashMap<>();

    public PolicyEvaluator(Arena arena, Policy policy, StatsModel model, double tailFraction) {
        this.arena = arena;
        this.policy = policy;
        this.model = model;
        this.side = arena.getSide();
        this.tailFraction = tailFraction;
    }

    public Policy policy() {
        return policy;
    }

    // ---------- backward: expected value from a state ----------

    public Val value(ArenaNode node, long[] masks) {
        if (node.terminal) {
            return new Val(node.terminalScore, node.terminalScore);
        }
        if (!node.isExpanded()) {
            return staticVal(node);
        }
        StateKey key = new StateKey(node, masks);
        Val cached = memo.get(key);
        if (cached != null) {
            return cached;
        }
        Val result;
        Decision decision = node.myTurn ? policy.decide(node, masks, side) : null;
        if (decision != null) {
            Edge edge = arena.ensureEdge(node, decision.move());
            result = value(edge.child, policy.afterMyMove(masks, edge.token));
        } else {
            result = average(node, masks);
        }
        memo.put(key, result);
        return result;
    }

    /** The frequency-weighted value of the moves played in the position (opponent's, or the crowd's in mine). */
    private Val average(ArenaNode node, long[] masks) {
        double total = model.nodeTotal(node);
        Val own = staticVal(node);
        if (total <= 0.) {
            return own;
        }
        double score = 0.;
        double eval = 0.;
        double covered = 0.;
        for (Edge edge : node.edges) {
            double games = model.edgeGames(edge);
            if (games <= 0.) {
                continue;
            }
            double f = games / total;
            long[] childMasks = node.myTurn ? policy.afterMyMove(masks, edge.token) : masks;
            Val v = value(edge.child, childMasks);
            score += f * v.score();
            eval += f * v.eval();
            covered += f;
        }
        double rest = Math.max(0., 1. - covered);
        return new Val(score + rest * own.score(), eval + rest * own.eval());
    }

    private Val staticVal(ArenaNode node) {
        return new Val(model.staticScore(node), node.normEval);
    }

    // ---------- forward: totals over the games ----------

    public Evaluation evaluate() {
        List<SetupRule> setups = policy.setups();
        int ruleCount = policy.rules().size();
        Evaluation ev = new Evaluation();
        ev.ruleFires = new double[ruleCount];
        ev.ruleUnnatural = new double[ruleCount];
        ev.tokenFires = new double[setups.size()][];
        ev.tokenUnnatural = new double[setups.size()][];
        for (int j = 0; j < setups.size(); j++) {
            ev.tokenFires[j] = new double[setups.get(j).tokens().size()];
            ev.tokenUnnatural[j] = new double[setups.get(j).tokens().size()];
        }
        ev.visits = new ArrayList<>();
        List<Leaf> leaves = new ArrayList<>();
        double decisions = 0.;
        double ruleDecisions = 0.;

        Map<StateKey, Double> level = new LinkedHashMap<>();
        level.put(new StateKey(arena.getRoot(), policy.initialMasks()), 1.);
        while (!level.isEmpty()) {
            Map<StateKey, Double> next = new LinkedHashMap<>();
            for (Map.Entry<StateKey, Double> entry : level.entrySet()) {
                ArenaNode node = entry.getKey().node();
                long[] masks = entry.getKey().masks();
                double p = entry.getValue();
                if (node.terminal || !node.isExpanded()) {
                    Val v = node.terminal ? new Val(node.terminalScore, node.terminalScore) : staticVal(node);
                    leaves.add(new Leaf(p, v.score(), v.eval()));
                    continue;
                }
                if (!node.myTurn) {
                    distribute(node, masks, p, next, leaves);
                    continue;
                }
                decisions += p;
                Decision decision = policy.decide(node, masks, side);
                ev.visits.add(new Visit(node, masks, p, decision));
                if (decision == null) {
                    distribute(node, masks, p, next, leaves);
                    continue;
                }
                ruleDecisions += p;
                Edge edge = arena.ensureEdge(node, decision.move());
                double total = model.nodeTotal(node);
                double share = total > 0. ? Math.min(1., model.edgeGames(edge) / total) : 0.;
                ev.ruleFires[decision.ruleIndex()] += p;
                ev.ruleUnnatural[decision.ruleIndex()] += p * (1. - share);
                if (decision.tokenIndex() >= 0) {
                    int setupIndex = decision.ruleIndex() - policy.setupRuleIndex(0);
                    ev.tokenFires[setupIndex][decision.tokenIndex()] += p;
                    ev.tokenUnnatural[setupIndex][decision.tokenIndex()] += p * (1. - share);
                }
                next.merge(new StateKey(edge.child, policy.afterMyMove(masks, edge.token)), p, Double::sum);
            }
            level = next;
        }

        double score = 0.;
        double eval = 0.;
        double mass = 0.;
        for (Leaf leaf : leaves) {
            score += leaf.prob() * leaf.score();
            eval += leaf.prob() * leaf.eval();
            mass += leaf.prob();
        }
        ev.score = mass > 0. ? score / mass : 0.5;
        ev.eval = mass > 0. ? eval / mass : 0.5;
        ev.lowTail = lowTail(leaves, mass);
        ev.coverage = decisions > 0. ? ruleDecisions / decisions : 0.;
        return ev;
    }

    private void distribute(ArenaNode node, long[] masks, double p, Map<StateKey, Double> next, List<Leaf> leaves) {
        double total = model.nodeTotal(node);
        Val own = staticVal(node);
        if (total <= 0.) {
            leaves.add(new Leaf(p, own.score(), own.eval()));
            return;
        }
        double covered = 0.;
        for (Edge edge : node.edges) {
            double games = model.edgeGames(edge);
            if (games <= 0.) {
                continue;
            }
            double f = games / total;
            long[] childMasks = node.myTurn ? policy.afterMyMove(masks, edge.token) : masks;
            next.merge(new StateKey(edge.child, childMasks), p * f, Double::sum);
            covered += f;
        }
        double rest = Math.max(0., 1. - covered);
        if (rest > 0.) {
            leaves.add(new Leaf(p * rest, own.score(), own.eval()));
        }
    }

    private double lowTail(List<Leaf> leaves, double mass) {
        if (leaves.isEmpty() || mass <= 0.) {
            return 0.5;
        }
        List<Leaf> sorted = new ArrayList<>(leaves);
        sorted.sort((a, b) -> Double.compare(a.score(), b.score()));
        double target = tailFraction * mass;
        double taken = 0.;
        double sum = 0.;
        for (Leaf leaf : sorted) {
            double take = Math.min(leaf.prob(), target - taken);
            if (take <= 0.) {
                break;
            }
            sum += take * leaf.score();
            taken += take;
        }
        return taken > 0. ? sum / taken : 0.5;
    }
}
