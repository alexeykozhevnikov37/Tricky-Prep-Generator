package com.ser_databus.tpg.prep;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.evaluate.LineEval;
import com.ser_databus.tpg.evaluate.StockfishClient;
import com.ser_databus.tpg.lichess.*;
import com.ser_databus.tpg.prep.tree.*;
import javafx.util.Pair;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static com.ser_databus.tpg.prep.tree.PruneReason.*;
import static com.ser_databus.tpg.util.CalculationUtil.getAverageScore;
import static com.ser_databus.tpg.util.CalculationUtil.normalizeEval;
import static com.ser_databus.tpg.util.Const.*;
import static com.ser_databus.tpg.util.MoveUtil.lichessUciToMove;

@Slf4j
public class PrepEvaluator {
    private final static int LICHESS_OPENING_DEPTH = 25;
    private final static int LICHESS_MERIT_MOVE_FACTOR = 50;
    private final LichessClient lichessClient = new LichessClient();
    private final StockfishClient stockfishClient = new StockfishClient();

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
        Optional<LineEval> bestLine = stockfishClient.getBestLine(board, settings.getEngineSearchDepth());
        this.side = side;
        if (side == board.getSideToMove()) {
            Move myBestMove = bestLine.get().move;
            Board newBoard = board.clone();
            newBoard.doMove(myBestMove);
            if (newBoard.isMated() || newBoard.isStaleMate()) {
                log.info("The position {} is next to game over", fen);
                return null;
            }
            Optional<LineEval> opBestLine = stockfishClient.getBestLine(newBoard, settings.getEngineSearchDepth());
            searchTree = new MyNode(board, new MoveHistory(board), 1., 0, null);
            searchTree.normEval = normalizeEval(-opBestLine.get().eval);
        } else {
            searchTree = new OpNode(board, new MoveHistory(board), 1., 0, null);
            searchTree.normEval = normalizeEval(-bestLine.get().eval);
        }
        minNormEval = searchTree.normEval - settings.getMaxEvalSetback();
        this.lichessRatingSelector = lichessRatingSelector;
        logAllBranchingFactor = 0;
        opNodes = 0;
        newGenerationFlag = true;

