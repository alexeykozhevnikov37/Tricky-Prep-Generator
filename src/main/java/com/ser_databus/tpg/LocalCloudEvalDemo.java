package com.ser_databus.tpg;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.elite.PositionKey;
import com.ser_databus.tpg.evaldb.CloudEval;
import com.ser_databus.tpg.evaldb.CloudEvalPosition;
import com.ser_databus.tpg.evaldb.CloudEvalProgress;
import com.ser_databus.tpg.evaldb.CloudPv;
import com.ser_databus.tpg.evaldb.LocalCloudEvalClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;

import static com.ser_databus.tpg.util.MoveUtil.moveToSan;

/**
 * Looks positions up in the local Lichess cloud eval index and prints everything the client returns.
 * <p>
 * Run without arguments to check the 1.e4 position and then type FENs (an empty line exits), or pass a FEN as the
 * arguments (quoted or not).
 */
public class LocalCloudEvalDemo {
    private static final String DEFAULT_FEN = "rnbqkb1r/pppppppp/5n2/8/2P5/5N2/PP1PPPPP/RNBQKB1R b KQkq - 0 2";

    public static void main(String[] args) throws IOException {
        try (LocalCloudEvalClient client = new LocalCloudEvalClient()) {
            printIndexInfo(client);
            if (args.length > 0) {
                show(client, String.join(" ", args));
                return;
            }
            show(client, DEFAULT_FEN);
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }
    }

    private static void printIndexInfo(LocalCloudEvalClient client) {
        CloudEvalProgress progress = client.getProgress();
        System.out.printf("Cloud eval index: ~%,d positions; %s%n", client.estimatePositions(),
                client.getBuildSettings().describe());
        if (progress != null && progress.status() != CloudEvalProgress.Status.DONE) {
            System.out.printf("The index is incomplete: %,d lines of the dump are loaded so far%n",
                    progress.linesConsumed());
        }
    }

    private static void show(LocalCloudEvalClient client, String fen) {
        System.out.println();
        System.out.println("FEN:     " + fen);
        Board board;
        String key;
        try {
            board = toBoard(fen);
            key = PositionKey.of(board);
        } catch (RuntimeException e) {
            System.out.println("Couldn't read the FEN: " + e);
            return;
        }
        System.out.println("Key:     " + key);

        long start = System.nanoTime();
        Optional<CloudEvalPosition> result = client.getEvals(fen);
        long micros = (System.nanoTime() - start) / 1_000;
        System.out.printf("Lookup:  %,d µs%n", micros);
        if (result.isEmpty()) {
            System.out.println("Not in the index: Lichess has no evaluation of this position, or it was filtered "
                    + "out when the index was built (" + client.getBuildSettings().describe() + ")");
            return;
        }

        CloudEvalPosition position = result.get();
        Side toMove = position.sideToMove();
        CloudPv best = position.bestPv();
        System.out.printf("Best:    %s (White's view), %+.2f for the side to move (%s), depth %d: %s%n",
                best.scoreText(), best.score(toMove), toMove == Side.WHITE ? "White" : "Black",
                position.maxDepth(), sanLine(board, best));
        System.out.printf("%d evaluation(s), scores from White's point of view:%n", position.evals().size());
        for (CloudEval eval : position.evals()) {
            System.out.printf("  depth %d, %,d knodes, %d line(s)%n", eval.depth(), eval.knodes(), eval.pvs().size());
            int rank = 1;
            for (CloudPv pv : eval.pvs()) {
                System.out.printf("    %2d. %7s   %s%n", rank++, pv.scoreText(), sanLine(board, pv));
            }
        }
    }

    /** "1... e5 2. Nf3 Nc6", numbered from the FEN's move number. Falls back to raw UCI if the line can't be replayed. */
    private static String sanLine(Board position, CloudPv pv) {
        if (pv.moves().isEmpty()) {
            return "(no moves)";
        }
        try {
            List<Move> moves = pv.toMoves(position);
            Board board = position.clone();
            StringBuilder sb = new StringBuilder();
            int moveNumber = board.getMoveCounter();
            for (int i = 0; i < moves.size(); i++) {
                Move move = moves.get(i);
                if (board.getSideToMove() == Side.WHITE) {
                    sb.append(moveNumber).append(". ");
                } else if (i == 0) {
                    sb.append(moveNumber).append("... ");
                }
                sb.append(moveToSan(board, move)).append(' ');
                board.doMove(move);
                if (board.getSideToMove() == Side.WHITE) {
                    moveNumber++;
                }
            }
            return sb.toString().trim();
        } catch (RuntimeException e) {
            return String.join(" ", pv.moves()) + "  (UCI_Chess960; couldn't replay: " + e.getMessage() + ")";
        }
    }

    /** chesslib needs all six FEN fields; FENs from the dump or the Lichess explorer may have only four. */
    private static Board toBoard(String fen) {
        String[] fields = fen.trim().split("\\s+");
        if (fields.length < 4) {
            throw new IllegalArgumentException("a FEN needs at least 4 fields");
        }
        String full = String.join(" ", fields) + (fields.length == 4 ? " 0 1" : fields.length == 5 ? " 1" : "");
        Board board = new Board();
        board.loadFromFen(full);
        return board;
    }
}
