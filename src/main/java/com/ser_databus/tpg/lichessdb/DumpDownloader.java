package com.ser_databus.tpg.lichessdb;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Downloads the dumps of the given months in a background thread, in the given order, while the caller indexes the
 * ones already downloaded.
 * <ul>
 *     <li>At most {@value #MAX_FILES} dumps are on disk at a time: the one being indexed and the next one. A slot is
 *     freed by {@link #release} once the caller is done with a dump (and has deleted it).</li>
 *     <li>A dump is written to {@code <name>.part} and renamed when complete, so a half-downloaded file is never
 *     indexed. After a network error, or in a later run, a {@code .part} file is continued with an HTTP Range
 *     request.</li>
 *     <li>A connection that delivers no data for {@link #STALL_TIMEOUT} is dropped and continued.</li>
 * </ul>
 */
public class DumpDownloader implements AutoCloseable {
    static final int MAX_FILES = 2;
    /** Failed attempts in a row (without any new data) before a download is given up. */
    private static final int MAX_FAILURES = 10;
    private static final Duration STALL_TIMEOUT = Duration.ofMinutes(2);
    private static final long REPORT_EVERY_BYTES = 1L << 30;
    private static final int COPY_BUFFER = 1 << 20;

    private final HttpClient client;
    private final Path dir;
    private final List<YearMonth> months;
    private final Function<YearMonth, URI> urls;
    private final Map<YearMonth, CompletableFuture<Path>> ready = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_FILES);
    private final Thread thread;
    private final ScheduledExecutorService watchdog;

    private volatile boolean stopped;
    /** Set by the watchdog when it drops a stalled connection. */
    private volatile boolean stalled;
    private volatile long lastProgressNanos;
    /** Response body being read right now, or null. */
    private volatile InputStream currentBody;

    public DumpDownloader(HttpClient client, Path dir, List<YearMonth> months) {
        this(client, dir, months, DumpCatalog::url);
    }

    /** @param urls where each month's dump is downloaded from (tests use a local server) */
    public DumpDownloader(HttpClient client, Path dir, List<YearMonth> months, Function<YearMonth, URI> urls) {
        this.client = client;
        this.dir = dir;
        this.months = List.copyOf(months);
        this.urls = urls;
        for (YearMonth month : this.months) {
            ready.put(month, new CompletableFuture<>());
        }
        thread = new Thread(this::run, "lichess-dump-download");
        thread.setDaemon(true);
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lichess-dump-download-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        watchdog.scheduleAtFixedRate(this::checkStall, 10, 10, TimeUnit.SECONDS);
        thread.start();
    }

    /**
     * Waits until the month's dump is downloaded.
     *
     * @return the downloaded file, or null if the downloader was closed meanwhile
     * @throws IOException if the download failed
     */
    public Path await(YearMonth month) throws IOException, InterruptedException {
        CompletableFuture<Path> future = ready.get(month);
        if (future == null) {
            throw new IllegalArgumentException("Month " + month + " isn't downloaded by this downloader");
        }
        try {
            return future.get();
        } catch (CancellationException e) {
            return null;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new IOException("Download of " + month + " failed", cause);
        }
    }

    /** The caller is done with this month's dump: the next dump may be downloaded. */
    public void release(YearMonth month) {
        slots.release();
    }

    /** Stops downloading. A partial download stays as a {@code .part} file and is continued in the next run. */
    @Override
    public void close() {
        stopped = true;
        thread.interrupt();
        watchdog.shutdownNow();
        for (CompletableFuture<Path> future : ready.values()) {
            future.cancel(false);
        }
    }

    private void run() {
        try {
            for (YearMonth month : months) {
                CompletableFuture<Path> future = ready.get(month);
                acquireSlot();
                if (stopped) {
                    return;
                }
                try {
                    future.complete(download(month));
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    future.completeExceptionally(e);
                    failRemaining(month);
                    return;
                }
            }
        } catch (InterruptedException e) {
            // Closed while waiting for a free slot
        } finally {
            if (stopped) {
                for (CompletableFuture<Path> future : ready.values()) {
                    future.cancel(false);
                }
            }
        }
    }

    /** The months after a failed one aren't downloaded: indexing must go month by month. */
    private void failRemaining(YearMonth failed) {
        for (Map.Entry<YearMonth, CompletableFuture<Path>> e : ready.entrySet()) {
            if (!e.getValue().isDone()) {
                e.getValue().completeExceptionally(
                        new IOException("Not downloaded because the download of " + failed + " failed"));
            }
        }
    }

    private Path download(YearMonth month) throws IOException, InterruptedException {
        String name = DumpCatalog.fileName(month);
        Path target = dir.resolve(name);
        if (Files.isRegularFile(target)) {
            System.out.printf("%s is already downloaded%n", name);
            return target;
        }
        Files.createDirectories(dir);
        Path part = dir.resolve(name + ".part");
        URI uri = urls.apply(month);
        int failures = 0;
        while (true) {
            if (stopped) {
                throw new InterruptedException("stopped");
            }
            long before = sizeOf(part);
            try {
                if (fetch(uri, part, before, name)) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.printf("Downloaded %s (%,d MB)%n", name, Files.size(target) >> 20);
                    return target;
                }
                // The .part file was invalid and is deleted: start again right away
            } catch (FileNotFoundException e) {
                throw e;
            } catch (IOException | InterruptedException e) {
                // close() sets "stopped" before it interrupts; any other interrupt comes from the watchdog
                if (stopped) {
                    throw new InterruptedException("stopped");
                }
                boolean wasStall = stalled;
                stalled = false;
                Thread.interrupted();
                failures = sizeOf(part) > before ? 1 : failures + 1;
                if (failures > MAX_FAILURES) {
                    throw new IOException("Download of " + name + " failed " + MAX_FAILURES + " times in a row", e);
                }
                long waitSeconds = Math.min(300, 5L << Math.min(failures - 1, 6));
                System.out.printf("  download of %s interrupted (%s), retrying in %d s%n", name,
                        wasStall ? "no data for " + STALL_TIMEOUT.toMinutes() + " min" : e.toString(), waitSeconds);
                sleepUnlessStopped(waitSeconds * 1000);
            }
        }
    }

    /** Waits for a free slot; only {@link #close()} ends the wait early (watchdog interrupts are ignored). */
    private void acquireSlot() throws InterruptedException {
        while (true) {
            try {
                slots.acquire();
                return;
            } catch (InterruptedException e) {
                if (stopped) {
                    throw e;
                }
            }
        }
    }

    /** Sleeps; only {@link #close()} ends the sleep early (watchdog interrupts are ignored). */
    private void sleepUnlessStopped(long millis) throws InterruptedException {
        long end = System.currentTimeMillis() + millis;
        while (!stopped) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(left);
            } catch (InterruptedException e) {
                if (stopped) {
                    throw e;
                }
            }
        }
        throw new InterruptedException("stopped");
    }

    /**
     * Downloads (the rest of) a dump into the .part file.
     *
     * @return true if the .part file is now complete, false if it was invalid and has been deleted
     */
    private boolean fetch(URI uri, Path part, long have, String name) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("User-Agent", DumpCatalog.USER_AGENT)
                // Time limit for the response headers; a stalled body is handled by the watchdog
                .timeout(STALL_TIMEOUT)
                .GET();
        if (have > 0) {
            builder.header("Range", "bytes=" + have + "-");
        }
        HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        try (InputStream body = response.body()) {
            if (status == 404) {
                throw new FileNotFoundException("Not found: " + uri);
            }
            if (status == 416) {
                // Nothing left after "have" bytes: the .part file is complete, unless it's larger than the dump
                long total = DumpCatalog.head(client, uri).headers().firstValueAsLong("Content-Length").orElse(-1);
                if (total == have) {
                    return true;
                }
                System.out.printf("  %s doesn't match the dump on the server, downloading again%n", part.getFileName());
                Files.deleteIfExists(part);
                return false;
            }
            if (status != 200 && status != 206) {
                throw new IOException("HTTP " + status + " for " + uri);
            }
            // 200 after a Range request: the server sends the whole file, so start from scratch
            boolean append = status == 206;
            long total = append ? totalFromContentRange(response)
                    : response.headers().firstValueAsLong("Content-Length").orElse(-1);
            long done = append ? have : 0;
            if (done == 0) {
                System.out.printf("Downloading %s (%s)%n", name, gb(total));
            } else {
                System.out.printf("Continuing the download of %s at %s of %s%n", name, gb(done), gb(total));
            }

            OpenOption[] options = append
                    ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
                    : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING};
            long start = System.nanoTime();
            long doneAtStart = done;
            long nextReport = (done / REPORT_EVERY_BYTES + 1) * REPORT_EVERY_BYTES;
            lastProgressNanos = System.nanoTime();
            currentBody = body;
            try (OutputStream out = Files.newOutputStream(part, options)) {
                byte[] buffer = new byte[COPY_BUFFER];
                int n;
                while ((n = body.read(buffer)) >= 0) {
                    if (stopped) {
                        throw new InterruptedException("stopped");
                    }
                    out.write(buffer, 0, n);
                    done += n;
                    lastProgressNanos = System.nanoTime();
                    if (done >= nextReport) {
                        nextReport += REPORT_EVERY_BYTES;
                        double seconds = Math.max((System.nanoTime() - start) / 1e9, 0.001);
                        System.out.printf("  download of %s: %s of %s, %.1f MB/s%n", name, gb(done), gb(total),
                                (done - doneAtStart) / seconds / (1 << 20));
                    }
                }
            } finally {
                currentBody = null;
            }
            if (total >= 0 && done < total) {
                throw new IOException("the connection was closed after " + done + " of " + total + " bytes");
            }
            return true;
        }
    }

    /** Drops a connection that delivers no data, so the download continues with a new one. */
    private void checkStall() {
        InputStream body = currentBody;
        if (body != null && !stopped && System.nanoTime() - lastProgressNanos > STALL_TIMEOUT.toNanos()) {
            stalled = true;
            try {
                body.close();
            } catch (IOException | RuntimeException ignored) {
                // The read is interrupted below anyway
            }
            thread.interrupt();
        }
    }

    /** "bytes 100-999/1000" -> 1000; -1 if unknown. */
    private static long totalFromContentRange(HttpResponse<?> response) {
        String range = response.headers().firstValue("Content-Range").orElse("");
        int slash = range.lastIndexOf('/');
        if (slash < 0) {
            return -1;
        }
        try {
            return Long.parseLong(range.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static long sizeOf(Path file) throws IOException {
        return Files.exists(file) ? Files.size(file) : 0;
    }

    private static String gb(long bytes) {
        return bytes < 0 ? "? GB" : String.format("%.1f GB", bytes / (double) (1L << 30));
    }
}
