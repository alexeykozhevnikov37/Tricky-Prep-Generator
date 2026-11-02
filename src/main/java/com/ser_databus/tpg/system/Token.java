package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.File;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Rank;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;

/**
 * A move described without its context: which piece goes to which square (and whether it captures, promotes or
 * castles). The same token is the same "idea" in different positions and move orders: Bf4 is Bf4 whether it's played
 * on move 2 or move 5.
 *
 * @param promotion null if the move isn't a promotion
 */
public record Token(PieceType piece, Square to, boolean capture, PieceType promotion, boolean castle) {

    /** @param board the position before the move (isn't changed) */
    public static Token of(Board board, Move move) {
        Piece moving = board.getPiece(move.getFrom());
        PieceType type = moving.getPieceType();
        boolean castle = type == PieceType.KING
                && Math.abs(move.getFrom().getFile().ordinal() - move.getTo().getFile().ordinal()) == 2;
        boolean capture = board.getPiece(move.getTo()) != Piece.NONE
                || type == PieceType.PAWN && move.getFrom().getFile() != move.getTo().getFile();
        PieceType promotion = move.getPromotion() == null || move.getPromotion() == Piece.NONE
                ? null : move.getPromotion().getPieceType();
        return new Token(type, move.getTo(), capture, promotion, castle);
    }

    @Override
    public String toString() {
        if (castle) {
            return to.getFile() == File.FILE_G ? "O-O" : "O-O-O";
        }
        String square = to.value().toLowerCase();
        StringBuilder sb = new StringBuilder();
        if (piece == PieceType.PAWN) {
            if (capture) {
                sb.append("Px");
            }
        } else {
            sb.append(piece.getSanSymbol());
            if (capture) {
                sb.append('x');
            }
        }
        sb.append(square);
        if (promotion != null) {
            sb.append('=').append(promotion.getSanSymbol());
        }
        return sb.toString();
    }

    /** True if a piece of this type and side stands on one of its starting squares. */
    public static boolean isHomeSquare(PieceType type, Side side, Square square) {
        Rank backRank = side == Side.WHITE ? Rank.RANK_1 : Rank.RANK_8;
        Rank pawnRank = side == Side.WHITE ? Rank.RANK_2 : Rank.RANK_7;
        File file = square.getFile();
        Rank rank = square.getRank();
        return switch (type) {
            case PAWN -> rank == pawnRank;
            case KNIGHT -> rank == backRank && (file == File.FILE_B || file == File.FILE_G);
            case BISHOP -> rank == backRank && (file == File.FILE_C || file == File.FILE_F);
            case ROOK -> rank == backRank && (file == File.FILE_A || file == File.FILE_H);
            case QUEEN -> rank == backRank && file == File.FILE_D;
            case KING -> rank == backRank && file == File.FILE_E;
            default -> false;
        };
    }

    public static String pieceName(PieceType type) {
        return type.name().toLowerCase();
    }
}
