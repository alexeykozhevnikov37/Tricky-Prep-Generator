package com.ser_databus.tpg;

import com.github.bhlangonijr.chesslib.Board;
import com.ser_databus.tpg.evaluate.LineEval;
import com.ser_databus.tpg.evaluate.StockfishClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class StockfishEvalTest {

    @Test
    void testMultiPvEval() throws Exception {
        Board board = new Board();
        board.loadFromFen("rnbqkb1r/p4p2/2p1pn1p/1p4p1/2pPP3/2N2NB1/PP3PPP/R2QKB1R w KQkq - 0 9");
        StockfishClient stockfishClient = new StockfishClient();
        LineEval line1 = stockfishClient.getBestLine(board, 20).get();
        LineEval line2 = stockfishClient.getBestLine(board, 20).get();
        assertEquals(line1.move, line2.move);
        assertEquals(line1.eval, line2.eval);
    }
}
