package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.ser_databus.tpg.elite.PositionKey;
import com.ser_databus.tpg.evaluate.LineEval;
import com.ser_databus.tpg.evaluate.PositionEvaluator;
import com.ser_databus.tpg.lichess.LichessMoveStats;
import com.ser_databus.tpg.lichess.LichessPosition;
import com.ser_databus.tpg.lichess.LichessPositionSource;
import com.ser_databus.tpg.model.MoveHistory;
import com.ser_databus.tpg.system.ArenaNode.Edge;
import com.ser_databus.tpg.system.ArenaNode.LeafReason;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.ser_databus.tpg.util.CalculationUtil.normalizeEval;
import static com.ser_databus.tpg.util.MoveUtil.lichessUciToMove;

/**
 * The positions reached by human (Elite) play from the root, built breadth-first one ply at a time. Every position
 * gets its game statistics and an engine eval. Positions are explored further unless they are decided, too rare,
 * below the eval floor, or beyond the index depth (see {@link #expandNextLevel}).
 * <p>
 * In my positions, all moves the crowd plays often enough are explored (plus the engine's best move), because the
 * systems choose among them later. In the opponent's positions, his moves are explored while the probability of
 * reaching the position stays above {@link SystemSettings#getMinReach()}.
 */
@Slf4j
public class Arena {
    private final Side side;
    private final SystemSettings settings;
    private final LichessPositionSource lichess;
    private final PositionEvaluator engine;

    private final Map<String, ArenaNode> nodes = new HashMap<>();
    private int nextNodeId;
    private int nextEdgeId;

    private ArenaNode root;
    private double evalFloor;
    private List<ArenaNode> frontier = new ArrayList<>();
    private int depth;
    private int expandedCount;

    public Arena(Side side, SystemSettings settings, LichessPositionSource lichess, PositionEvaluator engine) {
        this.side = side;
        this.settings = settings;
        this.lichess = lichess;
        this.engine = engine;
    }

    /** Creates and evaluates the root. */
    public void init(String fen) {
        Board board = new Board();
        board.loadFromFen(fen);
        root = createNode(board, 0, MoveHistory.empty(), 1.);
        frontier = new ArrayList<>(List.of(root));
        fetchAll(frontier);
        evalFloor = root.normEval - settings.getMaxEvalSetback();
        log.info("Root eval {}%, eval floor {}%", pct(root.normEval), pct(evalFloor));
    }

    public ArenaNode getRoot() {
        return root;
    }

    public Side getSide() {
        return side;
    }

    public double getEvalFloor() {
        return evalFloor;
    }

    /** Plies explored from the root so far. */
    public int getDepth() {
        return depth;
    }

    public int size() {
        return nodes.size();
    }

    public int getExpandedCount() {
        return expandedCount;
    }

    public Collection<ArenaNode> nodes() {
        return nodes.values();
    }

    /**
     * Explores the current frontier one ply deeper: decides for each frontier position whether it's explored, creates
     * the positions after the chosen moves and fetches their data.
     *
     * @return false if nothing was explored (the arena is complete)
     */
    public boolean expandNextLevel() {
        Map<String, ArenaNode> next = new LinkedHashMap<>();
        for (ArenaNode node : frontier) {
            LeafReason reason = leafReason(node);
            if (reason != null) {
                node.leafReason = reason;
                node.stats = null;
                continue;
            }
            createChildren(node, next);
            node.leafReason = LeafReason.EXPANDED;
            node.stats = null;
            expandedCount++;
        }
        if (next.isEmpty()) {
            frontier = List.of();
            return false;
        }
        depth++;
        frontier = new ArrayList<>(next.values());
        fetchAll(frontier);
        log.info("Depth {} plies: {} new positions, {} in total, {} explored", depth, frontier.size(), nodes.size(),
                expandedCount);
        engine.flushCache();
        lichess.flushCache();
        return true;
    }

    /** Null if the node should be explored. */
    private LeafReason leafReason(ArenaNode node) {
        if (node.terminal) {
            return LeafReason.TERMINAL;
        }
        if (node.moveNumber > settings.getMaxMoveNumber()) {
            return LeafReason.BEYOND_INDEX;
        }
        if (node.totalGames() < settings.getMinGamesToExpand() || node.stats == null) {
            return LeafReason.FEW_GAMES;
        }
        if (!node.myTurn && node.normEval < evalFloor) {
            // The position after my move: no system wants to be here
            return LeafReason.BELOW_EVAL_FLOOR;
        }
        double settled = settings.getSettledNormEval();
        if (node.normEval >= settled || node.normEval <= 1. - settled) {
            return LeafReason.SETTLED;
        }
        return null;
    }

