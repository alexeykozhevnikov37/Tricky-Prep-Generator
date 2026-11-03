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
    private static final int B2000 = LichessRating.R2000.ordinal();
    private static final int B2200 = LichessRating.R2200.ordinal();
    private static final int B2500 = LichessRating.R2500.ordinal();

    private static final String BLITZ_MOVES = "1. e4 e5 2. Nf3 Nc6 3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7";
    private static final String RAPID_MOVES = "1. e4 c5 2. Nf3 d6 3. d4 cxd4 4. Nxd4 Nf6 5. Nc3 a6";
    private static final String CLASSICAL_MOVES = "1. d4 d5 2. c4 e6 3. Nc3 Nf6 4. Bg5 Be7 5. e3 O-O";

    /** Blitz, average 2250 -> 2200 bucket. */
    private static final String GAME_BLITZ = """
            [Event "Rated Blitz game"]
            [Site "https://lichess.org/aaaaaaaa"]
            [Result "1-0"]
            [WhiteElo "2200"]
            [BlackElo "2300"]
            [TimeControl "300+0"]
            [Termination "Normal"]

            1. e4 { [%clk 0:05:00] } 1... e5 { [%clk 0:05:00] } 2. Nf3 { [%clk 0:04:58] } 2... Nc6 { [%clk 0:04:57] }
            3. Bb5 a6 4. Ba4 Nf6 5. O-O Be7 1-0

            """;
    /** Rapid (600 + 40 * 5 = 800 s), average 2050 -> 2000 bucket. */
    private static final String GAME_RAPID = """
            [Event "Rated Rapid game"]
            [Result "0-1"]
            [WhiteElo "2000"]
            [BlackElo "2100"]
            [TimeControl "600+5"]
            [Termination "Time forfeit"]

            %s 0-1

            """.formatted(RAPID_MOVES);
    /** Classical, average 2550 -> 2500 bucket. */
    private static final String GAME_CLASSICAL = """
            [Event "Rated Classical game"]
            [Result "1/2-1/2"]
            [WhiteElo "2500"]
            [BlackElo "2600"]
            [TimeControl "1800+0"]

            %s 1/2-1/2

            """.formatted(CLASSICAL_MOVES);
    /** Blitz, average 1999 -> 1800 bucket: not indexed. */
    private static final String GAME_LOW_RATED = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [WhiteElo "1999"]
            [BlackElo "2000"]
            [TimeControl "300+0"]

            1. c4 e5 2. Nc3 Nf6 3. g3 d5 4. cxd5 Nxd5 5. Bg2 Nb6 1-0

            """;
    /** Rating gap 400: not indexed. */
    private static final String GAME_RATING_GAP = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [WhiteElo "2600"]
            [BlackElo "2200"]
            [TimeControl "300+0"]

            1. g3 d5 2. Bg2 Nf6 3. Nf3 c6 4. O-O Bg4 5. d3 e6 1-0

            """;
    private static final String GAME_ABANDONED = """
            [Event "Rated Blitz game"]
            [Result "0-1"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [TimeControl "300+0"]
            [Termination "Abandoned"]

            1. Nf3 d5 2. g3 Nf6 3. Bg2 e6 4. O-O Be7 5. d3 O-O 0-1

            """;
    private static final String GAME_RULES_INFRACTION = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [TimeControl "300+0"]
            [Termination "Rules infraction"]

            1. f4 d5 2. Nf3 Nf6 3. e3 g6 4. b3 Bg7 5. Bb2 O-O 1-0

            """;
    /** 2 plies: not indexed. */
    private static final String GAME_SHORT = """
            [Event "Rated Blitz game"]
            [Result "0-1"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [TimeControl "300+0"]

            1. b3 e5 0-1

            """;
    private static final String GAME_BULLET = """
            [Event "Rated Bullet game"]
            [Result "1-0"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [TimeControl "60+0"]

            1. a3 e5 2. h3 d5 3. a4 Nf6 4. h4 Nc6 5. b3 Bd6 1-0

            """;
    private static final String GAME_BOT = """
            [Event "Rated Blitz game"]
            [Result "1-0"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [WhiteTitle "BOT"]
            [TimeControl "300+0"]

            1. a3 e5 2. h3 d5 3. a4 Nf6 4. h4 Nc6 5. b3 Bd6 1-0

            """;
    private static final String GAME_UNKNOWN = """
            [Event "Rated Blitz game"]
            [Result "*"]
            [WhiteElo "2200"]
            [BlackElo "2200"]
            [TimeControl "300+0"]

            1. a3 e5 2. h3 d5 3. a4 Nf6 4. h4 Nc6 5. b3 Bd6 *

            """;

    private static final String ALL_IN_FILE_A = GAME_BLITZ + GAME_BULLET + GAME_RAPID + GAME_BOT + GAME_UNKNOWN
            + GAME_LOW_RATED + GAME_RATING_GAP + GAME_ABANDONED + GAME_RULES_INFRACTION + GAME_SHORT;
    private static final int GAMES_IN_FILE_A = 10;

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
            assertEquals(GAMES_IN_FILE_A, store.getAllProgress().get("a.pgn").gamesConsumed());
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
        // Only the blitz, rapid and classical games that pass all filters count
        BandedPositionAgg start = store.getPosition(key(""));
        assertEquals(3, start.games());
        assertEquals(1, start.white(B2200));
        assertEquals(1, start.black(B2000));
        assertEquals(1, start.draws(B2500));
        assertEquals(2, start.getMoves().size());
        assertEquals(1, start.getMove("e2e4").counts[B2200]);
        assertEquals(1, start.getMove("e2e4").counts[B2000]);
        assertEquals(2, start.getMove("e2e4").games());
        assertEquals(1, start.getMove("d2d4").counts[B2500]);
        assertEquals("d4", start.getMove("d2d4").san);

        BandedPositionAgg afterE4 = store.getPosition(key("1. e4"));
        assertEquals(2, afterE4.games());
        assertEquals(1, afterE4.getMove("c7c5").counts[B2000]);
        assertEquals(1, afterE4.getMove("e7e5").counts[B2200]);

        // Final positions are counted as reached, without moves
        BandedPositionAgg rapidEnd = store.getPosition(key(RAPID_MOVES));
        assertEquals(1, rapidEnd.black(B2000));
        assertEquals(0, rapidEnd.getMoves().size());
        BandedPositionAgg blitzEnd = store.getPosition(key(BLITZ_MOVES));
        assertEquals(1, blitzEnd.white(B2200));
        assertEquals(1, blitzEnd.games());
        assertEquals(1, store.getPosition(key(CLASSICAL_MOVES)).draws(B2500));

        // The skipped games left nothing behind
        assertNull(store.getPosition(key("1. a3")));   // bullet, bot, unknown result
        assertNull(store.getPosition(key("1. c4")));   // average rating below 2000
        assertNull(store.getPosition(key("1. g3")));   // rating gap over 300
        assertNull(store.getPosition(key("1. Nf3")));  // abandoned
        assertNull(store.getPosition(key("1. f4")));   // rules infraction
        assertNull(store.getPosition(key("1. b3")));   // shorter than 10 plies
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
