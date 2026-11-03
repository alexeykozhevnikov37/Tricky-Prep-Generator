package com.ser_databus.tpg.lichessdb;

import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.elite.PgnGameReader;
import com.ser_databus.tpg.lichess.LichessRating;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GameFilterTest {
    private static PgnGameReader.TaggedGame game(String result, String whiteElo, String blackElo, String timeControl,
                                                 String whiteTitle, String blackTitle, String variant, String fen) {
        return new PgnGameReader.TaggedGame(result, "1. e4 e5", whiteElo, blackElo, timeControl, whiteTitle,
                blackTitle, variant, fen, "Normal");
    }

    private static PgnGameReader.TaggedGame withTermination(String termination) {
        return new PgnGameReader.TaggedGame("1-0", "1. e4 e5", "2400", "2500", "300+0", null, null, null, null,
                termination);
    }

    private static PgnGameReader.TaggedGame blitz(String result, String whiteElo, String blackElo) {
        return game(result, whiteElo, blackElo, "300+0", null, null, null, null);
    }

    @Test
    void speedFollowsLichess() {
        assertEquals(GameFilter.Speed.ULTRA_BULLET, GameFilter.speedOf("15+0"));
        assertEquals(GameFilter.Speed.BULLET, GameFilter.speedOf("179+0"));
        assertEquals(GameFilter.Speed.BLITZ, GameFilter.speedOf("180+0"));
        assertEquals(GameFilter.Speed.BLITZ, GameFilter.speedOf("300+3"));     // 300 + 120 = 420
        assertEquals(GameFilter.Speed.BLITZ, GameFilter.speedOf("479+0"));
        assertEquals(GameFilter.Speed.RAPID, GameFilter.speedOf("480+0"));
        assertEquals(GameFilter.Speed.BULLET, GameFilter.speedOf("60+2"));     // 60 + 80 = 140
        assertEquals(GameFilter.Speed.RAPID, GameFilter.speedOf("1499+0"));
        assertEquals(GameFilter.Speed.CLASSICAL, GameFilter.speedOf("1500+0"));
        assertEquals(GameFilter.Speed.CLASSICAL, GameFilter.speedOf("21599+0"));
        assertEquals(GameFilter.Speed.CORRESPONDENCE, GameFilter.speedOf("21600+0"));
        assertEquals(GameFilter.Speed.CORRESPONDENCE, GameFilter.speedOf("-"));
        assertNull(GameFilter.speedOf(null));
        assertNull(GameFilter.speedOf("?"));
        assertNull(GameFilter.speedOf("300"));
    }

    @Test
    void acceptsBlitzRapidClassical() {
        for (String tc : new String[]{"180+0", "600+5", "1800+30"}) {
            GameFilter.Verdict v = GameFilter.check(game("1-0", "2400", "2500", tc, null, null, null, null));
            assertTrue(v.accepted(), tc);
            assertEquals(LichessRating.R2200, v.bucket());
            assertEquals(GameReplayer.Outcome.WHITE, v.outcome());
        }
        assertEquals(GameFilter.SkipReason.SPEED,
                GameFilter.check(game("1-0", "2400", "2500", "60+0", null, null, null, null)).skip());
        assertEquals(GameFilter.SkipReason.SPEED,
                GameFilter.check(game("1-0", "2400", "2500", "-", null, null, null, null)).skip());
        assertEquals(GameFilter.SkipReason.SPEED,
                GameFilter.check(game("1-0", "2400", "2500", null, null, null, null, null)).skip());
    }

    @Test
    void skipsBotsMissingRatingsAndUnknownResults() {
        assertEquals(GameFilter.SkipReason.BOT,
                GameFilter.check(game("1-0", "2400", "2500", "300+0", "BOT", null, null, null)).skip());
        assertEquals(GameFilter.SkipReason.BOT,
                GameFilter.check(game("1-0", "2400", "2500", "300+0", null, "BOT", null, null)).skip());
        assertTrue(GameFilter.check(game("1-0", "2400", "2500", "300+0", "GM", null, null, null)).accepted());
        assertEquals(GameFilter.SkipReason.RATING, GameFilter.check(blitz("1-0", "?", "2500")).skip());
        assertEquals(GameFilter.SkipReason.RATING, GameFilter.check(blitz("1-0", "2400", null)).skip());
        assertEquals(GameFilter.SkipReason.RESULT, GameFilter.check(blitz("*", "2400", "2500")).skip());
        assertEquals(GameFilter.SkipReason.RESULT, GameFilter.check(blitz(null, "2400", "2500")).skip());
        assertEquals(GameReplayer.Outcome.DRAW, GameFilter.check(blitz("1/2-1/2", "2400", "2500")).outcome());
        assertEquals(GameReplayer.Outcome.BLACK, GameFilter.check(blitz("0-1", "2400", "2500")).outcome());
    }

    @Test
    void onlyBuckets2000AndUpAreIndexed() {
        // (1999 + 2000) / 2 = 1999 -> 1800 bucket
        assertEquals(GameFilter.SkipReason.LOW_RATING, GameFilter.check(blitz("1-0", "1999", "2000")).skip());
        assertEquals(GameFilter.SkipReason.LOW_RATING, GameFilter.check(blitz("1-0", "1500", "1500")).skip());
        assertEquals(LichessRating.R2000, GameFilter.check(blitz("1-0", "2000", "2001")).bucket());
        // the average counts: 1900 against 2150 is a 2025 game
        assertEquals(LichessRating.R2000, GameFilter.check(blitz("1-0", "1900", "2150")).bucket());
        assertEquals(LichessRating.R2200, GameFilter.check(blitz("1-0", "2200", "2499")).bucket());
        assertEquals(LichessRating.R2500, GameFilter.check(blitz("1-0", "2500", "2800")).bucket());
    }

    @Test
    void skipsLargeRatingGaps() {
        // exactly 300 is kept, more is skipped, in both directions
        assertTrue(GameFilter.check(blitz("1-0", "2200", "2500")).accepted());
        assertTrue(GameFilter.check(blitz("1-0", "2500", "2200")).accepted());
        assertEquals(GameFilter.SkipReason.RATING_GAP, GameFilter.check(blitz("1-0", "2200", "2501")).skip());
        assertEquals(GameFilter.SkipReason.RATING_GAP, GameFilter.check(blitz("1-0", "2600", "1400")).skip());
    }

    @Test
    void skipsAbandonedGamesAndRulesInfractions() {
        assertEquals(GameFilter.SkipReason.TERMINATION, GameFilter.check(withTermination("Abandoned")).skip());
        assertEquals(GameFilter.SkipReason.TERMINATION, GameFilter.check(withTermination("Rules infraction")).skip());
        assertTrue(GameFilter.check(withTermination("Normal")).accepted());
        assertTrue(GameFilter.check(withTermination("Time forfeit")).accepted());
        assertTrue(GameFilter.check(withTermination(null)).accepted());
    }

    @Test
    void shortGames() {
        assertTrue(!GameFilter.isLongEnough(9));
        assertTrue(GameFilter.isLongEnough(10));
        assertTrue(GameFilter.isLongEnough(50));
    }

    @Test
    void skipsVariantsAndCustomStartPositions() {
        assertEquals(GameFilter.SkipReason.NON_STANDARD, GameFilter.check(
                game("1-0", "2400", "2500", "300+0", null, null, "From Position", "8/8/8/8/8/8/8/K6k w - - 0 1"))
                .skip());
        assertEquals(GameFilter.SkipReason.NON_STANDARD, GameFilter.check(
                game("1-0", "2400", "2500", "300+0", null, null, "Chess960", null)).skip());
        assertTrue(GameFilter.check(game("1-0", "2400", "2500", "300+0", null, null, "Standard",
                "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")).accepted());
    }
}
