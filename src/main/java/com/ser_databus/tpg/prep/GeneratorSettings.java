package com.ser_databus.tpg.prep;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class GeneratorSettings {
    private int engineSearchDepth;
    private double minChance;
    private double myMovesQuotaBase;
    private double myMovesQuotaReferencePoint;
    private double maxEvalSetback;
    private double vpMarginForPruning;
    private double lichessScoreMarginForPruning;
    private double deepLineFactor;
    private boolean isVarietyMultiplierEnabled;
}
