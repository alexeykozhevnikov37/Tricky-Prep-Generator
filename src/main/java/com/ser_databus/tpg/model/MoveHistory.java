package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.util.MoveUtil;

import java.util.Arrays;
import java.util.List;

/**
 * The line from the root position to a node, as a chain: each history keeps only its last move (and how it's written)
 * and a link to the history before it, so a node's history costs a few dozen bytes however deep the node is. The
 * whole line is put together only when it's asked for ({@link #getMoves()}, {@link #toString()}).
 */
public final class MoveHistory {
    private static final MoveHistory EMPTY = new MoveHistory(null, null, null, 0);

    private final MoveHistory previous;
    private final Move lastMove;
    /** The last move as it's written in the line: "12. Nf3", "Nc6", or "7. ... Be7" if the line starts with it */
    private final String lastMoveText;
    private final int length;

    private MoveHistory(MoveHistory previous, Move lastMove, String lastMoveText, int length) {
        this.previous = previous;
        this.lastMove = lastMove;
        this.lastMoveText = lastMoveText;
        this.length = length;
    }

    /** The history of the root position: no moves */
    public static MoveHistory empty() {
        return EMPTY;
    }

    /**
     * The history with one more move.
     *
     * @param positionBefore the position the move is played in, i.e. the position at the end of this history; it is
     *                       used to write the move and isn't changed
     */
    public MoveHistory prolong(Board positionBefore, Move move) {
        return new MoveHistory(this, move, createMoveText(positionBefore, move, length == 0), length + 1);
    }

    /** The moves played from the root position, in order. */
    public List<Move> getMoves() {
        Move[] moves = new Move[length];
        for (MoveHistory history = this; history.length > 0; history = history.previous) {
            moves[history.length - 1] = history.lastMove;
        }
        return List.of(moves);
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public int size() {
        return length;
    }

    public Move getLastMove() {
        if (length == 0) {
            throw new IndexOutOfBoundsException("The history has no moves");
        }
        return lastMove;
    }

    @Override
    public String toString() {
        if (length == 0) {
            return "root";
        }
        String[] texts = new String[length];
        for (MoveHistory history = this; history.length > 0; history = history.previous) {
            texts[history.length - 1] = history.lastMoveText;
        }
        return String.join(" ", Arrays.asList(texts));
    }

    private static String createMoveText(Board positionBefore, Move move, boolean isFirstMove) {
        StringBuilder sb = new StringBuilder();
        if (positionBefore.getSideToMove() == Side.WHITE) {
            sb.append(positionBefore.getMoveCounter()).append(". ");
        } else if (isFirstMove) {
            sb.append(positionBefore.getMoveCounter()).append(". ... ");
        }
        sb.append(MoveUtil.moveToSan(positionBefore, move));
        return sb.toString();
    }
}
