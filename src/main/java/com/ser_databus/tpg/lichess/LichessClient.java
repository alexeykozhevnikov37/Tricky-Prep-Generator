package com.ser_databus.tpg.lichess;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ser_databus.tpg.util.ChessUtil.getCroppedFen;
import static com.ser_databus.tpg.util.Const.MS_IN_NS;

@Slf4j
public class LichessClient implements LichessPositionSource {
    private static final String API_URL = "https://explorer.lichess.ovh/";
    private static final Path TOKEN_FILE = Paths.get("lichess.properties");
    private static final String LICHESS_TOKEN = loadToken();
    private static final int REQUESTS_PER_SECOND = 50;
    /**
     * Number of HTTP requests to Lichess that may run at the same time. Lichess asks API clients to make one request
     * at a time and answers 429 (and a minute of cool down here) otherwise, so raise this with care.
     */
    private static final int PARALLEL_REQUESTS = 1;
    private final LichessCache lichessCache = new LichessCache();

    private final Bucket bucket;
    /** Requests that are being fetched right now, so that the same position isn't requested twice. */
    private final ConcurrentHashMap<String, CompletableFuture<LichessPosition>> inFlight = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    /** Flushes the cache on exit; removed by {@link #close()} so a closed client (and its cache) can be collected */
    private final Thread shutdownHook = new Thread(this::flushCache, "lichess-cache-flush");
    private volatile boolean closed;

