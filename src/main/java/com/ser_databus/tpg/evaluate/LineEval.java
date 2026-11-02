package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.move.Move;

public class LineEval {
    public final int rank;
    public final double eval;
    public final Move move;
    /** Search depth (in plies) the eval was calculated at; 0 if unknown. */
    public final int depth;

    LineEval(int rank, double eval, Move move, int depth) {
        this.rank = rank;
        this.eval = eval;
        this.move = move;
        this.depth = depth;
    }
}
