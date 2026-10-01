package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import de.codecentric.centerdevice.javafxsvg.SvgImageLoaderFactory;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;

import java.util.*;
import java.util.stream.Collectors;

import static com.ser_databus.tpg.util.MoveUtil.moveToSan;

public class ChessExplorer extends Application {
    private static final String IMAGES_FOLDER = "src/main/resources/images/";
    private static final int FILE_COUNT = 8, RANK_COUNT = FILE_COUNT;
    private static final int TILE_SIZE = 60;
    private static final Color DARK_SQUARE_COLOR = Color.rgb(181, 136, 99);
    private static final Color LIGHT_SQUARE_COLOR = Color.rgb(240, 217, 181);
    private static final Color DARK_SELECTION_FROM_COLOR = Color.rgb(100, 111, 64);
    private static final Color LIGHT_SELECTION_FROM_COLOR = Color.rgb(130, 151, 105);
    private static final Color DARK_SELECTION_TO_COLOR = Color.rgb(132, 121, 78);
    private static final Color LIGHT_SELECTION_TO_COLOR = Color.rgb(174, 177, 135);
    private static final Color DARK_PREVIOUS_MOVE_COLOR = Color.rgb(170, 162, 58);
    private static final Color LIGHT_PREVIOUS_MOVE_COLOR = Color.rgb(205, 210, 106);
    private static final Color LABEL_SELECTED_COLOR = Color.rgb(52, 129, 202);
    private GridPane boardPane;
    private Board initialBoard;
    private Board currentBoard;
    private Move lastMove = null;
    private int currentPly = 0;
    private Label[] fileLabels;
    private Label[] rankLabels;
    private ImageView[][] pictures;
    private Rectangle[][] rectangles;
    private Pane[][] selections;
    private Circle[][] circles;
    private TextArea fenArea;
    private List<Label> plyToLabel = new ArrayList<>();
    private List<Move> plyToMove = new ArrayList<>();
    private Pane movesPane;

    private Square selectedSquare = null;
    private Square focused;
    private List<Square> squaresToMove = null;
    private final Map<Piece, Image> pieceToImage = new HashMap<>();

    public ChessExplorer() {
        for (Piece piece : Piece.allPieces) {
            if (piece == Piece.NONE) {
                continue;
            }
            String filePath = IMAGES_FOLDER + piece.name().toLowerCase() + ".svg";
            Image img = new Image(Objects.requireNonNull(
                    getClass().getResourceAsStream(filePath)
            ));
            pieceToImage.put(piece, img);
        }
    }

