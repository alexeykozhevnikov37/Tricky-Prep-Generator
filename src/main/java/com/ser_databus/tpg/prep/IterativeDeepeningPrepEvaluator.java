package com.ser_databus.tpg.prep;

import com.github.bhlangonijr.chesslib.Side;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import com.ser_databus.tpg.prep.tree.Node;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class IterativeDeepeningPrepEvaluator {
    private final PrepEvaluator prepEvaluator;
    private GeneratorSettings settings;
    private int iteration;
    private List<Node> generatedPreps;
    private double minChance;
    private double currentMinChance;

    public IterativeDeepeningPrepEvaluator(GeneratorSettings generatorSettings) {
        settings = generatorSettings;
        prepEvaluator = new PrepEvaluator();
    }

    public void  generateTrickyPrep(String fen, Side side, LichessRatingSelector ratingSelector) {
        this.minChance = settings.getMinChance();
        generatedPreps = new ArrayList<>();
        int iterationCount = getIterationCount();
        for (int i = 0; i < iterationCount; i++) {
            currentMinChance = minChance * Math.pow(2., iterationCount - i - 1);
            log.info("Iteration {} out of {}. minChance={}%",
                    i + 1, iterationCount, String.format("%.1f", currentMinChance * 100.));
            Node prep = prepEvaluator.createIterativePrep(fen, side, ratingSelector, settings, currentMinChance);
            generatedPreps.add(prep);
        }
        log.info("Iterative prep generation completed.");
    }

    private int getIterationCount() {
        return Math.max(1, 1 + (int) (Math.log(1. / minChance) / Math.log(2)));
    }
}
