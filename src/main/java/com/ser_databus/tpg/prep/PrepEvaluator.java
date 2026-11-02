package com.ser_databus.tpg.prep;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.evaluate.LineEval;
import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.evaluate.PositionEvaluator;
import com.ser_databus.tpg.lichess.*;
import com.ser_databus.tpg.model.MoveHistory;
import com.ser_databus.tpg.model.MyNode;
import com.ser_databus.tpg.model.Node;
import com.ser_databus.tpg.model.OpNode;
import com.ser_databus.tpg.util.Const;
import com.ser_databus.tpg.util.MoveUtil;
import javafx.util.Pair;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.ser_databus.tpg.model.PruneReason.*;
import static com.ser_databus.tpg.util.CalculationUtil.getAverageScore;
import static com.ser_databus.tpg.util.CalculationUtil.normalizeEval;
import static com.ser_databus.tpg.util.Const.*;
import static com.ser_databus.tpg.util.MoveUtil.lichessUciToMove;

@Slf4j
public class PrepEvaluator {
    private final static int LICHESS_OPENING_DEPTH = 25;
    private final static int LICHESS_MERIT_MOVE_FACTOR = 50;
    /**
     * The chance of reaching a position (0.01 = 1%) from which several of my moves are compared. Below it, only the
     * engine's best move is played; at it, two moves are compared, and more for likelier positions
     * (see {@link #MY_MOVES_QUOTA_BASE}).
     */
    private final static double MY_MOVES_QUOTA_REFERENCE_POINT = 0.01;
    /**
     * How fast the number of my candidate moves grows with the chance of reaching the position: every time that chance
     * is multiplied by this base, one more of my moves is compared. With base 2 and reference point 0.01: 2 moves at
     * 1%, 3 at 2%, 4 at 4%, ... Must be greater than 1.
     */
    private final static double MY_MOVES_QUOTA_BASE = 1.7;
    /** Replaced by {@link #useSources} between generations only, so read without locking by the async steps */
    private volatile LichessDataSource lichessDataSource;
    private volatile EvalSource evalSource;
    private volatile LichessPositionSource lichessClient;
    private volatile PositionEvaluator engine;

    private double minNormEval;

    private Node searchTree;

    private LichessRatingSelector lichessRatingSelector;
    private Side side;
    private boolean newGenerationFlag;
    private double logAllBranchingFactor;
    private int opNodes;
    private int myMovesConsidered;
    private int myLeavesCreated;
    private int branchesCut;
    private double minChance;
    private GeneratorSettings settings;
    /** Polled while the prep is generated; when it returns true, generation stops with a CancellationException. */
    private volatile BooleanSupplier cancellationCheck = () -> false;
    /** Receives a short description of the current operation, e.g. "Expanding 1104 nodes at level 16" */
    private volatile Consumer<String> progressListener = message -> {
    };

    public PrepEvaluator(LichessDataSource lichessDataSource, EvalSource evalSource) {
        useSources(lichessDataSource, evalSource);
    }

    public LichessDataSource getLichessDataSource() {
        return lichessDataSource;
    }

    public EvalSource getEvalSource() {
        return evalSource;
    }

    /**
     * Switches to other sources of Lichess statistics and engine evaluations, if they differ from the current ones.
     * Only the source that changed is closed (its cache flushed first) and created anew, so e.g. switching the
     * Lichess data source keeps the running engines. Must not be called while a prep is generated.
     *
     * @throws RuntimeException if a new source couldn't be created; the next call tries to create it again
     */
    public synchronized void useSources(LichessDataSource lichessDataSource, EvalSource evalSource) {
        if (lichessDataSource != this.lichessDataSource || lichessClient == null) {
            closeLichessClient();
            log.info("Lichess data source: {}", lichessDataSource);
            lichessClient = lichessDataSource.create();
            this.lichessDataSource = lichessDataSource;
        }
        if (evalSource != this.evalSource || engine == null) {
            closeEngine();
            log.info("Engine evaluations: {}", evalSource);
            engine = evalSource.create();
            this.evalSource = evalSource;
        }
    }

    private void closeLichessClient() {
        LichessPositionSource old = lichessClient;
        lichessClient = null;
        lichessDataSource = null;
        if (old != null) {
            try {
                old.close();
            } catch (RuntimeException e) {
                log.warn("Couldn't close the Lichess data source", e);
            }
        }
    }

    private void closeEngine() {
        PositionEvaluator old = engine;
        engine = null;
        evalSource = null;
        if (old != null) {
            try {
                old.abandonPendingRequests();
                old.flushCache();
                old.close();
            } catch (RuntimeException e) {
                log.warn("Couldn't close the engine evaluator", e);
            }
        }
    }

    /**
     * Sets the check that is polled during {@link #createIterativePrep}: once it returns true, the generation stops
     * by throwing a {@link CancellationException}.
     */
    public void setCancellationCheck(BooleanSupplier cancellationCheck) {
        this.cancellationCheck = cancellationCheck == null ? () -> false : cancellationCheck;
    }

    /** Receives a short description of the current operation while the prep is generated. */
    public void setProgressListener(Consumer<String> progressListener) {
        this.progressListener = progressListener == null ? message -> {
        } : progressListener;
    }

    private void reportProgress(String message) {
        try {
            progressListener.accept(message);
        } catch (RuntimeException e) {
            log.warn("Progress listener failed", e);
        }
    }

    private void checkCancelled() {
        throwIfCancelled(cancellationCheck);
    }