        Side sideToMove = board.getSideToMove();
        for (int treeDepth = 0; newGenerationFlag; treeDepth++) {
            newGenerationFlag = false;
            myMovesConsidered = 0;
            myLeavesCreated = 0;
            branchesCut = 0;
            processLevelRecursive(searchTree, 0, treeDepth, searchTree.normEval);
            if (sideToMove == side) {
                calculatePrepTreeValues();
                pruneRecursive(searchTree, searchTree.normEval, treeDepth);
                choosePrep(searchTree);
                log.info("Current prep after {} iterations:", treeDepth + 1);
                printPrepTree(searchTree);
                printAdditionalStats();
                stockfishClient.printStats();
            }
            sideToMove = sideToMove.flip();
        }
        log.info("Prep generation completed");
        return searchTree;
    }

    private void processLevelRecursive(Node root, int currentLevel, int level, double normEvalThreshold) {
        if (currentLevel < level) {
            if (root.isPruned) {
                return;
            }
            for (Node childNode : root.moveToNode.values()) {
                double childNormEvalThreshold = normEvalThreshold;
                double childNormEval = childNode.normEval;
                if (!childNode.moveToNode.isEmpty() && childNormEval > normEvalThreshold) {
                    childNormEvalThreshold = childNormEval;
                }
                processLevelRecursive(childNode, currentLevel + 1, level, childNormEvalThreshold);
            }
        } else if (currentLevel == level) {
            if (root instanceof MyNode) {
                processMyNode((MyNode) root, normEvalThreshold);
            } else if (root instanceof OpNode) {
                processOpNode((OpNode) root);
            }
        }
    }

    private void processMyNode(MyNode myNode, double normEvalBenchmark) {
        if (myNode.isPruned || myNode.isStubLeafNode) {
            return;
        }
        String path = myNode.moveHistory.toString();
        log.debug("Process my node {}, chance={}%", path, String.format("%.1f", myNode.chance * 100.));
        Board board = myNode.board;
        String fen = myNode.fen;
        board.loadFromFen(fen);

        LichessPosition myPosition = lichessClient.getPosition(fen, lichessRatingSelector);
        List<LichessMoveStats> myMoveStats = myPosition.getMoves();
        long lichessGamesThreshold = myMoveStats.get(0).getGames() / LICHESS_MERIT_MOVE_FACTOR;
        List<Move> movesToConsider = new ArrayList<>();
        for (LichessMoveStats moveStats : myMoveStats) {
            if (moveStats.getGames() >= lichessGamesThreshold) {
                Move move = lichessUciToMove(board, moveStats.uci);
                movesToConsider.add(move);
            }
        }

        Optional<LineEval> bestLine = stockfishClient.getBestLine(board, settings.getEngineSearchDepth());
        if (bestLine.isEmpty()) {
            throw new IllegalStateException("Cannot get my best move in position " + myNode.fen);
        }
        Move myBestMove = bestLine.get().move;
        Board newBoard = board.clone();
        newBoard.doMove(myBestMove);
        Optional<LineEval> bestOpLine = stockfishClient.getBestLine(newBoard, settings.getEngineSearchDepth());
        if (bestOpLine.isEmpty()) {
            myNode.normEval = 1.;
            return;
        }
        double myBestEval = -bestOpLine.get().eval;
        double myBestNormEval = normalizeEval(myBestEval);
        myNode.normEval = myBestNormEval;
        if (myBestNormEval > normEvalBenchmark) {
            normEvalBenchmark = myBestNormEval;
        }
        if (!movesToConsider.contains(myBestMove)) {
            movesToConsider = new ArrayList<>(movesToConsider);
            movesToConsider.add(myBestMove);
        }
        int maxChoices = getMaxChoices(myNode.chance);
        List<Pair<Move, Double>> chosenMoves = new ArrayList<>();
        if (maxChoices == 1) {
            chosenMoves.add(new Pair<>(myBestMove, myBestEval));
        } else {
            Map<Move, Double> candidateMoves = new HashMap<>();
            double normEvalThreshold = Math.max(minNormEval, normEvalBenchmark - settings.getMaxEvalSetback());
            for (Move myMove : movesToConsider) {
                log.trace("Analyzing my move " + myMove);
                Board b = board.clone();
                b.doMove(myMove);
                Optional<LineEval> stockfishLine = stockfishClient.getBestLine(b, settings.getEngineSearchDepth());
                double myEval = stockfishLine.map(lineEval -> -lineEval.eval).orElse(VICTORY_SCORE);
                double myNormEval = normalizeEval(myEval);
                if (myNormEval < myBestNormEval && myNormEval < normEvalThreshold) {
                    log.debug("Move {} is discarded because normEval {} is lower than threshold {}", myMove,
                            String.format("%.1f", myNormEval * 100.), String.format("%.1f", normEvalThreshold * 100.));
                    continue;
                }
                candidateMoves.put(myMove, myEval);
            }
            chosenMoves = getChosenMoves(board, maxChoices, myMoveStats, candidateMoves);
        }
        for (var pair : chosenMoves) {
            Move myMove = pair.getKey();
            OpNode child = myNode.createChild(myMove);
            // Load a position from lichess proactively, so that calculateNodeValuesRecursive goes fast
            lichessClient.getPosition(child.fen, lichessRatingSelector);

            child.normEval = normalizeEval(pair.getValue());
            log.debug("Move {} with normEval {} is chosen for further investigation", myMove,
                    String.format("%.1f", child.normEval * 100.));
            newGenerationFlag = true;
            myLeavesCreated++;
        }
        myMovesConsidered += movesToConsider.size();
    }

    private List<Pair<Move, Double>> getChosenMoves(Board board, int maxChoices,
                                                    List<LichessMoveStats> myMoveStats, Map<Move, Double> candidateMoves) {
        Move bestScoreMove = getBestScoreMove(board, myMoveStats, candidateMoves.keySet());

        List<Pair<Move, Double>> chosenMoves = candidateMoves.entrySet()
                .stream()
                .sorted((a, b) -> {
                    if (a.getKey().equals(bestScoreMove)) {
                        return -1;
                    } else if (b.getKey().equals(bestScoreMove)) {
                        return 1;
                    }
                    return -Double.compare(a.getValue(), b.getValue());
                })
                .map(entry -> new Pair<>(entry.getKey(), entry.getValue()))
                .toList();
        if (chosenMoves.size() > maxChoices) {
            for (int i = maxChoices; i < chosenMoves.size(); i++) {
                log.debug("Move {} with normEval {} is discarded because of quota {}", chosenMoves.get(i).getKey(),
                        String.format("%.1f", normalizeEval(chosenMoves.get(i).getValue()) * 100.), maxChoices);
            }
            chosenMoves = chosenMoves.subList(0, maxChoices);
        }
        return chosenMoves;
    }

    private Move getBestScoreMove(Board board, List<LichessMoveStats> myMoveStats, Set<Move> movesToChooseFrom) {
        Move bestScoreMove = null;
        double bestScore = 0.;
        for (LichessMoveStats moveStats : myMoveStats) {
            Move move = lichessUciToMove(board, moveStats.uci);
            if (movesToChooseFrom.contains(move) && moveStats.getGames() > MIN_GAMES_FOR_VALID_SCORE) {
                double score = getAverageScore(moveStats, side);
                if (score > bestScore) {
                    bestScoreMove = move;
                    bestScore = score;
                }
            }
        }
        return bestScoreMove;
    }

    private void processOpNode(OpNode opNode) {
        Board board = opNode.board;
        if (opNode.isPruned || opNode.board.getMoveCounter() > LICHESS_OPENING_DEPTH) {
            return;
        }
        log.debug("Process opponent node {}, chance={}%", opNode.moveHistory, String.format("%.1f", opNode.chance * 100.));
        String fen = board.getFen();
        LichessPosition opPosition = lichessClient.getPosition(fen, lichessRatingSelector);
        List<LichessMoveStats> opMoveStats = opPosition.getMoves();
        long totalGames = opPosition.getGames();
        if (totalGames < MIN_GAMES_FOR_OP_MOVE) {
            opNode.prune(TOO_FEW_GAMES);
            return;
        }
        Move firstMove = lichessUciToMove(board, opMoveStats.get(0).uci);
        long firstMoveGames = opMoveStats.get(0).getGames();
        boolean isDeepLine = firstMoveGames * 2 >= totalGames;
        if (opNode.chance * firstMoveGames / totalGames < getDeepestLineChance()) {
            log.debug("processOpNode {}: so wide that even the first line is out of deep line, skip", opNode.moveHistory);
            return;
        } else if (opNode.chance * firstMoveGames / totalGames < minChance && !isDeepLine) {
            log.trace("processOpNode {}: too small to consider, skip", opNode.fen);
            return;
        }

        Optional<LineEval> bestLine = stockfishClient.getBestLine(board, settings.getEngineSearchDepth());
        if (bestLine.isEmpty()) {
            log.trace("This looks like a mate! Skip");
            return;
        }
        Move bestOpMove = bestLine.get().move;
        double bestOpEval = bestLine.get().eval;
        double branchingFactor = 1.;
        for (LichessMoveStats opMoveStat : opMoveStats) {
            Move opMove = lichessUciToMove(board, opMoveStat.uci);
            log.trace("Analyzing opponent move " + opMove);
            double moveChance = opNode.chance * opMoveStat.getGames() / totalGames;
            if (moveChance < getDeepestLineChance() || opMoveStat.getGames() < MIN_GAMES_FOR_OP_MOVE) {
                log.trace("Opponent move {} is too rare to encounter. Pass", opMove);
                continue;
            }
            // eval is not set here. It should be set during the next processMyNode phase
            MyNode childNode = opNode.createChild(opMove, moveChance);
            if (!opMove.equals(bestOpMove)) {
                Optional<LineEval> lineAfterOpMove = stockfishClient.getBestLine(childNode.board,
                        settings.getEngineSearchDepth());
                double eval = -lineAfterOpMove.get().eval;
                branchingFactor += Math.min(1., Math.exp((-bestOpEval + eval) / TYPICAL_INACCURACY_EVAL_LOSS));
            }
            log.debug("Opponent move " + opMove + " has {}% chance to occur", String.format("%.1f", moveChance * 100.));
            newGenerationFlag = true;
            if (moveChance < minChance && (!isDeepLine || !opMove.equals(firstMove))) {
                makeNodeStubLeaf(childNode, opMoveStat);
            }
        }
        opNode.branchingFactor = branchingFactor;
        logAllBranchingFactor += Math.log(branchingFactor);
        opNodes++;
    }

    private void makeNodeStubLeaf(MyNode myNode, LichessMoveStats opMoveStat) {
        Optional<LineEval> lineEval = stockfishClient.getBestLine(myNode.board, settings.getEngineSearchDepth());
        if (lineEval.isEmpty()) {
            throw new IllegalStateException("Cannot get my last move in position " + myNode.fen);
        }
        Move bestMove = lineEval.get().move;
        String bestMoveUci = bestMove.toString();
        myNode.normEval = normalizeEval(lineEval.get().eval);
        myNode.isStubLeafNode = true;
        myNode.chosenPrepMove = bestMove;
        LichessPosition lichessPosition = lichessClient.getPosition(myNode.fen, lichessRatingSelector);
        LichessMoveStats myMoveStats = lichessPosition.getMoves()
                .stream()
                .filter(moveStats -> bestMoveUci.equals(moveStats.uci))
                .findAny()
                .orElse(opMoveStat);
        myNode.lichessScore = getAverageScore(myMoveStats, side);
        myNode.moveHistory = myNode.moveHistory.prolong(bestMove);
        myLeavesCreated++;
        log.debug("Created stub leaf node " + myNode);
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
        for (Node child : node.moveToNode.values()) {
            calculateNodeValuesRecursive(child);
        }
        if (node instanceof MyNode myNode) {
            if (myNode.isStubLeafNode) {
                myNode.vp = myNode.normEval;
                myNode.leaves++;
                return;
            }

            double maxVp = 0.;
            double maxNormEval = 0.;
            double topicalCumulativeVariety = 0.;
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
                    topicalCumulativeVariety = child.cumulativeVariety;
                    myNode.chosenPrepMove = childEntry.getKey();
                    myNode.lichessScore = child.lichessScore;
                }
                if (child.normEval > maxNormEval) {
                    maxNormEval = child.normEval;
                }
            }
            if (maxVp > 0.) {
                myNode.vp = maxVp;
                myNode.normEval = maxNormEval;
                myNode.cumulativeVariety = topicalCumulativeVariety;
                myNode.leaves = leaves;
                myNode.branches = branches + 1; // +1 because the node itself is a branch
            } else {
                myNode.vp = myNode.normEval;
                myNode.leaves = 1;
            }
        } else if (node instanceof OpNode opNode) {
            if (opNode.moveToNode.isEmpty()) {
                LichessPosition lichessPosition = lichessClient.getPosition(opNode.fen, lichessRatingSelector);
                opNode.lichessScore = getAverageScore(lichessPosition, side);
                opNode.vp = opNode.normEval;
                opNode.leaves = 1;
                return;
            }
            double chanceSum = 0.;
            double weightedVPSum = 0.;
            double weightedLichessVPSum = 0.;
            double weightedCumulativeVarietySum = 0.;
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
                weightedCumulativeVarietySum += child.cumulativeVariety * chanceSum;
                weightedLichessVPSum += child.lichessScore * child.chance;
                if (child.normEval < minNormEval) {
                    minNormEval = child.normEval;
                }
            }
            opNode.vp = weightedVPSum / chanceSum;
            opNode.lichessScore = weightedLichessVPSum / chanceSum;
            opNode.normEval = minNormEval;
            opNode.cumulativeVariety = weightedCumulativeVarietySum / chanceSum + Math.log(opNode.branchingFactor);
            opNode.branches = branches;
            opNode.leaves = leaves;
        }
    }

    private void choosePrep(Node node) {
        if (node.isPruned) {
            return;
        }
        for (Node child : node.moveToNode.values()) {
            choosePrep(child);
        }
        if (node instanceof MyNode myNode) {
            if (myNode.isStubLeafNode) {
                return;
            }

            double maxVp = 0.;
            double maxNormEval = 0.;
            for (var childEntry : node.moveToNode.entrySet()) {
                Node child = childEntry.getValue();
                if (child.isPruned) {
                    continue;
                }
                if (child.vp > maxVp) {
                    maxVp = child.vp;
                    myNode.chosenPrepMove = childEntry.getKey();
                    myNode.lichessScore = child.lichessScore;
                }
                if (child.normEval > maxNormEval) {
                    maxNormEval = child.normEval;
                }
            }
            if (maxVp > 0.) {
                myNode.vp = maxVp;
                myNode.normEval = maxNormEval;
            } else {
                myNode.vp = myNode.normEval;
            }
        } else if (node instanceof OpNode opNode) {
            if (opNode.moveToNode.isEmpty()) {
                return;
            }
            double chanceSum = 0.;
            double weightedVPSum = 0.;
            double weightedLichessVPSum = 0.;
            double minNormEval = 1.;
            for (var childEntry : node.moveToNode.entrySet()) {
                Node child = childEntry.getValue();
                if (child.isPruned) {
                    continue;
                }
                chanceSum += child.chance;
                weightedVPSum += child.vp * child.chance;
                weightedLichessVPSum += child.lichessScore * child.chance;
                if (child.normEval < minNormEval) {
                    minNormEval = child.normEval;
                }
            }
            opNode.vp = weightedVPSum / chanceSum;
            opNode.lichessScore = weightedLichessVPSum / chanceSum;
            opNode.normEval = minNormEval;
        }
    }

    private void pruneRecursive(Node node, double normEvalBenchmark, int treeHeight) {
        if (node.isPruned) {
            return;
        }
        for (Node child : node.moveToNode.values()) {
            double childNormEvalBenchmark = Math.max(child.normEval, normEvalBenchmark);
            pruneRecursive(child, childNormEvalBenchmark, treeHeight - 1);
        }
        if (node instanceof MyNode myNode) {
            if (myNode.isPruned) {
                return;
            }
            if (myNode.isStubLeafNode) {
                // TODO: If a stub leaf node gets pruned then its parent's evaluation was incorrect.
                //       We should get back and re-evaluate the parent. But it's a very rare occasion in the opening
                if (myNode.normEval < normEvalBenchmark - settings.getMaxEvalSetback()) {
                    log.debug("Stub leaf node {} was pruned because of a subsequent eval check. normEval={}, normEvalBenchmark={}",
                            myNode, String.format("%.1f", myNode.normEval * 100.),
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
            double maxNormEval = 0.;
            double minCumulativeVariety = Double.POSITIVE_INFINITY;
            for (Node child : node.moveToNode.values()) {
                if (child.normEval > maxNormEval) {
                    maxNormEval = child.normEval;
                }
                if (child.cumulativeVariety < minCumulativeVariety && !child.isPruned && !child.moveToNode.isEmpty()) {
                    minCumulativeVariety = child.cumulativeVariety;
                }
            }
            childCycle:
            for (Node child : node.moveToNode.values()) {
                // If the child has the biggest normalized eval, do not prune it
                if (child.isPruned || child.normEval >= maxNormEval) {
                    continue;
                }
                double multiplier = getCumulativeVarietyMultiplier(minCumulativeVariety, child.cumulativeVariety);
                if (child.normEval < normEvalBenchmark - settings.getMaxEvalSetback() * multiplier) {
                    log.debug("Branch {} was pruned because of a subsequent eval check. normEval={}, normEvalBenchmark={}, multiplier={}",
                            child, String.format("%.1f", child.normEval * 100.),
                            String.format("%.1f", normEvalBenchmark * 100.),
                            String.format("%.3f", multiplier));
                    child.prune(SUBPAR_EVAL_SUBSEQUENT);
                    branchesCut++;
                    pruneWithParent(myNode);
                    continue;
                }
                for (Node otherChild : node.moveToNode.values()) {
                    if (otherChild.isPruned || child == otherChild) {
                        continue;
                    }
                    if (treeHeight > 0 && otherChild.vp >= child.vp + settings.getVpMarginForPruning()) {
                        if (otherChild.lichessScore >= child.lichessScore + settings.getLichessScoreMarginForPruning()) {
                            branchesCut++;
                            log.debug("Branch {} was pruned because rival branch {} has better score. VP: {} vs. {}, lichess VP: {} vs. {}",
                                    child, otherChild, String.format("%.1f", child.vp * 100.),
                                    String.format("%.1f", otherChild.vp * 100.),
                                    String.format("%.1f", child.lichessScore * 100.),
                                    String.format("%.1f", otherChild.lichessScore * 100.));
                            child.prune(SUBPAR_BOTH_VP_AND_LICHESS_SCORE);
                            continue childCycle;
                        }
                    }
                }
            }
        }
    }

    private void pruneWithParent(Node node) {
        if (node instanceof OpNode opNode) {
            log.debug("Branch {} was pruned because all of its children were pruned", node);
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
        log.debug("Branches cut: {}", branchesCut);
        if (opNodes > 0) {
            double averageBranchingFactor = Math.exp(logAllBranchingFactor / opNodes);
            log.info("Average branching factor for the opponent: {}", String.format("%.2f", averageBranchingFactor));
        }
        log.debug("Moves chosen vs Moves considered: {} / {}", myLeavesCreated, myMovesConsidered);
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

    private double getDeepestLineChance() {
        return minChance / settings.getDeepLineFactor();
    }

    private int getMaxChoices(double chance) {
        return chance < settings.getMyMovesQuotaReferencePoint() ? 1 :
                2 + (int) (Math.log(chance / settings.getMyMovesQuotaReferencePoint()) /
                        Math.log(settings.getMyMovesQuotaBase()));
    }

    private double getCumulativeVarietyMultiplier(double minCumulativeVariety, double actualCumulativeVariety) {
        return !settings.isVarietyMultiplierEnabled() || minCumulativeVariety == Double.POSITIVE_INFINITY
                ? 1. : (1 + minCumulativeVariety) / (1 + actualCumulativeVariety);
    }
}
