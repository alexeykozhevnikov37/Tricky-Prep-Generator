package com.ser_databus.tpg.system;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.evaluate.PositionEvaluator;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.lichess.LichessPositionSource;
import lombok.extern.slf4j.Slf4j;

/**
 * Console program: finds opening systems from a position for one side, using the local Lichess Elite index and engine
 * evals only.
 * <p>
 * Arguments (all optional):
 * <pre>
 *   --fen="FEN"               root position, default: the starting position
 *   --side=white|black        my side, default: the side to move
 *   --depth=N                 engine search depth, default 18
 *   --eval-source=cloud|stockfish   default: cloud evals first, then Stockfish
 *   --max-eval-setback=X      default 0.1 (normalized eval)
 *   --min-reach=X             default 0.005
 *   --max-systems=N           default 5
 *   --max-steps=N             default 25
 *   --progressive             print intermediate results after every full move of depth
 * </pre>
 */
@Slf4j
public class SystemMinerMain {
    private static final String START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public static void main(String[] args) {
        String fen = START_FEN;
        Side side = null;
        SystemSettings.SystemSettingsBuilder builder = SystemSettings.builder();
        for (String arg : args) {
            String value = arg.contains("=") ? arg.substring(arg.indexOf('=') + 1) : "";
            if (arg.startsWith("--fen=")) {
                fen = value;
            } else if (arg.startsWith("--side=")) {
                side = value.equalsIgnoreCase("black") ? Side.BLACK : Side.WHITE;
            } else if (arg.startsWith("--depth=")) {
                builder.engineSearchDepth(Integer.parseInt(value));
            } else if (arg.startsWith("--eval-source=")) {
                builder.evalSource(value.equalsIgnoreCase("stockfish") ? EvalSource.STOCKFISH
                        : EvalSource.CLOUD_THEN_STOCKFISH);
            } else if (arg.startsWith("--max-eval-setback=")) {
                builder.maxEvalSetback(Double.parseDouble(value));
            } else if (arg.startsWith("--min-reach=")) {
                builder.minReach(Double.parseDouble(value));
            } else if (arg.startsWith("--max-systems=")) {
                builder.maxSystems(Integer.parseInt(value));
            } else if (arg.startsWith("--max-steps=")) {
                builder.maxSteps(Integer.parseInt(value));
            } else if (arg.equals("--progressive")) {
                builder.progressive(true);
            } else {
                System.err.println("Unknown argument: " + arg);
                System.exit(1);
            }
        }
        if (side == null) {
            Board board = new Board();
            board.loadFromFen(fen);
            side = board.getSideToMove();
        }
        run(fen, side, builder.build());
    }

    public static void run(String fen, Side side, SystemSettings settings) {
        LichessPositionSource lichess = LichessDataSource.LOCAL_ELITE_DB.create();
        try (PositionEvaluator engine = settings.getEvalSource().create()) {
            Arena arena = new Arena(side, settings, lichess, engine);
            arena.init(fen);
            if (arena.getRoot().terminal) {
                System.out.println("The root position is game over");
                return;
            }
            SystemReport report = new SystemReport(System.out, arena, settings);
            while (arena.expandNextLevel()) {
                if (settings.isProgressive() && arena.getDepth() >= settings.getProgressiveFromPly()
                        && arena.getDepth() % 2 == 0) {
                    report.printBrief(new SystemGrower(arena, settings).run(false));
                }
            }
            log.info("Arena complete: {} positions, {} explored. Growing the systems...", arena.size(),
                    arena.getExpandedCount());
            SystemGrower.Result result = new SystemGrower(arena, settings).run(true);
            report.printHeader(fen);
            report.print(result);
            engine.printStats();
            engine.flushCache();
        }
    }
}
