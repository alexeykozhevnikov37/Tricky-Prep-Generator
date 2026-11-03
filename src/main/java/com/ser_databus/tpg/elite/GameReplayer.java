package com.ser_databus.tpg.elite;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays the first plies of a game and returns, for every ply, the position before the move ({@link PositionKey})
 * and the move in Lichess explorer notation.
 */
public class GameReplayer {
    /** Outcome of a game, mapped to the white / draws / black counters. */
    public enum Outcome { WHITE, DRAW, BLACK }

    public record Ply(String fen, String uci, String san) {
    }

    public record ReplayedGame(Outcome outcome, List<Ply> plies) {
    }

    /**
     * A distinct position of a game and the last move played from it, or uci/san null if no move was played from it
     * (the final position, or the position after the last replayed ply).
     */
    public record UniquePly(String fen, String uci, String san) {
    }

    /** @param plies number of plies replayed (at most the limit of the replayer) */
    public record UniqueGame(Outcome outcome, List<UniquePly> positions, int plies) {
    }

    public static class SanException extends Exception {
        public SanException(String message) {
            super(message);
        }
    }

    private final int maxPlies;

    public GameReplayer(int maxPlies) {
        this.maxPlies = maxPlies;
    }

    /** @return null if the result is unknown ("*" or missing): such a game can't be counted as a win, draw or loss */
    public static Outcome parseOutcome(String result) {
        if (result == null) {
            return null;
        }
        return switch (result) {
            case "1-0" -> Outcome.WHITE;
            case "0-1" -> Outcome.BLACK;
            case "1/2-1/2" -> Outcome.DRAW;
            default -> null;
        };
    }

    public ReplayedGame replay(Outcome outcome, String moveText) throws SanException {
        List<String> sans = extractSans(moveText, maxPlies);
        List<Ply> plies = new ArrayList<>(sans.size());
        Board board = new Board();
        for (String san : sans) {
            Move move = sanToMove(board, san);
            plies.add(new Ply(PositionKey.of(board), toLichessUci(board, move), cleanSan(san)));
            board.doMove(move);
        }
        return new ReplayedGame(outcome, plies);
    }

    /**
     * Replays the first plies like {@link #replay}, but returns every distinct position only once, the way the Lichess
     * opening explorer counts a game: a position that occurs several times keeps the <b>last</b> move played from it.
     * The position after the last replayed ply is included too (without a move, unless it occurred before with one).
     * Positions are in the order of their first occurrence.
     */
    public UniqueGame replayUnique(Outcome outcome, String moveText) throws SanException {
        List<String> sans = extractSans(moveText, maxPlies);
        List<UniquePly> positions = new ArrayList<>(sans.size() + 1);
        Map<String, Integer> indexByFen = new HashMap<>();
        Board board = new Board();
        for (String san : sans) {
            Move move = sanToMove(board, san);
            String fen = PositionKey.of(board);
            UniquePly ply = new UniquePly(fen, toLichessUci(board, move), cleanSan(san));
            Integer index = indexByFen.putIfAbsent(fen, positions.size());
            if (index == null) {
                positions.add(ply);
            } else {
                positions.set(index, ply);
            }
            board.doMove(move);
        }
        String last = PositionKey.of(board);
        if (!indexByFen.containsKey(last)) {
            positions.add(new UniquePly(last, null, null));
        }
        return new UniqueGame(outcome, positions, sans.size());
    }