    @Override
    public void start(Stage stage) {
        SvgImageLoaderFactory.install();
        initialBoard = new Board();
        currentBoard = initialBoard.clone();

        // Chessboard UI
        boardPane = new GridPane();
        boardPane.setOnMouseMoved(this::handleMouseMove);
        boardPane.setOnMouseExited(e -> handleMouseExited());

        // Move tree
        movesPane = new Pane();
        fileLabels = new Label[FILE_COUNT];
        rankLabels = new Label[RANK_COUNT];
        pictures = new ImageView[FILE_COUNT][FILE_COUNT];
        rectangles = new Rectangle[FILE_COUNT][FILE_COUNT];
        circles = new Circle[FILE_COUNT][FILE_COUNT];
        selections = new Pane[FILE_COUNT][FILE_COUNT];
        rankLabels = new Label[FILE_COUNT];

        // file labels on top
        for (int file = 0; file < FILE_COUNT; file++) {
            Label fileLbl = new Label(String.valueOf((char) ('a' + file)));
            fileLbl.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
            boardPane.add(fileLbl, file + 1, 0);
            fileLabels[file] = fileLbl;
        }
        // rank labels on left
        for (int rank = 0; rank < RANK_COUNT; rank++) {
            Label rankLbl = new Label(String.valueOf(8 - rank));
            rankLbl.setStyle("-fx-font-size: 14; -fx-font-weight: bold;");
            boardPane.add(rankLbl, 0, rank + 1);
            rankLabels[rank] = rankLbl;
        }

        for (int rank = 0; rank < RANK_COUNT; rank++) {
            for (int file = 0; file < FILE_COUNT; file++) {
                Square sq = getSquareByRankAndFile(rank, file);
                Rectangle rect = new Rectangle(TILE_SIZE, TILE_SIZE);
                rectangles[rank][file] = rect;

                ImageView imageView = new ImageView();
                imageView.setFitWidth(TILE_SIZE * 0.8);
                imageView.setFitHeight(TILE_SIZE * 0.8);
                imageView.setPreserveRatio(true);
                pictures[rank][file] = imageView;

                Pane selection = getSelection(rank, file);
                selection.setVisible(false);
                selections[rank][file] = selection;

                Circle circle = new Circle(0., 0., TILE_SIZE / 3.);
                circle.setVisible(false);
                circles[rank][file] = circle;

                StackPane tile = new StackPane(imageView, circle, selection, rect);
                tile.setMinSize(TILE_SIZE, TILE_SIZE);
                tile.setMaxSize(TILE_SIZE, TILE_SIZE);
                tile.setOnMouseClicked(e -> handleSquareClick(sq));
                boardPane.add(tile, file, rank);
            }
        }
        updateBoard();

        // FEN input
        fenArea = new TextArea();
        fenArea.setPromptText("Paste FEN here...");
        fenArea.setPrefRowCount(2);

        Button setFenBtn = new Button("Set FEN");
        setFenBtn.setOnAction(e -> handleFenUpdate());

        VBox rightPane = new VBox(10, new Label("Moves"), movesPane,
                new Label("FEN Editor"), fenArea, setFenBtn);
        rightPane.setPadding(new Insets(10));
        rightPane.setPrefWidth(250);

        HBox root = new HBox(10, boardPane, rightPane);
        HBox.setHgrow(boardPane, Priority.ALWAYS);
        root.setPadding(new Insets(10));

        stage.setTitle("Tricky prep generator");
        stage.setScene(new Scene(root));
        stage.show();
    }

    private void updateBoard() {
        for (int rank = 0; rank < RANK_COUNT; rank++) {
            for (int file = 0; file < FILE_COUNT; file++) {
                Square sq = getSquareByRankAndFile(rank, file);
                Piece piece = currentBoard.getPiece(sq);
                if (piece == Piece.NONE) {
                    pictures[rank][file].setImage(null);
                } else {
                    Image img = pieceToImage.get(piece);
                    pictures[rank][file].setImage(img);
                }
                boolean isDark = (rank + file) % 2 == 0;
                Color rectColor = isDark ? DARK_SQUARE_COLOR : LIGHT_SQUARE_COLOR;
                if (lastMove != null && (lastMove.getFrom() == sq || lastMove.getTo() == sq)) {
                    rectColor = isDark ? DARK_PREVIOUS_MOVE_COLOR : LIGHT_PREVIOUS_MOVE_COLOR;
                }
                if (sq == selectedSquare) {
                    rectColor = isDark ? DARK_SELECTION_FROM_COLOR : LIGHT_SELECTION_FROM_COLOR;
                }
                if (sq == focused) {
                    rectColor = isDark ? DARK_SELECTION_TO_COLOR : LIGHT_SELECTION_TO_COLOR;
                }
                rectangles[rank][file].setFill(rectColor);
                if (squaresToMove.contains(sq)) {
                    if (piece == Piece.NONE) {
                        circles[rank][file].setVisible(true);
                    } else {
                        selections[rank][file].setVisible(true);
                    }
                } else {
                    circles[rank][file].setVisible(false);
                    selections[rank][file].setVisible(false);
                }
            }
        }
    }

