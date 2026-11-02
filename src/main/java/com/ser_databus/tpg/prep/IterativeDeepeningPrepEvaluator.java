package com.ser_databus.tpg.prep;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import com.ser_databus.tpg.model.Node;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

@Slf4j
public class IterativeDeepeningPrepEvaluator {
    /** Receives the progress of {@link #generateTrickyPrep}. Called on the generating thread. */
    public interface PrepListener {
        /**
         * @param iteration      1-based number of the iteration that starts
         * @param iterationCount total number of iterations
         * @param minChance      minChance used by this iteration
         */
        default void onIterationStarted(int iteration, int iterationCount, double minChance) {
        }

        /** @param prep the root of the prep tree of this iteration, or null if the position is (next to) game over */
        default void onIterationCompleted(int iteration, int iterationCount, double minChance, Node prep) {
        }

        /** A short description of the current operation, e.g. "Expanding 1104 nodes at level 16" */
        default void onProgress(String message) {
        }
    }

    private static final PrepListener NO_LISTENER = new PrepListener() {
    };

    private final PrepEvaluator prepEvaluator;
    private GeneratorSettings settings;
    private List<Node> generatedPreps;
    private double minChance;
    private double currentMinChance;

    public IterativeDeepeningPrepEvaluator(GeneratorSettings generatorSettings) {
        this(generatorSettings, new PrepEvaluator(generatorSettings.getLichessDataSource(),
                generatorSettings.getEvalSource()));
    }

    /**
     * Reuses an existing evaluator (and its engines and caches). It must have been created with the same
     * Lichess data source and eval source as in the settings.
     */
    public IterativeDeepeningPrepEvaluator(GeneratorSettings generatorSettings, PrepEvaluator prepEvaluator) {
        settings = generatorSettings;
        this.prepEvaluator = prepEvaluator;
    }

    public void generateTrickyPrep(String fen, Side side, LichessRatingSelector ratingSelector) {
        generateTrickyPrep(fen, side, ratingSelector, NO_LISTENER, () -> false);
    }

    /**
     * Builds the prep once per iteration and reports every finished iteration to the listener.
     *
     * @param isCancelled polled during the generation; once it returns true, the generation stops by throwing
     *                    a {@link CancellationException}
     */
    public void generateTrickyPrep(String fen, Side side, LichessRatingSelector ratingSelector,
                                   PrepListener listener, BooleanSupplier isCancelled) {
        this.minChance = settings.getMinChance();
        generatedPreps = new ArrayList<>();
        int iterationCount = settings.isMinChanceIterationEnabled() ? getIterationCount() : 1;
        prepEvaluator.setCancellationCheck(isCancelled);
        prepEvaluator.setProgressListener(listener::onProgress);
        try {
            for (int i = 0; i < iterationCount; i++) {
                if (isCancelled.getAsBoolean()) {
                    throw new CancellationException("Prep generation was cancelled");
                }
                currentMinChance = minChance * Math.pow(2., iterationCount - i - 1);
                log.info("Iteration {} out of {}. minChance={}%",
                        i + 1, iterationCount, String.format("%.1f", currentMinChance * 100.));
                listener.onIterationStarted(i + 1, iterationCount, currentMinChance);
                Node prep = prepEvaluator.createIterativePrep(fen, side, ratingSelector, settings, currentMinChance);
                generatedPreps.add(prep);
                listener.onIterationCompleted(i + 1, iterationCount, currentMinChance, prep);
            }
        } finally {
            prepEvaluator.setCancellationCheck(null);
            prepEvaluator.setProgressListener(null);
        }
        log.info("Iterative prep generation completed.");
    }

    /** The preps of the last {@link #generateTrickyPrep} call, one per finished iteration. */
    public List<Node> getGeneratedPreps() {
        return generatedPreps == null ? List.of() : Collections.unmodifiableList(generatedPreps);
    }

    private int getIterationCount() {
        return Math.max(1, 1 + (int) (Math.log(1. / minChance) / Math.log(2)));
    }
}
