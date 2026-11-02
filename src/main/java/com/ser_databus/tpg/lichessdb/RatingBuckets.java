package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.lichess.LichessRating;

/**
 * Rating buckets of the Lichess opening explorer. A game goes into one bucket by the average rating of its two
 * players, {@code (whiteElo + blackElo) / 2} with integer division, the same way the explorer does it
 * ({@code RatingGroup::select} in lila-openingexplorer). Both sides' moves are counted in that bucket.
 * <p>
 * The explorer has extra groups above 2500, but its rating filter puts everything from 2500 up into the 2500 group,
 * so the index keeps the nine groups of {@link LichessRating}: below 1000 ({@link LichessRating#R400}), 1000, 1200,
 * 1400, 1600, 1800, 2000, 2200 and 2500+.
 */
public final class RatingBuckets {
    /** Number of buckets, the index of a bucket is {@link LichessRating#ordinal()}. */
    public static final int COUNT = LichessRating.values().length;

    private RatingBuckets() {
    }

    /** @return the bucket of a game with these player ratings */
    public static LichessRating of(int whiteElo, int blackElo) {
        return ofAverage((whiteElo + blackElo) / 2);
    }

    public static LichessRating ofAverage(int average) {
        if (average < 1000) {
            return LichessRating.R400;
        } else if (average < 1200) {
            return LichessRating.R1000;
        } else if (average < 1400) {
            return LichessRating.R1200;
        } else if (average < 1600) {
            return LichessRating.R1400;
        } else if (average < 1800) {
            return LichessRating.R1600;
        } else if (average < 2000) {
            return LichessRating.R1800;
        } else if (average < 2200) {
            return LichessRating.R2000;
        } else if (average < 2500) {
            return LichessRating.R2200;
        } else {
            return LichessRating.R2500;
        }
    }
}
