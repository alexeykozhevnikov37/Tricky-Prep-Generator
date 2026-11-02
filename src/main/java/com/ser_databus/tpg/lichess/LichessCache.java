package com.ser_databus.tpg.lichess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe Lichess position cache. New positions are kept in memory immediately and appended to the cache
 * file only when {@link #flush()} is called.
 */
@Slf4j
public class LichessCache {
    private static final String CACHE_FILE = "src/main/resources/lichessCache.txt";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, LichessPosition> movesCache = new ConcurrentHashMap<>();
    /** Cache file lines added since the last flush. */
    private final ConcurrentLinkedQueue<String> pendingRows = new ConcurrentLinkedQueue<>();

    public LichessCache() {
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        loadCache();
    }

    private void loadCache() {
        File file = new File(CACHE_FILE);
        if (!file.exists()) return;

        log.debug("Loading Lichess cache...");
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 2) {
                    movesCache.put(parts[0], jsonToLichessPosition(parts[1]));
                }
            }
        } catch (IOException e) {
            log.error("Error while loading lichess cache", e);
            throw new IllegalStateException(e);
        }
    }

    public String makeKey(String fen, LichessRatingSelector ratingSelector) {
        return fen + "|" + (ratingSelector.isMaster ? "masters" :
                ratingSelector.low.rating + "|" + ratingSelector.high.rating);
    }

    public LichessPosition get(String fen, LichessRatingSelector ratingSelector) {
        return movesCache.get(makeKey(fen, ratingSelector));
    }

    public void put(String fen, LichessRatingSelector ratingSelector, LichessPosition value) throws JsonProcessingException {
        String key = makeKey(fen, ratingSelector);
        if (movesCache.putIfAbsent(key, value) == null) {
            pendingRows.add(key + "=" + lichessPositionToJson(value) + "\n");
        }
    }

    /** Appends all positions fetched since the previous flush to the cache file. */
    public synchronized void flush() {
        if (pendingRows.isEmpty()) {
            return;
        }
        int written = 0;
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(CACHE_FILE, true))) {
            String row;
            while ((row = pendingRows.poll()) != null) {
                bw.write(row);
                written++;
            }
        } catch (IOException e) {
            log.error("Error while appending rows to the lichess cache", e);
        }
        log.debug("Lichess cache: {} new positions are stored", written);
    }

    public LichessPosition jsonToLichessPosition(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, LichessPosition.class);
    }

    public String lichessPositionToJson(LichessPosition lichessPosition) throws JsonProcessingException {
        return objectMapper.writeValueAsString(lichessPosition);
    }
}
