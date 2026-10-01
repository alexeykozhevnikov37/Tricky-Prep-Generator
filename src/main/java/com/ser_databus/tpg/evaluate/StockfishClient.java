package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;

import static com.ser_databus.tpg.util.Const.MS_IN_NS;
import static com.ser_databus.tpg.util.Const.VICTORY_SCORE;
import static com.ser_databus.tpg.util.MoveUtil.stockfishUciToMove;

@Slf4j
public class StockfishClient {
    private static final String STOCKFISH_PATH =
            "C:\\Games\\Chess\\stockfish-19-windows-x86-64-uni\\stockfish-windows-x86-64-universal.exe";
    private static final String CACHE_FILE = "src/main/resources/evalCache.txt";
    private final Map<Integer, Integer> msToCount = new HashMap<>();

    private final BufferedWriter uciCommandsWriter;
    private final BufferedReader uciCommandsReader;
    private final Map<String, LineEval> evalCache;

    public StockfishClient() {
        ProcessBuilder pb = new ProcessBuilder(STOCKFISH_PATH);
        try {
            Process engine = pb.start();
            uciCommandsWriter = new BufferedWriter(new OutputStreamWriter(engine.getOutputStream()));
            uciCommandsReader = new BufferedReader(new InputStreamReader(engine.getInputStream()));
            this.evalCache = loadCache();

            sendCommand(uciCommandsWriter, "uci");
            waitFor(uciCommandsReader, "uciok");

            // Detect system memory and allocate 75% to Stockfish hash
//        long totalMemoryBytes = ((com.sun.management.OperatingSystemMXBean)
//                ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
            long hashMB = 2048; // (totalMemoryBytes / (1024 * 1024)) * 50 / 100;
            int cores = 4; // Runtime.getRuntime().availableProcessors();
            sendCommand(uciCommandsWriter, "setoption name Threads value " + cores);

            sendCommand(uciCommandsWriter, "setoption name Hash value " + hashMB);
            sendCommand(uciCommandsWriter, "ucinewgame");
        } catch (IOException ex) {
            throw new IllegalStateException("Couldn't start the engine", ex);
        }
    }

    public Optional<LineEval> getBestLine(Board board, int depth) {
        String key = getCacheKey(board, depth);
        if (evalCache.containsKey(key)) {
            log.trace("Stockfish position {}, depth {}: loaded from cache", board.getFen(), depth);
            LineEval lineEval = evalCache.get(key);
            if (lineEval == null) {
                log.trace("{}: at depth {}: game over", board.getFen(), depth);
            } else {
                log.trace("{}: at depth {} the best move {} gives {}", board.getFen(), depth, lineEval.move, lineEval.eval);
            }
            return Optional.ofNullable(evalCache.get(key));
        }
        try {
            Optional<LineEval> lineEval = getBestLineRaw(board, depth);
            if (lineEval.isEmpty()) {
                log.trace("{}: at depth {} there are no moves", board.getFen(), depth);
            } else {
                log.trace("{}: at depth {} the best move {} gives {}", board.getFen(), depth, lineEval.get().move,
                        lineEval.get().eval);
            }
            evalCache.put(key, lineEval.orElse(null));
            appendToCacheFile(key, lineEval);
            return lineEval;
        } catch (IOException | InterruptedException ex) {
            log.error("Error while calculating singlePV eval", ex);
            throw new IllegalStateException(ex);
        }
    }

    public List<LineEval> getNBestLines(Board board, int depth, int multiPV) {
        try {
            return getNBestLinesRaw(board, depth, multiPV);
        } catch (IOException | InterruptedException ex) {
            log.error("Error while calculating multiPV eval", ex);
            throw new IllegalStateException(ex);
        }
    }

    private Optional<LineEval> getBestLineRaw(Board board, int depth) throws IOException, InterruptedException {
        long start = System.nanoTime();
        String engineeredFen = board.getFen(false) + " 0 1";

        sendCommand(uciCommandsWriter, "isready");
        waitFor(uciCommandsReader, "readyok");

        sendCommand(uciCommandsWriter, "setoption name MultiPV value 1");
        sendCommand(uciCommandsWriter, "position fen " + engineeredFen);
        sendCommand(uciCommandsWriter, "go depth " + depth);

        Optional<LineEval> lineEval = Optional.empty();
        String line;
        while ((line = uciCommandsReader.readLine()) != null) {
            if (line.startsWith("info") && line.contains("score")) {
                lineEval = parsePVLine(board, line);
            }
            if (line.startsWith("bestmove")) {
                break;
            }
        }

        long end = System.nanoTime();
        int ms = (int) ((end - start) / MS_IN_NS);
        if (msToCount.containsKey(ms)) {
            msToCount.put(ms, msToCount.get(ms) + 1);
        } else {
            msToCount.put(ms, 1);
        }
        log.trace(board.getFen() + " evaluated in " + ms + " ms");
        log.trace("Stockfish position {}, depth {}: fetched from the engine", engineeredFen, depth);
        return lineEval;
    }

