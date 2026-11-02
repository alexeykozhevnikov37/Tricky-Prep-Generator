package com.ser_databus.tpg.evaldb;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Streaming parser of one line of the Lichess evaluation dump:
 * <pre>
 * {"fen":"7r/1p3k2/p1bPR3/5p2/2B2P1p/8/PP4P1/3K4 b - -",
 *  "evals":[{"pvs":[{"cp":69,"line":"f7g7 e6e2 h8d8"},{"mate":-3,"line":"..."}],"knodes":4189972,"depth":46}]}
 * </pre>
 * The streaming API is several times faster than building a JSON tree, which matters for ~400M lines.
 */
final class CloudEvalJson {
    private CloudEvalJson() {
    }

    /**
     * @param lineMoves keep at most this many moves of each line, 0 = all
     * @return the position with the dump FEN (4 fields) as the key, or null if it has no usable evaluation
     */
    static CloudEvalPosition parse(JsonFactory factory, String line, int lineMoves) throws IOException {
        String fen = null;
        List<CloudEval> evals = new ArrayList<>(2);
        try (JsonParser p = factory.createParser(line)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Not a JSON object");
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String field = p.currentName();
                JsonToken value = p.nextToken();
                if ("fen".equals(field)) {
                    fen = p.getText();
                } else if ("evals".equals(field) && value == JsonToken.START_ARRAY) {
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        CloudEval eval = parseEval(p, lineMoves);
                        if (!eval.pvs().isEmpty()) {
                            evals.add(eval);
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
        }
        if (fen == null) {
            throw new IOException("No fen");
        }
        return evals.isEmpty() ? null : new CloudEvalPosition(fen, evals);
    }

    private static CloudEval parseEval(JsonParser p, int lineMoves) throws IOException {
        int depth = 0;
        long knodes = 0;
        List<CloudPv> pvs = new ArrayList<>(1);
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            JsonToken value = p.nextToken();
            switch (field) {
                case "depth" -> depth = p.getIntValue();
                case "knodes" -> knodes = p.getLongValue();
                case "pvs" -> {
                    if (value != JsonToken.START_ARRAY) {
                        throw new IOException("pvs is not an array");
                    }
                    while (p.nextToken() == JsonToken.START_OBJECT) {
                        pvs.add(parsePv(p, lineMoves));
                    }
                }
                default -> p.skipChildren();
            }
        }
        return new CloudEval(depth, knodes, pvs);
    }

    private static CloudPv parsePv(JsonParser p, int lineMoves) throws IOException {
        Integer cp = null;
        Integer mate = null;
        String line = "";
        while (p.nextToken() == JsonToken.FIELD_NAME) {
            String field = p.currentName();
            p.nextToken();
            switch (field) {
                case "cp" -> cp = p.getIntValue();
                case "mate" -> mate = p.getIntValue();
                case "line" -> line = p.getText();
                default -> p.skipChildren();
            }
        }
        return new CloudPv(cp, mate, splitLine(line, lineMoves));
    }

    private static List<String> splitLine(String line, int lineMoves) {
        int limit = lineMoves <= 0 ? Integer.MAX_VALUE : lineMoves;
        List<String> moves = new ArrayList<>(Math.min(limit, 10));
        int start = 0;
        int length = line.length();
        while (start < length && moves.size() < limit) {
            int end = line.indexOf(' ', start);
            if (end < 0) {
                end = length;
            }
            if (end > start) {
                moves.add(line.substring(start, end));
            }
            start = end + 1;
        }
        return moves;
    }
}
