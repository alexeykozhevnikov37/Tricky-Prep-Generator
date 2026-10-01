package com.ser_databus.tpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.ser_databus.tpg.util.ChessUtil.getCroppedFen;

public class CleanLichessCache {
    public static void main(String[] args) throws IOException {
        String inputFile = "src/main/resources/lichessCache.txt";
        String outputFile = "src/main/resources/lichessCacheClean.txt";

        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> newKeyToValue = new HashMap<>();

        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(inputFile), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                int ratingsIndex = line.indexOf('|');
                int valueIndex = line.indexOf('=');
                if (valueIndex < 0) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String fen = line.substring(0, ratingsIndex);
                String croppedFen = getCroppedFen(fen);
                String ratings = line.substring(ratingsIndex + 1, valueIndex);
                String newKey = croppedFen + "|" + ratings;
                String valueJson = line.substring(valueIndex + 1);

                JsonNode node = mapper.readTree(valueJson);
                ObjectNode obj = (ObjectNode) node;
                obj.remove("recentGames");
                obj.remove("topGames");
                obj.remove("opening");
                JsonNode moves = obj.get("moves");
                ArrayNode moveArray = (ArrayNode) moves;
                for (JsonNode moveArrayElement : moveArray) {
                    ObjectNode moveObject = (ObjectNode) moveArrayElement;
                    moveObject.remove("opening");
                    moveObject.remove("game");
                }
                String newValue = mapper.writeValueAsString(node);
                if (newKeyToValue.containsKey(newKey)) {
                    System.out.println("Key " + newKey + " is already there!");
                    if (!newValue.equals(newKeyToValue.get(newKey))) {
                        System.out.println("There are discrepancies: " + newValue + " vs. " +
                                newKeyToValue.get(newKey));
                    }
                } else {
                    newKeyToValue.put(newKey, newValue);
                }
            }
            for (var entry : newKeyToValue.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        }
    }
}
