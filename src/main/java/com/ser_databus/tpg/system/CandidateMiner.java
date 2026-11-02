package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.system.ArenaNode.Edge;
import com.ser_databus.tpg.system.Evaluation.Visit;
import com.ser_databus.tpg.system.PolicyEvaluator.Val;
import com.ser_databus.tpg.system.Rule.ExactRule;
import com.ser_databus.tpg.system.Rule.ReactionRule;
import com.ser_databus.tpg.system.Rule.SetupRule;
import com.ser_databus.tpg.util.MoveUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds rules worth trying, from the moves the players actually play. In every position the current system reaches,
 * each explored move of mine is compared with what the system does there: the advantage of a move is its expected
 * score minus the system's. Positive advantages, weighted by how often the position is reached, are summed up by:
 * <ul>
 *     <li>exact position and move: candidate exact rules;</li>
 *     <li>token (piece + square): candidates to start or extend a setup;</li>
 *     <li>opponent's piece on a square (only pieces that left their starting squares) + my token: candidate
 *     reactions and guarded setups.</li>
 * </ul>
 * The best candidates of each kind are returned as new policies; the grower evaluates them exactly.
 */
public final class CandidateMiner {
    public record Candidate(Policy policy, String change) {
    }

    private record ExactKey(String positionKey, Move move) {
    }

    private record ReactionKey(PieceType piece, Square square, Token token) {
    }

    private final SystemSettings settings;
    private final Side side;

    public CandidateMiner(SystemSettings settings, Side side) {
        this.settings = settings;
        this.side = side;
    }

    public List<Candidate> mine(PolicyEvaluator evaluator, Evaluation evaluation) {
        Policy policy = evaluator.policy();
        Map<ExactKey, Double> exactScores = new HashMap<>();
        Map<ExactKey, ArenaNode> exactNodes = new HashMap<>();
        Map<Token, Double> tokenScores = new HashMap<>();
        Map<ReactionKey, Double> reactionScores = new HashMap<>();
        Side opponent = side.flip();

        for (Visit visit : evaluation.visits) {
            if (visit.decision() != null && policy.isExactRule(visit.decision().ruleIndex())) {
                continue;
            }
            ArenaNode node = visit.node();
            Val current = evaluator.value(node, visit.masks());
            List<Square> triggers = null;
            for (Edge edge : node.edges) {
                if (visit.decision() != null && edge.move.equals(visit.decision().move())) {
                    continue;
                }
                Val alternative = evaluator.value(edge.child, policy.afterMyMove(visit.masks(), edge.token));
                double advantage = alternative.score() - current.score();
                if (advantage <= 1e-9) {
                    continue;
                }
                double weight = visit.prob() * advantage;
                ExactKey exactKey = new ExactKey(node.positionKey, edge.move);
                exactScores.merge(exactKey, weight, Double::sum);
                exactNodes.putIfAbsent(exactKey, node);
                tokenScores.merge(edge.token, weight, Double::sum);
                if (triggers == null) {
                    triggers = movedOpponentPieces(node, opponent);
                }
                for (Square square : triggers) {
                    PieceType type = node.pieceAt(square).getPieceType();
                    reactionScores.merge(new ReactionKey(type, square, edge.token), weight, Double::sum);
                }
            }
        }

        int k = settings.getCandidatesPerKind();
        List<Candidate> candidates = new ArrayList<>();

        for (ExactKey key : top(exactScores, k)) {
            if (policy.hasExactFor(key.positionKey())) {
                continue;
            }
            ArenaNode node = exactNodes.get(key);
            String san = MoveUtil.moveToSan(node.board(), key.move());
            ExactRule rule = new ExactRule(key.positionKey(), key.move(), san, node.line.toString());
            candidates.add(new Candidate(policy.with(rule), "+ " + rule.describe()));
        }

        Set<ReactionRule> existingReactions = new HashSet<>(policy.reactions());
        List<ReactionKey> topReactions = top(reactionScores, k);
        for (ReactionKey key : topReactions) {
            ReactionRule rule = new ReactionRule(key.piece(), key.square(), key.token());
            if (!existingReactions.contains(rule)) {
                candidates.add(new Candidate(policy.with(rule), "+ " + rule.describe()));
            }
        }

        List<Token> topTokens = top(tokenScores, k);
        List<SetupRule> setups = policy.setups();
        for (int j = 0; j < setups.size(); j++) {
            SetupRule setup = setups.get(j);
            for (Token token : topTokens) {
                if (!setup.tokens().contains(token) && setup.tokens().size() < 64) {
                    candidates.add(new Candidate(policy.withSetupReplaced(j, setup.withToken(token)),
                            "Setup \"" + setupName(setup) + "\" += " + token));
                }
            }
        }
        if (policy.countSetups(false) < settings.getMaxSetups()) {
            for (Token token : topTokens) {
                SetupRule rule = new SetupRule(null, null, List.of(token));
                candidates.add(new Candidate(policy.with(rule), "+ new " + rule.describe()));
            }
        }
        if (policy.countSetups(true) < settings.getMaxSetups()) {
            for (ReactionKey key : topReactions) {
                SetupRule rule = new SetupRule(key.piece(), key.square(), List.of(key.token()));
                candidates.add(new Candidate(policy.with(rule), "+ new " + rule.describe()));
            }
        }
        return candidates;
    }

    static String setupName(SetupRule setup) {
        String first = setup.tokens().isEmpty() ? "" : setup.tokens().get(0).toString();
        return setup.isGuarded()
                ? Token.pieceName(setup.guardPiece()) + " " + setup.guardSquare().value().toLowerCase() + ": " + first + "..."
                : first + "...";
    }

    /** Squares of the opponent's pieces that aren't on their starting squares. */
    private static List<Square> movedOpponentPieces(ArenaNode node, Side opponent) {
        List<Square> result = new ArrayList<>();
        for (Square square : Square.values()) {
            if (square == Square.NONE) {
                continue;
            }
            Piece piece = node.pieceAt(square);
            if (piece == Piece.NONE || piece.getPieceSide() != opponent) {
                continue;
            }
            if (!Token.isHomeSquare(piece.getPieceType(), opponent, square)) {
                result.add(square);
            }
        }
        return result;
    }

    private static <K> List<K> top(Map<K, Double> scores, int k) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<K, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(k)
                .map(Map.Entry::getKey)
                .toList();
    }
}
