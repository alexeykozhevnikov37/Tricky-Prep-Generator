package com.ser_databus.tpg.evaldb;

import com.fasterxml.jackson.core.JsonFactory;
import com.github.luben.zstd.ZstdInputStream;
import com.ser_databus.tpg.elite.PositionKey;
import org.rocksdb.RocksDBException;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Console program that loads the Lichess evaluation dump ({@code lichess_db_eval.jsonl.zst} from
 * database.lichess.org) into a RocksDB index for {@link LocalCloudEvalClient}.
 * <p>
 * The whole dump (400M+ positions) would take 50+ GB even in the compact binary format, so by default only positions
 * with at least {@value #DEFAULT_MIN_PIECES} pieces are kept and every line is cut to {@value #DEFAULT_LINE_MOVES}
 * moves (scores, depths and all MultiPV lines are always kept). That's roughly 25 GB.
 * <p>
 * The build can be stopped (Ctrl+C) and resumed at any time: positions and progress are written in one atomic batch.
 * It also works on a dump that is still downloading: it indexes what has arrived so far and continues from there on
 * the next run (the file may be renamed in between, e.g. from Chrome's .crdownload name to the final one).
 * The dump can be the .zst file or an already decompressed .jsonl file.
 * <p>
 * Arguments (all optional):
 * <pre>
 *   --status            only print what has been indexed
 *   --dump=PATH         default: raw/eval/lichess_db_eval.jsonl.zst
 *   --db-dir=PATH       default: data/evalIndex
 *   --line-moves=N      moves kept per line, 0 = whole line; default 4. Fixed when the index is created.
 *   --min-pieces=N      skip positions with fewer pieces, 0 = keep all; default 16. Fixed when the index is created.
 * </pre>
 */
public class CloudEvalIndexBuilder {
    public static final String DEFAULT_DUMP = "raw/eval/lichess_db_eval.jsonl.zst";
    public static final int DEFAULT_LINE_MOVES = 4;
    public static final int DEFAULT_MIN_PIECES = 16;

    private static final int BATCH_LINES = 50_000;
    private static final long REPORT_EVERY_LINES = 2_000_000;
    private static final int MAX_REPORTED_ERRORS = 20;

    private enum Skip { NO_EVALS, FILTERED }

    private record Parsed(String key, CloudEvalPosition position, byte[] value) {
        boolean needsMerge() {
            return value == null;
        }
    }

    private final Path dump;
    private final CloudEvalStore store;
    private final CloudEvalStore.BuildSettings settings;
    private final JsonFactory jsonFactory = new JsonFactory();

    private volatile boolean stopRequested;

    private long badLines;
    private long filtered;
    private long noEvals;
    private int reportedErrors;

    public CloudEvalIndexBuilder(Path dump, CloudEvalStore store, CloudEvalStore.BuildSettings settings) {
        this.dump = dump;
        this.store = store;
        this.settings = settings;
    }

    public static void main(String[] args) throws Exception {
        Path dump = Paths.get(DEFAULT_DUMP);
        Path dbDir = Paths.get(LocalCloudEvalClient.DEFAULT_INDEX_DIR);
        int lineMoves = DEFAULT_LINE_MOVES;
        int minPieces = DEFAULT_MIN_PIECES;
        boolean statusOnly = false;
        for (String arg : args) {
            if (arg.equals("--status")) {
                statusOnly = true;
            } else if (arg.startsWith("--dump=")) {
                dump = Paths.get(arg.substring("--dump=".length()));
            } else if (arg.startsWith("--db-dir=")) {
                dbDir = Paths.get(arg.substring("--db-dir=".length()));
            } else if (arg.startsWith("--line-moves=")) {
                lineMoves = Integer.parseInt(arg.substring("--line-moves=".length()));
            } else if (arg.startsWith("--min-pieces=")) {
                minPieces = Integer.parseInt(arg.substring("--min-pieces=".length()));
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(1);
            }
        }

        System.out.println("Dump:  " + dump.toAbsolutePath());
        System.out.println("Index: " + dbDir.toAbsolutePath());
        if (statusOnly) {
            try (CloudEvalStore store = new CloudEvalStore(dbDir, true)) {
                printStatus(store);
            }
            return;
        }

        CloudEvalStore.BuildSettings settings = new CloudEvalStore.BuildSettings(lineMoves, minPieces);
        CountDownLatch closed = new CountDownLatch(1);
        CloudEvalIndexBuilder[] builder = new CloudEvalIndexBuilder[1];
        Thread hook = new Thread(() -> {
            if (builder[0] != null) {
                builder[0].stopRequested = true;
            }
            System.out.println("Stop requested: committing the current batch...");
            try {
                // The JVM exits when the hook returns, so wait until the index is committed and closed
                closed.await(5, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
                // Exit anyway: the index stays consistent, only uncommitted lines will be read again
            }
        });
        Runtime.getRuntime().addShutdownHook(hook);
        try (CloudEvalStore store = new CloudEvalStore(dbDir, false)) {
            builder[0] = new CloudEvalIndexBuilder(dump, store, settings);
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
        if (!Files.isRegularFile(dump)) {
            throw new IOException("Dump not found: " + dump.toAbsolutePath());
        }
        store.initOrCheck(settings);
        System.out.println("Keeping " + settings.describe());

        String fileName = dump.getFileName().toString();
        long size = Files.size(dump);
        CloudEvalProgress previous = store.getProgress();
        if (previous != null) {
            if (previous.status() == CloudEvalProgress.Status.DONE) {
                System.out.printf("The whole dump is already indexed (%,d lines, ~%,d positions).%n",
                        previous.linesConsumed(), store.estimatePositions());
                if (previous.fileSize() != size) {
                    System.out.println("WARNING: the dump file has changed since. To load a newer dump, delete the "
                            + "index folder and run again.");
                }
                return;
            }
            if (!previous.fileName().equals(fileName)) {
                System.out.printf("Note: the index was started from \"%s\"; continuing with \"%s\"%n",
                        previous.fileName(), fileName);
            }
            if (size < previous.fileSize()) {
                System.out.println("WARNING: the dump is smaller than when indexing started. Is it the same file?");
            }
        }
        long consumed = previous == null ? 0 : previous.linesConsumed();
        long records = previous == null ? 0 : previous.recordsWritten();

        long start = System.nanoTime();
        long linesThisRun = 0;
        long nextReport = consumed + REPORT_EVERY_LINES;
        boolean eof = false;
        String truncation = null;

        InputStream file = new BufferedInputStream(Files.newInputStream(dump), 1 << 20);
        CountingInputStream counting = new CountingInputStream(file);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(decompressIfNeeded(file, counting), StandardCharsets.UTF_8), 1 << 20)) {
            if (consumed > 0) {
                System.out.printf("Skipping the %,d lines indexed before...%n", consumed);
                for (long i = 0; i < consumed; i++) {
                    if (reader.readLine() == null) {
                        throw new IOException("The dump has fewer lines than are already indexed. "
                                + "Delete the index folder to rebuild it from this dump.");
                    }
                }
            }
            long bytesAtStart = counting.count;
            System.out.printf("Indexing from line %,d...%n", consumed + 1);

            while (!eof && !stopRequested) {
                List<String> lines = new ArrayList<>(BATCH_LINES);
                try {
                    String line;
                    while (lines.size() < BATCH_LINES && (line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                    eof = lines.size() < BATCH_LINES;
                } catch (IOException e) {
                    // The zstd decoder fails at the end of an incomplete file: the dump is still downloading (or
                    // broken). Everything read before that point is complete and is indexed.
                    eof = true;
                    truncation = e.toString();
                }

                Map<String, byte[]> puts = new HashMap<>();
                Map<String, CloudEvalPosition> merges = new LinkedHashMap<>();
                Exception lastLineError = process(lines, consumed, puts, merges);
                long counted = lines.size();
                if (lastLineError != null) {
                    if (eof) {
                        // A partially downloaded dump can end in the middle of a line: read it again next time
                        counted--;
                        if (truncation == null) {
                            truncation = "the last line is incomplete";
                        }
                    } else {
                        reportBadLine(consumed + lines.size(), lastLineError);
                    }
                }
                if (eof && truncation == null && isDownloadInProgress(fileName)) {
                    truncation = "the file is still being downloaded";
                }
                consumed += counted;
                linesThisRun += counted;
                records += puts.size() + merges.size();
                CloudEvalProgress.Status status = eof && truncation == null
                        ? CloudEvalProgress.Status.DONE : CloudEvalProgress.Status.IN_PROGRESS;
                store.write(puts, merges, new CloudEvalProgress(status, consumed, records, size, fileName));

                if (consumed >= nextReport || eof || stopRequested) {
                    nextReport = consumed + REPORT_EVERY_LINES;
                    double seconds = (System.nanoTime() - start) / 1e9;
                    long bytes = counting.count;
                    String eta = "";
                    if (bytes > bytesAtStart && bytes < size) {
                        double remaining = seconds * (size - bytes) / (bytes - bytesAtStart);
                        eta = String.format(", ETA %d h %02d min", (long) remaining / 3600,
                                (long) remaining / 60 % 60);
                    }
                    System.out.printf("  %,d lines (%.1f%% of the file), %,d positions stored, %,.0f lines/s%s%n",
                            consumed, 100.0 * bytes / Math.max(size, 1), records,
                            linesThisRun / Math.max(seconds, 0.001), eta);
                }
            }
        }
        store.flushToDisk();
        double seconds = (System.nanoTime() - start) / 1e9;
        System.out.printf("%s. Lines read in this run: %,d; skipped: %,d filtered out, %,d without evaluations, "
                        + "%,d unreadable. Time: %.0f s. Positions in the index: ~%,d%n",
                stopRequested ? "Stopped" : truncation != null ? "Reached the end of the downloaded part" : "Finished",
                linesThisRun, filtered, noEvals, badLines, seconds, store.estimatePositions());
        if (truncation != null && !stopRequested) {
            System.out.println("The dump ends early (" + truncation + "). If it's still downloading, run the "
                    + "builder again when the download is finished: it will continue from line "
                    + String.format("%,d", consumed + 1) + ".");
        }
    }

    /**
     * Parses the lines in parallel and sorts them into plain puts and merges.
     *
     * @return the error of the last line if it couldn't be parsed (not counted or reported yet: at the end of a
     * partially downloaded dump it's just incomplete), otherwise null
     */
    private Exception process(List<String> lines, long firstLineIndex, Map<String, byte[]> puts,
                              Map<String, CloudEvalPosition> merges) {
        Object[] results = lines.parallelStream().map(this::parseLine).toArray();
        for (int i = 0; i < results.length; i++) {
            Object result = results[i];
            if (result instanceof Parsed parsed) {
                if (parsed.needsMerge()) {
                    merges.merge(parsed.key(), parsed.position(), CloudEvalPosition::plus);
                } else {
                    puts.put(parsed.key(), parsed.value());
                }
            } else if (result == Skip.FILTERED) {
                filtered++;
            } else if (result == Skip.NO_EVALS) {
                noEvals++;
            } else if (i < results.length - 1) {
                reportBadLine(firstLineIndex + i + 1, (Exception) result);
            } else {
                return (Exception) result;
            }
        }
        return null;
    }

    private void reportBadLine(long lineNumber, Exception error) {
        badLines++;
        if (reportedErrors++ < MAX_REPORTED_ERRORS) {
            System.out.printf("  skipped line %,d: %s%n", lineNumber, error);
        }
    }

    /** Browsers keep unfinished downloads under these extensions; such a file is never marked as fully indexed. */
    private static boolean isDownloadInProgress(String fileName) {
        String name = fileName.toLowerCase();
        return name.endsWith(".crdownload") || name.endsWith(".part") || name.endsWith(".partial")
                || name.endsWith(".download");
    }

    private Object parseLine(String line) {
        try {
            CloudEvalPosition position = CloudEvalJson.parse(jsonFactory, line, settings.lineMoves());
            if (position == null) {
                return Skip.NO_EVALS;
            }
            if (countPieces(position.key()) < settings.minPieces()) {
                return Skip.FILTERED;
            }
            String key = toKey(position.key());
            if (key.equals(position.key())) {
                return new Parsed(key, position, CloudEvalCodec.encode(position));
            }
            // The en passant square was dropped: another line of the dump may have the same position
            return new Parsed(key, new CloudEvalPosition(key, position.evals()), null);
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * Dump FENs already have the 4 key fields. Only an en passant square needs the full {@link PositionKey}
     * normalization (it's dropped when no en passant capture is legal); that's rare, so most lines skip loading a
     * board.
     */
    private static String toKey(String fen) {
        int spaces = 0;
        for (int i = 0; i < fen.length(); i++) {
            if (fen.charAt(i) == ' ') {
                spaces++;
            }
        }
        if (spaces == 3 && fen.endsWith(" -")) {
            return fen;
        }
        return PositionKey.of(fen);
    }

    private static int countPieces(String fen) {
        int pieces = 0;
        for (int i = 0; i < fen.length(); i++) {
            char c = fen.charAt(i);
            if (c == ' ') {
                break;
            }
            if (Character.isLetter(c)) {
                pieces++;
            }
        }
        return pieces;
    }

    /** zstd frames and skippable frames (the dump starts with one) are detected by their magic numbers. */
    private static InputStream decompressIfNeeded(InputStream file, CountingInputStream counting) throws IOException {
        file.mark(4);
        byte[] magic = file.readNBytes(4);
        file.reset();
        if (magic.length == 4) {
            int m = (magic[0] & 0xFF) | (magic[1] & 0xFF) << 8 | (magic[2] & 0xFF) << 16 | (magic[3] & 0xFF) << 24;
            if (m == 0xFD2FB528 || (m & 0xFFFFFFF0) == 0x184D2A50) {
                return new ZstdInputStream(counting);
            }
        }
        return counting;
    }

    private static void printStatus(CloudEvalStore store) throws RocksDBException {
        CloudEvalStore.BuildSettings settings = store.getBuildSettings();
        CloudEvalProgress progress = store.getProgress();
        if (settings == null || progress == null) {
            System.out.println("Nothing is indexed yet");
            return;
        }
        System.out.println("Settings: " + settings.describe());
        System.out.printf("%s: %,d lines of %s read, %,d positions written (~%,d in the index)%n",
                progress.status() == CloudEvalProgress.Status.DONE ? "Done" : "In progress",
                progress.linesConsumed(), progress.fileName(), progress.recordsWritten(), store.estimatePositions());
    }

    /** Counts compressed bytes read from the file, for the progress percentage. */
    private static final class CountingInputStream extends FilterInputStream {
        volatile long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}
