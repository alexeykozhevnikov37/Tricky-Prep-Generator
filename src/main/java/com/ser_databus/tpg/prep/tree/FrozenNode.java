package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public abstract class FrozenNode {
    public final Map<Move, FrozenNode> moveToNode;
    public final Board board;
    public final String fen;
    public final double chance;
    public final int depth;
    public final MoveHistory moveHistory;
    public final double normEval;
    public final boolean isPruned;
    public final PruneReason pruneReason;

    public final double vp;
    public final double lichessScore;
    public final double cumulativeVariety;
    public final int leaves;
    public final int branches;

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
