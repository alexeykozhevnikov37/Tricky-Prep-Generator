package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.prep.GeneratorSettings;
import com.ser_databus.tpg.prep.IterativeDeepeningPrepEvaluator;
import com.ser_databus.tpg.prep.PrepEvaluator;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Runs prep generations one at a time on a background thread. The {@link PrepEvaluator} (engines, databases and
 * caches) is created once, by {@link #startUp} when the application starts (or by the first generation if that
 * failed), and reused by all generations. When the Lichess data source or the eval source in the settings changes,
 * only the source that changed is replaced, between generations.
 */
@Slf4j
public class PrepGenerationService {
    /** All callbacks are called on the generator thread. */
    public interface Callbacks extends IterativeDeepeningPrepEvaluator.PrepListener {
        /** The generation leaves the queue and starts (the engines are started first if they aren't yet) */
        default void onStarted() {
        }

        default void onFinished() {
        }

        default void onCancelled() {
        }

        default void onFailed(Throwable error) {
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prep-generator");
        thread.setDaemon(true);
        return thread;
    });
    /** Only used on the generator thread */
    private PrepEvaluator prepEvaluator;

    /**
     * Starts the engines and opens the databases and caches of the given sources in the background, so the first
     * generation doesn't have to wait for them. Generations queued meanwhile start right after it. Called again
     * when the sources are changed in the settings: then only the source that changed is replaced.
     * The callbacks are called on the generator thread.
     */
    public void startUp(LichessDataSource lichessDataSource, EvalSource evalSource, Runnable onReady,
                        Consumer<Throwable> onFailed) {
        executor.execute(() -> {
            try {
                long start = System.nanoTime();
                ensurePrepEvaluator(lichessDataSource, evalSource);
                log.info("The prep generator is ready in {} ms", (System.nanoTime() - start) / 1_000_000);
                onReady.run();
            } catch (Throwable t) {
                log.error("Couldn't start the prep generator", t);
                onFailed.accept(t);
            }
        });
    }

    private void ensurePrepEvaluator(LichessDataSource lichessDataSource, EvalSource evalSource) {
        if (prepEvaluator == null) {
            prepEvaluator = new PrepEvaluator(lichessDataSource, evalSource);
        } else {
            prepEvaluator.useSources(lichessDataSource, evalSource);
        }
    }

    /**
     * Queues a generation. If another one is running, this one starts when that one is finished or cancelled.
     *
     * @param isCancelled once it returns true, the generation stops as soon as possible
     */
    public void generate(String fen, Side side, GeneratorSettings settings, Callbacks callbacks,
                         BooleanSupplier isCancelled) {
        executor.execute(() -> run(fen, side, settings, callbacks, isCancelled));
    }

    private void run(String fen, Side side, GeneratorSettings settings, Callbacks callbacks,
                     BooleanSupplier isCancelled) {
        if (isCancelled.getAsBoolean()) {
            callbacks.onCancelled();
            return;
        }
        try {
            callbacks.onStarted();
            ensurePrepEvaluator(settings.getLichessDataSource(), settings.getEvalSource());
            log.info("Generating prep for {} from {} (Lichess: {}{}, evaluations: {}, minChance iteration: {}, "
                            + "node evaluation: {})",
                    side, fen, settings.getLichessDataSource(),
                    settings.getLichessDataSource() == LichessDataSource.PUBLIC_API
                            ? " " + settings.getLichessRatingSelector() : "",
                    settings.getEvalSource(), settings.isMinChanceIterationEnabled() ? "on" : "off",
                    settings.getNodeEvaluationMethod());
            new IterativeDeepeningPrepEvaluator(settings, prepEvaluator)
                    .generateTrickyPrep(fen, side, settings.getLichessRatingSelector(), callbacks, isCancelled);
            callbacks.onFinished();
        } catch (CancellationException e) {
            log.info("Prep generation for {} was cancelled", fen);
            callbacks.onCancelled();
        } catch (Throwable t) {
            if (isCancelled.getAsBoolean()) {
                log.info("Prep generation for {} was cancelled ({})", fen, t.toString());
                callbacks.onCancelled();
            } else {
                log.error("Prep generation for {} failed", fen, t);
                callbacks.onFailed(t);
            }
        } finally {
            // A cancelled generation may leave thousands of queued engine searches: drop those nobody waits for
            if (prepEvaluator != null) {
                prepEvaluator.abandonPendingRequests();
                // The preps live on in the window (if it's still open), not in the shared evaluator
                prepEvaluator.releaseTree();
            }
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
