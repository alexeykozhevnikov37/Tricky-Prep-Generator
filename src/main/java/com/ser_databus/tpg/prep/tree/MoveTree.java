package com.ser_databus.tpg.prep.tree;

import com.github.bhlangonijr.chesslib.move.Move;
import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class MoveTree {
    public final MoveHistory moveHistory;
    public final MoveTree parent;
    public final Map<Move, MoveTree> children = new HashMap<>();

    public void createChild(Move newMove) {
        MoveHistory newMoveHistory = moveHistory.prolong(newMove);
        MoveTree child = new MoveTree(newMoveHistory, this);
        children.put(newMove, child);
    }
}
