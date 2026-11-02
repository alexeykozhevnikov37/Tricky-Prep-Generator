package com.ser_databus.tpg.elite;

import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Console program that indexes the opening positions (first {@value #MAX_PLIES} plies, i.e. moves 1-25) of the
 * Lichess Elite Database PGN files into a RocksDB index.
 * <p>
 * Files are processed one by one in name order. Finished files are remembered in the index itself and are never
 * parsed again, so new PGN files can be dropped into the folder and the program re-run to add them. A file that is
 * interrupted (Ctrl+C, crash) is resumed from its last committed game: statistics and progress are always written
 * in one atomic batch, so nothing is counted twice.
 * <p>
 * Arguments (all optional):
 * <pre>
 *   --status            only print what has been indexed
 *   --pgn-dir=PATH      default: raw/Lichess Elite Database
 *   --db-dir=PATH       default: data/eliteIndex
 *   --flush=N           positions kept in memory before they're merged into the index, default 1000000
 *                       (about 300 bytes of heap per position)
 * </pre>
 */
public class EliteIndexBuilder {
    public static final int MAX_PLIES = 50;

    private static final String DEFAULT_PGN_DIR = "raw/Lichess Elite Database";
    private static final String DEFAULT_DB_DIR = "data/eliteIndex";
    private static final int DEFAULT_FLUSH_POSITIONS = 1_000_000;
    /** Games replayed in parallel at once. */
    private static final int GAME_BATCH = 4_000;
    private static final int MAX_REPORTED_ERRORS = 20;

    private final Path pgnDir;
    private final EliteIndexStore store;
    private final int flushPositions;
    private final GameReplayer replayer = new GameReplayer(MAX_PLIES);

    /** Interned UCI/SAN strings: there are only a few thousand distinct ones, but millions of references. */
    private final Map<String, String> strings = new HashMap<>();
    private Map<String, PositionAgg> buffer = new HashMap<>();

    private volatile boolean stopRequested;

    private long totalIndexedGames;
    private long totalUnknownResult;
    private long totalBadSan;
    private int reportedErrors;

    public EliteIndexBuilder(Path pgnDir, EliteIndexStore store, int flushPositions) {
        this.pgnDir = pgnDir;
        this.store = store;
        this.flushPositions = flushPositions;
    }

    public static void main(String[] args) throws Exception {
        Path pgnDir = Paths.get(DEFAULT_PGN_DIR);
        Path dbDir = Paths.get(DEFAULT_DB_DIR);
        int flushPositions = DEFAULT_FLUSH_POSITIONS;
        boolean statusOnly = false;
        for (String arg : args) {
            if (arg.equals("--status")) {
                statusOnly = true;
            } else if (arg.startsWith("--pgn-dir=")) {
                pgnDir = Paths.get(arg.substring("--pgn-dir=".length()));
            } else if (arg.startsWith("--db-dir=")) {
                dbDir = Paths.get(arg.substring("--db-dir=".length()));
            } else if (arg.startsWith("--flush=")) {
                flushPositions = Integer.parseInt(arg.substring("--flush=".length()));
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(1);
            }
        }

        System.out.println("PGN folder: " + pgnDir.toAbsolutePath());
        System.out.println("Index:      " + dbDir.toAbsolutePath());
        if (statusOnly) {
            try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
                new EliteIndexBuilder(pgnDir, store, flushPositions).printStatus();
            }
            return;
        }

        CountDownLatch closed = new CountDownLatch(1);
        EliteIndexBuilder[] builder = new EliteIndexBuilder[1];
        Thread hook = new Thread(() -> {
            if (builder[0] != null) {
                builder[0].stopRequested = true;
            }
            System.out.println("Stop requested: committing the current batch...");
            try {
                // The JVM exits when the hook returns, so wait until the index is committed and closed
                closed.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                // Exit anyway: the index stays consistent, only uncommitted games will be parsed again
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try (EliteIndexStore store = new EliteIndexStore(dbDir)) {
            builder[0] = new EliteIndexBuilder(pgnDir, store, flushPositions);
            builder[0].run();
        } finally {
            closed.countDown();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress
        }
    }

    public void run() throws IOException, RocksDBException {
        store.checkKeyFormat(true);
        List<Path> files = listPgnFiles();
        Map<String, FileProgress> progress = store.getAllProgress();
        System.out.printf("%d PGN files found, %d already fully indexed%n", files.size(),
                progress.values().stream().filter(p -> p.status() == FileProgress.Status.DONE).count());

        long start = System.nanoTime();
        for (Path file : files) {
            if (stopRequested) {
                break;
            }
            String name = file.getFileName().toString();
            FileProgress fileProgress = progress.get(name);
            long size = Files.size(file);
            long lastModified = Files.getLastModifiedTime(file).toMillis();
            if (fileProgress != null && fileProgress.status() == FileProgress.Status.DONE) {
                if (fileProgress.fileSize() != size) {
                    System.out.printf("WARNING: %s has changed since it was indexed; it is NOT re-indexed. "
                            + "Delete the index folder to rebuild from scratch.%n", name);
                }
                continue;
            }
            indexFile(file, name, fileProgress, size, lastModified);
        }
        store.flushToDisk();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s. Games indexed in this run: %,d, skipped (result \"*\"): %,d, skipped (bad SAN): %,d. "
                        + "Time: %.0f s. Positions in the index: ~%,d%n",
                stopRequested ? "Stopped" : "Finished", totalIndexedGames, totalUnknownResult, totalBadSan, seconds,
                store.estimatePositions());
    }

    private void indexFile(Path file, String name, FileProgress previous, long size, long lastModified)
            throws IOException, RocksDBException {
        long consumed = 0;
        long fileStart = System.nanoTime();
        long indexed = 0;
        long newPositions = 0;
        try (PgnGameReader reader = new PgnGameReader(file)) {
            if (previous != null) {
                if (previous.fileSize() != size) {
                    System.out.printf("WARNING: %s has changed since indexing started; resuming anyway.%n", name);
                }
                System.out.printf("%s: resuming after %,d games%n", name, previous.gamesConsumed());
                consumed = reader.skip(previous.gamesConsumed());
            } else {
                System.out.printf("%s (%,d MB)%n", name, size >> 20);
            }

            boolean eof = false;
            while (!eof && !stopRequested) {
                List<PgnGameReader.RawGame> batch = new ArrayList<>(GAME_BATCH);
                PgnGameReader.RawGame game;
                while (batch.size() < GAME_BATCH && (game = reader.next()) != null) {
                    batch.add(game);
                }
                eof = batch.size() < GAME_BATCH;
                indexed += addToBuffer(batch, name, consumed);
                consumed += batch.size();

                if (eof || stopRequested || buffer.size() >= flushPositions) {
                    FileProgress.Status status = eof ? FileProgress.Status.DONE : FileProgress.Status.IN_PROGRESS;
                    int buffered = buffer.size();
                    newPositions += store.merge(buffer, name, new FileProgress(status, consumed, size, lastModified));
                    buffer = new HashMap<>();
                    double seconds = (System.nanoTime() - fileStart) / 1e9;
                    System.out.printf("  %s: %,d games read, %,d positions merged (%,d new in total), %.0f games/s%n",
                            status == FileProgress.Status.DONE ? "done" : "checkpoint", consumed, buffered,
                            newPositions, indexed / Math.max(seconds, 0.001));
                }
            }
        }
        totalIndexedGames += indexed;
    }

    /** Replays the games in parallel and adds them to the in-memory buffer. @return number of indexed games */
    private long addToBuffer(List<PgnGameReader.RawGame> games, String fileName, long firstGameIndex) {
        Object[] results = games.parallelStream().map(g -> {
            GameReplayer.Outcome outcome = GameReplayer.parseOutcome(g.result());
            if (outcome == null) {
                return null;
            }
            try {
                return replayer.replay(outcome, g.moveText());
            } catch (GameReplayer.SanException | RuntimeException e) {
                return e;
            }
        }).toArray();

        long indexed = 0;
        for (int i = 0; i < results.length; i++) {
            Object result = results[i];
            if (result == null) {
                totalUnknownResult++;
            } else if (result instanceof Exception e) {
                totalBadSan++;
                if (reportedErrors++ < MAX_REPORTED_ERRORS) {
                    System.out.printf("  skipped game #%d in %s: %s%n", firstGameIndex + i + 1, fileName, e);
                }
            } else {
                GameReplayer.ReplayedGame game = (GameReplayer.ReplayedGame) result;
                for (GameReplayer.Ply ply : game.plies()) {
                    buffer.computeIfAbsent(ply.fen(), k -> new PositionAgg())
                            .add(intern(ply.uci()), intern(ply.san()), game.outcome());
                }
                indexed++;
            }
        }
        return indexed;
    }

    private String intern(String s) {
        String existing = strings.putIfAbsent(s, s);
        return existing != null ? existing : s;
    }

    private List<Path> listPgnFiles() throws IOException {
        if (!Files.isDirectory(pgnDir)) {
            throw new IOException("PGN folder not found: " + pgnDir.toAbsolutePath());
        }
        try (Stream<Path> stream = Files.list(pgnDir)) {
            return stream.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pgn"))
                    .sorted()
                    .toList();
        }
    }

    public void printStatus() throws IOException {
        Map<String, FileProgress> progress = store.getAllProgress();
        List<Path> files = Files.isDirectory(pgnDir) ? listPgnFiles() : List.of();
        long done = 0;
        long games = 0;
        for (Path file : files) {
            String name = file.getFileName().toString();
            FileProgress p = progress.get(name);
            String state = p == null ? "not indexed" : p.status() == FileProgress.Status.DONE ? "done"
                    : "in progress";
            if (p != null) {
                games += p.gamesConsumed();
                if (p.status() == FileProgress.Status.DONE) {
                    done++;
                }
            }
            System.out.printf("%-32s %-12s %,12d games%n", name, state, p == null ? 0 : p.gamesConsumed());
        }
        System.out.printf("%d of %d files done, %,d games read, ~%,d positions in the index%n", done, files.size(),
                games, store.estimatePositions());
    }
}
