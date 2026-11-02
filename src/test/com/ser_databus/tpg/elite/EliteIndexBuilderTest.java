package com.ser_databus.tpg.elite;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class EliteIndexBuilderTest {
    private static final String START = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -";
    /** Built the same way as the index keys. */
    private static final String AFTER_E4;

    static {
        Board board = new Board();
        board.doMove(new Move(Square.E2, Square.E4));
        AFTER_E4 = PositionKey.of(board);
    }

    private static final String GAME_1 = """
            [Event "Rated Blitz game"]
            [Result "1-0"]

            1. e4 e5 2. Nf3 Nc6 1-0

            """;
    private static final String GAME_2 = """
            [Event "Rated Blitz game"]
            [Result "0-1"]

            1. e4 c5 2. Nf3 d6 0-1

            """;
    private static final String GAME_3 = """
            [Event "Rated Rapid game"]
            [Result "1/2-1/2"]

            1. d4 d5
            2. c4 1/2-1/2

            """;
    private static final String GAME_UNKNOWN = """
            [Event "Rated Blitz game"]
            [Result "*"]

            1. d4 Nf6 *

            """;

    @TempDir
    Path tmp;

    @Test
    void indexesAndSkipsFinishedFiles() throws Exception {
        Path pgnDir = Files.createDirectories(tmp.resolve("pgn"));
        Files.writeString(pgnDir.resolve("a.pgn"), GAME_1 + GAME_2 + GAME_UNKNOWN);
        Files.writeString(pgnDir.resolve("b.pgn"), GAME_3);
        Path dbDir = tmp.resolve("db");

        try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
            new EliteIndexBuilder(pgnDir, store, 2).run();
            assertStats(store);
            assertEquals(FileProgress.Status.DONE, store.getAllProgress().get("a.pgn").status());
            assertEquals(4, store.getAllProgress().get("a.pgn").gamesConsumed());
        }
        // A second run must not count anything again
        try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
            new EliteIndexBuilder(pgnDir, store, 2).run();
            assertStats(store);
        }
    }

    @Test
    void resumesInterruptedFileWithoutDoubleCounting() throws Exception {
        Path pgnDir = Files.createDirectories(tmp.resolve("pgn"));
        Path dbDir = tmp.resolve("db");
        // Simulate an interrupted run: the first game of a.pgn is committed with an IN_PROGRESS checkpoint
        Files.writeString(pgnDir.resolve("a.pgn"), GAME_1);
        try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
            new EliteIndexBuilder(pgnDir, store, 1_000_000).run();
            store.merge(new HashMap<>(), "a.pgn", new FileProgress(FileProgress.Status.IN_PROGRESS, 1, 0, 0));
        }
        Files.writeString(pgnDir.resolve("a.pgn"), GAME_1 + GAME_2 + GAME_UNKNOWN);
        Files.writeString(pgnDir.resolve("b.pgn"), GAME_3);
        try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
            new EliteIndexBuilder(pgnDir, store, 1_000_000).run();
            assertStats(store);
        }
    }

    private void assertStats(EliteIndexStore store) throws Exception {
        PositionAgg start = store.getPosition(START);
        assertEquals(3, start.games());
        Map<String, PositionAgg.MoveAgg> moves = byUci(start);
        assertEquals(2, moves.size());
        assertEquals(1, moves.get("e2e4").white);
        assertEquals(1, moves.get("e2e4").black);
        assertEquals(1, moves.get("d2d4").draws);
        assertEquals("d4", moves.get("d2d4").san);

        PositionAgg afterE4 = store.getPosition(AFTER_E4);
        assertEquals(2, afterE4.games());
        assertEquals(1, byUci(afterE4).get("c7c5").black);

        assertNull(store.getPosition("8/8/8/8/8/8/8/8 w -"));
    }

    private Map<String, PositionAgg.MoveAgg> byUci(PositionAgg position) {
        Map<String, PositionAgg.MoveAgg> result = new HashMap<>();
        for (PositionAgg.MoveAgg m : position.getMoves()) {
            result.put(m.uci, m);
        }
        return result;
    }
}