    private List<LineEval> getNBestLinesRaw(Board board, int depth, int multiPV) throws IOException, InterruptedException {
        long start = System.nanoTime();

        sendCommand(uciCommandsWriter, "uci");
        waitFor(uciCommandsReader, "uciok");

        sendCommand(uciCommandsWriter, "setoption name MultiPV value " + multiPV);

        sendCommand(uciCommandsWriter, "isready");
        waitFor(uciCommandsReader, "readyok");

        sendCommand(uciCommandsWriter, "position fen " + board.getFen());

        sendCommand(uciCommandsWriter, "go depth " + depth);

        Map<Integer, LineEval> pvMap = new HashMap<>();
        String line;
        while ((line = uciCommandsReader.readLine()) != null) {
            if (line.startsWith("info") && line.contains("multipv")) {
                Optional<LineEval> le = parsePVLine(board, line);
                if (le.isEmpty()) {
                    return List.of();
                }
                pvMap.put(le.get().rank, le.get());
            }
            if (line.startsWith("bestmove")) {
                break;
            }
        }

        List<LineEval> result = new ArrayList<>(pvMap.values());
        result.sort(Comparator.comparingInt(le -> le.rank));

        long end = System.nanoTime();
        log.trace("{}: {} lines calculated in {} ms", board.getFen(), multiPV, (end - start) / MS_IN_NS);
        return result;
    }

    private void sendCommand(BufferedWriter writer, String command) throws IOException {
        writer.write(command + "\n");
        writer.flush();
    }

    private void waitFor(BufferedReader reader, String expected) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.contains(expected)) break;
        }
    }

    private Optional<LineEval> parsePVLine(Board board, String line) {
        String[] parts = line.split(" ");
        Integer rank = null;
        Double score = null;
        Move bestMove = null;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("multipv") && i + 1 < parts.length) {
                rank = Integer.parseInt(parts[i + 1]);
            }
            if (parts[i].equals("score") && i + 2 < parts.length) {
                String type = parts[i + 1];
                String val = parts[i + 2];
                if (type.equals("cp")) {
                    score = Integer.parseInt(val) / 100.0;
                } else if (type.equals("mate")) {
                    int moves = Integer.parseInt(val);
                    score = moves > 0 ? VICTORY_SCORE - moves / 100. : -VICTORY_SCORE - moves / 100.;
                }
            }
            if (parts[i].equals("pv") && i + 1 < parts.length) {
                bestMove = stockfishUciToMove(board, parts[i + 1]);
                break;
            }
        }

        if (bestMove == null && rank == null) {
            return Optional.empty();
        }
        return Optional.of(new LineEval(rank, score, bestMove));
    }

    private String getCacheKey(Board board, int depth) {
        return board.getFen(false) + "|" + depth;
    }

    private double parseScore(String line) {
        String[] parts = line.split(" ");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("score") && i + 2 < parts.length) {
                String type = parts[i + 1];
                String val = parts[i + 2];
                if (type.equals("cp")) {
                    return Integer.parseInt(val) / 100.0;
                } else if (type.equals("mate")) {
                    return Integer.parseInt(val) > 0 ? 327. : -327.;
                }
            }
        }
        throw new IllegalStateException();
    }

    private Map<String, LineEval> loadCache() {
        Map<String, LineEval> map = new HashMap<>();
        File f = new File(CACHE_FILE);
        if (!f.exists()) return map;

        log.debug("Loading Stockfish cache...");
        Board board = new Board();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split("=");
                if (parts.length == 3) {
                    String key = parts[0];
                    String fen = key.split("\\|")[0];
                    board.loadFromFen(fen);
                    if ("null".equals(parts[1])) {
                        map.put(key, null);
                    } else {
                        map.put(key, new LineEval(1, Double.parseDouble(parts[1]), stockfishUciToMove(board, parts[2])));
                    }
                }
            }
        } catch (IOException e) {
            log.error("Error while loading stockfish eval cache", e);
            throw new IllegalStateException(e);
        }
        return map;
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private void appendToCacheFile(String key, Optional<LineEval> lineEval) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(CACHE_FILE, true))) {
            if (lineEval.isEmpty()) {
                bw.write(key + "=null=null\n");
            } else {
                bw.write(key + "=" + lineEval.get().eval + "=" + lineEval.get().move + "\n");
            }
        } catch (IOException e) {
            log.error("Error while appending stockfish eval cache", e);
        }
    }

    public void printStats() {
        log.trace("Stockfish time stats:");
        if (msToCount.isEmpty()) {
            log.trace("  No data.");
            return;
        }

        long totalCount = 0;
        long totalSum = 0;

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (var entry : msToCount.entrySet()) {
            int ms = entry.getKey();
            int count = entry.getValue();

            totalCount += count;
            totalSum += (long) ms * count;

            if (ms < min) min = ms;
            if (ms > max) max = ms;
        }

        double avg = (double) totalSum / totalCount;

        log.trace("  Total requests: {}", totalCount);
        log.trace("  Min latency: {} ms", min);
        log.trace("  Max latency: {} ms", max);
        log.trace("  Average latency: {} ms", avg);

        // Percentiles
        List<Integer> sortedKeys = new ArrayList<>(msToCount.keySet());
        Collections.sort(sortedKeys);

        long[] targets = {
                Math.round(totalCount * 0.50),
                Math.round(totalCount * 0.90),
                Math.round(totalCount * 0.95),
                Math.round(totalCount * 0.99)
        };
        String[] labels = {"p50", "p90", "p95", "p99"};

        long cumulative = 0;
        int idx = 0;
        for (int ms : sortedKeys) {
            cumulative += msToCount.get(ms);
            while (idx < targets.length && cumulative >= targets[idx]) {
                log.trace("  {} latency: {} ms", labels[idx], ms);
                idx++;
            }
            if (idx >= targets.length) break;
        }
    }
}
