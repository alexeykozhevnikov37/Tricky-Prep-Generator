package com.ser_databus.tpg.elite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GameReplayerTest {
    private final GameReplayer replayer = new GameReplayer(50);

    @Test
    void replaysPositionsAndLichessUci() throws Exception {
        GameReplayer.ReplayedGame game = replayer.replay(GameReplayer.Outcome.WHITE,
                "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. O-O Nf6 1-0");
        List<GameReplayer.Ply> plies = game.plies();
        assertEquals(8, plies.size());
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", plies.get(0).fen());
        assertEquals("e2e4", plies.get(0).uci());
        assertEquals("e4", plies.get(0).san());
        // Castling uses the Lichess explorer notation "king takes rook"
        assertEquals("e1h1", plies.get(6).uci());
        assertEquals("O-O", plies.get(6).san());
        assertEquals("r1bqkbnr/1ppp1ppp/p1n5/1B2p3/4P3/5N2/PPPP1PPP/RNBQK2R w KQkq -", plies.get(6).fen());
    }

    @Test
    void stopsAtMaxPlies() throws Exception {
        GameReplayer short2 = new GameReplayer(2);
        assertEquals(2, short2.replay(GameReplayer.Outcome.DRAW, "1. d4 d5 2. c4 e6").plies().size());
    }

    @Test
    void disambiguationCapturesAndPromotion() throws Exception {
        // Both knights can go to d2: "Nbd2"; then a pawn race ending with a promotion
        GameReplayer.ReplayedGame game = replayer.replay(GameReplayer.Outcome.BLACK,
                "1. Nf3 d5 2. d3 e5 3. Nbd2 e4 4. dxe4 dxe4 5. b4 exf3 6. b5 fxg2 7. b6 gxh1=Q 0-1");
        List<GameReplayer.Ply> plies = game.plies();
        assertEquals("b1d2", plies.get(4).uci());
        assertEquals("Nbd2", plies.get(4).san());
        assertEquals("g2h1q", plies.get(13).uci());
        assertEquals("gxh1=Q", plies.get(13).san());
    }

    @Test
    void illegalMoveIsRejected() {
        assertThrows(GameReplayer.SanException.class,
                () -> replayer.replay(GameReplayer.Outcome.WHITE, "1. e4 e5 2. Ke3"));
    }
}
