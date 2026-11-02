package com.ser_databus.tpg.elite;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import static com.ser_databus.tpg.util.ChessUtil.getCroppedFen;

/**
 * Index key of a position: the cropped FEN (pieces, side to move, castling, en passant) with the en passant square
 * kept only when an en passant capture is actually legal, like the Lichess explorer does.
 * <p>
 * chesslib writes the en passant square after every double pawn push ("... b KQkq f3" after 7. f4), so without this
 * the same position would be split into several keys depending on the last move.
 */
public final class PositionKey {
    /** Stored in the index; an index with another (or no) key format must be rebuilt. */
    public static final String FORMAT = "2-legal-en-passant";

    private PositionKey() {
    }

    public static String of(Board board) {
        String croppedFen = getCroppedFen(board.getFen());
        int lastSpace = croppedFen.lastIndexOf(' ');
        String enPassant = croppedFen.substring(lastSpace + 1);
        if (enPassant.equals("-") || isEnPassantLegal(board, enPassant)) {
            return croppedFen;
        }
        return croppedFen.substring(0, lastSpace + 1) + "-";
    }

    /** Key of a position given as a full or cropped FEN from any source. */
    public static String of(String fen) {
        Board board = new Board();
        board.loadFromFen(withCounters(fen));
        return of(board);
    }

    private static boolean isEnPassantLegal(Board board, String enPassant) {
        Square target;
        try {
            target = Square.fromValue(enPassant.toUpperCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
        for (Move move : MoveGenerator.generateLegalMoves(board)) {
            if (move.getTo() == target && board.getPiece(move.getFrom()).getPieceType() == PieceType.PAWN) {
                return true;
            }
        }
        return false;
    }

    /** chesslib expects all six FEN fields. */
    private static String withCounters(String fen) {
        String trimmed = fen.trim();
        int fields = trimmed.split("\\s+").length;
        return fields >= 6 ? trimmed : fields == 5 ? trimmed + " 1" : trimmed + " 0 1";
    }
}
