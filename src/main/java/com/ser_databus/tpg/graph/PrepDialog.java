package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.graph.PrepTreeView.PrepBox;
import com.ser_databus.tpg.graph.PrepTreeView.PrepImage;
import com.ser_databus.tpg.model.MoveHistory;
import com.ser_databus.tpg.model.MyNode;
import com.ser_databus.tpg.model.Node;
import com.ser_databus.tpg.prep.GeneratorSettings;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.stage.Window;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Generates the prep for a position and shows it. Every finished iteration of the generation can be selected;
 * its prep is drawn as a tree ({@link PrepTreeView}), and clicking a box shows its position on the (read-only)
 * board. Closing the window cancels the generation.
 */
@Slf4j
public class PrepDialog {
    private static final double TILE_SIZE = 48;

    private final Stage stage = new Stage();
    private final Board setupInitialBoard;
    /** The moves from {@link #setupInitialBoard} to the root position of the prep */
    private final List<Move> setupMoves;
    private final Side side;
    private final ChessBoardView boardView = new ChessBoardView(TILE_SIZE);
    private final Label positionLabel = new Label();
    private final ComboBox<Iteration> iterationBox = new ComboBox<>();
    private final MenuButton exportButton = new MenuButton("Export");
    /** The result of the last export, e.g. "Saved prep.png" */
    private final Label exportLabel = new Label();
    /**
     * Where the last export of each type (png, txt, pgn) was saved, so the next save dialog of that type starts there.
     * Kept in the user's Java preferences (on Windows, the registry), so it survives restarts.
     */
    private static final Preferences EXPORT_DIRECTORIES = Preferences.userNodeForPackage(PrepDialog.class);
    /** Suggested export file names are cut to this length (without the extension) */
    private static final int MAX_FILE_NAME_LENGTH = 150;
    private final PrepTreeView treeView = new PrepTreeView();
    private final Label statusLabel = new Label("Waiting to start...");
    /** Shown over the status while the prep is being generated */
    private final Tooltip slowGenerationHint = new Tooltip(
            "Generation takes too much time? Try to decrease engine evaluation depth or more aggressive branch pruning");
    private final ProgressIndicator progressIndicator = new ProgressIndicator();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile long startNanos = System.nanoTime();
    /** "Iteration 3 of 7 (minChance 4.0%)", shown before the current operation */
    private String iterationStatus = "";

    /** A finished iteration and the user's move choices in its prep */
    private record Iteration(int number, int count, double minChance, Node prep, long elapsedNanos,
                             Map<MyNode, Move> choices) {
        @Override
        public String toString() {
            return String.format("Iteration %d of %d  (minChance %s, %s)", number, count,
                    PrepTreeView.formatPercent(minChance), formatElapsed(elapsedNanos));
        }
    }

