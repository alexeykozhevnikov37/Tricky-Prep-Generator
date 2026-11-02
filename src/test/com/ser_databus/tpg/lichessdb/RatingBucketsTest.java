package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.lichess.LichessRating;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RatingBucketsTest {
    @Test
    void bucketEdges() {
        assertEquals(LichessRating.R400, RatingBuckets.ofAverage(600));
        assertEquals(LichessRating.R400, RatingBuckets.ofAverage(999));
        assertEquals(LichessRating.R1000, RatingBuckets.ofAverage(1000));
        assertEquals(LichessRating.R1800, RatingBuckets.ofAverage(1999));
        assertEquals(LichessRating.R2000, RatingBuckets.ofAverage(2000));
        assertEquals(LichessRating.R2200, RatingBuckets.ofAverage(2499));
        assertEquals(LichessRating.R2500, RatingBuckets.ofAverage(2500));
        assertEquals(LichessRating.R2500, RatingBuckets.ofAverage(2900));
    }

    @Test
    void averageOfBothPlayersWithIntegerDivision() {
        // (1999 + 2000) / 2 = 1999
        assertEquals(LichessRating.R1800, RatingBuckets.of(1999, 2000));
        assertEquals(LichessRating.R2000, RatingBuckets.of(2000, 2001));
        // a 2600 player against a 1400 player: average 2000, both moves go there
        assertEquals(LichessRating.R2000, RatingBuckets.of(2600, 1400));
        assertEquals(LichessRating.R2000, RatingBuckets.of(1400, 2600));
    }
}
