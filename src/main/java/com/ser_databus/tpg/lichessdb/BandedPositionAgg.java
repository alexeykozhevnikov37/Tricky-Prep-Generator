package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.lichess.LichessRating;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulated statistics of one position, per rating bucket ({@link RatingBuckets}, index =
 * {@link LichessRating#ordinal()}):
 * <ul>
 *     <li>white wins / draws / black wins of the games that <b>reached</b> the position within the indexed plies
 *     (each game counted once, including games that ended in this position);</li>
 *     <li>for every move played from the position: the number of games that played it.</li>
 * </ul>
 */
public class BandedPositionAgg {
    public static class MoveAgg {
        public final String uci;
        public final String san;
        /** Games that played the move, per bucket. */
        public final long[] counts = new long[RatingBuckets.COUNT];

        public MoveAgg(String uci, String san) {
            this.uci = uci;
            this.san = san;
        }

        public long games() {
            long games = 0;
            for (long c : counts) {
                games += c;
            }
            return games;
        }

        /** @return bit i is set if bucket i has games */
        public int bucketMask() {
            int mask = 0;
            for (int b = 0; b < counts.length; b++) {
                if (counts[b] != 0) {
                    mask |= 1 << b;
                }
            }
            return mask;
        }
    }

    /** {@code wdl[bucket * 3 + 0/1/2]} = white wins / draws / black wins. */
    private final long[] wdl = new long[RatingBuckets.COUNT * 3];
    /** Usually a handful of moves, so a list with linear search is faster and smaller than a map. */
    private final List<MoveAgg> moves = new ArrayList<>(2);

    public List<MoveAgg> getMoves() {
        return moves;
    }

    public long white(int bucket) {
        return wdl[bucket * 3];
    }

    public long draws(int bucket) {
        return wdl[bucket * 3 + 1];
    }

    public long black(int bucket) {
        return wdl[bucket * 3 + 2];
    }

    /** Games that reached the position in this bucket. */
    public long games(int bucket) {
        return white(bucket) + draws(bucket) + black(bucket);
    }

    /** Games that reached the position, all buckets. */
    public long games() {
        long games = 0;
        for (long v : wdl) {
            games += v;
        }
        return games;
    }

    /** @return bit i is set if bucket i has games that reached the position */
    public int bucketMask() {
        int mask = 0;
        for (int b = 0; b < RatingBuckets.COUNT; b++) {
            if (games(b) != 0) {
                mask |= 1 << b;
            }
        }
        return mask;
    }

    public void addReached(int bucket, GameReplayer.Outcome outcome) {
        switch (outcome) {
            case WHITE -> wdl[bucket * 3]++;
            case DRAW -> wdl[bucket * 3 + 1]++;
            case BLACK -> wdl[bucket * 3 + 2]++;
        }
    }

    public void addReached(int bucket, long white, long draws, long black) {
        wdl[bucket * 3] += white;
        wdl[bucket * 3 + 1] += draws;
        wdl[bucket * 3 + 2] += black;
    }

    public void addMove(String uci, String san, int bucket) {
        addMove(uci, san, bucket, 1);
    }

    public void addMove(String uci, String san, int bucket, long count) {
        getOrAddMove(uci, san).counts[bucket] += count;
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

    /** @return the move, or null if it was never played from this position */
    public MoveAgg getMove(String uci) {
        for (MoveAgg m : moves) {
            if (m.uci.equals(uci)) {
                return m;
            }
        }
        return null;
    }

    /** Adds all counters of another aggregate to this one. */
    public void addAll(BandedPositionAgg other) {
        for (int i = 0; i < wdl.length; i++) {
            wdl[i] += other.wdl[i];
        }
        for (MoveAgg m : other.moves) {
            MoveAgg mine = getOrAddMove(m.uci, m.san);
            for (int b = 0; b < m.counts.length; b++) {
                mine.counts[b] += m.counts[b];
            }
        }
    }
}
