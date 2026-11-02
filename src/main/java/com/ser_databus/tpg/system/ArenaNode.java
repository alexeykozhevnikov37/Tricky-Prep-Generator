package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.ser_databus.tpg.lichess.LichessPosition;
import com.ser_databus.tpg.model.MoveHistory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A position of the arena: the graph of positions reached by human play from the root. Positions reached by different
 * move orders at the same ply are one node (the key is the position key plus the ply, so the graph has no cycles).
 */
public final class ArenaNode {
    public enum LeafReason {
        /** Not explored yet: the next deepening level will decide. */
        FRONTIER,
        TERMINAL,
        BEYOND_INDEX,
        FEW_GAMES,
        BELOW_EVAL_FLOOR,
        SETTLED,
        /** Created on demand when a rule plays a move outside the arena. */
        OFF_ARENA,
        EXPANDED
    }

    public final int id;
    /** {@link com.ser_databus.tpg.elite.PositionKey} + "|" + ply */
    public final String key;
    public final String positionKey;
    public final String fen;
    public final int ply;
    public final int moveNumber;
    public final boolean myTurn;
    public final MoveHistory line;
    /** The highest probability with which the opponents' moves lead here (my moves aren't counted). */
    public double reach;

    // ---------- fetched data ----------
    public volatile boolean fetched;
    /** Engine eval of the position, normalized, from my point of view. */
    public double normEval;
    public int evalDepth;
    public Move engineBestMove;
    /** No legal moves: mate or stalemate. */
    public boolean terminal;
    /** Score of a terminal position for me: 0, 0.5 or 1. */
    public double terminalScore;
    /** Elite games in this position, by result. */
    public long white;
    public long draws;
    public long black;
    /** The Lichess data, kept only until the node is expanded. */
    LichessPosition stats;

    // ---------- structure ----------
    public LeafReason leafReason = LeafReason.FRONTIER;
    /** Moves explored from this position: popular ones (and the engine's best move in my positions). */
    public final List<Edge> edges = new ArrayList<>();
    /** Moves that rules play outside the explored ones. They aren't part of the crowd's choice. */
    public final List<Edge> extraEdges = new ArrayList<>(1);
    /** Games of the moves in {@link #edges}. */
    public long edgeGames;

    // ---------- position cache for the rules ----------
    private Piece[] pieces;
    private Map<Token, Move> tokenMoves;

    ArenaNode(int id, String key, String positionKey, Board board, int ply, boolean myTurn, MoveHistory line,
              double reach) {
        this.id = id;
        this.key = key;
        this.positionKey = positionKey;
        this.fen = board.getFen();
        this.ply = ply;
        this.moveNumber = board.getMoveCounter();
        this.myTurn = myTurn;
        this.line = line;
        this.reach = reach;
    }

    public boolean isExpanded() {
        return leafReason == LeafReason.EXPANDED;
    }

    public long totalGames() {
        return white + draws + black;
    }

    public Board board() {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }

    /** The explored or extra move, or null. */
    public Edge findEdge(Move move) {
        for (Edge e : edges) {
            if (e.move.equals(move)) {
                return e;
            }
        }
        for (Edge e : extraEdges) {
            if (e.move.equals(move)) {
                return e;
            }
        }
        return null;
    }

    /** The legal move of the side to move that matches the token, or null. */
    public Move tokenMove(Token token) {
        ensurePositionCache();
        return tokenMoves.get(token);
    }

    public Map<Token, Move> tokenMoves() {
        ensurePositionCache();
        return Collections.unmodifiableMap(tokenMoves);
    }

    public boolean hasPiece(Side side, PieceType type, Square square) {
        ensurePositionCache();
        Piece piece = pieces[square.ordinal()];
        return piece != Piece.NONE && piece.getPieceSide() == side && piece.getPieceType() == type;
    }

    public Piece pieceAt(Square square) {
        ensurePositionCache();
        return pieces[square.ordinal()];
    }

    private synchronized void ensurePositionCache() {
        if (tokenMoves != null) {
            return;
        }
        Board board = board();
        Piece[] p = new Piece[64];
        for (Square square : Square.values()) {
            if (square != Square.NONE) {
                p[square.ordinal()] = board.getPiece(square);
            }
        }
        Map<Token, Move> moves = new HashMap<>();
        for (Move move : MoveGenerator.generateLegalMoves(board)) {
            moves.putIfAbsent(Token.of(board, move), move);
        }
        pieces = p;
        tokenMoves = moves;
    }

    @Override
    public String toString() {
        return line + " (ply " + ply + ")";
    }

    /** A move from this node. */
    public static final class Edge {
        public final int id;
        public final Move move;
        public final Token token;
        public final ArenaNode child;
        /** Elite games with this move; 0 for the engine's move nobody played. */
        public final long games;

        Edge(int id, Move move, Token token, ArenaNode child, long games) {
            this.id = id;
            this.move = move;
            this.token = token;
            this.child = child;
            this.games = games;
        }
    }
}
