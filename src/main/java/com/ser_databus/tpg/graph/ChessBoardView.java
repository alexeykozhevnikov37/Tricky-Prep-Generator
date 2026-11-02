package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Piece;
import com.github.bhlangonijr.chesslib.PieceType;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.Square;
import com.github.bhlangonijr.chesslib.move.Move;
import com.github.bhlangonijr.chesslib.move.MoveGenerator;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A chess board. In interactive mode the side to move can move pieces by dragging them or by clicking the piece
 * and then the target square; only legal moves are accepted. The view doesn't change the position itself: it
 * reports the move to {@link #setOnMove} and the owner shows the new position with {@link #setPosition}.
 * In read-only mode the board only displays positions.
 * <p>
 * The board fills the space its parent gives it: the squares are resized to the biggest size that fits.
 */
public class ChessBoardView extends StackPane {
    private static final int SIZE = 8;
    private static final String IMAGES_FOLDER = "/images/";
    /** Pieces are rendered once at this size and scaled down by the image views, so they stay sharp on HiDPI. */
    private static final double PIECE_IMAGE_SIZE = 200;
    private static final double PIECE_SCALE = 1.0;
    private static final double MIN_TILE_SIZE = 24;
    private static final double MIN_COORDINATE_SIZE = 16;

    static final Color DARK_SQUARE_COLOR = Color.rgb(181, 136, 99);
    static final Color LIGHT_SQUARE_COLOR = Color.rgb(240, 217, 181);
    static final Color DARK_SELECTION_FROM_COLOR = Color.rgb(100, 111, 64);
    static final Color LIGHT_SELECTION_FROM_COLOR = Color.rgb(130, 151, 105);
    static final Color DARK_SELECTION_TO_COLOR = Color.rgb(132, 121, 78);
    static final Color LIGHT_SELECTION_TO_COLOR = Color.rgb(174, 177, 135);
    static final Color DARK_PREVIOUS_MOVE_COLOR = Color.rgb(170, 162, 58);
    static final Color LIGHT_PREVIOUS_MOVE_COLOR = Color.rgb(205, 210, 106);
    private static final Color MOVE_HINT_COLOR = Color.rgb(20, 85, 30, 0.45);
    /** Promotion chooser, like Lichess: the board dimmed, the choices in grey circles turning orange on hover */
    private static final Color PROMOTION_DIM_COLOR = Color.rgb(0, 0, 0, 0.45);
    private static final Color PROMOTION_SQUARE_COLOR = Color.rgb(250, 250, 250, 0.92);
    private static final RadialGradient PROMOTION_CHOICE_FILL = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true,
            CycleMethod.NO_CYCLE, new Stop(0.25, Color.web("#b0b0b0")), new Stop(1, Color.web("#808080")));
    private static final RadialGradient PROMOTION_HOVER_FILL = new RadialGradient(0, 0, 0.5, 0.5, 0.5, true,
            CycleMethod.NO_CYCLE, new Stop(0, Color.web("#ff8a3d")), new Stop(1, Color.web("#d85000")));
    /** Promotion pieces from the promotion square towards the middle of the board, in the Lichess order */
    private static final PieceType[] PROMOTION_ORDER = {PieceType.QUEEN, PieceType.KNIGHT, PieceType.ROOK,
            PieceType.BISHOP};

    private static final Map<Piece, Image> PIECE_IMAGES = new HashMap<>();

    /** Size of the board when the parent doesn't stretch or shrink it */
    private final double preferredTileSize;
    private double tileSize;
    /** Width of the rank labels and height of the file labels */
    private double coordinateSize;
    private final GridPane grid = new GridPane();
    private final Pane dragLayer = new Pane();
    private final ImageView dragImage = new ImageView();
    private final Label[] fileLabels = new Label[SIZE];
    private final Label[] rankLabels = new Label[SIZE];
    // all indexed by [row][column] on the screen, row 0 at the top
    private final StackPane[][] tiles = new StackPane[SIZE][SIZE];
    private final Rectangle[][] squares = new Rectangle[SIZE][SIZE];
    private final ImageView[][] pieces = new ImageView[SIZE][SIZE];
    private final Pane[][] captureHints = new Pane[SIZE][SIZE];
    private final Circle[][] moveHints = new Circle[SIZE][SIZE];
    private final StackPane[][] promotionLayers = new StackPane[SIZE][SIZE];
    private final Rectangle[][] promotionSquares = new Rectangle[SIZE][SIZE];
    private final Circle[][] promotionCircles = new Circle[SIZE][SIZE];
    private final ImageView[][] promotionPieces = new ImageView[SIZE][SIZE];

    private Board board = new Board();
    private List<Move> highlightedMoves = List.of();
    private Side orientation = Side.WHITE;
    private boolean interactive;
    private Consumer<Move> onMove = move -> {
    };

    private Square selectedSquare;
    /** Legal moves of the piece on {@link #selectedSquare} */
    private List<Move> selectedMoves = List.of();
    private Square hoveredTarget;
    private Square dragFrom;
    private boolean dragging;
    private boolean pressedOnSelectedSquare;
    /** While the user chooses a promotion piece: the square of each choice and its move; null otherwise */
    private Map<Square, Move> promotionChoices;
    private Square promotionHover;

    /** @param preferredTileSize square size when the parent gives the board its preferred size */
    public ChessBoardView(double preferredTileSize) {
        this.preferredTileSize = preferredTileSize;
        this.tileSize = preferredTileSize;
        loadPieceImages();

        for (int col = 0; col < SIZE; col++) {
            Label label = createCoordinateLabel();
            grid.add(label, col + 1, SIZE);
            fileLabels[col] = label;
        }
        for (int row = 0; row < SIZE; row++) {
            Label label = createCoordinateLabel();
            grid.add(label, 0, row);
            rankLabels[row] = label;
        }
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                grid.add(createTile(row, col), col + 1, row);
            }
        }
        grid.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        grid.setOnMousePressed(this::handleMousePressed);
        grid.setOnMouseDragged(this::handleMouseDragged);
        grid.setOnMouseReleased(this::handleMouseReleased);
        grid.setOnMouseMoved(this::handleMouseMoved);
        grid.setOnMouseExited(e -> {
            if (!dragging && hoveredTarget != null) {
                hoveredTarget = null;
                refresh();
            }
        });

        // the dragged piece floats above the board
        dragImage.setPreserveRatio(true);
        dragImage.setManaged(false);
        dragImage.setVisible(false);
        dragLayer.getChildren().add(dragImage);
        dragLayer.setMouseTransparent(true);
        getChildren().addAll(grid, dragLayer);

        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        applySizes(preferredTileSize, coordinateSizeFor(preferredTileSize));
        updateCoordinateLabels();
        refresh();
    }

    // ---------- sizing ----------

    private static double coordinateSizeFor(double tileSize) {
        return Math.max(MIN_COORDINATE_SIZE, Math.round(tileSize * 0.32));
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + snappedRightInset()
                + coordinateSizeFor(preferredTileSize) + SIZE * preferredTileSize;
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset()
                + coordinateSizeFor(preferredTileSize) + SIZE * preferredTileSize;
    }

    @Override
    protected double computeMinWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + MIN_COORDINATE_SIZE + SIZE * MIN_TILE_SIZE;
    }

    @Override
    protected double computeMinHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + MIN_COORDINATE_SIZE + SIZE * MIN_TILE_SIZE;
    }

    /** Fits the squares into the current size of the view, then lays out as usual. */
    @Override
    protected void layoutChildren() {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double side = Math.min(width, height);
        double coordinates = coordinateSizeFor(side / (SIZE + 0.32));
        // whole pixels, so the squares don't get blurry seams
        double tile = Math.max(MIN_TILE_SIZE, Math.floor((side - coordinates) / SIZE));
        applySizes(tile, coordinates);
        super.layoutChildren();
    }

    private void applySizes(double tile, double coordinates) {
        boolean firstTime = squares[0][0].getWidth() == 0;
        if (!firstTime && tile == tileSize && coordinates == coordinateSize) {
            return;
        }
        tileSize = tile;
        coordinateSize = coordinates;
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                squares[row][col].setWidth(tile);
                squares[row][col].setHeight(tile);
                tiles[row][col].setMinSize(tile, tile);
                tiles[row][col].setPrefSize(tile, tile);
                tiles[row][col].setMaxSize(tile, tile);
                pieces[row][col].setFitWidth(tile * PIECE_SCALE);
                pieces[row][col].setFitHeight(tile * PIECE_SCALE);
                moveHints[row][col].setRadius(tile * 0.16);
                promotionSquares[row][col].setWidth(tile);
                promotionSquares[row][col].setHeight(tile);
                promotionCircles[row][col].setRadius(tile * 0.47);
                promotionPieces[row][col].setFitWidth(tile * PIECE_SCALE);
                promotionPieces[row][col].setFitHeight(tile * PIECE_SCALE);
                captureHints[row][col].getChildren().setAll(createCaptureTriangles(isDark(row, col)));
            }
        }
        String labelStyle = String.format(java.util.Locale.ROOT,
                "-fx-font-size: %.1f; -fx-font-weight: bold; -fx-text-fill: #555555;",
                Math.max(10., Math.min(18., tile * 0.22)));
        for (Label label : fileLabels) {
            label.setMinSize(tile, coordinates);
            label.setPrefSize(tile, coordinates);
            label.setMaxSize(tile, coordinates);
            label.setStyle(labelStyle);
        }
        for (Label label : rankLabels) {
            label.setMinSize(coordinates, tile);
            label.setPrefSize(coordinates, tile);
            label.setMaxSize(coordinates, tile);
            label.setStyle(labelStyle);
        }
        dragImage.setFitWidth(tile * PIECE_SCALE * 1.1);
        dragImage.setFitHeight(tile * PIECE_SCALE * 1.1);
    }

    /** Shows the position. The squares of the highlighted moves get the previous move color. */
    public void setPosition(Board board, List<Move> highlightedMoves) {
        this.board = board.clone();
        this.highlightedMoves = List.copyOf(highlightedMoves);
        cancelDrag();
        clearSelection();
        promotionChoices = null;
        refresh();
    }

    public Side getOrientation() {
        return orientation;
    }

    /** @param bottomSide the side whose pieces start at the bottom of the board */
    public void setOrientation(Side bottomSide) {
        orientation = bottomSide;
        cancelDrag();
        clearSelection();
        promotionChoices = null;
        updateCoordinateLabels();
        refresh();
    }

    public void flip() {
        setOrientation(orientation.flip());
    }

    public void setInteractive(boolean interactive) {
        this.interactive = interactive;
        cancelDrag();
        clearSelection();
        promotionChoices = null;
        refresh();
    }

    /** Called with every legal move the user makes in interactive mode */
    public void setOnMove(Consumer<Move> onMove) {
        this.onMove = onMove == null ? move -> {
        } : onMove;
    }

    /** The last move of each side: the last two moves of the list, or fewer if the list is shorter. */
    public static List<Move> lastMoveOfEachSide(List<Move> moves) {
        return new ArrayList<>(moves.subList(Math.max(0, moves.size() - 2), moves.size()));
    }

    private StackPane createTile(int row, int col) {
        Rectangle rect = new Rectangle();
        squares[row][col] = rect;

        Pane captureHint = new Pane();
        captureHint.setVisible(false);
        captureHints[row][col] = captureHint;

        ImageView imageView = new ImageView();
        imageView.setPreserveRatio(true);
        pieces[row][col] = imageView;

        Circle moveHint = new Circle(1, MOVE_HINT_COLOR);
        moveHint.setVisible(false);
        moveHints[row][col] = moveHint;

        // the promotion chooser covers the whole tile: a dimmed square, or a choice in a circle
        Rectangle promotionSquare = new Rectangle();
        promotionSquares[row][col] = promotionSquare;
        Circle promotionCircle = new Circle(1);
        promotionCircles[row][col] = promotionCircle;
        ImageView promotionPiece = new ImageView();
        promotionPiece.setPreserveRatio(true);
        promotionPieces[row][col] = promotionPiece;
        StackPane promotionLayer = new StackPane(promotionSquare, promotionCircle, promotionPiece);
        promotionLayer.setVisible(false);
        promotionLayers[row][col] = promotionLayer;

        // bottom to top: square, corner markers, piece, move-hint dot, promotion chooser
        StackPane tile = new StackPane(rect, captureHint, imageView, moveHint, promotionLayer);
        tiles[row][col] = tile;
        return tile;
    }

    /** Triangles in the corners of a square: shown on occupied squares the selected piece can capture on. */
    private List<Polygon> createCaptureTriangles(boolean isDark) {
        List<Polygon> triangles = new ArrayList<>();
        double c = tileSize * .5; // tile center: Pane uses top-left based coordinates
        for (int i = -1; i < 2; i += 2) {
            for (int j = -1; j < 2; j += 2) {
                Polygon triangle = new Polygon(
                        c + tileSize * .5 * i, c + tileSize * .5 * j,
                        c + tileSize * .5 * i, c + tileSize * .3 * j,
                        c + tileSize * .3 * i, c + tileSize * .5 * j
                );
                triangle.setFill(isDark ? DARK_SELECTION_TO_COLOR : LIGHT_SELECTION_TO_COLOR);
                triangle.setStrokeWidth(0);
                triangles.add(triangle);
            }
        }
        return triangles;
    }

    private static Label createCoordinateLabel() {
        Label label = new Label();
        label.setAlignment(Pos.CENTER);
        return label;
    }

    private void updateCoordinateLabels() {
        for (int col = 0; col < SIZE; col++) {
            fileLabels[col].setText(String.valueOf(Character.toLowerCase(squareAt(SIZE - 1, col).toString().charAt(0))));
        }
        for (int row = 0; row < SIZE; row++) {
            rankLabels[row].setText(String.valueOf(squareAt(row, 0).toString().charAt(1)));
        }
    }

    private void refresh() {
        Set<Square> previousMoveSquares = new HashSet<>();
        for (Move move : highlightedMoves) {
            previousMoveSquares.add(move.getFrom());
            previousMoveSquares.add(move.getTo());
        }
        Set<Square> targets = new HashSet<>();
        for (Move move : selectedMoves) {
            targets.add(move.getTo());
        }
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                Square sq = squareAt(row, col);
                Piece piece = board.getPiece(sq);
                boolean isHiddenByDrag = dragging && sq == dragFrom;
                pieces[row][col].setImage(piece == Piece.NONE || isHiddenByDrag ? null : PIECE_IMAGES.get(piece));

                boolean isDark = isDark(row, col);
                Color color = isDark ? DARK_SQUARE_COLOR : LIGHT_SQUARE_COLOR;
                if (previousMoveSquares.contains(sq)) {
                    color = isDark ? DARK_PREVIOUS_MOVE_COLOR : LIGHT_PREVIOUS_MOVE_COLOR;
                }
                if (sq == selectedSquare) {
                    color = isDark ? DARK_SELECTION_FROM_COLOR : LIGHT_SELECTION_FROM_COLOR;
                }
                if (sq == hoveredTarget) {
                    color = isDark ? DARK_SELECTION_TO_COLOR : LIGHT_SELECTION_TO_COLOR;
                }
                squares[row][col].setFill(color);

                boolean isTarget = targets.contains(sq);
                moveHints[row][col].setVisible(isTarget && piece == Piece.NONE && sq != hoveredTarget);
                captureHints[row][col].setVisible(isTarget && piece != Piece.NONE && sq != hoveredTarget);
                refreshPromotionLayer(row, col, sq);
            }
        }
    }

    private void refreshPromotionLayer(int row, int col, Square sq) {
        StackPane layer = promotionLayers[row][col];
        if (promotionChoices == null) {
            layer.setVisible(false);
            return;
        }
        layer.setVisible(true);
        Move choice = promotionChoices.get(sq);
        Circle circle = promotionCircles[row][col];
        ImageView piece = promotionPieces[row][col];
        if (choice == null) {
            promotionSquares[row][col].setFill(PROMOTION_DIM_COLOR);
            circle.setVisible(false);
            piece.setImage(null);
            layer.setCursor(Cursor.DEFAULT);
        } else {
            boolean isHovered = sq == promotionHover;
            promotionSquares[row][col].setFill(PROMOTION_SQUARE_COLOR);
            circle.setVisible(true);
            circle.setFill(isHovered ? PROMOTION_HOVER_FILL : PROMOTION_CHOICE_FILL);
            piece.setImage(PIECE_IMAGES.get(choice.getPromotion()));
            double scale = isHovered ? 1.08 : 0.92;
            piece.setScaleX(scale);
            piece.setScaleY(scale);
            layer.setCursor(Cursor.HAND);
        }
    }

    // ---------- mouse handling ----------

    private void handleMousePressed(MouseEvent e) {
        if (!interactive || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (promotionChoices != null) {
            // a click on a choice promotes, anywhere else cancels the move
            Square clicked = squareUnder(e);
            Move move = clicked == null ? null : promotionChoices.get(clicked);
            promotionChoices = null;
            promotionHover = null;
            refresh();
            if (move != null) {
                onMove.accept(move);
            }
            e.consume();
            return;
        }
        Square sq = squareUnder(e);
        if (sq == null) {
            clearSelection();
            refresh();
            return;
        }
        if (selectedSquare != null && isTarget(sq)) {
            // second click of click-to-move
            tryMove(selectedSquare, sq);
            return;
        }
        Piece piece = board.getPiece(sq);
        if (piece != Piece.NONE && piece.getPieceSide() == board.getSideToMove()) {
            pressedOnSelectedSquare = sq == selectedSquare;
            select(sq);
            dragFrom = sq;
            dragging = false;
            dragImage.setImage(PIECE_IMAGES.get(piece));
            positionDragImage(e);
        } else {
            clearSelection();
        }
        refresh();
    }

    private void handleMouseDragged(MouseEvent e) {
        if (dragFrom == null) {
            return;
        }
        if (!dragging) {
            dragging = true;
            dragImage.setVisible(true);
        }
        positionDragImage(e);
        Square sq = squareUnder(e);
        hoveredTarget = sq != null && isTarget(sq) ? sq : null;
        refresh();
    }

    private void handleMouseReleased(MouseEvent e) {
        if (dragFrom == null) {
            return;
        }
        Square from = dragFrom;
        boolean wasDragging = dragging;
        cancelDrag();
        hoveredTarget = null;
        if (wasDragging) {
            Square sq = squareUnder(e);
            if (sq != null && sq != from && isTarget(sq)) {
                tryMove(from, sq);
                return;
            }
            // dropped outside the legal targets: the piece goes back and stays selected
        } else if (pressedOnSelectedSquare) {
            // a click on the already selected piece deselects it
            clearSelection();
        }
        refresh();
    }

    private void handleMouseMoved(MouseEvent e) {
        if (promotionChoices != null) {
            Square sq = squareUnder(e);
            Square hover = sq != null && promotionChoices.containsKey(sq) ? sq : null;
            if (hover != promotionHover) {
                promotionHover = hover;
                refresh();
            }
            return;
        }
        if (!interactive || selectedSquare == null) {
            return;
        }
        Square sq = squareUnder(e);
        Square newTarget = sq != null && isTarget(sq) ? sq : null;
        if (newTarget != hoveredTarget) {
            hoveredTarget = newTarget;
            refresh();
        }
    }

    private void positionDragImage(MouseEvent e) {
        Point2D p = dragLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
        double size = dragImage.getFitWidth();
        dragImage.relocate(p.getX() - size / 2, p.getY() - size / 2);
    }

    private void cancelDrag() {
        dragFrom = null;
        dragging = false;
        dragImage.setVisible(false);
    }

    private void select(Square square) {
        selectedSquare = square;
        selectedMoves = MoveGenerator.generateLegalMoves(board).stream()
                .filter(m -> m.getFrom() == square)
                .toList();
    }

    private void clearSelection() {
        selectedSquare = null;
        selectedMoves = List.of();
        hoveredTarget = null;
    }

    private boolean isTarget(Square square) {
        return selectedMoves.stream().anyMatch(m -> m.getTo() == square);
    }

    private void tryMove(Square from, Square to) {
        List<Move> candidates = selectedMoves.stream()
                .filter(m -> m.getFrom() == from && m.getTo() == to)
                .toList();
        clearSelection();
        if (candidates.isEmpty()) {
            refresh();
            return;
        }
        if (candidates.size() == 1) {
            refresh();
            onMove.accept(candidates.get(0));
        } else {
            showPromotionChooser(candidates);
        }
    }

    /**
     * Several legal moves between the same squares are promotions. Like on Lichess, the choices appear in the
     * promotion square's file, from the promotion square towards the middle of the board, and the rest of the
     * board is dimmed; the next click chooses or cancels.
     */
    private void showPromotionChooser(List<Move> promotions) {
        Square target = promotions.get(0).getTo();
        int targetRow = -1;
        int targetCol = -1;
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                if (squareAt(row, col) == target) {
                    targetRow = row;
                    targetCol = col;
                }
            }
        }
        int direction = targetRow < SIZE / 2 ? 1 : -1;
        promotionChoices = new LinkedHashMap<>();
        for (int i = 0; i < PROMOTION_ORDER.length; i++) {
            PieceType type = PROMOTION_ORDER[i];
            Square square = squareAt(targetRow + i * direction, targetCol);
            promotions.stream()
                    .filter(m -> m.getPromotion() != Piece.NONE && m.getPromotion().getPieceType() == type)
                    .findFirst()
                    .ifPresent(move -> promotionChoices.put(square, move));
        }
        promotionHover = null;
        refresh();
    }

    private Square squareUnder(MouseEvent e) {
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; col++) {
                Bounds bounds = tiles[row][col].localToScene(tiles[row][col].getLayoutBounds());
                if (bounds.contains(e.getSceneX(), e.getSceneY())) {
                    return squareAt(row, col);
                }
            }
        }
        return null;
    }

    private Square squareAt(int row, int col) {
        int rankIndex = orientation == Side.WHITE ? SIZE - 1 - row : row;
        int fileIndex = orientation == Side.WHITE ? col : SIZE - 1 - col;
        return Square.squareAt(rankIndex * SIZE + fileIndex);
    }

    /** The top-left square (a8 or h1) is light; flipping the board keeps the colors of the screen squares. */
    private static boolean isDark(int row, int col) {
        return (row + col) % 2 == 1;
    }

    private static synchronized void loadPieceImages() {
        if (!PIECE_IMAGES.isEmpty()) {
            return;
        }
        for (Piece piece : Piece.allPieces) {
            if (piece == Piece.NONE) {
                continue;
            }
            String filePath = IMAGES_FOLDER + piece.name().toLowerCase() + ".svg";
            PIECE_IMAGES.put(piece, SvgImageLoader.load(filePath, PIECE_IMAGE_SIZE));
        }
    }
}
