package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.lichess.LichessRating;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class BandedPositionCodecTest {
    private static final int B400 = LichessRating.R400.ordinal();
    private static final int B1600 = LichessRating.R1600.ordinal();
    private static final int B2500 = LichessRating.R2500.ordinal();

    @Test
    void roundTrip() {
        BandedPositionAgg p = new BandedPositionAgg();
        p.addReached(B1600, GameReplayer.Outcome.WHITE);
        p.addReached(B1600, GameReplayer.Outcome.DRAW);
        p.addReached(B2500, 300, 200, 1_000_000);
        p.addReached(B400, GameReplayer.Outcome.BLACK);
        p.addMove("e2e4", "e4", B1600, 2);
        p.addMove("e2e4", "e4", B2500, 1_000_000);
        p.addMove("e1h1", "O-O", B400);

        BandedPositionAgg d = BandedPositionCodec.decode(BandedPositionCodec.encode(p));
        for (int b = 0; b < RatingBuckets.COUNT; b++) {
            assertEquals(p.white(b), d.white(b));
            assertEquals(p.draws(b), d.draws(b));
            assertEquals(p.black(b), d.black(b));
        }
        assertEquals(1_000_503, d.games());
        assertEquals(2, d.getMoves().size());
        assertArrayEquals(p.getMove("e2e4").counts, d.getMove("e2e4").counts);
        assertEquals("e4", d.getMove("e2e4").san);
        assertEquals(1, d.getMove("e1h1").counts[B400]);
        assertEquals("O-O", d.getMove("e1h1").san);
    }

    @Test
    void positionWithoutMoves() {
        BandedPositionAgg p = new BandedPositionAgg();
        p.addReached(B1600, GameReplayer.Outcome.BLACK);
        BandedPositionAgg d = BandedPositionCodec.decode(BandedPositionCodec.encode(p));
        assertEquals(1, d.black(B1600));
        assertEquals(0, d.getMoves().size());
        assertNull(d.getMove("e2e4"));
    }

    @Test
    void addAllAddsPerBucket() {
        BandedPositionAgg a = new BandedPositionAgg();
        a.addReached(B1600, GameReplayer.Outcome.WHITE);
        a.addMove("d2d4", "d4", B1600);
        BandedPositionAgg b = new BandedPositionAgg();
        b.addReached(B1600, GameReplayer.Outcome.WHITE);
        b.addReached(B2500, GameReplayer.Outcome.DRAW);
        b.addMove("d2d4", "d4", B2500);
        b.addMove("c2c4", "c4", B1600);
        a.addAll(b);
        assertEquals(2, a.white(B1600));
        assertEquals(1, a.draws(B2500));
        assertEquals(1, a.getMove("d2d4").counts[B1600]);
        assertEquals(1, a.getMove("d2d4").counts[B2500]);
        assertEquals(2, a.getMove("d2d4").games());
        assertEquals(1, a.getMove("c2c4").games());
    }
}
