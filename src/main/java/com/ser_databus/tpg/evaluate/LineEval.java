package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.move.Move;

public class LineEval {
    public final int rank;
    public final double eval;
    public final Move move;

    LineEval(int rank, double eval, Move move) {
        this.rank = rank;
        this.eval = eval;
        this.move = move;
    }
}
