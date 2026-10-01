package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.util.MoveUtil;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class MoveHistory {
    private final Board initialBoard;

    private final List<Move> moves;

    private final String asString;

    public MoveHistory(Board initialBoard) {
        this.initialBoard = initialBoard;
        moves = new ArrayList<>();
        asString = "root";
    }

    public MoveHistory prolong(Move move) {
        List<Move> newMoves = new ArrayList<>(moves);
        newMoves.add(move);
        Board newBoard = initialBoard.clone();
        newBoard.doMove(move);
        String asString = moves.isEmpty() ? createMoveSuffix(move) : (this.asString + " " + createMoveSuffix(move));
        return new MoveHistory(newBoard, newMoves, asString);
    }

    public Move getLastMove() {
        return moves.get(moves.size() - 1);
    }

    @Override
    public String toString() {
        return asString;
    }

    public String createMoveSuffix(Move newMove) {
        StringBuilder sb = new StringBuilder();
        if (initialBoard.getSideToMove() == Side.WHITE) {
            sb.append(initialBoard.getMoveCounter()).append(". ");
        } else if (moves.isEmpty()) {
            sb.append(initialBoard.getMoveCounter()).append(". ... ");
        }
        sb.append(MoveUtil.moveToSan(initialBoard, newMove));
        return sb.toString();
    }
}
