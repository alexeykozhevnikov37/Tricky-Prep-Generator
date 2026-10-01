package com.ser_databus.tpg;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static com.ser_databus.tpg.util.ChessUtil.getCroppedFen;

public class CleanStockfishCache {
    public static void main(String[] args) throws IOException {
        String inputFile = "src/main/resources/evalCache.txt";
        String outputFile = "src/main/resources/evalCacheClean.txt";
        Map<String, String> keyToValues = new HashMap<>();

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
                int depthIndex = line.indexOf('|');
                int valuesIndex = line.indexOf('=');
                if (depthIndex < 0 || valuesIndex < 0) {
                    System.err.println("Cannot parse a line: " + line);
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String fen = line.substring(0, depthIndex);
                String depth = line.substring(depthIndex + 1, valuesIndex);
                String values = line.substring(valuesIndex + 1);
                String croppedFen = getCroppedFen(fen);
                String newKey = croppedFen + "|" + depth;
                if (keyToValues.containsKey(newKey)) {
                    System.out.println("Fen " + newKey + " is already there");
                    if (!values.equals(keyToValues.get(newKey))) {
                        System.out.println("There are discrepancies: " + values + " vs. " +
                                keyToValues.get(newKey));
                    }
                } else {
                    keyToValues.put(newKey, values);
                }
            }
            for (var entry : keyToValues.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        }
    }
}
