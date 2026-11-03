package com.ser_databus.tpg.lichessdb;

import com.github.luben.zstd.ZstdInputStream;
import com.ser_databus.tpg.elite.FileProgress;
import com.ser_databus.tpg.elite.GameReplayer;
import com.ser_databus.tpg.elite.PgnGameReader;
import org.rocksdb.RocksDBException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Console program that builds a rating-banded index of the Lichess standard rated games
 * (<a href="https://database.lichess.org/">database.lichess.org</a>) in RocksDB, the way the Lichess opening explorer
 * counts them:
 * <ul>
 *     <li>only blitz, rapid and classical games, no bots, both ratings and the result known, and only the rating
 *     buckets 2000, 2200 and 2500; no abandoned games or rules infractions, no rating gap over 300, at least 10
 *     plies ({@link GameFilter});</li>
 *     <li>the first {@value BandedIndexStore#MAX_PLIES} plies of each game, each position counted once per game
 *     ({@link GameReplayer#replayUnique});</li>
 *     <li>per position and rating bucket of the game ({@link RatingBuckets}): white wins / draws / black wins of the
 *     games that reached it, and how often each move was played from it ({@link BandedPositionAgg}).</li>
 * </ul>
 * The monthly dumps are processed <b>from the most recent month to the oldest</b>. They are downloaded one by one;
 * the next (older) dump is downloaded while the current one is indexed, so at most two dumps are on disk. A dump is
 * deleted once it is fully indexed.
 * <p>
 * Like {@link com.ser_databus.tpg.elite.EliteIndexBuilder}, the program can be stopped (Ctrl+C) and run again at any
 * time: statistics and progress are written in one atomic batch, finished months are skipped, an interrupted month is
 * resumed from its last committed game, and an interrupted download is continued.
 * <p>
 * Arguments (all optional):
 * <pre>
 *   --from=YYYY-MM      newest month to index; default: the latest published month
 *   --to=YYYY-MM        oldest month to index; default: the first dump (2013-01)
 *   --db-dir=PATH       default: data/lichessIndex
 *   --download-dir=PATH default: raw/lichess
 *   --flush=N           positions kept in memory before they're merged into the index, default 1000000
 *   --keep-dumps        don't delete the dumps after indexing
 *   --local-file=PATH   index this local .pgn or .pgn.zst file instead of downloading (can be repeated)
 *   --status            only print what has been indexed
 * </pre>
 */
public class LichessIndexBuilder {
    private static final String DEFAULT_DB_DIR = "data/lichessIndex";
    private static final String DEFAULT_DOWNLOAD_DIR = "raw/lichess";
    private static final int DEFAULT_FLUSH_POSITIONS = 1_000_000;
    /** Games replayed in parallel at once. */
    private static final int GAME_BATCH = 4_000;
    private static final int MAX_REPORTED_ERRORS = 20;

    /** A game that passed the filter and was replayed. */
    private record Accepted(int bucket, GameReplayer.UniqueGame game) {
    }

    /** Game counters of one file or of the whole run. */
    private static final class Counts {
        long read;
        long indexed;
        long badSan;
        final long[] skipped = new long[GameFilter.SkipReason.values().length];

        void addAll(Counts other) {
            read += other.read;
            indexed += other.indexed;
            badSan += other.badSan;
            for (int i = 0; i < skipped.length; i++) {
                skipped[i] += other.skipped[i];
            }
        }

        String describeSkipped() {
            StringBuilder sb = new StringBuilder();
            for (GameFilter.SkipReason reason : GameFilter.SkipReason.values()) {
                sb.append(String.format("%s %,d, ", reason.description(), skipped[reason.ordinal()]));
            }
            sb.append(String.format("bad SAN %,d", badSan));
            return sb.toString();
        }
    }

    private final BandedIndexStore store;
    private final int flushPositions;
    private final GameReplayer replayer = new GameReplayer(BandedIndexStore.MAX_PLIES);

    /** Interned UCI/SAN strings: there are only a few thousand distinct ones, but millions of references. */
    private final Map<String, String> strings = new HashMap<>();
    private Map<String, BandedPositionAgg> buffer = new HashMap<>();
    private final Counts total = new Counts();
    private int reportedErrors;

    private volatile boolean stopRequested;
    private volatile DumpDownloader downloader;

    public LichessIndexBuilder(BandedIndexStore store, int flushPositions) {
        this.store = store;
        this.flushPositions = flushPositions;
    }

    public static void main(String[] args) throws Exception {
        YearMonth from = null;
        YearMonth to = null;
        Path dbDir = Paths.get(DEFAULT_DB_DIR);
        Path downloadDir = Paths.get(DEFAULT_DOWNLOAD_DIR);
        int flushPositions = DEFAULT_FLUSH_POSITIONS;
        boolean keepDumps = false;
        boolean statusOnly = false;
        List<Path> localFiles = new ArrayList<>();
        try {
            for (String arg : args) {
                if (arg.equals("--status")) {
                    statusOnly = true;
                } else if (arg.equals("--keep-dumps")) {
                    keepDumps = true;
                } else if (arg.startsWith("--from=")) {
                    from = YearMonth.parse(arg.substring("--from=".length()));
                } else if (arg.startsWith("--to=")) {
                    to = YearMonth.parse(arg.substring("--to=".length()));
                } else if (arg.startsWith("--db-dir=")) {
                    dbDir = Paths.get(arg.substring("--db-dir=".length()));
                } else if (arg.startsWith("--download-dir=")) {
                    downloadDir = Paths.get(arg.substring("--download-dir=".length()));
                } else if (arg.startsWith("--flush=")) {
                    flushPositions = Integer.parseInt(arg.substring("--flush=".length()));
                } else if (arg.startsWith("--local-file=")) {
                    localFiles.add(Paths.get(arg.substring("--local-file=".length())));
                } else {
                    System.err.println("Unknown argument: " + arg);
                    System.exit(1);
                }
            }
        } catch (DateTimeParseException | NumberFormatException e) {
            System.err.println("Bad argument: " + e.getMessage() + " (months are written as YYYY-MM)");
            System.exit(1);
        }

        System.out.println("Index: " + dbDir.toAbsolutePath());
        if (statusOnly) {
            try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
                new LichessIndexBuilder(store, flushPositions).printStatus();
            }
            return;
        }

        CountDownLatch closed = new CountDownLatch(1);
        LichessIndexBuilder[] builder = new LichessIndexBuilder[1];
        Thread hook = new Thread(() -> {
            if (builder[0] != null) {
                builder[0].requestStop();
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
        try (BandedIndexStore store = new BandedIndexStore(dbDir)) {
            builder[0] = new LichessIndexBuilder(store, flushPositions);
            if (localFiles.isEmpty()) {
                System.out.println("Downloads: " + downloadDir.toAbsolutePath());
                builder[0].runDownloads(from, to, downloadDir, keepDumps);
            } else {
                builder[0].runLocalFiles(localFiles);
            }
        } finally {
            closed.countDown();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ignored) {
            // Shutdown is already in progress
        }
    }

    /** Stops after the current batch is committed (and stops the downloads). */
    public void requestStop() {
        stopRequested = true;
        DumpDownloader d = downloader;
        if (d != null) {
            d.close();
        }
    }

    /** Downloads and indexes the months from {@code from} (null: the latest published) down to {@code to}. */
    public void runDownloads(YearMonth from, YearMonth to, Path downloadDir, boolean keepDumps)
            throws IOException, RocksDBException, InterruptedException {
        store.checkFormat(true);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        if (from == null) {
            from = DumpCatalog.findLatestPublished(client);
            System.out.println("Latest published dump: " + from);
        }
        List<YearMonth> months = DumpCatalog.monthsNewestFirst(from, to);
        Map<String, FileProgress> progress = store.getAllProgress();
        List<YearMonth> pending = new ArrayList<>();
        for (YearMonth month : months) {
            FileProgress p = progress.get(DumpCatalog.fileName(month));
            if (p == null || p.status() != FileProgress.Status.DONE) {
                pending.add(month);
            }
        }
        System.out.printf("Months %s to %s: %d, already indexed %d, to do %d (newest first)%n",
                months.get(0), months.get(months.size() - 1), months.size(), months.size() - pending.size(),
                pending.size());
        if (pending.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        DumpDownloader d = new DumpDownloader(client, downloadDir, pending);
        downloader = d;
        if (stopRequested) {
            d.close();
        }
        d.start();
        try {
            for (YearMonth month : pending) {
                if (stopRequested) {
                    break;
                }
                Path file;
                try {
                    file = d.await(month);
                } catch (IOException e) {
                    System.out.printf("The download of %s failed: %s. Run the program again to retry.%n", month, e);
                    break;
                }
                if (file == null || stopRequested) {
                    break;
                }
                String name = DumpCatalog.fileName(month);
                boolean done = indexFile(file, name, progress.get(name));
                if (!done) {
                    break;
                }
                if (!keepDumps) {
                    Files.deleteIfExists(file);
                }
                d.release(month);
            }
        } finally {
            d.close();
            downloader = null;
        }
        finish(start);
    }

    /** Indexes local dump files (.pgn or .pgn.zst), in the given order; files already DONE are skipped. */
    public void runLocalFiles(List<Path> files) throws IOException, RocksDBException {
        store.checkFormat(true);
        Map<String, FileProgress> progress = store.getAllProgress();
        long start = System.nanoTime();
        for (Path file : files) {
            if (stopRequested) {
                break;
            }
            String name = file.getFileName().toString();
            FileProgress p = progress.get(name);
            if (p != null && p.status() == FileProgress.Status.DONE) {
                System.out.printf("%s is already indexed%n", name);
                continue;
            }
            if (!indexFile(file, name, p)) {
                break;
            }
        }
        finish(start);
    }

    private void finish(long start) throws RocksDBException {
        store.flushToDisk();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s. Games in this run: %,d read, %,d indexed. Skipped: %s. Time: %.0f s. "
                        + "Positions in the index: ~%,d%n",
                stopRequested ? "Stopped" : "Finished", total.read, total.indexed, total.describeSkipped(), seconds,
                store.estimatePositions());
    }

    /**
     * Indexes one dump, resuming after {@code previous.gamesConsumed()} games if it was interrupted before.
     *
     * @return true if the whole file is indexed, false if stopped
     */
    boolean indexFile(Path file, String name, FileProgress previous) throws IOException, RocksDBException {
        long size = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        long consumed = 0;
        long fileStart = System.nanoTime();
        long newPositions = 0;
        Counts counts = new Counts();
        boolean eof = false;
        try (PgnGameReader reader = new PgnGameReader(openDump(file))) {
            if (previous != null && previous.gamesConsumed() > 0) {
                System.out.printf("%s: resuming after %,d games%n", name, previous.gamesConsumed());
                consumed = reader.skip(previous.gamesConsumed());
            } else {
                System.out.printf("%s (%,d MB)%n", name, size >> 20);
            }

            while (!eof && !stopRequested) {
                List<PgnGameReader.TaggedGame> batch = new ArrayList<>(GAME_BATCH);
                PgnGameReader.TaggedGame game;
                while (batch.size() < GAME_BATCH && (game = reader.nextTagged()) != null) {
                    batch.add(game);
                }
                eof = batch.size() < GAME_BATCH;
                addToBuffer(batch, name, consumed, counts);
                consumed += batch.size();

                if (eof || stopRequested || buffer.size() >= flushPositions) {
                    FileProgress.Status status = eof ? FileProgress.Status.DONE : FileProgress.Status.IN_PROGRESS;
                    int buffered = buffer.size();
                    newPositions += store.merge(buffer, name, new FileProgress(status, consumed, size, lastModified));
                    buffer = new HashMap<>();
                    double seconds = Math.max((System.nanoTime() - fileStart) / 1e9, 0.001);
                    System.out.printf("  %s: %,d games read, %,d indexed, %,d positions merged (%,d new in total), "
                                    + "%,.0f games/s%n",
                            status == FileProgress.Status.DONE ? "done" : "checkpoint", consumed, counts.indexed,
                            buffered, newPositions, counts.read / seconds);
                }
            }
        }
        if (eof) {
            System.out.printf("  %s: %,d games indexed. Skipped: %s%n", name, counts.indexed, counts.describeSkipped());
        }
        total.addAll(counts);
        return eof;
    }

    /** Filters and replays the games in parallel and adds them to the in-memory buffer. */
    private void addToBuffer(List<PgnGameReader.TaggedGame> games, String fileName, long firstGameIndex,
                             Counts counts) {
        Object[] results = games.parallelStream().map(g -> {
            GameFilter.Verdict verdict = GameFilter.check(g);
            if (!verdict.accepted()) {
                return verdict.skip();
            }
            try {
                GameReplayer.UniqueGame replayed = replayer.replayUnique(verdict.outcome(), g.moveText());
                if (!GameFilter.isLongEnough(replayed.plies())) {
                    return GameFilter.SkipReason.SHORT_GAME;
                }
                return new Accepted(verdict.bucket().ordinal(), replayed);
            } catch (GameReplayer.SanException | RuntimeException e) {
                return e;
            }
        }).toArray();

        counts.read += results.length;
        for (int i = 0; i < results.length; i++) {
            Object result = results[i];
            if (result instanceof GameFilter.SkipReason reason) {
                counts.skipped[reason.ordinal()]++;
            } else if (result instanceof Exception e) {
                counts.badSan++;
                if (reportedErrors++ < MAX_REPORTED_ERRORS) {
                    System.out.printf("  skipped game #%,d in %s: %s%n", firstGameIndex + i + 1, fileName, e);
                }
            } else {
                Accepted accepted = (Accepted) result;
                int bucket = accepted.bucket();
                GameReplayer.Outcome outcome = accepted.game().outcome();
                for (GameReplayer.UniquePly ply : accepted.game().positions()) {
                    BandedPositionAgg agg = buffer.computeIfAbsent(ply.fen(), k -> new BandedPositionAgg());
                    agg.addReached(bucket, outcome);
                    if (ply.uci() != null) {
                        agg.addMove(intern(ply.uci()), intern(ply.san()), bucket);
                    }
                }
                counts.indexed++;
            }
        }
    }

    private String intern(String s) {
        String existing = strings.putIfAbsent(s, s);
        return existing != null ? existing : s;
    }

    /** A .zst dump is decompressed on the fly (detected by the zstd magic number), anything else is read as is. */
    static InputStream openDump(Path file) throws IOException {
        InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20);
        try {
            in.mark(4);
            byte[] magic = in.readNBytes(4);
            in.reset();
            if (magic.length == 4) {
                int m = (magic[0] & 0xFF) | (magic[1] & 0xFF) << 8 | (magic[2] & 0xFF) << 16 | (magic[3] & 0xFF) << 24;
                // a zstd frame, or a skippable frame (0x184D2A50..5F)
                if (m == 0xFD2FB528 || (m & 0xFFFFFFF0) == 0x184D2A50) {
                    return new BufferedInputStream(new ZstdInputStream(in), 1 << 20);
                }
            }
            return in;
        } catch (IOException | RuntimeException e) {
            in.close();
            throw e;
        }
    }

    public void printStatus() {
        Map<String, FileProgress> progress = store.getAllProgress();
        if (progress.isEmpty()) {
            System.out.println("Nothing is indexed yet");
            return;
        }
        long done = 0;
        long games = 0;
        List<String> names = new ArrayList<>(progress.keySet());
        // Dump names contain the month, so this lists the newest month first
        names.sort(java.util.Comparator.reverseOrder());
        for (String name : names) {
            FileProgress p = progress.get(name);
            games += p.gamesConsumed();
            if (p.status() == FileProgress.Status.DONE) {
                done++;
            }
            System.out.printf("%-48s %-12s %,14d games read%n", name,
                    p.status() == FileProgress.Status.DONE ? "done" : "in progress", p.gamesConsumed());
        }
        System.out.printf("%d files done, %d in progress, %,d games read, ~%,d positions in the index%n", done,
                progress.size() - done, games, store.estimatePositions());
    }
}