    public PrepDialog(Window owner, Board setupInitialBoard, List<Move> setupMoves, Side side,
                      GeneratorSettings settings, PrepGenerationService service) {
        this.setupInitialBoard = setupInitialBoard.clone();
        this.setupMoves = List.copyOf(setupMoves);
        this.side = side;
        String rootFen = playMoves(List.of()).getFen();

        // left: the board, resizable with the split pane divider
        boardView.setInteractive(false);
        boardView.setOrientation(side);
        positionLabel.setWrapText(true);
        positionLabel.setStyle("-fx-font-size: 13;");
        positionLabel.setMinHeight(Region.USE_PREF_SIZE);
        VBox left = new VBox(8, boardView, positionLabel);
        VBox.setVgrow(boardView, Priority.ALWAYS);
        left.setPadding(new Insets(0, 8, 0, 0));
        left.setMinWidth(220);

        treeView.setOnPositionSelected(this::showPosition);
        SplitPane splitPane = new SplitPane(left, treeView);
        splitPane.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(left, false);

        // top: iteration selector, zoom and the generation status
        iterationBox.setPromptText("No prep yet");
        iterationBox.setPrefWidth(340);
        iterationBox.valueProperty().addListener((obs, oldValue, newValue) -> showIteration(newValue));
        Button fitButton = new Button("Fit");
        fitButton.setOnAction(e -> treeView.zoomToFit());
        Button actualSizeButton = new Button("100%");
        actualSizeButton.setOnAction(e -> treeView.zoomToActualSize());
        MenuItem exportPicture = new MenuItem("Export to picture (PNG)...");
        exportPicture.setOnAction(e -> exportPicture());
        MenuItem exportText = new MenuItem("Export to text...");
        exportText.setOnAction(e -> exportText());
        MenuItem exportPgn = new MenuItem("Export to PGN...");
        exportPgn.setOnAction(e -> exportPgn());
        exportButton.getItems().addAll(exportPicture, exportText, exportPgn);
        exportButton.setTooltip(new Tooltip("Save the selected prep, as it's shown (with your move choices), "
                + "as a picture, as text or as PGN"));
        exportButton.setDisable(true);
        exportLabel.setStyle("-fx-text-fill: gray;");
        exportLabel.setMinWidth(Region.USE_PREF_SIZE);
        Label zoomLabel = new Label();
        zoomLabel.setMinWidth(45);
        zoomLabel.textProperty().bind(treeView.zoomProperty().multiply(100).asString("%.0f%%"));
        progressIndicator.setMaxSize(18, 18);
        progressIndicator.setMinSize(18, 18);
        statusLabel.setMinWidth(0);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        HBox top = new HBox(8, new Label("Prep:"), iterationBox, fitButton, actualSizeButton, zoomLabel,
                exportButton, exportLabel, progressIndicator, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(splitPane);
        root.setPadding(new Insets(8));
        BorderPane.setMargin(top, new Insets(0, 0, 8, 0));

        // No initOwner: on Windows an owned window can't be minimized. Instead, the window closes together with
        // the main window, as an owned one would (closing it cancels the generation)
        EventHandler<WindowEvent> closeWithOwner = e -> stage.close();
        if (owner != null) {
            owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
        }
        stage.setTitle("Prep for " + (side == Side.WHITE ? "White" : "Black") + " — " + rootFen);
        stage.setScene(new Scene(root, 1400, 820));
        stage.setOnHidden(e -> {
            cancelled.set(true);
            // The main window must forget this one, or every closed prep window (with all its preps) stays in memory
            if (owner != null) {
                owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, closeWithOwner);
            }
            releaseMemorySoon();
        });

        showPosition(null);
        service.generate(rootFen, side, settings, new UiCallbacks(), cancelled::get);
    }

