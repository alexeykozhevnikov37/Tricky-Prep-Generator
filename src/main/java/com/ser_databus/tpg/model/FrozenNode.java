package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.move.Move;
import lombok.RequiredArgsConstructor;

import java.util.Map;

@RequiredArgsConstructor
public abstract class FrozenNode {
    public final Map<Move, FrozenNode> moveToNode;
    public final String fen;
    public final double chance;
    public final int depth;
    public final MoveHistory moveHistory;
    public final double normEval;
    public final double chosenNormEval;
    public final boolean isPruned;
    public final PruneReason pruneReason;

    public final double vp;
    public final double lichessScore;
    public final double lichessVp;
    /** See {@link Node#getThickness()}; NaN if unknown */
    public final double thickness;
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
                    .append(", lichess score=").append(String.format("%.1f", lichessScore * 100.)).append("%")
                    .append(", lichess VP=").append(String.format("%.1f", lichessVp * 100.)).append("%");
            if (branches > 0) {
                stringBuilder.append(", branches/leaves=").append(branches).append("/").append(leaves);
            }
            if (!Double.isNaN(thickness)) {
                stringBuilder.append(", thickness=").append(String.format("%.2f", thickness));
            }
        }
        return stringBuilder.toString();
    }
}
