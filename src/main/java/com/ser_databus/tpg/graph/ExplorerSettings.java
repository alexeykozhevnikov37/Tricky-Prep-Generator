package com.ser_databus.tpg.graph;

import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.lichess.LichessRatingSelector;
import com.ser_databus.tpg.prep.GeneratorSettings;
import com.ser_databus.tpg.prep.NodeEvaluationMethod;
import com.ser_databus.tpg.util.Const;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The prep generator settings the user can edit in {@link SettingsDialog}, persisted in {@link #FILE}:
 * the numeric {@link Key}s, plus the eval source, the Lichess data source, the Lichess rating selector
 * (used only with {@link LichessDataSource#PUBLIC_API}), whether minChance iteration is enabled and the node
 * evaluation method.
 */
@Slf4j
public class ExplorerSettings {
    /** Relative to the working directory, i.e. the project root, where the data folders are, too. */
    public static final Path FILE = Paths.get("explorer-settings.properties");

    public static final EvalSource DEFAULT_EVAL_SOURCE = EvalSource.CLOUD_THEN_STOCKFISH;
    public static final LichessDataSource DEFAULT_LICHESS_DATA_SOURCE = LichessDataSource.LOCAL_ELITE_DB;
    public static final boolean DEFAULT_LICHESS_MASTERS = false;
    public static final LichessRating DEFAULT_LICHESS_RATING_FROM = LichessRating.R2200;
    public static final LichessRating DEFAULT_LICHESS_RATING_TO = LichessRating.R2500;
    public static final boolean DEFAULT_MIN_CHANCE_ITERATION_ENABLED = true;
    public static final NodeEvaluationMethod DEFAULT_NODE_EVALUATION_METHOD = NodeEvaluationMethod.STOCKFISH;

    static final String EVAL_SOURCE_PROPERTY = "evalSource";
    static final String LICHESS_DATA_SOURCE_PROPERTY = "lichessDataSource";
    static final String LICHESS_MASTERS_PROPERTY = "lichessMasters";
    static final String LICHESS_RATING_FROM_PROPERTY = "lichessRatingFrom";
    static final String LICHESS_RATING_TO_PROPERTY = "lichessRatingTo";
    static final String MIN_CHANCE_ITERATION_PROPERTY = "isMinChanceIterationEnabled";
    static final String NODE_EVALUATION_METHOD_PROPERTY = "nodeEvaluationMethod";

    static final String EVAL_SOURCE_HINT = "Where the engine evaluations come from.\n"
            + "Local engine only: every position is searched by the local Stockfish (its results are cached).\n"
            + "Cloud eval database first: the local copy of the Lichess cloud evaluations is used when it has the "
            + "position searched at least as deep as the engine search depth; Stockfish evaluates the rest. "
            + "Much faster in well-known positions.";
    static final String LICHESS_DATA_SOURCE_HINT = "Where the statistics of the moves played by people come from.\n"
            + "Local database: the local index of the Lichess Elite Database (strong players only, positions up to "
            + "move 25). Fast, works offline; the rating selector is ignored.\n"
            + "Lichess online: the Lichess opening explorer, for the rating range below (answers are cached in "
            + "lichessCache.txt). Slower, especially for new positions: Lichess allows one request at a time.";
    static final String RATING_HINT = "The ratings of the Lichess games (blitz, rapid and classical) the statistics "
            + "are taken from: all rating groups from 'from' to 'to', both included. Masters = the Lichess masters "
            + "database (over-the-board games of titled players) instead.\n"
            + "Used only when the Lichess statistics come from Lichess online.";
    static final String MIN_CHANCE_ITERATION_HINT = "On: the prep is built in iterations: the first one starts close "
            + "to 1 (100%), every next one halves min chance, and the last one uses exactly min chance. You see "
            + "a rough prep early, and every iteration reuses the evaluations of the previous ones.\n"
            + "Off: the prep is built once, directly with min chance. Faster to the final result, but nothing "
            + "is shown until it's done.";
    static final String NODE_EVALUATION_METHOD_HINT = "What the value of a position at the end of a prep line "
            + "(and of the opponent's replies that aren't in the prep) is based on. The VP of the prep is the "
            + "average of these values, weighted by the chance of each line.\n"
            + "Engine only: the normalized engine eval.\n"
            + "Lichess score, then engine: the average score of the Lichess players in the position, shrunk "
            + "toward the normalized engine eval by " + Math.round(Const.NODE_VALUE_PRIOR_GAMES) + " games. "
            + "A position played in few games is worth about its engine eval, one played in many games about "
            + "its Lichess score; half and half at " + Math.round(Const.NODE_VALUE_PRIOR_GAMES) + " games.";

    public static String label(EvalSource source) {
        return switch (source) {
            case STOCKFISH -> "Local engine only";
            case CLOUD_THEN_STOCKFISH -> "Cloud eval database first, then local engine";
        };
    }

    public static String label(LichessDataSource source) {
        return switch (source) {
            case LOCAL_ELITE_DB -> "Local database (Lichess Elite)";
            case PUBLIC_API -> "Lichess online (opening explorer)";
        };
    }

    public static String label(NodeEvaluationMethod method) {
        return switch (method) {
            case STOCKFISH -> "Engine only";
            case LICHESS_THEN_STOCKFISH -> "Lichess score, then engine (by number of games)";
        };
    }

    public enum Key {
        ENGINE_SEARCH_DEPTH("engineSearchDepth", "Engine search depth", true, 18, 1, false, 60,
                "Stockfish search depth (in plies) used for every evaluation. An evaluation from the local "
                        + "Lichess cloud eval database is used instead when it was searched at least this deep.\n"
                        + "Deeper is more accurate, but much slower. Typical values: 16-22."),
        MIN_CHANCE("minChance", "Min chance", false, 0.01, 0, true, 1,
                "The smallest probability of reaching a position for the prep to be continued from it "
                        + "(0.01 = 1%). Rarer opponent replies end the line with the engine's best move.\n"
                        + "With minChance iteration on, the prep is built in iterations: the first one starts close "
                        + "to 1 (100%), every next one halves the value, and the last one uses exactly this value. "
                        + "Smaller = wider and deeper prep, but more iterations and much longer generation."),
        MAX_EVAL_SETBACK("maxEvalSetback", "Max eval setback", false, 0.13, 0, false, 1,
                "How much worse than the best move a move of mine may be and still be tried as a tricky "
                        + "alternative. Measured on the normalized eval scale from 0 to 1 (Lichess Win% / 100, roughly the expected "
                        + "score): 0.13 = 13 percentage points.\n"
                        + "No line may drop more than this below the eval of the starting position, either."),
        VP_MARGIN_FOR_PRUNING("vpMarginForPruning", "VP margin for pruning", false, 0.10, 0, false, 1,
                "One of my candidate moves is dropped when a rival move has a better VP (prep value: the average "
                        + "normalized eval at the ends of the lines, weighted by the chance of each line) by at "
                        + "least this margin, and its Lichess VP is better as well (see the Lichess score margin).\n"
                        + "Smaller = more aggressive pruning, faster but narrower search."),
        LICHESS_SCORE_MARGIN_FOR_PRUNING("lichessScoreMarginForPruning", "Lichess score margin for pruning", false, 0.0,
                -1, false, 1,
                "The second condition for dropping one of my candidate moves: the rival move's Lichess VP (the "
                        + "average score of Lichess players at the ends of the lines, weighted by the chance of each "
                        + "line) must be better by at least this margin. 0 = any advantage is enough."),
        DEEP_LINE_FACTOR("deepLineFactor", "Deep line factor", false, 5, 1, false, 1000,
                "When the opponent's most popular reply is played in at least half of the games, the line is "
                        + "followed further than min chance: down to min chance / deep line factor. Opponent replies "
                        + "rarer than that are ignored altogether.\n"
                        + "Bigger = deeper main lines.");

        public final String property;
        public final String label;
        public final boolean integer;
        public final double defaultValue;
        public final double min;
        public final boolean minExclusive;
        public final double max;
        public final String hint;

        Key(String property, String label, boolean integer, double defaultValue, double min, boolean minExclusive,
            double max, String hint) {
            this.property = property;
            this.label = label;
            this.integer = integer;
            this.defaultValue = defaultValue;
            this.min = min;
            this.minExclusive = minExclusive;
            this.max = max;
            this.hint = hint;
        }

        public boolean isValid(double value) {
            if (Double.isNaN(value) || (integer && value != Math.rint(value))) {
                return false;
            }
            return (minExclusive ? value > min : value >= min) && value <= max;
        }

        /** e.g. "0 < x ≤ 1" */
        public String rangeText() {
            return format(min) + (minExclusive ? " < " : " ≤ ") + "x ≤ " + format(max)
                    + (integer ? ", integer" : "");
        }

        public String format(double value) {
            return integer ? String.valueOf(Math.round(value))
                    : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
        }
    }

    private final Map<Key, Double> values = new EnumMap<>(Key.class);
    private EvalSource evalSource = DEFAULT_EVAL_SOURCE;
    private LichessDataSource lichessDataSource = DEFAULT_LICHESS_DATA_SOURCE;
    private boolean lichessMasters = DEFAULT_LICHESS_MASTERS;
    private LichessRating lichessRatingFrom = DEFAULT_LICHESS_RATING_FROM;
    private LichessRating lichessRatingTo = DEFAULT_LICHESS_RATING_TO;
    private boolean minChanceIterationEnabled = DEFAULT_MIN_CHANCE_ITERATION_ENABLED;
    private NodeEvaluationMethod nodeEvaluationMethod = DEFAULT_NODE_EVALUATION_METHOD;

    public ExplorerSettings() {
        for (Key key : Key.values()) {
            values.put(key, key.defaultValue);
        }
    }

    public double get(Key key) {
        return values.get(key);
    }

    public void set(Key key, double value) {
        if (!key.isValid(value)) {
            throw new IllegalArgumentException(key.property + " must be " + key.rangeText() + ", got " + value);
        }
        values.put(key, value);
    }

    public String format(Key key) {
        return key.format(get(key));
    }

    public EvalSource getEvalSource() {
        return evalSource;
    }

    public void setEvalSource(EvalSource evalSource) {
        this.evalSource = Objects.requireNonNull(evalSource);
    }

    public LichessDataSource getLichessDataSource() {
        return lichessDataSource;
    }

    public void setLichessDataSource(LichessDataSource lichessDataSource) {
        this.lichessDataSource = Objects.requireNonNull(lichessDataSource);
    }

    public boolean isLichessMasters() {
        return lichessMasters;
    }

    public LichessRating getLichessRatingFrom() {
        return lichessRatingFrom;
    }

    public LichessRating getLichessRatingTo() {
        return lichessRatingTo;
    }

    /**
     * @param from ignored if masters, otherwise must not be above {@code to}
     * @param to   ignored if masters
     */
    public void setLichessRatings(boolean masters, LichessRating from, LichessRating to) {
        if (!masters && from.getRating() > to.getRating()) {
            throw new IllegalArgumentException("Rating 'from' " + from + " is above 'to' " + to);
        }
        lichessMasters = masters;
        lichessRatingFrom = Objects.requireNonNull(from);
        lichessRatingTo = Objects.requireNonNull(to);
    }

    public LichessRatingSelector getLichessRatingSelector() {
        return lichessMasters ? LichessRatingSelector.master()
                : LichessRatingSelector.regular(lichessRatingFrom, lichessRatingTo);
    }

    public boolean isMinChanceIterationEnabled() {
        return minChanceIterationEnabled;
    }

    public void setMinChanceIterationEnabled(boolean minChanceIterationEnabled) {
        this.minChanceIterationEnabled = minChanceIterationEnabled;
    }

    public NodeEvaluationMethod getNodeEvaluationMethod() {
        return nodeEvaluationMethod;
    }

    public void setNodeEvaluationMethod(NodeEvaluationMethod nodeEvaluationMethod) {
        this.nodeEvaluationMethod = Objects.requireNonNull(nodeEvaluationMethod);
    }

    /** Loads the settings from {@link #FILE}. Missing or invalid values get their defaults. Never throws. */
    public static ExplorerSettings load() {
        ExplorerSettings settings = new ExplorerSettings();
        if (!Files.exists(FILE)) {
            log.info("No settings file {}, using the defaults", FILE.toAbsolutePath());
            return settings;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            log.warn("Couldn't read the settings file {}, using the defaults", FILE.toAbsolutePath(), e);
            return settings;
        }
        for (Key key : Key.values()) {
            String text = properties.getProperty(key.property);
            if (text == null) {
                continue;
            }
            try {
                double value = Double.parseDouble(text.trim());
                if (key.isValid(value)) {
                    settings.values.put(key, value);
                } else {
                    log.warn("Setting {}={} is out of range ({}), using the default", key.property, text,
                            key.rangeText());
                }
            } catch (NumberFormatException e) {
                log.warn("Setting {}={} is not a number, using the default", key.property, text);
            }
        }
        settings.evalSource = parseEnum(properties, EVAL_SOURCE_PROPERTY, EvalSource.class, DEFAULT_EVAL_SOURCE);
        settings.lichessDataSource = parseEnum(properties, LICHESS_DATA_SOURCE_PROPERTY, LichessDataSource.class,
                DEFAULT_LICHESS_DATA_SOURCE);
        settings.minChanceIterationEnabled = parseBoolean(properties, MIN_CHANCE_ITERATION_PROPERTY,
                DEFAULT_MIN_CHANCE_ITERATION_ENABLED);
        settings.nodeEvaluationMethod = parseEnum(properties, NODE_EVALUATION_METHOD_PROPERTY,
                NodeEvaluationMethod.class, DEFAULT_NODE_EVALUATION_METHOD);
        boolean masters = parseBoolean(properties, LICHESS_MASTERS_PROPERTY, DEFAULT_LICHESS_MASTERS);
        LichessRating from = parseRating(properties, LICHESS_RATING_FROM_PROPERTY, DEFAULT_LICHESS_RATING_FROM);
        LichessRating to = parseRating(properties, LICHESS_RATING_TO_PROPERTY, DEFAULT_LICHESS_RATING_TO);
        if (from.getRating() > to.getRating()) {
            log.warn("Setting {}={} is above {}={}, using the default rating range", LICHESS_RATING_FROM_PROPERTY,
                    from, LICHESS_RATING_TO_PROPERTY, to);
            from = DEFAULT_LICHESS_RATING_FROM;
            to = DEFAULT_LICHESS_RATING_TO;
        }
        settings.setLichessRatings(masters, from, to);
        return settings;
    }

    private static <E extends Enum<E>> E parseEnum(Properties properties, String property, Class<E> type,
                                                   E defaultValue) {
        String text = properties.getProperty(property);
        if (text == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(type, text.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Setting {}={} must be one of {}, using the default", property, text,
                    Arrays.toString(type.getEnumConstants()));
            return defaultValue;
        }
    }

    private static boolean parseBoolean(Properties properties, String property, boolean defaultValue) {
        String text = properties.getProperty(property);
        if (text == null) {
            return defaultValue;
        }
        String trimmed = text.trim();
        if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(trimmed);
        }
        log.warn("Setting {}={} must be true or false, using the default", property, text);
        return defaultValue;
    }

    private static LichessRating parseRating(Properties properties, String property, LichessRating defaultValue) {
        String text = properties.getProperty(property);
        if (text == null) {
            return defaultValue;
        }
        try {
            LichessRating rating = LichessRating.fromRating(Integer.parseInt(text.trim()));
            if (rating != null) {
                return rating;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        log.warn("Setting {}={} must be one of {}, using the default", property, text,
                Arrays.toString(LichessRating.values()));
        return defaultValue;
    }

    public void save() throws IOException {
        StringBuilder sb = new StringBuilder("# Tricky prep generator settings, edited in the Settings dialog\n");
        for (Key key : Key.values()) {
            sb.append("\n# ").append(key.label).append(" (").append(key.rangeText()).append(")\n")
                    .append(key.property).append('=').append(format(key)).append('\n');
        }
        sb.append("\n# Where to get Stockfish evaluations (").append(enumNames(EvalSource.values())).append(")\n")
                .append(EVAL_SOURCE_PROPERTY).append('=').append(evalSource.name()).append('\n');
        sb.append("\n# Where to get Lichess stats (").append(enumNames(LichessDataSource.values())).append(")\n")
                .append(LICHESS_DATA_SOURCE_PROPERTY).append('=').append(lichessDataSource.name()).append('\n');
        sb.append("\n# Lichess rating selector, used only with ").append(LichessDataSource.PUBLIC_API.name())
                .append(" (masters: true/false; ratings: ").append(Arrays.toString(LichessRating.values()))
                .append(", from ≤ to)\n")
                .append(LICHESS_MASTERS_PROPERTY).append('=').append(lichessMasters).append('\n')
                .append(LICHESS_RATING_FROM_PROPERTY).append('=').append(lichessRatingFrom.getRating()).append('\n')
                .append(LICHESS_RATING_TO_PROPERTY).append('=').append(lichessRatingTo.getRating()).append('\n');
        sb.append("\n# Build the prep in iterations with halving min chance (true/false)\n")
                .append(MIN_CHANCE_ITERATION_PROPERTY).append('=').append(minChanceIterationEnabled).append('\n');
        sb.append("\n# What the values of the prep line ends are based on (")
                .append(enumNames(NodeEvaluationMethod.values())).append(")\n")
                .append(NODE_EVALUATION_METHOD_PROPERTY).append('=').append(nodeEvaluationMethod.name()).append('\n');
        Files.writeString(FILE, sb.toString(), StandardCharsets.UTF_8);
        log.info("Settings are saved to {}", FILE.toAbsolutePath());
    }

    private static String enumNames(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.joining(" / "));
    }

    public GeneratorSettings toGeneratorSettings() {
        return GeneratorSettings.builder()
                .engineSearchDepth((int) Math.round(get(Key.ENGINE_SEARCH_DEPTH)))
                .minChance(get(Key.MIN_CHANCE))
                .maxEvalSetback(get(Key.MAX_EVAL_SETBACK))
                .vpMarginForPruning(get(Key.VP_MARGIN_FOR_PRUNING))
                .lichessScoreMarginForPruning(get(Key.LICHESS_SCORE_MARGIN_FOR_PRUNING))
                .deepLineFactor(get(Key.DEEP_LINE_FACTOR))
                .lichessDataSource(lichessDataSource)
                .lichessRatingSelector(getLichessRatingSelector())
                .evalSource(evalSource)
                .isMinChanceIterationEnabled(minChanceIterationEnabled)
                .nodeEvaluationMethod(nodeEvaluationMethod)
                .build();
    }
}