    /**
     * For the asynchronous steps, which may run after the generation ended: each step captures the check of the
     * generation that started it, so a cancelled generation's steps stop instead of requesting more evaluations.
     */
    private static void throwIfCancelled(BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean()) {
            throw new CancellationException("Prep generation was cancelled");
        }
    }

    private static boolean isCancellation(Throwable ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Drops the evaluations that are still queued but nobody waits for: what a cancelled generation requested.
     * Called when a generation ends.
     */
    public void abandonPendingRequests() {
        PositionEvaluator current = engine;
        if (current != null) {
            current.abandonPendingRequests();
        }
    }

    /**
     * Forgets the last generated tree. The evaluator is shared by all generations, so without this the tree stays in
     * memory after its window is closed, until the next generation replaces it. Called when a generation ends.
     */
    public void releaseTree() {
        searchTree = null;
    }

    @SuppressWarnings("OptionalGetWithoutIsPresent")
    public Node createIterativePrep(String fen, Side side, LichessRatingSelector lichessRatingSelector,
                                    GeneratorSettings settings, double minChance) {
        this.settings = settings;
        this.minChance = minChance;
        Board board = new Board();
        board.loadFromFen(fen);
        if (board.isMated() || board.isStaleMate()) {
            log.info("The position {} is game over", fen);
            return null;
        }
        reportProgress("Evaluating the starting position");
        Optional<LineEval> bestLine = engine.getBestLine(board, settings.getEngineSearchDepth());
        this.side = side;
        if (side == board.getSideToMove()) {
            Move myBestMove = bestLine.get().move;
            Board newBoard = board.clone();
            newBoard.doMove(myBestMove);
            if (newBoard.isMated() || newBoard.isStaleMate()) {
                log.info("The position {} is next to game over", fen);
                return null;
            }
            Optional<LineEval> opBestLine = engine.getBestLine(newBoard, settings.getEngineSearchDepth());
            searchTree = new MyNode(board, MoveHistory.empty(), 1., 0, null);
            searchTree.normEval = normalizeEval(-opBestLine.get().eval);
            searchTree.evalDepth = opBestLine.get().depth;
        } else {
            searchTree = new OpNode(board, MoveHistory.empty(), 1., 0, null);
            searchTree.normEval = normalizeEval(-bestLine.get().eval);
            searchTree.evalDepth = bestLine.get().depth;
            LichessPosition rootPosition = lichessClient.getPosition(fen, lichessRatingSelector);
            searchTree.lichessScore = getAverageScore(rootPosition, side);
        }
        // Just to fix this value, so it doesn't go awry later
        searchTree.correctedNormEval = searchTree.normEval;
        minNormEval = searchTree.normEval - settings.getMaxEvalSetback();
        this.lichessRatingSelector = lichessRatingSelector;
        logAllBranchingFactor = 0;
        opNodes = 0;
        newGenerationFlag = true;

        Side sideToMove = board.getSideToMove();
        for (int treeDepth = 0; newGenerationFlag; treeDepth++) {
            checkCancelled();
            newGenerationFlag = false;
            myMovesConsidered = 0;
            myLeavesCreated = 0;
            branchesCut = 0;
            log.info("minChance = {}, depth = {}", minChance, treeDepth);
            expandLevel(treeDepth);
            if (sideToMove == side) {
                reportProgress("Calculating prep values and pruning at level " + treeDepth);
                calculatePrepTreeValues();
                pruneRecursive(searchTree, searchTree.normEval, treeDepth);
                dropPrunedSubtrees(searchTree);
            }
            if (!newGenerationFlag) {
                log.info("Current prep after {} iterations:", treeDepth + 1);
                printPrepTree(searchTree);
                printAdditionalStats();
                engine.printStats();
            }
            sideToMove = sideToMove.flip();
            engine.flushCache();
            lichessClient.flushCache();
        }
        log.info("Prep generation completed");
        return searchTree;
    }

    /**
     * Expands all leaf nodes at the given level.
     * <ol>
     *     <li>The explored part of the tree is walked sequentially to collect the leaves.</li>
     *     <li>Lichess and Stockfish data for all leaves is requested at once and runs in parallel
     *     (bounded by the engine pool and the Lichess client). The requests are chained without blocking,
     *     so no thread waits per node.</li>
     *     <li>When everything has arrived, the leaves are expanded one by one. This step only reads cached
     *     results, so it's fast, and the tree and the counters are only changed by this thread.</li>
     * </ol>
     */
    private void expandLevel(int level) {
        List<Pair<Node, Double>> leaves = new ArrayList<>();
        collectLevelRecursive(searchTree, 0, level, searchTree.normEval, leaves);
        log.debug("Expanding {} nodes at level {}", leaves.size(), level);
        String operation = String.format("Expanding %d nodes at level %d", leaves.size(), level);
        reportProgress(operation);

        List<CompletableFuture<?>> prefetches = new ArrayList<>();
        // The analyses of my nodes are used by processMyNode as they are, so both see exactly the same choice
        Map<MyNode, CompletableFuture<MyNodeAnalysis>> analyses = new IdentityHashMap<>();
        for (var leaf : leaves) {
            Node node = leaf.getKey();
            CompletableFuture<?> prefetch;
            if (node instanceof MyNode myNode) {
                if (myNode.isPruned || myNode.isStubLeafNode) {
                    prefetch = DONE;
                } else if (isBeyondOpeningDepth(myNode)) {
                    prefetch = prefetchStubLeaf(myNode);
                } else {
                    CompletableFuture<MyNodeAnalysis> analysis = analyzeMyNodeAsync(myNode, leaf.getValue());
                    analyses.put(myNode, analysis);
                    prefetch = analysis;
                }
            } else {
                prefetch = node instanceof OpNode opNode ? prefetchOpNode(opNode) : DONE;
            }
            // A failed prefetch isn't fatal: the expansion below requests the data again and reports the error
            prefetches.add(prefetch.handle((result, ex) -> {
                if (ex != null && !isCancellation(ex)) {
                    log.warn("Prefetch failed for node {}: {}", node, ex.toString());
                }
                return null;
            }));
        }
        awaitAll(prefetches, operation + ": fetching data");
        reportProgress(operation + ": building the tree");

        for (var leaf : leaves) {
            Node node = leaf.getKey();
            if (node instanceof MyNode myNode) {
                processMyNode(myNode, leaf.getValue(), analyses.get(myNode));
            } else if (node instanceof OpNode opNode) {
                processOpNode(opNode);
            }
        }
    }

    private void collectLevelRecursive(Node root, int currentLevel, int level, double normEvalThreshold,
                                       List<Pair<Node, Double>> leaves) {
        if (currentLevel < level) {
            if (root.isPruned) {
                return;
            }
            for (Node childNode : root.moveToNode.values()) {
                double childNormEvalThreshold = normEvalThreshold;
                double childNormEval = childNode.correctedNormEval;
                if (!childNode.moveToNode.isEmpty() && childNormEval > normEvalThreshold) {
                    childNormEvalThreshold = childNormEval;
                }
                collectLevelRecursive(childNode, currentLevel + 1, level, childNormEvalThreshold, leaves);
            }
        } else if (currentLevel == level) {
            leaves.add(new Pair<>(root, normEvalThreshold));
        }
    }

    private static boolean isBeyondOpeningDepth(MyNode myNode) {
        return myNodeBoard(myNode).getMoveCounter() > LICHESS_OPENING_DEPTH;
    }

    /** A new board of the node's position, so it can be used on another thread while other nodes are processed */
    private static Board myNodeBoard(MyNode myNode) {
        return myNode.board();
    }

    /** Requests what makeNodeStubLeaf needs for a node beyond the opening depth. */
    private CompletableFuture<Void> prefetchStubLeaf(MyNode myNode) {
        return CompletableFuture.allOf(lichessClient.getPositionAsync(myNode.fen, lichessRatingSelector),
                engine.getBestLineAsync(myNodeBoard(myNode), settings.getEngineSearchDepth()));
    }

    /** One of my candidate moves, with everything that's needed to decide whether to investigate it. */
    private record Candidate(Move move, int evalDepth, double normEval, LichessPosition positionAfter,
                             double shallowVp) {
    }

    /** An opponent's reply to my candidate move: how often it's played and the engine line after it */
    private record ReplyEval(Board afterReply, long games, CompletableFuture<Optional<LineEval>> line) {
    }

    /**
     * What processMyNode applies to the tree.
     *
     * @param position             Lichess stats of the node's position
     * @param bestNormEval         normalized eval of my best move (the best of the candidates' evals)
     * @param bestEvalDepth        search depth of that eval
     * @param chosen               the moves to investigate further, the most promising first
     * @param candidatesConsidered number of candidate moves that were evaluated
     * @param myViableMoves        soft count of my good moves ({@link #countViableMoves}); 0 if not measured
     */
    private record MyNodeAnalysis(LichessPosition position, double bestNormEval, int bestEvalDepth,
                                  List<Candidate> chosen, int candidatesConsidered, double myViableMoves) {
    }

    /**
     * Chooses my moves to investigate in the node. Only reads data (Lichess positions and engine evals) and doesn't
     * change the tree, so it can run on other threads while other nodes are analyzed.
     * <ol>
     *     <li>If only one move may be chosen, it's the engine's best move: no other candidates are evaluated.</li>
     *     <li>Candidates: the popular Lichess moves plus the engine's best move ({@link #getCandidateMoves}).</li>
     *     <li>All candidates are evaluated ({@link PositionEvaluator#evaluateMovesAsync}). Those that lose more than
     *     the allowed eval setback are dropped.</li>
     *     <li>For each remaining candidate, the shallow VP is calculated: the expected eval after the opponent's
     *     reply, weighted by how often the replies are played ({@link #describeCandidateAsync}).</li>
     *     <li>The moves are chosen by the shallow VP and the shrunk Lichess score ({@link #chooseMyMoves}).</li>
     * </ol>
     */
    private CompletableFuture<MyNodeAnalysis> analyzeMyNodeAsync(MyNode myNode, double normEvalBenchmark) {
        int depth = settings.getEngineSearchDepth();
        int maxChoices = getMaxChoices(myNode.chance);
        double chance = myNode.chance;
        Board board = myNodeBoard(myNode);
        BooleanSupplier isCancelled = cancellationCheck;
        log.trace("Analyzing a position before my move: {}", myNode.moveHistory);
        CompletableFuture<LichessPosition> positionFuture =
                lichessClient.getPositionAsync(myNode.fen, lichessRatingSelector);
        CompletableFuture<Optional<LineEval>> bestLineFuture = engine.getBestLineAsync(board, depth);
        return positionFuture.thenCombine(bestLineFuture, Pair::new).thenCompose(positionAndBestLine -> {
            throwIfCancelled(isCancelled);
            LichessPosition position = positionAndBestLine.getKey();
            Optional<LineEval> bestLine = positionAndBestLine.getValue();
            if (bestLine.isEmpty()) {
                throw new IllegalStateException("Cannot get my best move in position " + myNode.fen);
            }
            Move myBestMove = bestLine.get().move;
            Board afterBestMove = afterMove(board, myBestMove);
            if (afterBestMove.isMated() || afterBestMove.isStaleMate()) {
                double normEval = afterBestMove.isMated() ? 1. : normalizeEval(0.);
                return CompletableFuture.completedFuture(new MyNodeAnalysis(position, normEval,
                        bestLine.get().depth, List.of(), 1, 0.));
            }
            if (maxChoices == 1) {
                return chooseBestMoveOnlyAsync(position, myBestMove, afterBestMove, depth);
            }
            List<LichessMoveStats> myMoveStats = position.getMoves();
            List<Move> candidates = getCandidateMoves(board, myMoveStats, myBestMove);
            return engine.evaluateMovesAsync(board, candidates, depth).thenCompose(lines -> {
                throwIfCancelled(isCancelled);
                LineEval best = null;
                for (LineEval line : lines.values()) {
                    if (best == null || line.eval > best.eval) {
                        best = line;
                    }
                }
                double bestNormEval = normalizeEval(best.eval);
                double myViableMoves = countViableMoves(lines.values(), bestNormEval);
                double normEvalThreshold = Math.max(minNormEval,
                        Math.max(normEvalBenchmark, bestNormEval) - settings.getMaxEvalSetback());
                List<CompletableFuture<Candidate>> candidateFutures = new ArrayList<>();
                for (var entry : lines.entrySet()) {
                    double normEval = normalizeEval(entry.getValue().eval);
                    if (normEval < bestNormEval && normEval < normEvalThreshold) {
                        continue;
                    }
                    candidateFutures.add(describeCandidateAsync(board, entry.getKey(), entry.getValue(), chance));
                }
                int bestEvalDepth = best.depth;
                return allOf(candidateFutures).thenApply(ignored -> {
                    List<Candidate> described = candidateFutures.stream().map(CompletableFuture::join).toList();
                    List<Candidate> chosen = chooseMyMoves(board, myMoveStats, maxChoices, described);
                    return new MyNodeAnalysis(position, bestNormEval, bestEvalDepth, chosen, candidates.size(),
                            myViableMoves);
                });
            });
        });
    }

    /**
     * The analysis of a node where only one move may be chosen: the engine's best move, evaluated as the negated
     * best line after it, and the Lichess stats after it. Nothing else is requested.
     */
    private CompletableFuture<MyNodeAnalysis> chooseBestMoveOnlyAsync(LichessPosition position, Move myBestMove,
                                                                      Board afterBestMove, int depth) {
        CompletableFuture<Optional<LineEval>> opLineFuture = engine.getBestLineAsync(afterBestMove, depth);
        CompletableFuture<LichessPosition> positionAfterFuture =
                lichessClient.getPositionAsync(afterBestMove.getFen(), lichessRatingSelector);
        return opLineFuture.thenCombine(positionAfterFuture, (opLine, positionAfter) -> {
            // The opponent has moves: mate and stalemate after my best move are handled before
            LineEval opBestLine = opLine.orElseThrow(() -> new IllegalStateException(
                    "Cannot get the opponent's best move in position " + afterBestMove.getFen()));
            double normEval = normalizeEval(-opBestLine.eval);
            Candidate candidate = new Candidate(myBestMove, opBestLine.depth, normEval, positionAfter, normEval);
            return new MyNodeAnalysis(position, normEval, opBestLine.depth, List.of(candidate), 1, 0.);
        });
    }

    /**
     * How many good moves I have: sum(exp(-evalLoss / TYPICAL_INACCURACY_NORM_EVAL_LOSS)) over the evaluated candidate
     * moves, where evalLoss is how much normalized eval the move loses compared to the best one. The best move counts
     * as 1, equally good moves as 1 each, inaccuracies as a fraction, mistakes as almost 0. All candidates count,
     * including those that are too bad to be investigated: they add almost nothing anyway.
     */
    private static double countViableMoves(Collection<LineEval> lines, double bestNormEval) {
        double viableMoves = 0.;
        for (LineEval line : lines) {
            double evalLoss = Math.max(0., bestNormEval - normalizeEval(line.eval));
            viableMoves += Math.exp(-evalLoss / TYPICAL_INACCURACY_NORM_EVAL_LOSS);
        }
        return viableMoves;
    }

    /**
     * Gets the Lichess stats after my move and calculates its shallow VP: the expected normalized eval after the
     * opponent's reply. The same way as the VP of an opponent node: the replies that processOpNode will
     * investigate count with their eval and frequency, all other replies (rare or not played at all) count with the
     * eval of my move. If processOpNode won't expand the node at all, the shallow VP is just the eval of my move.
     * The evals after the replies are the ones processOpNode requests anyway if the move is chosen.
     */
    private CompletableFuture<Candidate> describeCandidateAsync(Board board, Move myMove, LineEval line,
                                                               double chance) {
        int depth = settings.getEngineSearchDepth();
        double normEval = normalizeEval(line.eval);
        Board after = afterMove(board, myMove);
        BooleanSupplier isCancelled = cancellationCheck;
        return lichessClient.getPositionAsync(after.getFen(), lichessRatingSelector).thenCompose(positionAfter -> {
            throwIfCancelled(isCancelled);
            List<LichessMoveStats> replies = positionAfter.getMoves();
            long totalGames = positionAfter.getGames();
            if (replies.isEmpty() || !isOpNodeExpandable(chance, replies, totalGames)) {
                return CompletableFuture.completedFuture(
                        new Candidate(myMove, line.depth, normEval, positionAfter, normEval));
            }
            List<ReplyEval> replyEvals = new ArrayList<>();
            for (LichessMoveStats reply : replies) {
                if (isOpMoveIrrelevant(chance * reply.getGames() / totalGames, reply)) {
                    continue;
                }
                Board afterReply = afterMove(after, lichessUciToMove(after, reply.uci));
                replyEvals.add(new ReplyEval(afterReply, reply.getGames(), engine.getBestLineAsync(afterReply, depth)));
            }
            return allOf(replyEvals.stream().map(ReplyEval::line).toList()).thenApply(ignored -> {
                double coveredShare = 0.;
                double weightedNormEval = 0.;
                for (ReplyEval replyEval : replyEvals) {
                    double share = (double) replyEval.games() / totalGames;
                    // No line: I have no legal moves after the reply
                    double replyNormEval = resultOf(replyEval.line()).map(le -> normalizeEval(le.eval))
                            .orElse(replyEval.afterReply().isMated() ? 0. : normalizeEval(0.));
                    weightedNormEval += share * replyNormEval;
                    coveredShare += share;
                }
                double shallowVp = weightedNormEval + Math.max(0., 1. - coveredShare) * normEval;
                return new Candidate(myMove, line.depth, normEval, positionAfter, shallowVp);
            });
        });
    }

    /** Requests everything processOpNode will need. Must request the same positions as processOpNode. */
    private CompletableFuture<Void> prefetchOpNode(OpNode opNode) {
        if (opNode.isPruned) {
            return DONE;
        }
        Board board = opNode.board();
        if (board.getMoveCounter() > LICHESS_OPENING_DEPTH) {
            return DONE;
        }
        int depth = settings.getEngineSearchDepth();
        double chance = opNode.chance;
        log.trace("Prefetching a position before an opponent move: {}", opNode.moveHistory);
        BooleanSupplier isCancelled = cancellationCheck;
        return lichessClient.getPositionAsync(board.getFen(), lichessRatingSelector).thenCompose(opPosition -> {
            throwIfCancelled(isCancelled);
            List<LichessMoveStats> opMoveStats = opPosition.getMoves();
            long totalGames = opPosition.getGames();
            if (!isOpNodeExpandable(chance, opMoveStats, totalGames)) {
                return DONE;
            }
            Move firstMove = lichessUciToMove(board, opMoveStats.get(0).uci);
            boolean isDeepLine = isDeepLine(opMoveStats, totalGames);
            List<CompletableFuture<?>> requests = new ArrayList<>();
            Set<Move> relevantMoves = new HashSet<>();
            for (LichessMoveStats opMoveStat : opMoveStats) {
                double moveChance = chance * opMoveStat.getGames() / totalGames;
                if (isOpMoveIrrelevant(moveChance, opMoveStat)) {
                    continue;
                }
                Move opMove = lichessUciToMove(board, opMoveStat.uci);
                relevantMoves.add(opMove);
                Board after = afterMove(board, opMove);

                requests.add(engine.getBestLineAsync(after, depth));
                if (isStubLeafMove(moveChance, isDeepLine, opMove, firstMove)) {
                    // makeNodeStubLeaf needs the Lichess stats of the position after the move
                    if (log.isTraceEnabled()) {
                        log.trace("Prefetching a position after an opponent move: {} + {}", opNode.moveHistory,
                                MoveUtil.moveToSan(board, opMove));
                    }
                    requests.add(lichessClient.getPositionAsync(after.getFen(), lichessRatingSelector));
                }
            }
            // If the players don't play the engine's best move (enough), processOpNode makes a stub leaf of it, which
            // needs the eval and the Lichess stats of the position after it. Without this, those searches ran one
            // node at a time while the tree was built, with the other engines idle.
            requests.add(engine.getBestLineAsync(board, depth).thenCompose(bestLine -> {
                throwIfCancelled(isCancelled);
                if (bestLine.isEmpty() || relevantMoves.contains(bestLine.get().move)) {
                    return DONE;
                }
                Board afterBestMove = afterMove(board, bestLine.get().move);
                return CompletableFuture.allOf(engine.getBestLineAsync(afterBestMove, depth),
                        lichessClient.getPositionAsync(afterBestMove.getFen(), lichessRatingSelector));
            }));
            return allOf(requests);
        });
    }

    private List<Move> getMovesToConsider(Board board, List<LichessMoveStats> myMoveStats) {
        if (myMoveStats.isEmpty()) {
            return new ArrayList<>();
        }
        long lichessGamesThreshold = myMoveStats.get(0).getGames() / LICHESS_MERIT_MOVE_FACTOR;
        List<Move> movesToConsider = new ArrayList<>();
        for (LichessMoveStats moveStats : myMoveStats) {
            if (moveStats.getGames() >= lichessGamesThreshold) {
                movesToConsider.add(lichessUciToMove(board, moveStats.uci));
            }
        }
        return movesToConsider;
    }

    private boolean isOpNodeExpandable(double chance, List<LichessMoveStats> opMoveStats, long totalGames) {
        if (totalGames < MIN_GAMES_FOR_OP_MOVE) {
            return false;
        }
        double firstMoveChance = chance * opMoveStats.get(0).getGames() / totalGames;
        return firstMoveChance >= getDeepestLineChance()
                && (firstMoveChance >= minChance || isDeepLine(opMoveStats, totalGames));
    }

    private static boolean isDeepLine(List<LichessMoveStats> opMoveStats, long totalGames) {
        return opMoveStats.get(0).getGames() * 2 >= totalGames;
    }

    private boolean isOpMoveIrrelevant(double moveChance, LichessMoveStats opMoveStat) {
        return moveChance < getDeepestLineChance() || opMoveStat.getGames() < MIN_GAMES_FOR_OP_MOVE;
    }

    private boolean isStubLeafMove(double moveChance, boolean isDeepLine, Move opMove, Move firstMove) {
        return moveChance < minChance && (!isDeepLine || !opMove.equals(firstMove));
    }

    private static Board afterMove(Board board, Move move) {
        Board b = board.clone();
        b.doMove(move);
        return b;
    }

    private void processMyNode(MyNode myNode, double normEvalBenchmark, CompletableFuture<MyNodeAnalysis> prefetched) {
        if (myNode.isPruned || myNode.isStubLeafNode) {
            return;
        }
        if (log.isTraceEnabled()) {
            log.trace("Process my node {}, chance={}%", myNode.moveHistory, String.format("%.1f", myNode.chance * 100.));
        }
        Board board = myNode.board();
        String fen = myNode.fen;
        if (board.getMoveCounter() > LICHESS_OPENING_DEPTH) {
            makeNodeStubLeaf(myNode, null);
            newGenerationFlag = true;
            myLeavesCreated++;
            return;
        }

        MyNodeAnalysis analysis;
        if (prefetched != null && !prefetched.isCompletedExceptionally()) {
            analysis = await(prefetched);
        } else {
            // The prefetch failed (or there was none): try again, this time the error stops the generation
            analysis = await(analyzeMyNodeAsync(myNode, normEvalBenchmark));
        }
        myNode.lichessScore = getAverageScore(analysis.position(), side);
        myNode.normEval = analysis.bestNormEval();
        myNode.evalDepth = analysis.bestEvalDepth();
        myNode.myViableMoves = analysis.myViableMoves();
        for (Candidate candidate : analysis.chosen()) {
            Move myMove = candidate.move();
            OpNode child = myNode.createChild(myMove);
            child.lichessScore = getAverageScore(candidate.positionAfter(), side);
            child.normEval = candidate.normEval();
            child.evalDepth = candidate.evalDepth();

            log.trace("Move {} with normEval {} and shallow VP {} is chosen for further investigation", myMove,
                    String.format("%.1f", child.normEval * 100.), String.format("%.1f", candidate.shallowVp() * 100.));
            newGenerationFlag = true;
            myLeavesCreated++;
        }
        myMovesConsidered += analysis.candidatesConsidered();
    }

    /** My moves to evaluate when more than one may be chosen: the popular Lichess moves plus the engine's best move. */
    private List<Move> getCandidateMoves(Board board, List<LichessMoveStats> myMoveStats, Move myBestMove) {
        List<Move> candidates = getMovesToConsider(board, myMoveStats);
        if (!candidates.contains(myBestMove)) {
            candidates.add(myBestMove);
        }
        return candidates;
    }

    /**
     * Chooses which of the candidate moves (that passed the eval check) get investigated further:
     * <ol>
     *     <li>the move with the best shallow VP;</li>
     *     <li>if more than one move may be chosen, the move with the best shrunk Lichess score
     *     ({@link #getBestScoreCandidate}), as the practical choice;</li>
     *     <li>then the rest by the shallow VP, up to maxChoices.</li>
     * </ol>
     */
    private List<Candidate> chooseMyMoves(Board board, List<LichessMoveStats> myMoveStats, int maxChoices,
                                          List<Candidate> candidates) {
        List<Candidate> byShallowVp = candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::shallowVp)
                        .thenComparingDouble(Candidate::normEval)
                        .reversed())
                .toList();
        List<Candidate> chosen = new ArrayList<>();
        if (byShallowVp.isEmpty()) {
            return chosen;
        }
        chosen.add(byShallowVp.get(0));
        if (maxChoices > 1) {
            Candidate bestScoreCandidate = getBestScoreCandidate(board, myMoveStats, candidates);
            if (bestScoreCandidate != null && !containsMove(chosen, bestScoreCandidate)) {
                chosen.add(bestScoreCandidate);
            }
            for (Candidate candidate : byShallowVp) {
                if (chosen.size() >= maxChoices) {
                    break;
                }
                if (!containsMove(chosen, candidate)) {
                    chosen.add(candidate);
                }
            }
        }
        return chosen;
    }

    private static boolean containsMove(List<Candidate> candidates, Candidate candidate) {
        return candidates.stream().anyMatch(c -> c.move().equals(candidate.move()));
    }

    /**
     * The candidate with the best Lichess score, shrunk toward the engine's expectation (its normalized eval), so
     * that a lucky score from a few games doesn't beat a solid score from thousands. Only moves with more than
     * {@link com.ser_databus.tpg.util.Const#MIN_GAMES_FOR_VALID_SCORE} games count; null if there are none.
     */
    private Candidate getBestScoreCandidate(Board board, List<LichessMoveStats> myMoveStats,
                                            List<Candidate> candidates) {
        Map<Move, LichessMoveStats> statsByMove = new HashMap<>();
        for (LichessMoveStats moveStats : myMoveStats) {
            statsByMove.putIfAbsent(lichessUciToMove(board, moveStats.uci), moveStats);
        }
        Candidate bestScoreCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate candidate : candidates) {
            LichessMoveStats moveStats = statsByMove.get(candidate.move());
            if (moveStats == null || moveStats.getGames() <= MIN_GAMES_FOR_VALID_SCORE) {
                continue;
            }
            double score = getShrunkScore(moveStats, candidate.normEval());
            if (score > bestScore) {
                bestScoreCandidate = candidate;
                bestScore = score;
            }
        }
        return bestScoreCandidate;
    }

    /** My Lichess score with the move, shrunk toward the prior score by {@link Const#SCORE_PRIOR_GAMES} games. */
    private double getShrunkScore(LichessMoveStats moveStats, double priorScore) {
        double games = moveStats.getGames();
        return (getAverageScore(moveStats, side) * games + priorScore * SCORE_PRIOR_GAMES)
                / (games + SCORE_PRIOR_GAMES);
    }

    private void processOpNode(OpNode opNode) {
        if (opNode.isPruned) {
            return;
        }
        Board board = opNode.board();
        if (board.getMoveCounter() > LICHESS_OPENING_DEPTH) {
            return;
        }
        log.trace("Process opponent node {}, chance={}%", opNode.moveHistory, String.format("%.1f", opNode.chance * 100.));
        String fen = board.getFen();
        log.trace("ProcessOpNode: getting op position: {}", opNode.moveHistory);
        LichessPosition opPosition = lichessClient.getPosition(fen, lichessRatingSelector);
        List<LichessMoveStats> opMoveStats = opPosition.getMoves();
        long totalGames = opPosition.getGames();
        if (totalGames < MIN_GAMES_FOR_OP_MOVE) {
            return;
        }
        Move firstMove = lichessUciToMove(board, opMoveStats.get(0).uci);
        long firstMoveGames = opMoveStats.get(0).getGames();
        boolean isDeepLine = isDeepLine(opMoveStats, totalGames);
        if (opNode.chance * firstMoveGames / totalGames < getDeepestLineChance()) {
            log.trace("processOpNode {}: so wide that even the first line is out of deep line, skip", opNode.moveHistory);
            return;
        } else if (opNode.chance * firstMoveGames / totalGames < minChance && !isDeepLine) {
            log.trace("processOpNode {}: too small to consider, skip", opNode.fen);
            return;
        }

        int depth = settings.getEngineSearchDepth();
        // Submit all engine requests of this node at once and wait for all of them together.
        // The position after the opponent's best move is evaluated too: the next processMyNode needs it anyway.
        CompletableFuture<Optional<LineEval>> bestLineFuture = engine.getBestLineAsync(board, depth);
        Map<Move, CompletableFuture<Optional<LineEval>>> lineAfterOpMoveFutures = new HashMap<>();
        for (LichessMoveStats opMoveStat : opMoveStats) {
            double moveChance = opNode.chance * opMoveStat.getGames() / totalGames;
            if (isOpMoveIrrelevant(moveChance, opMoveStat)) {
                continue;
            }
            Move opMove = lichessUciToMove(board, opMoveStat.uci);
            lineAfterOpMoveFutures.put(opMove, engine.getBestLineAsync(afterMove(board, opMove), depth));
        }
        List<CompletableFuture<?>> allFutures = new ArrayList<>(lineAfterOpMoveFutures.values());
        allFutures.add(bestLineFuture);
        awaitAll(allFutures);

        Optional<LineEval> bestLine = resultOf(bestLineFuture);
        if (bestLine.isEmpty()) {
            log.trace("This looks like a mate! Skip");
            return;
        }
        Move bestOpMove = bestLine.get().move;
        double bestOpEval = bestLine.get().eval;
        double branchingFactor = 1.;
        Set<Move> actualOpMoves = new HashSet<>();
        for (LichessMoveStats opMoveStat : opMoveStats) {
            Move opMove = lichessUciToMove(board, opMoveStat.uci);
            log.trace("Analyzing opponent move " + opMove);
            double moveChance = opNode.chance * opMoveStat.getGames() / totalGames;
            if (isOpMoveIrrelevant(moveChance, opMoveStat)) {
                log.trace("Opponent move {} is too rare to encounter. Pass", opMove);
                continue;
            }
            // eval is not set here. It should be set during the next processMyNode phase
            MyNode childNode = opNode.createChild(opMove, moveChance);
            actualOpMoves.add(opMove);
            if (!opMove.equals(bestOpMove)) {
                Optional<LineEval> lineAfterOpMove = resultOf(lineAfterOpMoveFutures.get(opMove));
                double eval = -lineAfterOpMove.get().eval;
                branchingFactor += Math.min(1., Math.exp((-bestOpEval + eval) / TYPICAL_INACCURACY_EVAL_LOSS));
            }
            log.trace("Opponent move " + opMove + " has {}% chance to occur", String.format("%.1f", moveChance * 100.));
            newGenerationFlag = true;
            if (isStubLeafMove(moveChance, isDeepLine, opMove, firstMove)) {
                makeNodeStubLeaf(childNode, opMoveStat);
            }
        }
        if (!actualOpMoves.contains(bestOpMove)) {
            log.trace("The best opponent move " + bestOpMove + " is not present. Creating a stub leaf");
            LichessMoveStats moveStats = getMoveStatsByMove(opMoveStats, board, bestOpMove);
            double moveChance = moveStats == null ? 0. : opNode.chance * moveStats.getGames() / totalGames;
            MyNode childNode = opNode.createChild(bestOpMove, moveChance);
            makeNodeStubLeaf(childNode, moveStats);
            newGenerationFlag = true;
        }
        opNode.branchingFactor = branchingFactor;
        logAllBranchingFactor += Math.log(branchingFactor);
        opNodes++;
    }

    private LichessMoveStats getMoveStatsByMove(List<LichessMoveStats> opMoveStats, Board board, Move move) {
        for (LichessMoveStats opMoveStat : opMoveStats) {
            Move opMove = lichessUciToMove(board, opMoveStat.uci);
            if (move.equals(opMove)) {
                return opMoveStat;
            }
        }
        return null;
    }

    private void makeNodeStubLeaf(MyNode myNode, LichessMoveStats opMoveStat) {
        Board board = myNode.board();
        Optional<LineEval> lineEval = engine.getBestLine(board, settings.getEngineSearchDepth());
        if (lineEval.isEmpty()) {
            throw new IllegalStateException("Cannot get my last move in position " + myNode.fen);
        }
        Move bestMove = lineEval.get().move;
        String bestMoveUci = bestMove.toString();
        myNode.normEval = normalizeEval(lineEval.get().eval);
        myNode.evalDepth = lineEval.get().depth;
        myNode.correctedNormEval = myNode.normEval;
        myNode.vp = myNode.normEval;
        myNode.isStubLeafNode = true;
        myNode.chosenPrepMove = bestMove;
        log.trace("makeNodeStubLeaf: getting my position: {}", myNode.moveHistory);
        LichessPosition lichessPosition = lichessClient.getPosition(myNode.fen, lichessRatingSelector);
        LichessMoveStats myMoveStats = lichessPosition.getMoves()
                .stream()
                .filter(moveStats -> bestMoveUci.equals(moveStats.uci))
                .findAny()
                .orElse(opMoveStat);
        myNode.lichessScore = getAverageScore(myMoveStats, side);
        myNode.lichessVp = myNode.lichessScore;
        myNode.moveHistory = myNode.moveHistory.prolong(board, bestMove);
        myLeavesCreated++;
        log.trace("Created stub leaf node {}", myNode);
    }

    private void calculatePrepTreeValues() {
        log.debug("Calculating prep tree values...");
        calculateNodeValuesRecursive(searchTree);
    }

    private void calculateNodeValuesRecursive(Node node) {
        if (node.isPruned) {
            return;
        }
        node.leaves = 0;
        node.branches = 0;
        node.thicknessSum = 0.;
        node.thicknessWeight = 0.;
        for (Node child : node.moveToNode.values()) {
            calculateNodeValuesRecursive(child);
        }
        if (node instanceof MyNode myNode) {
            if (myNode.isStubLeafNode) {
                myNode.leaves++;
                return;
            }

            double maxVp = 0.;
            double maxNormEval = 0.;
            Node chosenChild = null;
            int branches = 0;
            int leaves = 0;
            for (var childEntry : node.moveToNode.entrySet()) {
                Node child = childEntry.getValue();
                if (child.isPruned) {
                    continue;
                }
                leaves += child.leaves;
                branches += child.branches;
                if (child.vp > maxVp) {
                    maxVp = child.vp;
                    chosenChild = child;
                    myNode.chosenPrepMove = childEntry.getKey();
                    myNode.lichessVp = child.lichessVp;
                }
                if (child.correctedNormEval > maxNormEval) {
                    maxNormEval = child.correctedNormEval;
                }
            }
            // Thickness: this decision (if it was measured) plus the decisions along the line of the chosen move
            if (myNode.myViableMoves > 0.) {
                myNode.thicknessSum = myNode.chance * log2(Math.max(1., myNode.myViableMoves));
                myNode.thicknessWeight = myNode.chance;
            }
            if (chosenChild != null) {
                myNode.thicknessSum += chosenChild.thicknessSum;
                myNode.thicknessWeight += chosenChild.thicknessWeight;
            }
            if (maxVp > 0.) {
                myNode.vp = maxVp;
                myNode.correctedNormEval = maxNormEval;
                myNode.leaves = leaves;
                myNode.branches = branches + 1; // +1 because the node itself is a branch
            } else {
                myNode.vp = myNode.normEval;
                myNode.correctedNormEval = myNode.normEval;
                myNode.leaves = 1;
            }
        } else if (node instanceof OpNode opNode) {
            if (opNode.moveToNode.isEmpty()) {
                log.trace("calculateNodeValuesRecursive: getting empty op position: {}", opNode.moveHistory);
                LichessPosition lichessPosition = lichessClient.getPosition(opNode.fen, lichessRatingSelector);
                opNode.lichessVp = getAverageScore(lichessPosition, side);
                opNode.vp = opNode.normEval;
                opNode.correctedNormEval = opNode.normEval;
                opNode.leaves = 1;
                return;
            }

            double chanceSum = 0.;
            double weightedVPSum = 0.;
            double weightedLichessVPSum = 0.;
            double minNormEval = 1.;
            int leaves = 0;
            int branches = 0;
            for (var childEntry : node.moveToNode.entrySet()) {
                Node child = childEntry.getValue();
                if (child.isPruned) {
                    continue;
                }
                leaves += child.leaves;
                branches += child.branches;
                chanceSum += child.chance;
                weightedVPSum += child.vp * child.chance;
                // The children's sums are already weighted by their (absolute) chances
                opNode.thicknessSum += child.thicknessSum;
                opNode.thicknessWeight += child.thicknessWeight;
                weightedLichessVPSum += child.lichessVp * child.chance;
                if (child.correctedNormEval < minNormEval) {
                    minNormEval = child.correctedNormEval;
                }
            }
            double offbeatContinuationChance = opNode.chance - chanceSum;
            double offbeatContinuationVPWeight = opNode.normEval * offbeatContinuationChance;
            double offbeatContinuationLichessVpWeight = opNode.lichessScore * offbeatContinuationChance;
            opNode.vp = (weightedVPSum + offbeatContinuationVPWeight) / opNode.chance;
            opNode.lichessVp = (weightedLichessVPSum + offbeatContinuationLichessVpWeight) / opNode.chance;
            opNode.correctedNormEval = minNormEval;
            opNode.branches = branches;
            opNode.leaves = leaves;
        }
    }

    private void pruneRecursive(Node node, double normEvalBenchmark, int treeHeight) {
        if (node.isPruned) {
            return;
        }
        for (Node child : node.moveToNode.values()) {
            double childNormEvalBenchmark = Math.max(child.correctedNormEval, normEvalBenchmark);
            pruneRecursive(child, childNormEvalBenchmark, treeHeight - 1);
        }
        if (node instanceof MyNode myNode) {
            if (myNode.isPruned) {
                return;
            }
            if (myNode.isStubLeafNode) {
                // TODO: If a stub leaf node gets pruned then its parent's evaluation was incorrect.
                //       We should get back and re-evaluate the parent. But it's a very rare occasion in the opening
                if (myNode.correctedNormEval < normEvalBenchmark - settings.getMaxEvalSetback()) {
                    log.trace("Stub leaf node {} was pruned because of a subsequent eval check. normEval={}, normEvalBenchmark={}",
                            myNode, String.format("%.1f", myNode.correctedNormEval * 100.),
                            String.format("%.1f", normEvalBenchmark * 100.));
                    myNode.prune(SUBPAR_EVAL_SUBSEQUENT);
                    branchesCut++;
                    pruneWithParent(myNode.parent);
                    return;
                }
            }
            if (myNode.moveToNode.isEmpty()) {
                return;
            }
            // Only the children that are still in the prep count: a pruned child must not take the protection
            // of the best eval away from the surviving ones
            double maxNormEval = 0.;
            for (Node child : myNode.moveToNode.values()) {
                if (child.isPruned) {
                    continue;
                }
                if (child.correctedNormEval > maxNormEval) {
                    maxNormEval = child.correctedNormEval;
                }
            }
            // Pass 1: the eval check. The child with the best eval is never pruned here, so one child survives
            boolean evalPruned = false;
            for (Node child : myNode.moveToNode.values()) {
                if (child.isPruned || child.correctedNormEval >= maxNormEval) {
                    continue;
                }
                if (child.correctedNormEval < normEvalBenchmark - settings.getMaxEvalSetback()) {
                    log.debug("Branch {} was pruned because of a subsequent eval check. normEval={}, normEvalBenchmark={}",
                            child, String.format("%.1f", child.correctedNormEval * 100.),
                            String.format("%.1f", normEvalBenchmark * 100.));
                    child.prune(SUBPAR_EVAL_SUBSEQUENT);
                    branchesCut++;
                    evalPruned = true;
                }
            }
            if (evalPruned) {
                pruneWithParent(myNode);
            }
            // Pass 2: the VP check among the children that survived the eval check. A child can only be pruned in
            // favor of a surviving rival. The VPs are compared with the thickness penalty:
            // the thicker line of the two must have a better VP to stay equal (see getThicknessKoef).
            childCycle:
            for (Node child : myNode.moveToNode.values()) {
                if (child.isPruned) {
                    continue;
                }
                for (Node otherChild : myNode.moveToNode.values()) {
                    if (otherChild.isPruned || child == otherChild) {
                        continue;
                    }
                    double thicknessKoef = getThicknessKoef(child, otherChild);
                    if (treeHeight > 0 && otherChild.vp - child.vp >= thicknessKoef * settings.getVpMarginForPruning()) {
                        if (otherChild.lichessVp >= child.lichessVp + settings.getLichessScoreMarginForPruning()) {
                            branchesCut++;
                            log.debug("Branch {} was pruned because rival branch {} has better score. VP: {} vs. {}, thickness koef: {}, lichess VP: {} vs. {}",
                                    child, otherChild, String.format("%.1f", child.vp * 100.),
                                    String.format("%.1f", otherChild.vp * 100.),
                                    String.format("%.2f", thicknessKoef),
                                    String.format("%.1f", child.lichessVp * 100.),
                                    String.format("%.1f", otherChild.lichessVp * 100.));
                            child.prune(SUBPAR_BOTH_VP_AND_LICHESS_SCORE);
                            continue childCycle;
                        }
                    }
                }
            }
        }
    }

    /**
     * Pruning is final, and nothing reads below a pruned node any more: only the pruned node itself stays, with its
     * values and the reason (the move selector shows them). Frees the memory of the abandoned subtrees. Done after
     * the pruning pass, because pruning a node's parent may happen while the parent's children are iterated.
     */
    private static void dropPrunedSubtrees(Node node) {
        if (node.isPruned) {
            node.moveToNode.clear();
            return;
        }
        for (Node child : node.moveToNode.values()) {
            dropPrunedSubtrees(child);
        }
    }

    private void pruneWithParent(Node node) {
        if (node instanceof OpNode opNode) {
            log.trace("Branch {} was pruned because all of its children were pruned", node);
            opNode.prune(SUBPAR_EVAL_CHAIN);
            branchesCut++;
            pruneWithParent(opNode.parent);
        } else if (node instanceof MyNode myNode) {
            if (myNode.moveToNode.values().stream().allMatch(child -> child.isPruned)) {
                myNode.prune(SUBPAR_EVAL_CHAIN);
                if (myNode.parent == null) {
                    throw new IllegalStateException("Pruned the entire tree because of engine eval fluctuations!");
                } else {
                    pruneWithParent(myNode.parent);
                }
            }
        }
    }

    private void printAdditionalStats() {
        log.debug("Branches: {}, leaves: {}", searchTree.branches, searchTree.leaves);
        log.trace("Branches cut: {}", branchesCut);
        if (opNodes > 0) {
            double averageBranchingFactor = Math.exp(logAllBranchingFactor / opNodes);
            log.trace("Average branching factor for the opponent: {}", String.format("%.2f", averageBranchingFactor));
        }
    }

    private void printPrepTree(Node node) {
        if (node instanceof MyNode myNode) {
            if (myNode.isStubLeafNode || myNode.moveToNode.isEmpty()) {
                log.info(myNode.toString());
                return;
            }
            Move chosenMove = myNode.chosenPrepMove;
            printPrepTree(myNode.moveToNode.get(chosenMove));
        } else if (node instanceof OpNode opNode) {
            log.info(opNode.toString());
            for (var entry : opNode.getSortedMoves()) {
                MyNode child = (MyNode) entry.getValue();
                printPrepTree(child);
            }
        }
    }

    private static final CompletableFuture<Void> DONE = CompletableFuture.completedFuture(null);

    private static CompletableFuture<Void> allOf(Collection<? extends CompletableFuture<?>> futures) {
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]));
    }

    /** Blocks until all the given requests are finished. Stops waiting if the generation is cancelled. */
    private void awaitAll(Collection<CompletableFuture<?>> futures) {
        awaitAll(futures, null);
    }

    /**
     * Blocks until all the given requests are finished. Stops waiting if the generation is cancelled.
     *
     * @param operation if not null, the progress ("operation: 530/1104 ready") is reported while waiting
     */
    private void awaitAll(Collection<CompletableFuture<?>> futures, String operation) {
        CompletableFuture<Void> all = allOf(futures);
        try {
            while (true) {
                try {
                    all.get(200, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException ex) {
                    checkCancelled();
                    if (operation != null) {
                        long done = futures.stream().filter(CompletableFuture::isDone).count();
                        reportProgress(operation + " (" + done + "/" + futures.size() + " ready)");
                    }
                }
            }
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Request failed", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Prep generation was interrupted");
        }
    }

    /** Waits for the request like {@link #awaitAll} (stops if the generation is cancelled) and returns its result. */
    private <T> T await(CompletableFuture<T> future) {
        awaitAll(List.<CompletableFuture<?>>of(future));
        return future.join();
    }

    /**
     * Result of an engine request. Doesn't wait if the request was covered by {@link #awaitAll}; otherwise
     * (a request that depended on another result) waits for this single request.
     */
    private static Optional<LineEval> resultOf(CompletableFuture<Optional<LineEval>> future) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            throw new IllegalStateException("Stockfish evaluation failed", ex.getCause());
        }
    }

    private double getDeepestLineChance() {
        return minChance / settings.getDeepLineFactor();
    }

    private int getMaxChoices(double chance) {
        return chance < MY_MOVES_QUOTA_REFERENCE_POINT ? 1 :
                2 + (int) (Math.log(chance / MY_MOVES_QUOTA_REFERENCE_POINT) / Math.log(MY_MOVES_QUOTA_BASE));
    }

    private double getThicknessKoef(Node child, Node rival) {
        if (!child.isThicknessKnown() || !rival.isThicknessKnown()) {
            return 1.;
        }
        // 2^thickness = average number of my good moves per decision (>= 1)
        double koef = Math.pow(2., rival.getThickness() - child.getThickness());
        return Math.max(Math.min(koef, 1.), 0.25);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.);
    }
}
