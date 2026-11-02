package com.ser_databus.tpg.lichessdb;

import com.github.luben.zstd.ZstdOutputStream;
import com.ser_databus.tpg.elite.FileProgress;
import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.lichess.LichessRating;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class LichessIndexBuilderTest {
    private static final int B1000 = LichessRating.R1000.ordinal();
    private static final int B1400 = LichessRating.R1400.ordinal();
    private static final int B2000 = LichessRating.R2000.ordinal();

    /** Blitz, average 1550 -> 1400 bucket. */
    private static final String GAME_BLITZ = """
            [Event "Rated Blitz game"]
            [Site "https://lichess.org/aaaaaaaa"]
            [Result "1-0"]
            [WhiteElo "1500"]
            [BlackElo "1600"]
            [TimeControl "300+0"]

            1. e4 { [%clk 0:05:00] } 1... e5 { [%clk 0:05:00] } 2. Nf3 { [%clk 0:04:58] } 2... Nc6 { [%clk 0:04:57] } 1-0

            """;
    /** Rapid (600 + 40 * 5 = 800 s), average 2050 -> 2000 bucket. */
    private static final String GAME_RAPID = """
            [Event "Rated Rapid game"]
            [Result "0-1"]
            [WhiteElo "2000"]
            [BlackElo "2100"]
            [TimeControl "600+5"]

            1. e4 c5 0-1

            """;
    /** Classical, average 1000 -> 1000 bucket. */
    private static final String GAME_CLASSICAL = """
            [Event "Rated Classical game"]
            [Result "1/2-1/2"]
            [WhiteElo "1000"]
            [BlackElo "1000"]
            [TimeControl "1800+0"]

            1. d4 d5 1/2-1/2

            """;
    private static final String GAME_BULLET = """
            [Event "Rated Bullet game"]
            [Result "1-0"]
            [WhiteElo "1500"]
            [BlackElo "1500"]
            [TimeControl "60+0"]

            1. e4 e5 1-0

            """;
    private static final String GAME_BOT = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [WhiteElo "1500"]
            [BlackElo "1500"]
            [WhiteTitle "BOT"]
            [TimeControl "300+0"]

            1. e4 e5 1-0

            """;
    private static final String GAME_UNKNOWN = """
            [Event "Rated Blitz game"]
            [Result "*"]
            [WhiteElo "1500"]
            [BlackElo "1500"]
            [TimeControl "300+0"]

            1. d4 Nf6 *

            """;

    private static final String ALL_IN_FILE_A = GAME_BLITZ + GAME_BULLET + GAME_RAPID + GAME_BOT + GAME_UNKNOWN;

    @TempDir
    Path tmp;

    @Test
    void indexesAndSkipsFinishedFiles() throws Exception {
        Path a = write("a.pgn", ALL_IN_FILE_A);
        Path b = write("b.pgn", GAME_CLASSICAL);
        Path dbDir = tmp.resolve("db");

        try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
            new LichessIndexBuilder(store, 2).runLocalFiles(List.of(a, b));
            assertStats(store);
            assertEquals(FileProgress.Status.DONE, store.getAllProgress().get("a.pgn").status());
            assertEquals(5, store.getAllProgress().get("a.pgn").gamesConsumed());
        }
        // A second run must not count anything again
        try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
            new LichessIndexBuilder(store, 2).runLocalFiles(List.of(a, b));
            assertStats(store);
        }
    }

    @Test
    void resumesInterruptedFileWithoutDoubleCounting() throws Exception {
        Path dbDir = tmp.resolve("db");
        // Simulate an interrupted run: the first game of a.pgn is committed with an IN_PROGRESS checkpoint
        Path a = write("a.pgn", GAME_BLITZ);
        try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
            new LichessIndexBuilder(store, 1_000_000).runLocalFiles(List.of(a));
            store.merge(new HashMap<>(), "a.pgn", new FileProgress(FileProgress.Status.IN_PROGRESS, 1, 0, 0));
        }
        write("a.pgn", ALL_IN_FILE_A);
        Path b = write("b.pgn", GAME_CLASSICAL);
        try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
            new LichessIndexBuilder(store, 1_000_000).runLocalFiles(List.of(a, b));
            assertStats(store);
        }
    }

    @Test
    void readsZstdDumps() throws Exception {
        Path zst = tmp.resolve("lichess_db_standard_rated_2013-01.pgn.zst");
        try (OutputStream out = new ZstdOutputStream(Files.newOutputStream(zst))) {
            out.write((ALL_IN_FILE_A + GAME_CLASSICAL).getBytes(StandardCharsets.UTF_8));
        }
        try (BandedIndexStore store = new BandedIndexStore(tmp.resolve("db"))) {
            new LichessIndexBuilder(store, 1_000_000).runLocalFiles(List.of(zst));
            assertStats(store);
        }
    }

    private void assertStats(BandedIndexStore store) throws Exception {
        // Only the blitz, rapid and classical games count
        BandedPositionAgg start = store.getPosition(key(""));
        assertEquals(3, start.games());
        assertEquals(1, start.white(B1400));
        assertEquals(1, start.black(B2000));
        assertEquals(1, start.draws(B1000));
        assertEquals(2, start.getMoves().size());
        assertEquals(1, start.getMove("e2e4").counts[B1400]);
        assertEquals(1, start.getMove("e2e4").counts[B2000]);
        assertEquals(2, start.getMove("e2e4").games());
        assertEquals(1, start.getMove("d2d4").counts[B1000]);
        assertEquals("d4", start.getMove("d2d4").san);

        BandedPositionAgg afterE4 = store.getPosition(key("1. e4"));
        assertEquals(2, afterE4.games());
        assertEquals(1, afterE4.getMove("c7c5").counts[B2000]);
        assertEquals(1, afterE4.getMove("e7e5").counts[B1400]);

        // Final positions are counted as reached, without moves
        BandedPositionAgg afterC5 = store.getPosition(key("1. e4 c5"));
        assertEquals(1, afterC5.black(B2000));
        assertEquals(0, afterC5.getMoves().size());
        BandedPositionAgg afterNc6 = store.getPosition(key("1. e4 e5 2. Nf3 Nc6"));
        assertEquals(1, afterNc6.white(B1400));
        assertEquals(1, afterNc6.games());

        // The skipped games left nothing behind (1. d4 Nf6 is only in the game with an unknown result)
        assertNull(store.getPosition(key("1. d4 Nf6")));
        assertNull(store.getPosition("8/8/8/8/8/8/8/8 w -"));
    }

    /** Position key after the given moves, built the same way as the index keys. */
    private static String key(String moves) throws Exception {
        List<GameReplayer.UniquePly> positions = new GameReplayer(50)
                .replayUnique(GameReplayer.Outcome.DRAW, moves).positions();
        return positions.get(positions.size() - 1).fen();
    }

    private Path write(String name, String content) throws Exception {
        Path file = tmp.resolve(name);
        Files.writeString(file, content);
        return file;
    }
}
