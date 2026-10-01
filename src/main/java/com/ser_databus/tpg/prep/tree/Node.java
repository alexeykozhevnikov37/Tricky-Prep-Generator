package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.HashMap;
import java.util.Map;

public abstract class Node {
    public Node(Board board, MoveHistory moveHistory, double chance, int depth) {
        this.board = board;
        this.fen = board.getFen();
        this.moveHistory = moveHistory;
        this.chance = chance;
        this.depth = depth;
    }

    public final Map<Move, Node> moveToNode = new HashMap<>();
    public final Board board;
    public final String fen;
    public final double chance;
    public final int depth;
    public MoveHistory moveHistory;
    public double normEval;
    public boolean isPruned;
    public PruneReason pruneReason;

    public double vp;
    public double lichessScore;
    public double cumulativeVariety = 0.;
    public int leaves;
    public int branches;

    public void prune(PruneReason reason) {
        isPruned = true;
        pruneReason = reason;
    }

    public abstract FrozenNode freeze();

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder(moveHistory.toString());
        if (isPruned) {
            stringBuilder.append(": pruned because of ").append(pruneReason);
        } else {
            stringBuilder.append(": chance=").append(String.format("%.1f", chance * 100.)).append("%")
                    .append(", normalized eval=").append(String.format("%.1f", normEval * 100.))
                    .append(", VP=").append(String.format("%.1f", vp * 100.)).append("%")
                    .append(", lichess VP=").append(String.format("%.1f", lichessScore * 100.)).append("%");
            if (branches > 0) {
                stringBuilder.append(", branches/leaves=").append(branches).append("/").append(leaves);
            }
        }
        return stringBuilder.toString();
    }
}