    public LichessClient() {
        Bandwidth limit = Bandwidth.classic(1, Refill.greedy(1, Duration.ofMillis(1000 / REQUESTS_PER_SECOND)));
        bucket = Bucket.builder().addLimit(limit).build();
        AtomicInteger threadCounter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(PARALLEL_REQUESTS, r -> {
            Thread t = new Thread(r, "lichess-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /** Reads the token from {@link #TOKEN_FILE}; null (requests go without a token) if it's missing. Never throws. */
    private static String loadToken() {
        if (!Files.exists(TOKEN_FILE)) {
            log.warn("No Lichess token file {}: put token=<your API token> there", TOKEN_FILE.toAbsolutePath());
            return null;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(TOKEN_FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.warn("Couldn't read the Lichess token file {}", TOKEN_FILE.toAbsolutePath(), e);
            return null;
        }
        String token = properties.getProperty("token", "").trim();
        if (token.isEmpty()) {
            log.warn("No token=... in the Lichess token file {}", TOKEN_FILE.toAbsolutePath());
            return null;
        }
        return token;
    }

    /**
     * Appends the new positions to the cache file and stops the request thread; queued requests are dropped.
     * Called when the user switches to another Lichess data source.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdownNow();
        flushCache();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // The JVM is shutting down already: the hook flushes the cache once more, which is harmless
        }
    }

    /**
     * Asynchronously gets the position statistics. A cached position returns an already completed future;
     * identical requests that are in flight share one HTTP call.
     */
    public CompletableFuture<LichessPosition> getPositionAsync(String fen, LichessRatingSelector ratingSelector) {
        String croppedFen = getCroppedFen(fen);
        LichessPosition cached = lichessCache.get(croppedFen, ratingSelector);
        if (cached != null) {
            log.trace("Lichess position {}: loaded from cache", croppedFen);
            return CompletableFuture.completedFuture(cached);
        }

        String key = lichessCache.makeKey(croppedFen, ratingSelector);
        CompletableFuture<LichessPosition> newFuture = new CompletableFuture<>();
        CompletableFuture<LichessPosition> existing = inFlight.putIfAbsent(key, newFuture);
        if (existing != null) {
            return existing;
        }
        // The position may have been cached between the first check and putIfAbsent
        cached = lichessCache.get(croppedFen, ratingSelector);
        if (cached != null) {
            inFlight.remove(key, newFuture);
            newFuture.complete(cached);
            return newFuture;
        }

        try {
            executor.execute(() -> {
                try {
                    newFuture.complete(fetchWithRetries(fen, ratingSelector));
                } catch (Throwable ex) {
                    newFuture.completeExceptionally(ex);
                } finally {
                    inFlight.remove(key, newFuture);
                }
            });
        } catch (RejectedExecutionException ex) {
            inFlight.remove(key, newFuture);
            newFuture.completeExceptionally(ex);
        }
        return newFuture;
    }

    /** Appends all positions fetched since the previous flush to the cache file. */
    public void flushCache() {
        lichessCache.flush();
    }

    /** Synchronous version of {@link #getPositionAsync}. */
    public LichessPosition getPosition(String fen, LichessRatingSelector ratingSelector) {
        try {
            return getPositionAsync(fen, ratingSelector).join();
        } catch (CompletionException ex) {
            throw ex.getCause() instanceof RuntimeException re ? re : new IllegalStateException(ex.getCause());
        }
    }

    private LichessPosition fetchWithRetries(String fen, LichessRatingSelector ratingSelector) {
        int attempts = 1;
        while (true) {
            try {
                log.trace("Getting a position " + fen + " from lichess");
                return getPositionJson(fen, ratingSelector);
            } catch (IOException | InterruptedException ex) {
                log.error("Error while getting lichess position: {}", ex.getMessage());
                if (attempts < 10) {
                    try {
                        log.warn("Too many Lichess requests. Cool down attempt N " + attempts);
                        Thread.sleep(30000L);
                    } catch (InterruptedException e) {
                        // Do nothing
                    }
                    attempts++;
                } else {
                    log.error("Couldn't fetch lichess position for fen {}, rating selector {}: retries exhausted",
                            fen, ratingSelector);
                    throw new IllegalStateException();
                }
            }
        }
    }

    private LichessPosition getPositionJson(String fen, LichessRatingSelector ratingSelector) throws IOException,
            InterruptedException {
        String croppedFen = getCroppedFen(fen);
        LichessPosition cached = lichessCache.get(croppedFen, ratingSelector);
        if (cached != null) {
            log.trace("Lichess position {}: loaded from cache", croppedFen);
            return cached;
        }

        bucket.asBlocking().consume(1);
        long start = System.nanoTime();
        String category = ratingSelector.isMaster ? "masters" : "lichess";
        String params = String.format(
                "fen=%s&variant=standard&speeds=blitz,rapid,classical",
                URLEncoder.encode(fen, StandardCharsets.UTF_8)
        );
        if (!ratingSelector.isMaster) {
            params += "&ratings=" + getRatingsString(ratingSelector.low, ratingSelector.high);
        }

        URL url = new URL(API_URL + category + "?" + params);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (LICHESS_TOKEN != null) {
            conn.setRequestProperty("Authorization", "Bearer " + LICHESS_TOKEN);
        }
        conn.setRequestProperty("Accept", "application/json");

        LichessPosition position;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder jsonBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBuilder.append(line);
            }

            position = lichessCache.jsonToLichessPosition(jsonBuilder.toString());
        }

        long end = System.nanoTime();
        log.trace(croppedFen + " stats queried in " + (end - start) / MS_IN_NS + " ms");
        lichessCache.put(croppedFen, ratingSelector, position);
        log.trace("Lichess position {}: fetched from Lichess", croppedFen);
        return position;
    }

    /*private LichessPosition lichessPositionFromJson(JSONObject position) {
        List<LichessMoveStats> moves = new ArrayList<>();

        long white = position.getLong("white");
        long draws = position.getLong("draws");
        long black = position.getLong("black");
        JSONArray movesJson = position.getJSONArray("moves");
        for (int i = 0; i < movesJson.length(); i++) {
            JSONObject m = movesJson.getJSONObject(i);
            moves.add(new LichessMoveStats(
                    m.getString("uci"),
                    m.getString("san"),
                    m.optInt("averageRating", 0),
                    m.optLong("white", 0),
                    m.optLong("draws", 0),
                    m.optLong("black", 0)
            ));
        }
        return new LichessPosition(white, draws, black, moves);
    }*/

    private String getRatingsString(LichessRating low, LichessRating high) {
        List<String> ratings = new ArrayList<>();
        ratings.add(Integer.toString(low.rating));
        for (LichessRating lr : LichessRating.values()) {
            if (lr.rating < high.rating && lr.rating > low.rating) {
                ratings.add(Integer.toString(lr.rating));
            }
        }
        ratings.add(Integer.toString(high.rating));
        return String.join(",", ratings);
    }
}
