package com.ser_databus.tpg.elite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link GameReplayer#replayUnique}: positions counted once per game, like the Lichess opening explorer. */
public class GameReplayerUniqueTest {
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -";

    private final GameReplayer replayer = new GameReplayer(50);

    @Test
    void includesTheFinalPositionWithoutMove() throws Exception {
        List<GameReplayer.UniquePly> positions = replayer.replayUnique(GameReplayer.Outcome.WHITE,
                "1. e4 e5 2. Nf3 Nc6 1-0").positions();
        assertEquals(5, positions.size());
        assertEquals(START, positions.get(0).fen());
        assertEquals("e2e4", positions.get(0).uci());
        assertEquals("e4", positions.get(0).san());
        assertEquals("b8c6", positions.get(3).uci());
        assertEquals("r1bqkbnr/pppp1ppp/2n5/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R w KQkq -", positions.get(4).fen());
        assertNull(positions.get(4).uci());
        assertNull(positions.get(4).san());
    }

    @Test
    void repeatedPositionKeepsTheLastMove() throws Exception {
        // The start position occurs again after 2... Ng8; the second time White plays Nc3
        List<GameReplayer.UniquePly> positions = replayer.replayUnique(GameReplayer.Outcome.DRAW,
                "1. Nf3 Nf6 2. Ng1 Ng8 3. Nc3 Nc6 1/2-1/2").positions();
        assertEquals(6, positions.size());
        assertEquals(START, positions.get(0).fen());
        assertEquals("b1c3", positions.get(0).uci());
        assertEquals("Nc3", positions.get(0).san());
        assertEquals("g8f6", positions.get(1).uci());
        assertEquals("f3g1", positions.get(2).uci());
        assertEquals("f6g8", positions.get(3).uci());
        assertEquals("b8c6", positions.get(4).uci());
        assertNull(positions.get(5).uci());
    }

    @Test
    void finalPositionSeenBeforeKeepsItsMove() throws Exception {
        // The game ends in the start position, which was left with Nf3 before
        List<GameReplayer.UniquePly> positions = replayer.replayUnique(GameReplayer.Outcome.DRAW,
                "1. Nf3 Nf6 2. Ng1 Ng8 1/2-1/2").positions();
        assertEquals(4, positions.size());
        assertEquals(START, positions.get(0).fen());
        assertEquals("g1f3", positions.get(0).uci());
    }

    @Test
    void stopsAtMaxPlies() throws Exception {
        GameReplayer two = new GameReplayer(2);
        List<GameReplayer.UniquePly> positions = two.replayUnique(GameReplayer.Outcome.DRAW,
                "1. d4 d5 2. c4 e6").positions();
        // start (d4), after d4 (d5), after d4 d5 (no move: c4 is beyond the limit)
        assertEquals(3, positions.size());
        assertEquals("d7d5", positions.get(1).uci());
        assertNull(positions.get(2).uci());
    }
}