    /**
     * A closed window's preps are garbage, but Java keeps the memory they took (and Task Manager shows it as used)
     * until a full garbage collection, which may not come for a long time. Once the cancelled generation has stopped
     * (it checks every 200 ms), a full collection frees the preps and gives the memory back to the system.
     */
    private static void releaseMemorySoon() {
        Thread collector = new Thread(() -> {
            try {
                Thread.sleep(2000);
                System.gc();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "release-memory");
        collector.setDaemon(true);
        collector.start();
    }

    public void show() {
        stage.show();
    }

    private void showIteration(Iteration iteration) {
        exportButton.setDisable(iteration == null || iteration.prep() == null);
        exportLabel.setText("");
        if (iteration == null) {
            treeView.setPrep(null, new IdentityHashMap<>());
        } else {
            if (iteration.prep() == null) {
                treeView.setPlaceholderText(
                        "The position is game over or next to game over: there is nothing to prepare");
            }
            treeView.setPrep(iteration.prep(), iteration.choices());
        }
        showPosition(null);
    }

    /** Shows the final position of the line of the node, or the root position if the node is null. */
    private void showPosition(Node node) {
        List<Move> prepMoves = node == null ? List.of() : node.moveHistory.getMoves();
        List<Move> allMoves = new ArrayList<>(setupMoves);
        allMoves.addAll(prepMoves);
        boardView.setPosition(playMoves(prepMoves), ChessBoardView.lastMoveOfEachSide(allMoves));
        positionLabel.setText(node == null ? "Starting position of the prep" : PrepTreeView.lineText(node));
    }

    private Board playMoves(List<Move> prepMoves) {
        Board board = setupInitialBoard.clone();
        for (Move move : setupMoves) {
            board.doMove(move);
        }
        for (Move move : prepMoves) {
            board.doMove(move);
        }
        return board;
    }

    // ---------- export ----------

    private void exportPicture() {
        Iteration iteration = iterationBox.getValue();
        File file = chooseExportFile(iteration, "png", "PNG picture");
        if (file == null) {
            return;
        }
        PrepImage picture = renderPicture(file);
        if (picture == null) {
            return;
        }
        String scaleNote = picture.scale() < 1
                ? String.format(" (scaled to %.0f%%: the tree is too big for 100%%)", picture.scale() * 100) : "";
        exportLabel.setText("Saving " + file.getName() + "...");
        // encoding a big PNG takes a while: not on the JavaFX thread
        Thread saver = new Thread(() -> {
            try {
                if (!ImageIO.write(picture.image(), "png", file)) {
                    throw new IOException("No PNG writer is available");
                }
                Platform.runLater(() -> exportLabel.setText("Saved " + file.getName() + scaleNote));
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Platform.runLater(() -> {
                    exportLabel.setText("");
                    showExportError(file, e);
                });
            }
        }, "prep-picture-export");
        // not a daemon: closing the app doesn't cut the file off
        saver.setDaemon(false);
        saver.start();
    }

    /** The picture of the shown prep; null if there is none or it couldn't be rendered (the error is shown) */
    private PrepImage renderPicture(File file) {
        try {
            return treeView.renderImage();
        } catch (RuntimeException | OutOfMemoryError e) {
            showExportError(file, e);
            return null;
        }
    }

    private void exportText() {
        Iteration iteration = iterationBox.getValue();
        PrepBox prep = treeView.getShownPrep();
        if (prep == null) {
            return;
        }
        File file = chooseExportFile(iteration, "txt", "Text file");
        if (file == null) {
            return;
        }
        List<String> header = List.of(
                "Prep for " + sideName() + ", " + iterationText(iteration),
                "Starting position: " + setupLineText(),
                "FEN: " + playMoves(List.of()).getFen());
        writeExport(file, PrepExporter.toText(prep, header));
    }

    private void exportPgn() {
        Iteration iteration = iterationBox.getValue();
        PrepBox prep = treeView.getShownPrep();
        if (prep == null) {
            return;
        }
        File file = chooseExportFile(iteration, "pgn", "PGN file");
        if (file == null) {
            return;
        }
        writeExport(file, PrepExporter.toPgn(prep, setupInitialBoard, setupMoves, "Prep for " + sideName(),
                iterationText(iteration)));
    }

    /** Shows the save dialog; null if the user cancelled it */
    private File chooseExportFile(Iteration iteration, String extension, String description) {
        if (iteration == null || iteration.prep() == null) {
            return null;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export the prep");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, "*." + extension));
        chooser.setInitialFileName(exportFileName() + "." + extension);
        File lastDirectory = lastExportDirectory(extension);
        if (lastDirectory != null) {
            chooser.setInitialDirectory(lastDirectory);
        }
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return null;
        }
        if (!file.getName().toLowerCase().endsWith("." + extension)) {
            file = new File(file.getParentFile(), file.getName() + "." + extension);
        }
        rememberExportDirectory(extension, file.getParentFile());
        return file;
    }

    /** The folder of the last export of this type; null if there was none or the folder is gone */
    private static File lastExportDirectory(String extension) {
        String path = EXPORT_DIRECTORIES.get(exportDirectoryKey(extension), null);
        if (path == null) {
            return null;
        }
        File directory = new File(path);
        return directory.isDirectory() ? directory : null;
    }

    private static void rememberExportDirectory(String extension, File directory) {
        if (directory == null) {
            return;
        }
        try {
            EXPORT_DIRECTORIES.put(exportDirectoryKey(extension), directory.getAbsolutePath());
            EXPORT_DIRECTORIES.flush();
        } catch (BackingStoreException | RuntimeException e) {
            // not remembering the folder isn't worth bothering the user
            log.warn("Couldn't remember the export folder {}: {}", directory, e.toString());
        }
    }

    private static String exportDirectoryKey(String extension) {
        return "exportDirectory." + extension;
    }

    private void writeExport(File file, String content) {
        try {
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            exportLabel.setText("Saved " + file.getName());
        } catch (IOException | RuntimeException e) {
            showExportError(file, e);
        }
    }

    private void showExportError(File file, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR, "Couldn't save " + file + ":\n" + error);
        alert.initOwner(stage);
        alert.show();
    }

    /** "Iteration 3 of 7 (minChance 4.0%)" */
    private static String iterationText(Iteration iteration) {
        return String.format("Iteration %d of %d (minChance %s)", iteration.number(), iteration.count(),
                PrepTreeView.formatPercent(iteration.minChance()));
    }

    /**
     * The suggested name of an export file, without the extension: the line to the prep and the side, e.g.
     * "1. e4 e5 2. Nf3 Nc6 for White". Characters Windows doesn't allow in file names are left out.
     */
    private String exportFileName() {
        String line = setupMoves.isEmpty() ? "Initial position" : setupLineText();
        String name = (line + " for " + sideName()).replaceAll("[\\\\/:*?\"<>|]", "").trim();
        // Windows paths are limited to 260 characters: keep the end of a very long line
        return name.length() <= MAX_FILE_NAME_LENGTH ? name
                : "..." + name.substring(name.length() - MAX_FILE_NAME_LENGTH + 3).trim();
    }

    private String sideName() {
        return side == Side.WHITE ? "White" : "Black";
    }

    /** The moves from the initial position of the explorer to the starting position of the prep */
    private String setupLineText() {
        if (setupMoves.isEmpty()) {
            return "the initial position";
        }
        MoveHistory history = MoveHistory.empty();
        Board board = setupInitialBoard.clone();
        for (Move move : setupMoves) {
            history = history.prolong(board, move);
            board.doMove(move);
        }
        return history.toString();
    }

    private static String formatElapsed(long nanos) {
        long seconds = nanos / 1_000_000_000L;
        return seconds >= 3600
                ? String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
                : String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    private void setStatus(String text, boolean inProgress) {
        statusLabel.setText(text);
        statusLabel.setTooltip(inProgress ? slowGenerationHint : null);
        progressIndicator.setVisible(inProgress);
        progressIndicator.setManaged(inProgress);
    }

    /** Receives the progress on the generator thread and shows it on the JavaFX thread. */
    private class UiCallbacks implements PrepGenerationService.Callbacks {
        @Override
        public void onStarted() {
            Platform.runLater(() -> {
                startNanos = System.nanoTime();
                setStatus("Starting...", true);
            });
        }

        @Override
        public void onIterationStarted(int iteration, int iterationCount, double minChance) {
            Platform.runLater(() -> {
                iterationStatus = String.format("Iteration %d of %d (minChance %s)",
                        iteration, iterationCount, PrepTreeView.formatPercent(minChance));
                setStatus(iterationStatus + "...", true);
            });
        }

        @Override
        public void onProgress(String message) {
            long elapsed = System.nanoTime() - startNanos;
            Platform.runLater(() -> setStatus(iterationStatus + ": " + message + "   [" + formatElapsed(elapsed) + "]",
                    true));
        }

        @Override
        public void onIterationCompleted(int iteration, int iterationCount, double minChance, Node prep) {
            long elapsed = System.nanoTime() - startNanos;
            Platform.runLater(() -> {
                var items = iterationBox.getItems();
                Iteration selected = iterationBox.getValue();
                // follow the newest iteration, unless the user picked an older one
                boolean follow = selected == null || selected == items.get(items.size() - 1);
                Iteration newIteration = new Iteration(iteration, iterationCount, minChance, prep, elapsed,
                        new IdentityHashMap<>());
                items.add(newIteration);
                if (follow) {
                    iterationBox.setValue(newIteration);
                }
            });
        }

        @Override
        public void onFinished() {
            long elapsed = System.nanoTime() - startNanos;
            Platform.runLater(() -> setStatus("Done in " + formatElapsed(elapsed), false));
        }

        @Override
        public void onCancelled() {
            Platform.runLater(() -> setStatus("Cancelled", false));
        }

        @Override
        public void onFailed(Throwable error) {
            Platform.runLater(() -> {
                setStatus("Failed: " + error, false);
                if (stage.isShowing()) {
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                            "Prep generation failed:\n" + error + (error.getCause() == null ? ""
                                    : "\nCaused by: " + error.getCause()));
                    alert.initOwner(stage);
                    alert.show();
                }
            });
        }
    }
}