    private Pane getSelection(int rank, int file) {
        Pane pane = new Pane();
        Polygon triangle = new Polygon();
        for (int i = -1; i < 2; i += 2) {
            for (int j = -1; j < 2; j += 2) {
                triangle.getPoints().addAll(
                        TILE_SIZE * .5 * i, TILE_SIZE * .5 * j,
                        TILE_SIZE * .5 * i, TILE_SIZE * .35 * j,
                        TILE_SIZE * .35 * i, TILE_SIZE * .5 * j
                );

                triangle.setFill((rank + file) % 2 == 0 ? DARK_SELECTION_TO_COLOR : LIGHT_SELECTION_TO_COLOR);
                triangle.setStrokeWidth(0);
                pane.getChildren().add(triangle);
            }
        }
        return pane;
    }

    private void handleFenUpdate() {
        try {
            String fen = fenArea.getText().trim();
            Board board = new Board();
            board.loadFromFen(fen);
            initialBoard = board;
            currentBoard = initialBoard.clone();
            movesPane.getChildren().removeAll();
            plyToMove.clear();
            plyToLabel.clear();
            updateBoard();
        } catch (Exception ex) {
            new Alert(Alert.AlertType.ERROR, "Invalid FEN").show();
        }
    }

    private void handleMouseMove(MouseEvent e) {
        int file = (int) (e.getX() / TILE_SIZE);
        int rank = (int) (e.getY() / TILE_SIZE);
        if (file < 0 || file >= FILE_COUNT || rank < 0 || rank > RANK_COUNT) {
            handleMouseExited();
            return;
        }
        Square square = getSquareByRankAndFile(rank, file);
        if (squaresToMove.contains(square)) {
            if (focused != square) {
                focused = square;
                updateBoard();
            } else if (focused != null) {
                focused = null;
                updateBoard();
            }
        }
    }

    private void handleMouseExited() {
        if (focused != null) {
            focused = null;
            updateBoard();
        }
    }

    private void handleSquareClick(Square clicked) {
        if (selectedSquare == null) {
            // first click
            if (currentBoard.getPiece(clicked) != null) {
                selectedSquare = clicked;
                List<Move> legalMoves = currentBoard.legalMoves();
                squaresToMove = legalMoves.stream()
                        .filter(move -> move.getFrom() == selectedSquare)
                        .map(Move::getTo)
                        .collect(Collectors.toList());
            }
        } else {
            Move move = new Move(selectedSquare, clicked, Piece.NONE);
            List<Move> legalMoves = MoveGenerator.generateLegalMoves(currentBoard);
            if (legalMoves.contains(move)) {
                currentBoard.doMove(move);
                plyToMove = plyToMove.subList(0, currentPly);
                plyToLabel = plyToLabel.subList(0, currentPly);
                plyToMove.add(move);
                Label newMoveLabel = createMoveLabel(move);
                plyToLabel.add(newMoveLabel);
                currentPly++;
                changeLabelFocus(newMoveLabel);
                updateMoveTree();
            }
            selectedSquare = null;
            squaresToMove.clear();
            updateBoard();

        }
    }

    private Label createMoveLabel(Move move) {
        Label moveLabel = new Label(moveToSan(currentBoard, move));
        moveLabel.setOnMouseClicked(e -> {
            restorePositionFromTree(currentPly + 1);
        });
        return moveLabel;
    }

    private void restorePositionFromTree(int plyTo) {
        // Walk up the tree to reconstruct moves
        Board newBoard = initialBoard.clone();
        for (int ply = 0; ply < plyTo; ply++) {
            lastMove = plyToMove.get(ply);
            newBoard.doMove(lastMove);
        }
        currentPly = plyTo + 1;
        changeLabelFocus(plyToLabel.get(plyTo));

        updateBoard();
    }

    private void updateMoveTree() {
        // TODO implement
    }

    private void changeLabelFocus(Label focusedLabel) {
        for (Label label : plyToLabel) {
            label.setTextFill(label == focusedLabel ? LABEL_SELECTED_COLOR : Color.WHITE);
        }
    }

    private Square getSquareByRankAndFile(int rank, int file) {
        return Square.squareAt((FILE_COUNT - 1 - rank) * RANK_COUNT + file);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
