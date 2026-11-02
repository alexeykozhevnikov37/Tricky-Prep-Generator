package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import com.ser_databus.tpg.evaluate.EvalSource;
import com.ser_databus.tpg.lichess.LichessDataSource;
import com.ser_databus.tpg.util.MoveUtil;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * The main window: the user sets up a position by playing moves on the board (or by pasting a FEN), chooses
 * the side to prepare for by flipping the board, and generates the prep in a {@link PrepDialog}.
 */
public class ChessExplorer extends Application {
    private static final double TILE_SIZE = 64;
    private static final String SETUP_PROMPT = "Set up the position to generate prep";
    private static final String MOVE_STYLE = "-fx-padding: 1 4 1 4; -fx-cursor: hand; -fx-font-size: 13;";
    private static final String CURRENT_MOVE_STYLE = MOVE_STYLE
            + "-fx-background-color: #3481ca; -fx-text-fill: white; -fx-background-radius: 3;";
    private static final String SECTION_STYLE = "-fx-font-weight: bold;";

    private Stage stage;
    private ChessBoardView boardView;
    private Board initialBoard = new Board();
    private Board currentBoard = new Board();
    /** The moves played from {@link #initialBoard}; the board shows the position after the first currentPly */
    private final List<Move> moves = new ArrayList<>();
    private final List<String> moveTexts = new ArrayList<>();
    private int currentPly;
    private FlowPane movesPane;
    private TextField fenField;
    private Label sideLabel;
    private Label engineStatusLabel;
    private ProgressIndicator engineProgress;
    private ExplorerSettings settings;
    private final PrepGenerationService generationService = new PrepGenerationService();

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        settings = ExplorerSettings.load();

        boardView = new ChessBoardView(TILE_SIZE);
        boardView.setInteractive(true);
        boardView.setOnMove(this::playMove);

