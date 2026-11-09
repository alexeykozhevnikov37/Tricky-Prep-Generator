package com.ser_databus.tpg.graph;

import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.lichess.LichessRating;
import com.ser_databus.tpg.prep.NodeEvaluationMethod;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.ser_databus.tpg.graph.ExplorerSettings.Key;

/** Edits the {@link ExplorerSettings} and saves them to their file when the user presses Save. */
public class SettingsDialog {
    private static final String SECTION_STYLE = "-fx-font-weight: bold;";

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ExplorerSettings settings;
    private final Map<Key, TextField> fields = new EnumMap<>(Key.class);
    private final ComboBox<EvalSource> evalSourceBox = createEnumBox(EvalSource.values(), ExplorerSettings::label);
    private final ComboBox<LichessDataSource> lichessDataSourceBox =
            createEnumBox(LichessDataSource.values(), ExplorerSettings::label);
    private final ComboBox<LichessRating> ratingFromBox = createEnumBox(LichessRating.values(), LichessRating::toString);
    private final ComboBox<LichessRating> ratingToBox = createEnumBox(LichessRating.values(), LichessRating::toString);
    private final CheckBox mastersBox = new CheckBox("Masters");
    private final CheckBox minChanceIterationBox = new CheckBox("Build the prep in iterations (halving min chance)");
    private final ComboBox<NodeEvaluationMethod> nodeEvaluationMethodBox =
            createEnumBox(NodeEvaluationMethod.values(), ExplorerSettings::label);
    private final Label errorLabel = new Label();

