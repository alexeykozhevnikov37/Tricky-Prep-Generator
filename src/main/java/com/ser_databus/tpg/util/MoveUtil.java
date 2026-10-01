package com.ser_databus.tpg.util;

import com.github.bhlangonijr.chesslib.*;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;

import java.util.ArrayList;
import java.util.List;

public class MoveUtil {
    public static Move lichessUciToMove(Board board, String uci) {
        Square squareFrom = Square.fromValue(uci.substring(0, 2).toUpperCase());
        Square squareTo = Square.fromValue(uci.substring(2, 4).toUpperCase());
        Square kingSquare = board.getKingSquare(board.getSideToMove());
        if (squareFrom == Square.E1 && kingSquare == Square.E1) {
            if (squareTo == Square.H1) {
                squareTo = Square.G1;
            } else if (squareTo == Square.A1) {
                squareTo = Square.C1;
            }
        }
        if (squareFrom == Square.E8 && kingSquare == Square.E8) {
            if (squareTo == Square.H8) {
                squareTo = Square.G8;
            } else if (squareTo == Square.A8) {
                squareTo = Square.C8;
            }
        }
        Piece promotion = getPromotion(board, uci, squareFrom, squareTo);
        return new Move(squareFrom, squareTo, promotion);
    }

    public static Move stockfishUciToMove(Board board, String uci) {
        Square squareFrom = Square.fromValue(uci.substring(0, 2).toUpperCase());
        Square squareTo = Square.fromValue(uci.substring(2, 4).toUpperCase());
        Piece promotion = getPromotion(board, uci, squareFrom, squareTo);
        return new Move(squareFrom, squareTo, promotion);
    }

    private static Piece getPromotion(Board board, String uci, Square squareFrom, Square squareTo) {
        Piece promotion = Piece.NONE;
        if (uci.length() <= 4) {
            return promotion;
        }
        if (squareFrom.getRank() == Rank.RANK_7 && squareTo.getRank() == Rank.RANK_8 &&
                board.getPiece(squareFrom) == Piece.WHITE_PAWN) {
            promotion = Constants.getPieceByNotation(uci.substring(4, 5).toUpperCase());
        }
        if (squareFrom.getRank() == Rank.RANK_2 && squareTo.getRank() == Rank.RANK_1 &&
                board.getPiece(squareFrom) == Piece.BLACK_PAWN) {
            promotion = Constants.getPieceByNotation(uci.substring(4, 5).toLowerCase());
        }
        return promotion;
    }

    public static String moveToSan(Board board, Move move) {
        Square from = move.getFrom();
        Square to = move.getTo();
        Piece movingPiece = board.getPiece(from);
        PieceType movingPieceType = movingPiece.getPieceType();

        if (movingPiece == Piece.WHITE_KING && from == Square.E1 && to == Square.G1 ||
                movingPiece == Piece.BLACK_KING && from == Square.E8 && to == Square.G8) {
            return "O-O" + getCheckSymbol(board, move);
        }
        if (movingPiece == Piece.WHITE_KING && from == Square.E1 && to == Square.C1 ||
                movingPiece == Piece.BLACK_KING && from == Square.E8 && to == Square.C8) {
            return "O-O-O" + getCheckSymbol(board, move);
        }

        boolean isCapture = board.getPiece(to) != Piece.NONE;
        // If pawn moves diagonally, it is a capture, too. Important for en passant
        if (movingPieceType == PieceType.PAWN
                && from.toString().charAt(0) != to.toString().charAt(0)) {
            isCapture = true;
        }

        String pieceSymbol = getPieceSymbol(movingPieceType);
        char fromFile = from.toString().charAt(0);
        char fromRank = from.toString().charAt(1);
        String toSq = to.toString().toLowerCase();

        StringBuilder san = new StringBuilder();
        if (movingPieceType == PieceType.PAWN) {
            if (isCapture) {
                san.append(Character.toLowerCase(fromFile)).append("x").append(toSq);
            } else {
                san.append(toSq);
            }
            if (move.getPromotion() != Piece.NONE) {
                san.append(getPromotionSymbol(move));
            }
        } else {
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(board);
            List<Square> ambiguous = new ArrayList<>();
            for (Move m : legalMoves) {
                if (!m.getFrom().equals(from) && m.getTo().equals(to)) {
                    Piece p = board.getPiece(m.getFrom());
                    if (p.getPieceType() == movingPieceType) {
                        ambiguous.add(m.getFrom());
                    }
                }
            }

            String rankDisambiguation = "";
            String fileDisambiguation = "";
            if (!ambiguous.isEmpty()) {
                if (ambiguous.stream().anyMatch(sq -> sq.toString().charAt(0) == fromFile)) {
                    rankDisambiguation = "" + fromRank;
                }
                if (ambiguous.stream().anyMatch(sq -> sq.toString().charAt(0) != fromFile)) {
                    fileDisambiguation = "" + Character.toLowerCase(fromFile);
                }
            }
            san.append(pieceSymbol).append(fileDisambiguation).append(rankDisambiguation);
            if (isCapture) {
                san.append("x");
            }
            san.append(toSq);
        }

        san.append(getCheckSymbol(board, move));
        return san.toString();
    }

    private static String getCheckSymbol(Board board, Move move) {
        Board b1 = board.clone();
        b1.doMove(move);
        boolean isKingAttacked = b1.isKingAttacked();
        List<Move> opp = MoveGenerator.generateLegalMoves(b1);
        return isKingAttacked ? (opp.isEmpty() ? "#" : "+") : "";
    }

    private static String getPromotionSymbol(Move move) {
        return move.getPromotion() == Piece.NONE ? "" : getPieceSymbol(move.getPromotion().getPieceType());
    }

    private static String getPieceSymbol(PieceType pieceType) {
        return pieceType == PieceType.PAWN ? "" : pieceType.getSanSymbol();
    }
}