        Label header = new Label(SETUP_PROMPT);
        header.setStyle("-fx-font-size: 20; -fx-font-weight: bold;");
        header.setMinWidth(0);
        // the board takes all the space left by the header and the side panel and resizes with the window
        VBox boardBox = new VBox(12, header, boardView);
        boardBox.setAlignment(Pos.TOP_CENTER);
        boardBox.setPadding(new Insets(10));
        boardBox.setMinSize(0, 0);
        VBox.setVgrow(boardView, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.setTop(createMenuBar());
        root.setCenter(boardBox);
        root.setRight(createSidePanel());

        Scene scene = new Scene(root);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);
        stage.setTitle("Tricky prep generator");
        stage.setScene(scene);
        // the prep windows are owned by this one: closing it ends the application
        stage.setOnHidden(e -> Platform.exit());
        goToPly(0);
        stage.show();
        stage.setMinWidth(700);
        stage.setMinHeight(520);
        startEngines();
    }

    /** Starts the engines and opens the databases now, so "Generate" doesn't have to wait for them. */
    private void startEngines() {
        setEngineStatus("Starting the engines and loading the databases...", "-fx-text-fill: gray;", true, null);
        generationService.startUp(settings.getLichessDataSource(), settings.getEvalSource(),
                () -> Platform.runLater(() -> setEngineStatus("Engines are ready", "-fx-text-fill: #2e7d32;",
                        false, null)),
                error -> Platform.runLater(() -> setEngineStatus("Couldn't start the engines (hover for details). "
                                + "They will be started again on Generate.", "-fx-text-fill: #c62828;", false,
                        error.toString() + (error.getCause() == null ? "" : "\nCaused by: " + error.getCause()))));
    }

    /**
     * The Java heap actually in use, updated every 2 seconds. Task Manager shows how much memory Java took from the
     * system, which only shrinks after a full garbage collection, so it can't show whether memory is freed.
     */
    private static Label createMemoryLabel() {
        Label label = new Label();
        label.setStyle("-fx-text-fill: gray;");
        label.setTooltip(new Tooltip("Java heap in use (including garbage not collected yet) / taken from the system / "
                + "the maximum Java may take (-Xmx)"));
        Runnable update = () -> {
            Runtime runtime = Runtime.getRuntime();
            long mb = 1024 * 1024;
            label.setText(String.format("Memory: %d MB used, %d MB taken, %d MB max",
                    (runtime.totalMemory() - runtime.freeMemory()) / mb, runtime.totalMemory() / mb,
                    runtime.maxMemory() / mb));
        };
        update.run();
        Timeline timeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> update.run()));
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
        return label;
    }

    private void setEngineStatus(String text, String style, boolean inProgress, String details) {
        engineStatusLabel.setText(text);
        engineStatusLabel.setStyle(style);
        engineStatusLabel.setTooltip(details == null ? null : new Tooltip(details));
        engineProgress.setVisible(inProgress);
        engineProgress.setManaged(inProgress);
    }

    @Override
    public void stop() {
        generationService.shutdown();
        // Ends the engine threads; engines and databases are closed by their shutdown hooks.
        // Called from another thread, so the JavaFX thread can finish its own shutdown meanwhile.
        new Thread(() -> System.exit(0), "exit").start();
    }

    private MenuBar createMenuBar() {
        // A menu without items doesn't fire actions, so the menu title is a clickable label
        Label settingsLabel = new Label("Settings");
        settingsLabel.setOnMouseClicked(e -> openSettings());
        Menu settingsMenu = new Menu();
        settingsMenu.setGraphic(settingsLabel);
        return new MenuBar(settingsMenu);
    }

    private VBox createSidePanel() {
        sideLabel = new Label();
        sideLabel.setStyle("-fx-font-size: 14;");
        Button flipButton = new Button("Flip board");
        flipButton.setTooltip(new Tooltip("The prep is generated for the side at the bottom of the board"));
        flipButton.setOnAction(e -> flipBoard());
        Region sideSpacer = new Region();
        HBox.setHgrow(sideSpacer, Priority.ALWAYS);
        HBox sideBox = new HBox(10, sideLabel, sideSpacer, flipButton);
        sideBox.setAlignment(Pos.CENTER_LEFT);

        movesPane = new FlowPane(2, 4);
        movesPane.setPadding(new Insets(4));
        ScrollPane movesScroll = new ScrollPane(movesPane);
        movesScroll.setFitToWidth(true);
        movesScroll.setPrefViewportHeight(200);
        // the move list gives up height first when the window gets smaller
        movesScroll.setMinViewportHeight(40);

        Button startButton = navigationButton("⏮", "To the start (Home)", () -> goToPly(0));
        Button backButton = navigationButton("◀", "Move back (Left)", () -> goToPly(Math.max(0, currentPly - 1)));
        Button forwardButton = navigationButton("▶", "Move forward (Right)",
                () -> goToPly(Math.min(moves.size(), currentPly + 1)));
        Button endButton = navigationButton("⏭", "To the last move (End)", () -> goToPly(moves.size()));
        Button newGameButton = new Button("New game");
        newGameButton.setOnAction(e -> setInitialBoard(new Board()));
        Region navSpacer = new Region();
        HBox.setHgrow(navSpacer, Priority.ALWAYS);
        HBox navigationBox = new HBox(4, startButton, backButton, forwardButton, endButton, navSpacer, newGameButton);

        fenField = new TextField();
        fenField.setPromptText("Paste FEN here...");
        fenField.setOnAction(e -> loadFen());
        Button loadFenButton = new Button("Load FEN");
        loadFenButton.setOnAction(e -> loadFen());
        HBox fenBox = new HBox(6, fenField, loadFenButton);
        HBox.setHgrow(fenField, Priority.ALWAYS);

        Button generateButton = new Button("Generate");
        generateButton.setMaxWidth(Double.MAX_VALUE);
        generateButton.setPrefHeight(44);
        generateButton.setStyle("-fx-font-size: 16; -fx-font-weight: bold;");
        generateButton.setTooltip(new Tooltip("Generate the prep for the position on the board"));
        generateButton.setOnAction(e -> generate());

        engineProgress = new ProgressIndicator();
        engineProgress.setMaxSize(16, 16);
        engineStatusLabel = new Label();
        engineStatusLabel.setWrapText(true);
        HBox engineStatusBox = new HBox(6, engineProgress, engineStatusLabel);
        engineStatusBox.setAlignment(Pos.CENTER_LEFT);
        Label memoryLabel = createMemoryLabel();

        Label movesTitle = new Label("Moves");
        movesTitle.setStyle(SECTION_STYLE);
        Label fenTitle = new Label("FEN");
        fenTitle.setStyle(SECTION_STYLE);
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        VBox panel = new VBox(10, sideBox, new Separator(), movesTitle, movesScroll, navigationBox,
                new Separator(), fenTitle, fenBox, spacer, memoryLabel, engineStatusBox, generateButton);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(340);
        panel.setMinWidth(340);
        return panel;
    }

    private static Button navigationButton(String text, String hint, Runnable action) {
        Button button = new Button(text);
        button.setTooltip(new Tooltip(hint));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void playMove(Move move) {
        String text = moveText(currentBoard, move, currentPly == 0);
        // a new move discards the moves that came after the current position
        while (moves.size() > currentPly) {
            moves.remove(moves.size() - 1);
            moveTexts.remove(moveTexts.size() - 1);
        }
        moves.add(move);
        moveTexts.add(text);
        goToPly(currentPly + 1);
    }

    /** "12. Nf3", "12... Nf6" for Black's first listed move, or just "Nf6" */
    private static String moveText(Board boardBefore, Move move, boolean isFirstMove) {
        String prefix = boardBefore.getSideToMove() == Side.WHITE ? boardBefore.getMoveCounter() + ". "
                : isFirstMove ? boardBefore.getMoveCounter() + "... " : "";
        return prefix + MoveUtil.moveToSan(boardBefore, move);
    }

    /** Shows the position after the first {@code ply} moves. */
    private void goToPly(int ply) {
        Board board = initialBoard.clone();
        for (int i = 0; i < ply; i++) {
            board.doMove(moves.get(i));
        }
        currentBoard = board;
        currentPly = ply;
        boardView.setPosition(board, ChessBoardView.lastMoveOfEachSide(moves.subList(0, ply)));
        fenField.setText(board.getFen());
        updateMovesPane();
        updateSideLabel();
    }

    private void updateMovesPane() {
        List<Label> labels = new ArrayList<>();
        for (int i = 0; i < moveTexts.size(); i++) {
            Label label = new Label(moveTexts.get(i));
            int ply = i + 1;
            label.setStyle(ply == currentPly ? CURRENT_MOVE_STYLE : MOVE_STYLE);
            label.setOnMouseClicked(e -> goToPly(ply));
            labels.add(label);
        }
        movesPane.getChildren().setAll(labels);
    }

    private void setInitialBoard(Board board) {
        initialBoard = board;
        moves.clear();
        moveTexts.clear();
        goToPly(0);
    }

    private void loadFen() {
        String fen = fenField.getText().trim();
        if (fen.isEmpty() || fen.equals(currentBoard.getFen())) {
            return;
        }
        try {
            Board board = new Board();
            board.loadFromFen(fen);
            if (board.getKingSquare(Side.WHITE) == Square.NONE || board.getKingSquare(Side.BLACK) == Square.NONE) {
                throw new IllegalArgumentException("Both kings must be on the board");
            }
            MoveGenerator.generateLegalMoves(board);
            setInitialBoard(board);
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Invalid FEN: " + fen);
            alert.initOwner(stage);
            alert.showAndWait();
            fenField.setText(currentBoard.getFen());
        }
    }

    private void flipBoard() {
        boardView.flip();
        updateSideLabel();
    }

    private void updateSideLabel() {
        Side side = boardView.getOrientation();
        String toMove = currentBoard.getSideToMove() == side ? "your move" : "opponent's move";
        sideLabel.setText("Prep for " + (side == Side.WHITE ? "White" : "Black") + " (" + toMove + ")");
    }

    private void handleKey(KeyEvent e) {
        if (stage.getScene().getFocusOwner() instanceof TextInputControl) {
            return;
        }
        switch (e.getCode()) {
            case LEFT -> goToPly(Math.max(0, currentPly - 1));
            case RIGHT -> goToPly(Math.min(moves.size(), currentPly + 1));
            case HOME -> goToPly(0);
            case END -> goToPly(moves.size());
            default -> {
                return;
            }
        }
        e.consume();
    }

    private void openSettings() {
        LichessDataSource oldLichessDataSource = settings.getLichessDataSource();
        EvalSource oldEvalSource = settings.getEvalSource();
        if (new SettingsDialog(stage, settings).showAndWait()
                && (settings.getLichessDataSource() != oldLichessDataSource
                || settings.getEvalSource() != oldEvalSource)) {
            // Open the new source now (after a running generation, if any), so "Generate" doesn't wait for it
            startEngines();
        }
    }

    private void generate() {
        if (currentBoard.isMated() || currentBoard.isStaleMate()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, "The game is over in this position: there is nothing to prepare.");
            alert.initOwner(stage);
            alert.showAndWait();
            return;
        }
        PrepDialog dialog = new PrepDialog(stage, initialBoard, moves.subList(0, currentPly),
                boardView.getOrientation(), settings.toGeneratorSettings(), generationService);
        dialog.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
