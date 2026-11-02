package com.ser_databus.tpg.lichess;

import com.ser_databus.tpg.elite.EliteIndexBuilder;
import com.ser_databus.tpg.elite.EliteIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalLichessClientTest {
    private static final String PGN = """
            [Event "Rated Blitz game"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0

            [Event "Rated Blitz game"]
            [Result "0-1"]

            1. e4 c5 2. Nf3 d6 0-1

            [Event "Rated Blitz game"]
            [Result "1/2-1/2"]

            1. e4 c5 2. Nc3 Nc6 1/2-1/2

            [Event "Rated Rapid game"]
            [Result "1/2-1/2"]

            1. d4 d5 1/2-1/2

            [Event "Rated Blitz game"]
            [Result "1-0"]

            1. Nf3 Nf6 2. d3 g6 3. e4 Bg7 1-0

            [Event "Rated Blitz game"]
            [Result "0-1"]

            1. Nf3 Nf6 2. e4 g6 3. d3 Bg7 0-1

            [Event "Rated Blitz game"]
            [Result "1-0"]

            1. Nf3 a6 2. e4 h6 3. e5 d5 4. exd6 1-0

            """;

    @TempDir
    Path tmp;

    @Test
    void returnsPositionsLikeLichessClient() throws Exception {
        Path pgnDir = Files.createDirectories(tmp.resolve("pgn"));
        Files.writeString(pgnDir.resolve("games.pgn"), PGN);
        Path indexDir = tmp.resolve("index");
        try (EliteIndexStore store = new EliteIndexStore(indexDir)) {
            new EliteIndexBuilder(pgnDir, store, 1_000_000).run();
        }

        try (LocalLichessClient client = new LocalLichessClient(indexDir)) {
            LichessPosition start = client.getPosition("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
            assertEquals(7, start.getGames());
            assertEquals(3, start.getWhite());
            assertEquals(2, start.getDraws());
            assertEquals(2, start.getBlack());
            // Sorted by games: e4 (3), Nf3 (3), d4 (1)
            assertEquals(3, start.getMoves().get(0).getGames());
            assertEquals(3, start.getMoves().get(1).getGames());
            assertEquals("d4", start.getMoves().get(2).san);
            assertEquals("d2d4", start.getMoves().get(2).uci);

            // After 1. e4: found with and without the (irrelevant) en passant square
            String afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq ";
            for (String ep : new String[]{"e3", "-"}) {
                LichessPosition position = client.getPosition(afterE4 + ep + " 0 1");
                assertEquals(3, position.getGames());
                assertEquals("c5", position.getMoves().get(0).san);
                assertEquals(2, position.getMoves().get(0).getGames());
            }

            // The same position after 3. e4 (en passant square e3 in chesslib) and after 3. d3: one key, both games
            LichessPosition transposed = client.getPosition(
                    "rnbqkb1r/pppppp1p/5np1/8/4P3/3P1N2/PPP2PPP/RNBQKB1R b KQkq - 0 3");
            assertEquals(2, transposed.getGames());
            assertEquals(1, transposed.getWhite());
            assertEquals(1, transposed.getBlack());

            // A legal en passant capture keeps the square: after 3... d5 White can play exd6
            LichessPosition epPosition = client.getPosition(
                    "rnbqkbnr/1pp1ppp1/p6p/3pP3/8/5N2/PPPP1PPP/RNBQKB1R w KQkq d6 0 4");
            assertEquals(1, epPosition.getGames());
            assertEquals("e5d6", epPosition.getMoves().get(0).uci);
            assertEquals("exd6", epPosition.getMoves().get(0).san);

            LichessPosition unknown = client.getPosition("8/8/8/4k3/8/8/8/4K3 w - - 0 1");
            assertEquals(0, unknown.getGames());
            assertTrue(unknown.getMoves().isEmpty());
        }
    }
}