    private void createChildren(ArenaNode node, Map<String, ArenaNode> next) {
        Board board = node.board();
        long total = node.totalGames();
        long edgeGames = 0;
        for (LichessMoveStats ms : node.stats.getMoves()) {
            long games = ms.getGames();
            if (games < settings.getMinGamesForMove()) {
                continue;
            }
            double share = (double) games / total;
            double childReach;
            if (node.myTurn) {
                if (share < settings.getMinCrowdShare()) {
                    continue;
                }
                childReach = node.reach;
            } else {
                childReach = node.reach * share;
                if (childReach < settings.getMinReach()) {
                    continue;
                }
            }
            Move move = lichessUciToMove(board, ms.uci);
            if (node.findEdge(move) != null) {
                continue;
            }
            node.edges.add(createEdge(node, board, move, games, childReach, next));
            edgeGames += games;
        }
        if (node.myTurn && node.engineBestMove != null && node.findEdge(node.engineBestMove) != null) {
            // Already explored
        } else if (node.myTurn && node.engineBestMove != null) {
            long games = gamesOf(node.stats, board, node.engineBestMove);
            node.edges.add(createEdge(node, board, node.engineBestMove, games, node.reach, next));
            edgeGames += games;
        }
        node.edgeGames = edgeGames;
    }

    private Edge createEdge(ArenaNode node, Board board, Move move, long games, double childReach,
                            Map<String, ArenaNode> next) {
        Token token = Token.of(board, move);
        Board after = board.clone();
        after.doMove(move);
        String key = PositionKey.of(after) + "|" + (node.ply + 1);
        ArenaNode child = nodes.get(key);
        if (child == null) {
            child = createNode(after, node.ply + 1, node.line.prolong(board, move), childReach);
            next.put(key, child);
        } else {
            child.reach = Math.max(child.reach, childReach);
            if (child.leafReason == LeafReason.OFF_ARENA && !next.containsKey(key)) {
                // Created earlier on demand by a rule; now it's part of the regular exploration
                child.leafReason = LeafReason.FRONTIER;
                child.stats = lichess.getPosition(child.fen, null);
                next.put(key, child);
            }
        }
        return new Edge(nextEdgeId++, move, token, child, games);
    }

    private ArenaNode createNode(Board board, int ply, MoveHistory line, double reach) {
        String positionKey = PositionKey.of(board);
        String key = positionKey + "|" + ply;
        ArenaNode node = new ArenaNode(nextNodeId++, key, positionKey, board, ply, board.getSideToMove() == side,
                line, reach);
        nodes.put(key, node);
        return node;
    }

    /**
     * The edge of the move from the node: an explored one, or a new extra edge to a position that is evaluated now and
     * isn't explored further. Used by the policy evaluator when a rule plays a move outside the arena.
     */
    public Edge ensureEdge(ArenaNode node, Move move) {
        Edge edge = node.findEdge(move);
        if (edge != null) {
            return edge;
        }
        Board board = node.board();
        Board after = board.clone();
        after.doMove(move);
        String key = PositionKey.of(after) + "|" + (node.ply + 1);
        ArenaNode child = nodes.get(key);
        if (child == null) {
            child = createNode(after, node.ply + 1, node.line.prolong(board, move), node.reach);
            fetchAll(List.of(child));
            child.leafReason = LeafReason.OFF_ARENA;
            child.stats = null;
        }
        long games = gamesOf(lichess.getPosition(node.fen, null), board, move);
        edge = new Edge(nextEdgeId++, move, Token.of(board, move), child, games);
        node.extraEdges.add(edge);
        return edge;
    }

    private static long gamesOf(LichessPosition position, Board board, Move move) {
        if (position == null || position.getMoves() == null) {
            return 0;
        }
        for (LichessMoveStats ms : position.getMoves()) {
            if (lichessUciToMove(board, ms.uci).equals(move)) {
                return ms.getGames();
            }
        }
        return 0;
    }

    /** Requests the game statistics and the engine eval of all nodes at once and waits for them. */
    private void fetchAll(List<ArenaNode> list) {
        int depth = settings.getEngineSearchDepth();
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (ArenaNode node : list) {
            if (node.fetched) {
                continue;
            }
            Board board = node.board();
            if (MoveGenerator.generateLegalMoves(board).isEmpty()) {
                node.terminal = true;
                double sideToMoveScore = board.isKingAttacked() ? 0. : 0.5;
                node.terminalScore = node.myTurn ? sideToMoveScore : 1. - sideToMoveScore;
                node.normEval = node.terminalScore;
                node.fetched = true;
                continue;
            }
            CompletableFuture<LichessPosition> positionFuture = lichess.getPositionAsync(node.fen, null);
            CompletableFuture<Optional<LineEval>> lineFuture = engine.getBestLineAsync(board, depth);
            futures.add(positionFuture.thenAcceptBoth(lineFuture, (position, line) -> applyFetch(node, position, line)));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void applyFetch(ArenaNode node, LichessPosition position, Optional<LineEval> line) {
        node.stats = position;
        node.white = position.getWhite();
        node.draws = position.getDraws();
        node.black = position.getBlack();
        LineEval best = line.orElseThrow(() -> new IllegalStateException("No engine line for " + node.fen));
        double sideToMoveEval = best.eval;
        node.normEval = normalizeEval(node.myTurn ? sideToMoveEval : -sideToMoveEval);
        node.evalDepth = best.depth;
        node.engineBestMove = best.move;
        node.fetched = true;
    }

    static String pct(double x) {
        return String.format("%.1f", x * 100.);
    }
}
