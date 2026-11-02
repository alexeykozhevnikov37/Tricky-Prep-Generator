package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;

import java.util.HashMap;
import java.util.Map;

public abstract class Node {
    /** @param board the node's position; only its FEN is kept */
    public Node(Board board, MoveHistory moveHistory, double chance, int depth) {
        this.fen = board.getFen();
        this.moveHistory = moveHistory;
        this.chance = chance;
        this.depth = depth;
    }

    public final Map<Move, Node> moveToNode = new HashMap<>();
    public final String fen;
    public final double chance;
    public final int depth;
    public MoveHistory moveHistory;
    public double normEval;
    /** Engine search depth (in plies) of the eval behind normEval; 0 if unknown. */
    public int evalDepth;
    public double correctedNormEval;
    public boolean isPruned;
    public PruneReason pruneReason;

    public double vp;
    public double lichessScore;
    public double lichessVp;
    /**
     * Sum of chance * log2(my viable moves) over my decision nodes in the prep line below this node, where the number
     * of my viable moves was measured (see {@link MyNode#myViableMoves}). See {@link #getThickness()}.
     */
    public double thicknessSum;
    /** Sum of the chances of the decision nodes counted in {@link #thicknessSum} */
    public double thicknessWeight;
    public int leaves;
    public int branches;

    /**
     * A new board with the node's position. Nodes keep only the FEN: a chesslib board takes kilobytes, which made up
     * most of the memory of the tree. The board has no game history, so it can't tell repetitions; the engines and
     * the Lichess data only use the FEN anyway.
     */
    public Board board() {
        Board board = new Board();
        board.loadFromFen(fen);
        return board;
    }

    public void prune(PruneReason reason) {
        isPruned = true;
        pruneReason = reason;
    }

    /** True if at least one of my decision nodes below this node has a measured number of viable moves */
    public boolean isThicknessKnown() {
        return thicknessWeight > 0.;
    }

    /**
     * How thick (slow, with many equal options for me) the prep line below this node is: the chance-weighted average
     * of log2(my viable moves) over my decision nodes in it. 0 = I have one good move in every position, 1 = two,
     * 2 = four, etc. NaN if unknown (no measured decision nodes yet).
     */
    public double getThickness() {
        return isThicknessKnown() ? thicknessSum / thicknessWeight : Double.NaN;
    }

    public abstract FrozenNode freeze();

    /** Short form for printing the prep: the line and the chance to get there */
    public String toPrepString() {
        return moveHistory + ": chance=" + String.format("%.1f", chance * 100.) + "%";
    }

    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder(moveHistory.toString());
        if (isPruned) {
            stringBuilder.append(": pruned because of ").append(pruneReason);
        } else {
            stringBuilder.append(": chance=").append(String.format("%.1f", chance * 100.)).append("%")
                    .append(", normalized eval=").append(String.format("%.1f", normEval * 100.))
                    .append(", VP=").append(String.format("%.1f", vp * 100.)).append("%")
                    .append(", lichess score=").append(String.format("%.1f", lichessScore * 100.)).append("%")
                    .append(", lichess VP=").append(String.format("%.1f", lichessVp * 100.)).append("%");
            if (branches > 0) {
                stringBuilder.append(", branches/leaves=").append(branches).append("/").append(leaves);
            }
            if (isThicknessKnown()) {
                stringBuilder.append(", thickness=").append(String.format("%.2f", getThickness()));
            }
        }
        return stringBuilder.toString();
    }
}
