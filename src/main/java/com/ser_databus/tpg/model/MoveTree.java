package com.ser_databus.tpg.model;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class MoveTree {
    public final MoveHistory moveHistory;
    public final MoveTree parent;
    public final Map<Move, MoveTree> children = new HashMap<>();

    /** @param positionBefore the position at the end of this node's line (isn't changed) */
    public void createChild(Board positionBefore, Move newMove) {
        MoveHistory newMoveHistory = moveHistory.prolong(positionBefore, newMove);
        MoveTree child = new MoveTree(newMoveHistory, this);
        children.put(newMove, child);
    }
}
