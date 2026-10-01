package com.ser_databus.tpg.lichess;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.ser_databus.tpg.util.ChessUtil.getCroppedFen;
import static com.ser_databus.tpg.util.Const.MS_IN_NS;

@Slf4j
public class LichessClient {
    private static final String API_URL = "https://explorer.lichess.ovh/";
    private static final String LICHESS_TOKEN = "lip_33jJBhXOIrBmS2mnHmUB";
    private static final int REQUESTS_PER_SECOND = 50;
    private final LichessCache lichessCache = new LichessCache();

    private final Bucket bucket;

    public LichessClient() {
        Bandwidth limit = Bandwidth.classic(1, Refill.greedy(1, Duration.ofMillis(1000 / REQUESTS_PER_SECOND)));
        bucket = Bucket.builder().addLimit(limit).build();
    }

    public LichessPosition getPosition(String fen, LichessRatingSelector ratingSelector) {
        int attempts = 1;
        while (true) {
            try {
                return getPositionJson(fen, ratingSelector);
            } catch (IOException | InterruptedException ex) {
                log.error("Error while getting lichess position: {}", ex.getMessage());
                if (attempts < 5) {
                    try {
                        log.warn("Too many Lichess requests. Cool down attempt N " + attempts);
                        Thread.sleep(60000L);
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
        conn.setRequestProperty("Authorization", "Bearer " + LICHESS_TOKEN);
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