    public SettingsDialog(Window owner, ExplorerSettings settings) {
        this.settings = settings;
        dialog.initOwner(owner);
        dialog.setTitle("Settings");
        dialog.setHeaderText("Prep generator settings\nHover over a setting to see what it does.");

        // Data sources
        GridPane sourcesGrid = createGrid();
        int row = 0;
        addRow(sourcesGrid, row++, "Stockfish evaluations", ExplorerSettings.EVAL_SOURCE_PROPERTY,
                ExplorerSettings.EVAL_SOURCE_HINT, evalSourceBox);
        addRow(sourcesGrid, row++, "Lichess stats", ExplorerSettings.LICHESS_DATA_SOURCE_PROPERTY,
                ExplorerSettings.LICHESS_DATA_SOURCE_HINT, lichessDataSourceBox);
        HBox ratingBox = new HBox(6, new Label("from"), ratingFromBox, new Label("to"), ratingToBox, mastersBox);
        ratingBox.setAlignment(Pos.CENTER_LEFT);
        Label ratingLabel = addRow(sourcesGrid, row++, "Lichess ratings",
                ExplorerSettings.LICHESS_MASTERS_PROPERTY + ", " + ExplorerSettings.LICHESS_RATING_FROM_PROPERTY
                        + ", " + ExplorerSettings.LICHESS_RATING_TO_PROPERTY,
                ExplorerSettings.RATING_HINT, ratingBox);
        for (Control control : List.of(ratingFromBox, ratingToBox, mastersBox)) {
            control.setTooltip(createTooltip(ExplorerSettings.LICHESS_MASTERS_PROPERTY + ", "
                    + ExplorerSettings.LICHESS_RATING_FROM_PROPERTY + ", "
                    + ExplorerSettings.LICHESS_RATING_TO_PROPERTY, ExplorerSettings.RATING_HINT));
        }
        // The rating selector is used only by Lichess online; from/to don't apply to the masters database
        BooleanBinding ratingsUnused = Bindings.createBooleanBinding(
                () -> lichessDataSourceBox.getValue() != LichessDataSource.PUBLIC_API,
                lichessDataSourceBox.valueProperty());
        ratingLabel.disableProperty().bind(ratingsUnused);
        ratingBox.disableProperty().bind(ratingsUnused);
        ratingFromBox.disableProperty().bind(mastersBox.selectedProperty());
        ratingToBox.disableProperty().bind(mastersBox.selectedProperty());

        // Generation
        GridPane grid = createGrid();
        row = 0;
        addRow(grid, row++, "MinChance iteration", ExplorerSettings.MIN_CHANCE_ITERATION_PROPERTY,
                ExplorerSettings.MIN_CHANCE_ITERATION_HINT, minChanceIterationBox);
        minChanceIterationBox.setTooltip(createTooltip(ExplorerSettings.MIN_CHANCE_ITERATION_PROPERTY,
                ExplorerSettings.MIN_CHANCE_ITERATION_HINT));
        addRow(grid, row++, "Node evaluation", ExplorerSettings.NODE_EVALUATION_METHOD_PROPERTY,
                ExplorerSettings.NODE_EVALUATION_METHOD_HINT, nodeEvaluationMethodBox);
        nodeEvaluationMethodBox.setTooltip(createTooltip(ExplorerSettings.NODE_EVALUATION_METHOD_PROPERTY,
                ExplorerSettings.NODE_EVALUATION_METHOD_HINT));
        for (Key key : Key.values()) {
            Label label = new Label(key.label + ":");
            label.setTooltip(createTooltip(key.property, key.hint));
            TextField field = new TextField();
            field.setPrefColumnCount(8);
            field.setTooltip(createTooltip(key.property, key.hint));
            Label range = new Label(key.rangeText());
            range.setStyle("-fx-text-fill: gray;");
            grid.addRow(row++, label, field, range);
            fields.put(key, field);
        }
        showSettings();

        Label sourcesHeader = new Label("Data sources");
        sourcesHeader.setStyle(SECTION_STYLE);
        Label generationHeader = new Label("Generation");
        generationHeader.setStyle(SECTION_STYLE);
        errorLabel.setStyle("-fx-text-fill: #c62828;");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(520);
        Label fileLabel = new Label("Saved to " + ExplorerSettings.FILE.toAbsolutePath());
        fileLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11;");
        VBox content = new VBox(10, sourcesHeader, sourcesGrid, new Separator(), generationHeader, grid, errorLabel,
                fileLabel);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        ButtonType defaultsType = new ButtonType("Restore defaults", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(defaultsType, ButtonType.OK, ButtonType.CANCEL);
        Button defaultsButton = (Button) dialog.getDialogPane().lookupButton(defaultsType);
        defaultsButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); // keep the dialog open
            showDefaults();
            errorLabel.setText("");
        });
        Button saveButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        saveButton.setText("Save");
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            if (!applyAndSave()) {
                event.consume(); // invalid input: keep the dialog open
            }
        });
    }

    /** @return true if the settings were changed and saved */
    public boolean showAndWait() {
        return dialog.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }

    private void showSettings() {
        fields.forEach((key, field) -> field.setText(settings.format(key)));
        evalSourceBox.setValue(settings.getEvalSource());
        lichessDataSourceBox.setValue(settings.getLichessDataSource());
        mastersBox.setSelected(settings.isLichessMasters());
        ratingFromBox.setValue(settings.getLichessRatingFrom());
        ratingToBox.setValue(settings.getLichessRatingTo());
        minChanceIterationBox.setSelected(settings.isMinChanceIterationEnabled());
        nodeEvaluationMethodBox.setValue(settings.getNodeEvaluationMethod());
    }

    private void showDefaults() {
        fields.forEach((key, field) -> field.setText(key.format(key.defaultValue)));
        evalSourceBox.setValue(ExplorerSettings.DEFAULT_EVAL_SOURCE);
        lichessDataSourceBox.setValue(ExplorerSettings.DEFAULT_LICHESS_DATA_SOURCE);
        mastersBox.setSelected(ExplorerSettings.DEFAULT_LICHESS_MASTERS);
        ratingFromBox.setValue(ExplorerSettings.DEFAULT_LICHESS_RATING_FROM);
        ratingToBox.setValue(ExplorerSettings.DEFAULT_LICHESS_RATING_TO);
        minChanceIterationBox.setSelected(ExplorerSettings.DEFAULT_MIN_CHANCE_ITERATION_ENABLED);
        nodeEvaluationMethodBox.setValue(ExplorerSettings.DEFAULT_NODE_EVALUATION_METHOD);
    }

    private static GridPane createGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        return grid;
    }

    /** @return the label of the row */
    private static Label addRow(GridPane grid, int row, String text, String property, String hint, Node control) {
        Label label = new Label(text + ":");
        label.setTooltip(createTooltip(property, hint));
        grid.add(label, 0, row);
        grid.add(control, 1, row, 2, 1);
        return label;
    }

    private static <E extends Enum<E>> ComboBox<E> createEnumBox(E[] values, Function<E, String> label) {
        ComboBox<E> box = new ComboBox<>();
        box.getItems().setAll(values);
        box.setConverter(new StringConverter<>() {
            @Override
            public String toString(E value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public E fromString(String string) {
                return null; // not editable
            }
        });
        return box;
    }

    private static Tooltip createTooltip(String property, String hint) {
        Tooltip tooltip = new Tooltip(property + "\n\n" + hint);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(420);
        tooltip.setShowDelay(Duration.millis(300));
        tooltip.setShowDuration(Duration.seconds(60));
        return tooltip;
    }

    private boolean applyAndSave() {
        Map<Key, Double> parsed = new EnumMap<>(Key.class);
        List<String> errors = new ArrayList<>();
        fields.forEach((key, field) -> {
            String text = field.getText().trim().replace(',', '.');
            try {
                double value = Double.parseDouble(text);
                if (key.isValid(value)) {
                    parsed.put(key, value);
                } else {
                    errors.add(key.label + " must be " + key.rangeText() + ".");
                }
            } catch (NumberFormatException e) {
                errors.add(key.label + ": \"" + field.getText() + "\" is not a number.");
            }
        });
        boolean masters = mastersBox.isSelected();
        LichessRating from = ratingFromBox.getValue();
        LichessRating to = ratingToBox.getValue();
        // Checked even when Lichess online isn't selected: the range is saved and may be used later
        if (!masters && from.getRating() > to.getRating()) {
            errors.add("Lichess ratings: 'from' (" + from + ") must not be above 'to' (" + to + ").");
        }
        if (!errors.isEmpty()) {
            errorLabel.setText(String.join("\n", errors));
            return false;
        }
        parsed.forEach(settings::set);
        settings.setEvalSource(evalSourceBox.getValue());
        settings.setLichessDataSource(lichessDataSourceBox.getValue());
        settings.setLichessRatings(masters, from, to);
        settings.setMinChanceIterationEnabled(minChanceIterationBox.isSelected());
        settings.setNodeEvaluationMethod(nodeEvaluationMethodBox.getValue());
        try {
            settings.save();
        } catch (IOException e) {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                    "The settings are applied, but couldn't be saved to "
                            + ExplorerSettings.FILE.toAbsolutePath() + ":\n" + e.getMessage());
            alert.initOwner(dialog.getOwner());
            alert.showAndWait();
        }
        return true;
    }
}
