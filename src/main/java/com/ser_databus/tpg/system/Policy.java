package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.system.Rule.ExactRule;
import com.ser_databus.tpg.system.Rule.ReactionRule;
import com.ser_databus.tpg.system.Rule.SetupRule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An immutable system: a list of rules plus the crowd fallback (where no rule applies, I play the moves the Elite
 * players play, with their frequencies).
 * <p>
 * Rule indexes (as used by {@link Decision} and {@link Evaluation}) follow {@link #rules()}: exact rules first, then
 * reactions, then setups. The only state the rules need besides the position is which setup moves I've already
 * played: one bit mask per setup.
 */
public final class Policy {
    private final List<ExactRule> exacts;
    private final Map<String, Integer> exactIndexByPosition;
    private final List<ReactionRule> reactions;
    private final List<SetupRule> setups;
    private final List<Rule> rules;

    /**
     * @param ruleIndex  index in {@link #rules()}
     * @param tokenIndex for setups, the index of the token played; -1 otherwise
     */
    public record Decision(int ruleIndex, int tokenIndex, Move move) {
    }

    private Policy(List<ExactRule> exacts, List<ReactionRule> reactions, List<SetupRule> setups) {
        this.exacts = List.copyOf(exacts);
        this.reactions = List.copyOf(reactions);
        this.setups = List.copyOf(setups);
        this.exactIndexByPosition = new HashMap<>();
        for (int i = 0; i < this.exacts.size(); i++) {
            exactIndexByPosition.put(this.exacts.get(i).positionKey(), i);
        }
        List<Rule> all = new ArrayList<>(this.exacts);
        all.addAll(this.reactions);
        all.addAll(this.setups);
        this.rules = List.copyOf(all);
    }

    public static Policy empty() {
        return new Policy(List.of(), List.of(), List.of());
    }

    public List<Rule> rules() {
        return rules;
    }

    public List<ExactRule> exacts() {
        return exacts;
    }

    public List<ReactionRule> reactions() {
        return reactions;
    }

    public List<SetupRule> setups() {
        return setups;
    }

    public int setupRuleIndex(int setupIndex) {
        return exacts.size() + reactions.size() + setupIndex;
    }

    public boolean isExactRule(int ruleIndex) {
        return ruleIndex < exacts.size();
    }

    public boolean hasExactFor(String positionKey) {
        return exactIndexByPosition.containsKey(positionKey);
    }

    public long[] initialMasks() {
        return new long[setups.size()];
    }

    /** A new policy with the rule added. Guarded setups go before the unguarded ones. */
    public Policy with(Rule rule) {
        List<ExactRule> e = new ArrayList<>(exacts);
        List<ReactionRule> r = new ArrayList<>(reactions);
        List<SetupRule> s = new ArrayList<>(setups);
        if (rule instanceof ExactRule exact) {
            e.removeIf(x -> x.positionKey().equals(exact.positionKey()));
            e.add(exact);
        } else if (rule instanceof ReactionRule reaction) {
            r.add(reaction);
        } else if (rule instanceof SetupRule setup) {
            if (setup.isGuarded()) {
                int firstUnguarded = 0;
                while (firstUnguarded < s.size() && s.get(firstUnguarded).isGuarded()) {
                    firstUnguarded++;
                }
                s.add(firstUnguarded, setup);
            } else {
                s.add(setup);
            }
        }
        return new Policy(e, r, s);
    }

    public Policy withSetupReplaced(int setupIndex, SetupRule setup) {
        List<SetupRule> s = new ArrayList<>(setups);
        s.set(setupIndex, setup);
        return new Policy(exacts, reactions, s);
    }

    public long countSetups(boolean guarded) {
        return setups.stream().filter(s -> s.isGuarded() == guarded).count();
    }

    /** What the rules play in my position, or null if no rule applies (then I play like the crowd). */
    public Decision decide(ArenaNode node, long[] masks, Side mySide) {
        Integer exactIndex = exactIndexByPosition.get(node.positionKey);
        if (exactIndex != null) {
            return new Decision(exactIndex, -1, exacts.get(exactIndex).move());
        }
        Side opponent = mySide.flip();
        for (int i = 0; i < reactions.size(); i++) {
            ReactionRule r = reactions.get(i);
            if (node.hasPiece(opponent, r.triggerPiece(), r.triggerSquare())) {
                Move move = node.tokenMove(r.response());
                if (move != null) {
                    return new Decision(exacts.size() + i, -1, move);
                }
            }
        }
        for (int j = 0; j < setups.size(); j++) {
            SetupRule s = setups.get(j);
            if (s.isGuarded() && !node.hasPiece(opponent, s.guardPiece(), s.guardSquare())) {
                continue;
            }
            long mask = masks[j];
            List<Token> tokens = s.tokens();
            for (int i = 0; i < tokens.size(); i++) {
                if ((mask >>> i & 1L) != 0) {
                    continue;
                }
                Move move = node.tokenMove(tokens.get(i));
                if (move != null) {
                    return new Decision(setupRuleIndex(j), i, move);
                }
            }
        }
        return null;
    }

    /** The setup masks after I played a move with this token. Returns the same array if nothing changed. */
    public long[] afterMyMove(long[] masks, Token token) {
        long[] result = masks;
        for (int j = 0; j < setups.size(); j++) {
            List<Token> tokens = setups.get(j).tokens();
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).equals(token) && (masks[j] >>> i & 1L) == 0) {
                    if (result == masks) {
                        result = masks.clone();
                    }
                    result[j] |= 1L << i;
                }
            }
        }
        return result;
    }
}
