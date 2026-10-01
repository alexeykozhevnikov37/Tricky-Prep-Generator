package com.ser_databus.tpg;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.util.MoveUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MoveUtilTest {

    @Test
    void testPawnMoveSimple() {
        Board board = new Board();
        board.loadFromFen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        String san = MoveUtil.moveToSan(board, new Move(Square.E2, Square.E4));
        assertEquals("e4", san);
    }

    @Test
    void testPawnCapture() {
        Board board = new Board();
        board.loadFromFen("rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkqd 6 0 3");
        String san = MoveUtil.moveToSan(board, new Move(Square.E4, Square.D5));
        assertEquals("exd5", san);
    }

    @Test
    void testFileDisambiguation() {
        Board board = new Board();
        board.loadFromFen("rnbqkb1r/ppp2ppp/4pn2/3pP3/3P4/2N5/PPP2PPP/R1BQKBNR b KQkq - 0 4");
        String san = MoveUtil.moveToSan(board, new Move(Square.F6, Square.D7));
        assertEquals("Nfd7", san);
    }

    @Test
    void testRankDisambiguation() {
        Board board = new Board();
        board.loadFromFen("r1bqkbnr/pppp1ppp/6n1/4P3/2BP4/2N5/PPP2PPP/R1BQK2R b KQkq - 2 7");
        String san = MoveUtil.moveToSan(board, new Move(Square.G8, Square.E7));
        assertEquals("N8e7", san);
    }

    @Test
    void testDoubleDisambiguation() {
        Board board = new Board();
        board.loadFromFen("4k3/8/8/8/8/1Q6/1QQ5/2K5 w - - 1 2");
        String san = MoveUtil.moveToSan(board, new Move(Square.B2, Square.C3));
        assertEquals("Qb2c3", san);
    }

    @Test
    void testPromotionWithCapture() {
        Board board = new Board();
        board.loadFromFen("4n1k1/3P4/8/8/8/8/8/4K3 w - - 0 1");
        String san = MoveUtil.moveToSan(board, new Move(Square.D7, Square.E8, Piece.WHITE_QUEEN));
        assertEquals("dxe8Q+", san);
    }

    @Test
    void testKingsideCastle() {
        Board board = new Board();
        board.loadFromFen("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        String san = MoveUtil.moveToSan(board, new Move(Square.E1, Square.G1));
        assertEquals("O-O", san);
    }

    @Test
    void testQueensideCastleWithCheck() {
        Board board = new Board();
        board.loadFromFen("r3k2r/8/8/8/8/8/8/R2K2R1 b Qkq - 1 1");
        String san = MoveUtil.moveToSan(board, new Move(Square.E8, Square.C8));
        assertEquals("O-O-O+", san);
    }

    @Test
    void testCheckWithEnPassant() {
        Board board = new Board();
        board.loadFromFen("rnbq1bnr/ppp1kppp/4p3/3pP3/8/5N2/PPPP1PPP/RNBQKB1R w KQ d6 0 4");
        String san = MoveUtil.moveToSan(board, new Move(Square.E5, Square.D6));
        assertEquals("exd6+", san);
    }

    @Test
    void testCheckmate() {
        Board board = new Board();
        board.loadFromFen("6k1/5ppp/8/8/8/8/5PPP/R5K1 w - - 0 1");
        String san = MoveUtil.moveToSan(board, new Move(Square.A1, Square.A8));
        assertEquals("Ra8#", san);
    }
}