    /** Splits move text into SAN tokens, skipping move numbers, results, comments, variations and NAGs. */
    static List<String> extractSans(String moveText, int limit) {
        List<String> result = new ArrayList<>(Math.min(limit, 128));
        int n = moveText.length();
        int i = 0;
        int variationDepth = 0;
        while (i < n && result.size() < limit) {
            char c = moveText.charAt(i);
            if (c == '{') {
                int end = moveText.indexOf('}', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == ';') {
                // Rest-of-line comment; lines were joined with spaces, so this is the best we can do
                break;
            }
            if (c == '(') {
                variationDepth++;
                i++;
                continue;
            }
            if (c == ')') {
                variationDepth = Math.max(0, variationDepth - 1);
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && !Character.isWhitespace(moveText.charAt(i)) && "{}();".indexOf(moveText.charAt(i)) < 0) {
                i++;
            }
            if (variationDepth > 0) {
                continue;
            }
            String token = moveText.substring(start, i);
            // Strip a move number glued to the move: "12.e4", "12...Nf6"
            int k = 0;
            while (k < token.length() && Character.isDigit(token.charAt(k))) {
                k++;
            }
            if (k < token.length() && token.charAt(k) == '.') {
                while (k < token.length() && token.charAt(k) == '.') {
                    k++;
                }
                token = token.substring(k);
            }
            if (token.isEmpty() || token.charAt(0) == '$' || isResult(token)) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    private static boolean isResult(String token) {
        return token.equals("1-0") || token.equals("0-1") || token.equals("1/2-1/2") || token.equals("*");
    }

    /** Removes check marks and annotations: "Nf3+!?" -> "Nf3". */
    static String cleanSan(String san) {
        int end = san.length();
        while (end > 0 && "+#!?".indexOf(san.charAt(end - 1)) >= 0) {
            end--;
        }
        String s = san.substring(0, end);
        return s.startsWith("0-0") ? s.replace('0', 'O') : s;
    }

    static Move sanToMove(Board board, String rawSan) throws SanException {
        String san = cleanSan(rawSan);
        List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);

        if (san.equals("O-O") || san.equals("O-O-O")) {
            Square kingSquare = board.getKingSquare(board.getSideToMove());
            String rank = kingSquare.toString().substring(1);
            Square to = Square.fromValue((san.equals("O-O") ? "G" : "C") + rank);
            for (Move m : legalMoves) {
                if (m.getFrom() == kingSquare && m.getTo() == to) {
                    return m;
                }
            }
            throw new SanException("Illegal castling " + rawSan + " in " + board.getFen());
        }

        String s = san;
        PieceType promotion = null;
        int eq = s.indexOf('=');
        if (eq >= 0) {
            if (eq + 1 >= s.length()) {
                throw new SanException("Bad promotion " + rawSan);
            }
            promotion = pieceTypeOf(s.charAt(eq + 1));
            s = s.substring(0, eq);
        } else if (s.length() >= 3 && Character.isLowerCase(s.charAt(0)) && "QRBN".indexOf(s.charAt(s.length() - 1)) >= 0) {
            // Promotion written without '=': "e8Q"
            promotion = pieceTypeOf(s.charAt(s.length() - 1));
            s = s.substring(0, s.length() - 1);
        }

        PieceType pieceType = PieceType.PAWN;
        if (!s.isEmpty() && "KQRBN".indexOf(s.charAt(0)) >= 0) {
            pieceType = pieceTypeOf(s.charAt(0));
            s = s.substring(1);
        }
        s = s.replace("x", "").replace("-", "");
        if (s.length() < 2) {
            throw new SanException("Bad SAN " + rawSan);
        }
        String destination = s.substring(s.length() - 2);
        String disambiguation = s.substring(0, s.length() - 2);
        Square to;
        try {
            to = Square.fromValue(destination.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new SanException("Bad destination in " + rawSan);
        }
        char fromFile = 0;
        char fromRank = 0;
        for (char d : disambiguation.toCharArray()) {
            if (d >= 'a' && d <= 'h') {
                fromFile = d;
            } else if (d >= '1' && d <= '8') {
                fromRank = d;
            } else {
                throw new SanException("Bad SAN " + rawSan);
            }
        }

        Move found = null;
        for (Move m : legalMoves) {
            if (m.getTo() != to || board.getPiece(m.getFrom()).getPieceType() != pieceType) {
                continue;
            }
            String from = m.getFrom().toString().toLowerCase();
            if (fromFile != 0 && from.charAt(0) != fromFile || fromRank != 0 && from.charAt(1) != fromRank) {
                continue;
            }
            PieceType movePromotion = m.getPromotion() == Piece.NONE ? null : m.getPromotion().getPieceType();
            if (movePromotion != promotion) {
                continue;
            }
            if (found != null) {
                throw new SanException("Ambiguous SAN " + rawSan + " in " + board.getFen());
            }
            found = m;
        }
        if (found == null) {
            throw new SanException("Illegal SAN " + rawSan + " in " + board.getFen());
        }
        return found;
    }

    private static PieceType pieceTypeOf(char c) throws SanException {
        return switch (Character.toUpperCase(c)) {
            case 'K' -> PieceType.KING;
            case 'Q' -> PieceType.QUEEN;
            case 'R' -> PieceType.ROOK;
            case 'B' -> PieceType.BISHOP;
            case 'N' -> PieceType.KNIGHT;
            default -> throw new SanException("Bad piece " + c);
        };
    }

    /** UCI as the Lichess explorer returns it: castling is "king takes own rook" (e1h1), promotions lower case. */
    static String toLichessUci(Board board, Move move) {
        String from = move.getFrom().toString().toLowerCase();
        String to = move.getTo().toString().toLowerCase();
        if (board.getPiece(move.getFrom()).getPieceType() == PieceType.KING) {
            if (from.equals("e1") && to.equals("g1")) return "e1h1";
            if (from.equals("e1") && to.equals("c1")) return "e1a1";
            if (from.equals("e8") && to.equals("g8")) return "e8h8";
            if (from.equals("e8") && to.equals("c8")) return "e8a8";
        }
        String uci = from + to;
        if (move.getPromotion() != Piece.NONE) {
            uci += move.getPromotion().getPieceType().getSanSymbol().toLowerCase();
        }
        return uci;
    }
}
