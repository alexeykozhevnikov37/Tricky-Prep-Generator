package com.ser_databus.tpg.elite;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulated statistics of one position: per-move white wins / draws / black wins.
 * Position totals are the sum over its moves, the same way the Lichess opening explorer computes them.
 */
public class PositionAgg {
    public static class MoveAgg {
        public final String uci;
        public final String san;
        public long white;
        public long draws;
        public long black;

        public MoveAgg(String uci, String san) {
            this.uci = uci;
            this.san = san;
        }

        public long games() {
            return white + draws + black;
        }
    }

    /** Usually a handful of moves, so a list with linear search is faster and smaller than a map. */
    private final List<MoveAgg> moves = new ArrayList<>(2);

    public List<MoveAgg> getMoves() {
        return moves;
    }

    public MoveAgg getOrAddMove(String uci, String san) {
        for (MoveAgg m : moves) {
            if (m.uci.equals(uci)) {
                return m;
            }
        }
        MoveAgg m = new MoveAgg(uci, san);
        moves.add(m);
        return m;
    }

    public void add(String uci, String san, GameReplayer.Outcome outcome) {
        add(uci, san, outcome == GameReplayer.Outcome.WHITE ? 1 : 0, outcome == GameReplayer.Outcome.DRAW ? 1 : 0,
                outcome == GameReplayer.Outcome.BLACK ? 1 : 0);
    }

    public void add(String uci, String san, long white, long draws, long black) {
        MoveAgg m = getOrAddMove(uci, san);
        m.white += white;
        m.draws += draws;
        m.black += black;
    }

    /** Adds all counters of another aggregate to this one. */
    public void addAll(PositionAgg other) {
        for (MoveAgg m : other.moves) {
            add(m.uci, m.san, m.white, m.draws, m.black);
        }
    }

    public long games() {
        long games = 0;
        for (MoveAgg m : moves) {
            games += m.games();
        }
        return games;
    }
}
