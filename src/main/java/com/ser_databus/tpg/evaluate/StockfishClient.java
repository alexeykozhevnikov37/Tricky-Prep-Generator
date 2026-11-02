package com.ser_databus.tpg.evaluate;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static com.ser_databus.tpg.util.Const.MS_IN_NS;
import static com.ser_databus.tpg.util.Const.VICTORY_SCORE;
import static com.ser_databus.tpg.util.MoveUtil.stockfishUciToMove;

/**
 * Pool of Stockfish engine processes driven by a fixed thread pool.
 * <p>
 * One Stockfish process can only search one position at a time, so the client starts {@link #POOL_SIZE}
 * engine processes and a thread pool of the same size. Every request is an async task that borrows an engine,
 * runs the search and returns the engine to the pool.
 * <p>
 * Results are stored in a RocksDB database ({@link StockfishEvalStore}) as soon as they are calculated, so they
 * don't take heap memory. Only the requests that are still running are kept in memory, so identical requests
 * share one search.
 */
@Slf4j
public class StockfishClient implements PositionEvaluator {
    private static final String STOCKFISH_PATH =
            "C:\\Games\\Chess\\stockfish-19-windows-x86-64-uni\\stockfish-windows-x86-64-universal.exe";

    /**
     * Number of worker threads and Stockfish processes: at most 8, and one core is left for the UI, the JVM and the
     * rest of the system, so the mouse and the windows stay responsive while the engines are busy.
     */
    private static final int POOL_SIZE = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() - 1));
    /** Search threads inside each Stockfish process: one busy core per process. */
    private static final int THREADS_PER_ENGINE = 1;
    /**
     * Hash per Stockfish process. The searches are shallow and the hash is cleared after each one, so a small table
     * is enough: 8 x 32 MB = 256 MB in total.
     */
    private static final int HASH_MB_PER_ENGINE = 32;

    private final Map<Integer, Integer> msToCount = new ConcurrentHashMap<>();

    /** Finished evaluations. */
    private final StockfishEvalStore store;
    /** key -> evaluation that is still running. Optional.empty() means "no legal moves". */
    private final ConcurrentHashMap<String, Request<Optional<LineEval>>> inFlight = new ConcurrentHashMap<>();
    /** key -> move set evaluation that is still running */
    private final ConcurrentHashMap<String, Request<List<LineEval>>> moveSetsInFlight =
            new ConcurrentHashMap<>();
    /** Evaluations calculated since the last {@link #flushCache()}, for the log */
    private final AtomicInteger newEvals = new AtomicInteger();
    private volatile boolean closed;
    /** Incremented by {@link #abandonPendingRequests()}; requests remember the newest round that asked for them */
    private final AtomicLong currentRound = new AtomicLong();

    private final List<Engine> allEngines = new ArrayList<>();
    private final BlockingQueue<Engine> freeEngines = new LinkedBlockingQueue<>();
    private final ExecutorService executor;

    public StockfishClient() {
        this.store = StockfishEvalStore.open();
        try {
            for (int i = 0; i < POOL_SIZE; i++) {
                Engine engine = new Engine();
                allEngines.add(engine);
                freeEngines.add(engine);
            }
        } catch (IOException ex) {
            allEngines.forEach(Engine::quit);
            store.close();
            throw new IllegalStateException("Couldn't start the engine", ex);
        }
        lowerEnginePriority();
        AtomicInteger threadCounter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(POOL_SIZE, r -> {
            Thread t = new Thread(r, "stockfish-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    /**
     * Asynchronously evaluates the position. Identical requests (same position and depth) share one search,
     * and cached positions return an already completed future.
     */
    @Override
    public CompletableFuture<Optional<LineEval>> getBestLineAsync(Board board, int depth) {
        String key = getCacheKey(board, depth);
        long round = currentRound.get();
        while (true) {
            Request<Optional<LineEval>> running = inFlight.get(key);
            if (running != null) {
                if (running.join(round)) {
                    return running.future;
                }
                // abandoned before it started: make a new one
                inFlight.remove(key, running);
                continue;
            }
            CompletableFuture<Optional<LineEval>> stored = loadStored(key, board, depth);
            if (stored != null) {
                return stored;
            }
            Request<Optional<LineEval>> request = new Request<>(round);
            if (inFlight.putIfAbsent(key, request) != null) {
                continue;
            }
            // Another request may have finished this position between the lookup and putIfAbsent
            stored = loadStored(key, board, depth);
            if (stored != null) {
                inFlight.remove(key, request);
                request.future.complete(stored.join());
                return request.future;
            }
            // Copy the board: the caller may change its own board while the task is waiting in the queue
            Board boardCopy = board.clone();
            submit(key, inFlight, request, engine -> {
                Optional<LineEval> lineEval = engine.getBestLine(boardCopy, depth);
                if (lineEval.isEmpty()) {
                    log.trace("{}: at depth {} there are no moves", boardCopy.getFen(), depth);
                } else {
                    log.trace("{}: at depth {} the best move {} gives {}", boardCopy.getFen(), depth,
                            lineEval.get().move, lineEval.get().eval);
                }
                return lineEval;
            }, lineEval -> store.put(key, lineEval), "calculating the eval of " + boardCopy.getFen());
            return request.future;
        }
    }

    /**
     * Runs a search on the next free engine; the result is saved, and only then the request leaves the in-flight map,
     * so a new request always finds it in one of them. A request that nobody asked for since the last
     * {@link #abandonPendingRequests()} is dropped instead of searched when its turn comes.
     */
    private <T> void submit(String key, Map<String, Request<T>> requests, Request<T> request, EngineTask<T> search,
                            Consumer<T> save, String description) {
        try {
            executor.execute(() -> {
                if (!request.start(currentRound.get())) {
                    requests.remove(key, request);
                    request.future.completeExceptionally(
                            new CancellationException("The evaluation isn't needed any more"));
                    return;
                }
                try {
                    T result = withEngine(search);
                    if (closed) {
                        // The engine was stopped in the middle of the search: the result may be incomplete
                        throw new CancellationException("The Stockfish client is closed");
                    }
                    save.accept(result);
                    newEvals.incrementAndGet();
                    request.future.complete(result);
                    requests.remove(key, request);
                } catch (Throwable ex) {
                    if (!closed) {
                        log.error("Error while {}", description, ex);
                    }
                    requests.remove(key, request);
                    request.future.completeExceptionally(ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            requests.remove(key, request);
            request.future.completeExceptionally(ex);
        }
    }

    /**
     * Drops the queued searches that nobody needs any more: every search that is still waiting for an engine and
     * isn't requested again from now on is skipped when its turn comes (its future fails with a
     * {@link CancellationException}). Running searches finish. Called when a prep generation ends, so the searches a
     * cancelled generation queued don't keep the engines busy and the next generation doesn't wait behind them.
     */
    @Override
    public void abandonPendingRequests() {
        long round = currentRound.incrementAndGet();
        log.debug("Pending Stockfish requests are abandoned (round {}): {} single searches and {} move set searches "
                + "in flight", round, inFlight.size(), moveSetsInFlight.size());
    }

    /** A search waiting for an engine or running */
    private static final class Request<T> {
        final CompletableFuture<T> future = new CompletableFuture<>();
        /** The newest request round that asked for it */
        private long round;
        private boolean abandoned;

        Request(long round) {
            this.round = round;
        }

        /** Another caller waits for this search, too; false if it was abandoned already, so a new one is needed */
        synchronized boolean join(long currentRound) {
            if (abandoned) {
                return false;
            }
            round = Math.max(round, currentRound);
            return true;
        }

        /** The engine is about to start the search: false if nobody asked for it in the current round */
        synchronized boolean start(long currentRound) {
            if (round < currentRound) {
                abandoned = true;
                return false;
            }
            return true;
        }
    }

    /** @return a completed future with the stored evaluation, or null if it isn't stored */
    @SuppressWarnings("OptionalAssignedToNull")
    private CompletableFuture<Optional<LineEval>> loadStored(String key, Board board, int depth) {
        Optional<LineEval> stored = store.get(key);
        if (stored == null) {
            return null;
        }
        log.trace("Stockfish position {}, depth {}: loaded from the database", board.getFen(), depth);
        return CompletableFuture.completedFuture(stored);
    }

    /** Synchronous version of {@link #getBestLineAsync(Board, int)}. */
    @Override
    public Optional<LineEval> getBestLine(Board board, int depth) {
        try {
            return getBestLineAsync(board, depth).join();
        } catch (CompletionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    /**
     * Evaluates the given moves in one search: {@code go depth N searchmoves ...} with MultiPV = number of moves.
     * The results are stored like single evaluations, and identical requests (same position, depth and move set in
     * any order) share one search, so repeated requests get exactly the same numbers.
     */
    @Override
    public CompletableFuture<Map<Move, LineEval>> evaluateMovesAsync(Board board, List<Move> moves, int depth) {
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("No moves to evaluate in " + board.getFen());
        }
        Board boardCopy = board.clone();
        List<Move> movesCopy = List.copyOf(moves);
        return getMoveSetLinesAsync(boardCopy, movesCopy, depth)
                .thenCompose(lines -> completeMoveSet(boardCopy, movesCopy, depth, lines));
    }

    /** The lines of a move set, from the database, a running search, or a new search. */
    private CompletableFuture<List<LineEval>> getMoveSetLinesAsync(Board board, List<Move> moves, int depth) {
        String key = getMoveSetCacheKey(board, moves, depth);
        long round = currentRound.get();
        while (true) {
            Request<List<LineEval>> running = moveSetsInFlight.get(key);
            if (running != null) {
                if (running.join(round)) {
                    return running.future;
                }
                moveSetsInFlight.remove(key, running);
                continue;
            }
            List<LineEval> stored = store.getMoveEvals(key);
            if (stored != null) {
                return CompletableFuture.completedFuture(stored);
            }
            Request<List<LineEval>> request = new Request<>(round);
            if (moveSetsInFlight.putIfAbsent(key, request) != null) {
                continue;
            }
            // Another request may have finished this move set between the lookup and putIfAbsent
            stored = store.getMoveEvals(key);
            if (stored != null) {
                moveSetsInFlight.remove(key, request);
                request.future.complete(stored);
                return request.future;
            }
            submit(key, moveSetsInFlight, request, engine -> engine.evaluateMoves(board, moves, depth), lines -> {
                // Only complete results are stored: a missing line is filled in by completeMoveSet every time
                if (lines.size() == moves.size()) {
                    store.putMoveEvals(key, lines);
                }
            }, "evaluating moves " + moves + " in " + board.getFen());
            return request.future;
        }
    }

    /**
     * Puts the lines in the order of the moves. If the engine didn't report a line for some move (it shouldn't
     * happen), the move is evaluated separately: the negated best line of the position after it.
     */
    private CompletableFuture<Map<Move, LineEval>> completeMoveSet(Board board, List<Move> moves, int depth,
                                                                   List<LineEval> lines) {
        Map<Move, LineEval> byMove = new HashMap<>();
        for (LineEval line : lines) {
            byMove.putIfAbsent(line.move, line);
        }
        Map<Move, CompletableFuture<LineEval>> futures = new LinkedHashMap<>();
        for (Move move : moves) {
            LineEval line = byMove.get(move);
            if (line != null) {
                futures.put(move, CompletableFuture.completedFuture(line));
                continue;
            }
            log.debug("{}: no line for move {} in a move set search, evaluating it separately", board.getFen(), move);
            Board after = board.clone();
            after.doMove(move);
            futures.put(move, getBestLineAsync(after, depth).thenApply(reply -> new LineEval(0,
                    reply.map(le -> -le.eval).orElse(after.isKingAttacked() ? VICTORY_SCORE : 0.),
                    move, reply.map(le -> le.depth).orElse(depth))));
        }
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture<?>[0])).thenApply(ignored -> {
            Map<Move, LineEval> result = new LinkedHashMap<>();
            futures.forEach((move, future) -> result.put(move, future.join()));
            return result;
        });
    }

    public CompletableFuture<List<LineEval>> getNBestLinesAsync(Board board, int depth, int multiPV) {
        Board boardCopy = board.clone();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return withEngine(engine -> engine.getNBestLines(boardCopy, depth, multiPV));
            } catch (IOException | InterruptedException ex) {
                log.error("Error while calculating multiPV eval", ex);
                throw new IllegalStateException(ex);
            }
        }, executor);
    }

    public List<LineEval> getNBestLines(Board board, int depth, int multiPV) {
        try {
            return getNBestLinesAsync(board, depth, multiPV).join();
        } catch (CompletionException ex) {
            throw new IllegalStateException(ex.getCause());
        }
    }

    /**
     * Evaluations are stored in the database as soon as they are calculated, so there is nothing to write here;
     * only logs how many were added since the previous call.
     */
    @Override
    public void flushCache() {
        int count = newEvals.getAndSet(0);
        if (count > 0) {
            log.debug("Stockfish evals: {} new evaluations are stored{}", count,
                    store.isReadOnly() ? " (not really: the database is read-only)" : "");
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (executor != null) {
            executor.shutdownNow();
        }
        allEngines.forEach(Engine::quit);
        try {
            // let the workers finish storing what they have, so the database isn't closed under them
            if (executor != null) {
                executor.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        store.close();
    }

    /**
     * Windows: the engines run at "below normal" priority, so whatever the user does on the computer goes first and
     * the engines get the rest of the CPU (searches are as fast when nothing else is running). Runs in the
     * background; if it fails, the engines just keep the normal priority.
     */
    private void lowerEnginePriority() {
        if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
            return;
        }
        StringJoiner pids = new StringJoiner(",");
        allEngines.forEach(engine -> pids.add(String.valueOf(engine.process.pid())));
        Thread thread = new Thread(() -> {
            try {
                Process powershell = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                        "Get-Process -Id " + pids + " | ForEach-Object { $_.PriorityClass = 'BelowNormal' }")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!powershell.waitFor(30, TimeUnit.SECONDS)) {
                    powershell.destroy();
                    log.warn("Couldn't lower the priority of the engines: PowerShell didn't finish in time");
                } else if (powershell.exitValue() != 0) {
                    log.warn("Couldn't lower the priority of the engines: PowerShell exit code {}",
                            powershell.exitValue());
                } else {
                    log.debug("The engines run at below normal priority");
                }
            } catch (IOException e) {
                log.warn("Couldn't lower the priority of the engines: {}", e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "engine-priority");
        thread.setDaemon(true);
        thread.start();
    }

    private <T> T withEngine(EngineTask<T> task) throws IOException, InterruptedException {
        Engine engine = freeEngines.take();
        try {
            return task.run(engine);
        } finally {
            freeEngines.add(engine);
        }
    }

    @FunctionalInterface
    private interface EngineTask<T> {
        T run(Engine engine) throws IOException, InterruptedException;
    }

    /** One Stockfish process. Used by one thread at a time (guaranteed by the free engine queue). */
    private class Engine {
        private final Process process;
        private final BufferedWriter writer;
        private final BufferedReader reader;

        Engine() throws IOException {
            process = new ProcessBuilder(STOCKFISH_PATH).start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            sendCommand("uci");
            waitFor("uciok");
            sendCommand("setoption name Threads value " + THREADS_PER_ENGINE);
            sendCommand("setoption name Hash value " + HASH_MB_PER_ENGINE);
            sendCommand("ucinewgame");
        }

        Optional<LineEval> getBestLine(Board board, int depth) throws IOException {
            long start = System.nanoTime();
            String engineeredFen = board.getFen(false) + " 0 1";

            sendCommand("isready");
            waitFor("readyok");

            sendCommand("setoption name MultiPV value 1");
            sendCommand("position fen " + engineeredFen);
            sendCommand("go depth " + depth);

            Optional<LineEval> lineEval = Optional.empty();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("info") && line.contains("score")) {
                    lineEval = parsePVLine(board, line);
                }
                if (line.startsWith("bestmove")) {
                    break;
                }
            }

            int ms = (int) ((System.nanoTime() - start) / MS_IN_NS);
            msToCount.merge(ms, 1, Integer::sum);
            log.trace("{} evaluated in {} ms", board.getFen(), ms);
            log.trace("Stockfish position {}, depth {}: fetched from the engine", engineeredFen, depth);
            clearHashTable();
            return lineEval;
        }

        /**
         * One search restricted to the given moves, with a line per move. Lines are reported at every depth;
         * the last one of each move is the deepest.
         */
        List<LineEval> evaluateMoves(Board board, List<Move> moves, int depth) throws IOException {
            long start = System.nanoTime();
            String engineeredFen = board.getFen(false) + " 0 1";

            sendCommand("isready");
            waitFor("readyok");

            sendCommand("setoption name MultiPV value " + moves.size());
            sendCommand("position fen " + engineeredFen);
            StringBuilder go = new StringBuilder("go depth ").append(depth).append(" searchmoves");
            for (Move move : moves) {
                go.append(' ').append(move.toString());
            }
            sendCommand(go.toString());

            Map<Move, LineEval> lastLines = new LinkedHashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("info") && line.contains(" multipv ") && line.contains(" pv ")
                        && !line.contains("bound")) {
                    parsePVLine(board, line).filter(le -> le.move != null)
                            .ifPresent(le -> lastLines.put(le.move, le));
                }
                if (line.startsWith("bestmove")) {
                    break;
                }
            }
            // Restore single PV mode for subsequent getBestLine calls on this engine
            sendCommand("setoption name MultiPV value 1");

            List<LineEval> result = new ArrayList<>(lastLines.values());
            result.sort(Comparator.comparingInt(le -> le.rank));
            int ms = (int) ((System.nanoTime() - start) / MS_IN_NS);
            msToCount.merge(ms, 1, Integer::sum);
            log.trace("{}: {} moves evaluated in {} ms", board.getFen(), moves.size(), ms);
            clearHashTable();
            return result;
        }

        List<LineEval> getNBestLines(Board board, int depth, int multiPV) throws IOException {
            long start = System.nanoTime();

            sendCommand("uci");
            waitFor("uciok");
            sendCommand("setoption name MultiPV value " + multiPV);
            sendCommand("isready");
            waitFor("readyok");
            sendCommand("position fen " + board.getFen());
            sendCommand("go depth " + depth);

            Map<Integer, LineEval> pvMap = new HashMap<>();
            boolean noMoves = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (!noMoves && line.startsWith("info") && line.contains("multipv")) {
                    Optional<LineEval> le = parsePVLine(board, line);
                    if (le.isEmpty()) {
                        // Keep reading until "bestmove" so the engine output stays in sync for the next request
                        noMoves = true;
                        pvMap.clear();
                    } else {
                        pvMap.put(le.get().rank, le.get());
                    }
                }
                if (line.startsWith("bestmove")) {
                    break;
                }
            }
            // Restore single PV mode for subsequent getBestLine calls on this engine
            sendCommand("setoption name MultiPV value 1");

            List<LineEval> result = new ArrayList<>(pvMap.values());
            result.sort(Comparator.comparingInt(le -> le.rank));
            log.trace("{}: {} lines calculated in {} ms", board.getFen(), multiPV, (System.nanoTime() - start) / MS_IN_NS);
            clearHashTable();
            return result;
        }

        /**
         * Empties the engine's hash table and search history, so the next search doesn't depend on
         * which positions this engine happened to calculate before. Waits until the engine has finished
         * clearing, so the engine is ready when it goes back to the pool.
         */
        private void clearHashTable() throws IOException {
            sendCommand("ucinewgame");
            sendCommand("isready");
            waitFor("readyok");
        }

        void quit() {
            try {
                sendCommand("quit");
            } catch (IOException ignored) {
                // the process is already gone
            }
            process.destroy();
        }

        private void sendCommand(String command) throws IOException {
            writer.write(command + "\n");
            writer.flush();
        }

        private void waitFor(String expected) throws IOException {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains(expected)) break;
            }
        }
    }

    private Optional<LineEval> parsePVLine(Board board, String line) {
        String[] parts = line.split(" ");
        Integer rank = null;
        Double score = null;
        Move bestMove = null;
        int depth = 0;

        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("depth") && i + 1 < parts.length) {
                depth = Integer.parseInt(parts[i + 1]);
            }
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
        return Optional.of(new LineEval(rank, score, bestMove, depth));
    }

    private String getCacheKey(Board board, int depth) {
        return board.getFen(false) + "|" + depth;
    }

    /** "FEN|depth|e2e4,g1f3": the moves sorted, so the same set in another order shares the result */
    private String getMoveSetCacheKey(Board board, List<Move> moves, int depth) {
        List<String> ucis = new ArrayList<>(moves.size());
        for (Move move : moves) {
            ucis.add(move.toString());
        }
        Collections.sort(ucis);
        return getCacheKey(board, depth) + "|" + String.join(",", ucis);
    }

    @Override
    public void printStats() {
        log.debug("Stockfish time stats:");
        if (msToCount.isEmpty()) {
            log.debug("  No data.");
            return;
        }

        Map<Integer, Integer> snapshot = new TreeMap<>(msToCount);
        long totalCount = 0;
        long totalSum = 0;
        for (var entry : snapshot.entrySet()) {
            totalCount += entry.getValue();
            totalSum += (long) entry.getKey() * entry.getValue();
        }
        int min = ((TreeMap<Integer, Integer>) snapshot).firstKey();
        int max = ((TreeMap<Integer, Integer>) snapshot).lastKey();
        double avg = (double) totalSum / totalCount;

        log.debug("  Total requests: {}", totalCount);
        log.debug("  Min latency: {} ms", min);
        log.debug("  Max latency: {} ms", max);
        log.debug("  Average latency: {} ms", (int) avg);

        long[] targets = {
                Math.round(totalCount * 0.50),
                Math.round(totalCount * 0.90),
                Math.round(totalCount * 0.95),
                Math.round(totalCount * 0.99)
        };
        String[] labels = {"p50", "p90", "p95", "p99"};

        long cumulative = 0;
        int idx = 0;
        for (var entry : snapshot.entrySet()) {
            cumulative += entry.getValue();
            while (idx < targets.length && cumulative >= targets[idx]) {
                log.debug("  {} latency: {} ms", labels[idx], entry.getKey());
                idx++;
            }
            if (idx >= targets.length) break;
        }
    }
}
