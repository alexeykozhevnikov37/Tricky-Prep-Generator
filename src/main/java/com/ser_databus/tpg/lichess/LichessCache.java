package com.ser_databus.tpg.lichess;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public class LichessCache {
    private static final String CACHE_FILE = "src/main/resources/lichessCache.txt";

    ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, LichessPosition> movesCache = new HashMap<>();

    public LichessCache() {
        loadCache();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
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

    private void saveRow(String key, String positionAsString) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(CACHE_FILE, true))) {
            bw.write(key + "=" + positionAsString + "\n");
        } catch (IOException e) {
            log.error("Error while appending a row to the lichess cache", e);
        }
    }

    private String makeKey(String fen, LichessRatingSelector ratingSelector) {
        return fen + "|" + (ratingSelector.isMaster ? "masters" :
                ratingSelector.low.rating + "|" + ratingSelector.high.rating);
    }

    public LichessPosition get(String fen, LichessRatingSelector ratingSelector) {
        return movesCache.get(makeKey(fen, ratingSelector));
    }

    public void put(String fen, LichessRatingSelector ratingSelector, LichessPosition value) throws JsonProcessingException {
        String key = makeKey(fen, ratingSelector);
        movesCache.put(key, value);
        saveRow(key, lichessPositionToJson(value));
    }

    public LichessPosition jsonToLichessPosition(String json) throws JsonProcessingException {
        return objectMapper.readValue(json, LichessPosition.class);
    }

    public String lichessPositionToJson(LichessPosition lichessPosition) throws JsonProcessingException {
        return objectMapper.writeValueAsString(lichessPosition);
    }
}
