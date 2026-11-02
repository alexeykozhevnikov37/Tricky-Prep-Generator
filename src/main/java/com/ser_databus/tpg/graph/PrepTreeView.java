package com.ser_databus.tpg.graph;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.Side;
import com.github.bhlangonijr.chesslib.move.Move;
import com.ser_databus.tpg.model.MyNode;
import com.ser_databus.tpg.model.Node;
import com.ser_databus.tpg.model.OpNode;
import com.ser_databus.tpg.model.PruneReason;
import com.ser_databus.tpg.util.CalculationUtil;
import com.ser_databus.tpg.util.MoveUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Scale;
import javafx.util.Duration;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Shows a prep tree growing from the center: the starting position in the middle, every box's children fanned out
 * around it, away from its own parent, then settled by a physical simulation into a compact, natural shape: boxes
 * don't overlap and arrows don't cross (see {@link PrepTreeLayout}).
 * <p>
 * Every box but the central one shows an opponent's move and my reply to it, e.g. "2. c4 dxc4". My reply is a move
 * selector listing my moves the generator considered; choosing another (not pruned) one redraws the tree with the
 * subtree of that move. The central box has a selector of my first move if I'm to move in the starting position.
 * <p>
 * The color of a box shows how much better the eval is than in the starting position: white up to 0.2 pawns, then
 * gradually greener, dark green from 3 pawns on. Boxes where the user changed the generator's move have a dashed
 * orange border.
 * <p>
 * The mouse wheel zooms (it scrolls over the scroll bars), dragging with the right mouse button moves the picture.
 */
public class PrepTreeView extends StackPane {
    /** Width of a box whose text fits; wider boxes are as wide as their text needs */
    private static final double BOX_BASE_WIDTH = 124;
    /** Six lines: the moves (the opponent's and mine), chance, eval, nodes, EP and LP */
    private static final double BOX_HEIGHT = 114;
    private static final double HEADER_SPACING = 5;
    private static final double BOX_HORIZONTAL_PADDING = 8;
    private static final double MOVE_FONT_SIZE = 13;
    private static final double STATS_FONT_SIZE = 12;
    private static final double MARGIN = 60;
    /** Pixels scrolled per mouse wheel delta unit over a scroll bar: one notch (40) scrolls 120 pixels */
    private static final double WHEEL_SCROLL_SPEED = 3;
    /** Arrow buttons of the scroll bars move by this part of the visible area (JavaFX default: 0.1) */
    private static final double SCROLL_BAR_UNIT = 0.3;
    private static final double MIN_ZOOM = 0.01;
    private static final double MAX_ZOOM = 3;
    /** Zoom factor per mouse wheel delta unit: one notch (40) is about 15% */
    private static final double WHEEL_ZOOM_BASE = 1.0035;

    /** Box color up to {@link #WHITE_GAIN} */
    private static final Color NO_GAIN_COLOR = Color.WHITE;
    /** Box color from {@link #FULL_GAIN} on */
    private static final Color FULL_GAIN_COLOR = Color.web("#1b5e20");
    /** Eval gains over the starting position (pawns) up to this one leave the box white */
    private static final double WHITE_GAIN = 0.20;
    /** From this eval gain over the starting position (pawns) on, the box is dark green */
    private static final double FULL_GAIN = 3.0;
    private static final double INACCURACY_LOSS = 0.15;
    private static final double MISTAKE_LOSS = 0.3;
    private static final double BLUNDER_LOSS = 0.45;
    private static final String BORDER_COLOR = "#546e7a";
    private static final String USER_CHOICE_BORDER_COLOR = "#ef6c00";
    private static final String SELECTED_BORDER_COLOR = "#1e88e5";
    private static final Color ARROW_COLOR = Color.web("#1c2429");
    /** Background of an exported picture: the same as on the screen */
    private static final Color EXPORT_BACKGROUND = Color.web("#f5f7f8");
    /**
     * An exported picture is rendered at 100%, unless it would have more pixels than this: then it's scaled down to
     * this size (about 320 MB of memory while it's saved)
     */
    private static final double MAX_EXPORT_PIXELS = 80_000_000;
    /** An exported picture is rendered in tiles of this size (pixels), which every graphics card can handle */
    private static final int EXPORT_TILE_SIZE = 2048;

    private final ScrollPane scrollPane = new ScrollPane();
    private final Pane canvas = new Pane();
    private final Scale scale = new Scale(1, 1, 0, 0);
    private final Group zoomGroup = new Group(canvas);
    private final StackPane holder = new StackPane(zoomGroup);
    private final Label placeholder = new Label();
    /** Drawn behind the selected box */
    private final Rectangle selectionHalo = new Rectangle();
    private final ReadOnlyDoubleWrapper zoom = new ReadOnlyDoubleWrapper(1);

    private Node root;
    /** The user's move choices in the shown prep; nodes that aren't in it keep the generator's choice */
    private Map<MyNode, Move> choices = new IdentityHashMap<>();
    private final Map<Node, VisualNode> visualByNode = new IdentityHashMap<>();
    private final Map<VisualNode, VBox> boxByVisual = new IdentityHashMap<>();
    private VisualNode rootVisual;
    /** Incremented by every rebuild: a layout that finishes after a newer rebuild started is thrown away */
    private long rebuildCount;
    /**
     * The layout (a physics simulation, seconds for a big tree) runs here, not on the JavaFX thread, so the windows
     * stay responsive; at low priority, so it doesn't compete with the mouse and the UI either.
     */
    private static final ExecutorService LAYOUT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prep-layout");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private Node selectedNode;
    private Consumer<Node> onPositionSelected = node -> {
    };

    private Point2D panStart;
    private double panStartH;
    private double panStartV;

    /** One of my moves in a box's move selector */
    private static final class MoveOption {
        final Move move;
        final String text;
        /** The position after the move */
        final Node child;
        boolean isGeneratorChoice;

        MoveOption(Move move, String text, Node child) {
            this.move = move;
            this.text = text;
            this.child = child;
        }
    }

    /** A box of the diagram */
    private static final class VisualNode {
        /** MyNode (I'm to move), or the root OpNode */
        final Node node;
        final int depth;
        /** The opponent's move leading here; null for the root */
        Move opponentMove;
        /** The opponent's move leading here, e.g. "2. c4?"; null for the root */
        String opponentMoveText;
        /**
         * How much the opponent's move leading here lowers his winning chances (Lichess scale, -1..1) compared with
         * his best move; NaN if unknown (and for the root)
         */
        double opponentLoss = Double.NaN;
        final List<MoveOption> options = new ArrayList<>();
        MoveOption selected;
        /** The node whose values the box shows: the position after the selected move, if there is one */
        Node statsNode;
        String moveText;
        boolean followsGenerator = true;
        final List<VisualNode> children = new ArrayList<>();
        /** Texts of the box, set when it's built */
        String myMoveLabel;
        /** Chance, eval, EP and LP, one per line */
        List<String> statLines;
        /** Eval after my move minus the eval of the starting position, in pawns */
        double gain;
        double width = BOX_BASE_WIDTH;
        double x;
        double y;

        VisualNode(Node node, int depth) {
            this.node = node;
            this.depth = depth;
        }
    }

    public PrepTreeView() {
        canvas.getTransforms().add(scale);
        selectionHalo.setFill(Color.web(SELECTED_BORDER_COLOR, 0.55));
        selectionHalo.setArcWidth(16);
        selectionHalo.setArcHeight(16);
        selectionHalo.setMouseTransparent(true);
        selectionHalo.setManaged(false);
        holder.setStyle("-fx-background-color: #f5f7f8;");
        holder.minWidthProperty().bind(Bindings.createDoubleBinding(
                () -> scrollPane.getViewportBounds().getWidth(), scrollPane.viewportBoundsProperty()));
        holder.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> scrollPane.getViewportBounds().getHeight(), scrollPane.viewportBoundsProperty()));
        scrollPane.setContent(holder);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.addEventFilter(ScrollEvent.SCROLL, this::handleScroll);
        scrollPane.addEventFilter(MouseEvent.MOUSE_PRESSED, this::handlePanStart);
        scrollPane.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::handlePanDrag);
        scrollPane.addEventFilter(MouseEvent.MOUSE_RELEASED, this::handlePanEnd);
        scrollPane.skinProperty().addListener((obs, oldSkin, newSkin) -> Platform.runLater(this::speedUpScrollBars));

        placeholder.setStyle("-fx-text-fill: gray; -fx-font-size: 14;");
        placeholder.setWrapText(true);
        placeholder.setMouseTransparent(true);
        getChildren().addAll(scrollPane, placeholder);
        setPlaceholderText("The prep will appear here when the first iteration is ready");
    }

    // ---------- public API ----------

    /**
     * Shows a prep tree.
     *
     * @param root    the root of the prep, or null to clear the view
     * @param choices the user's move choices for this prep: read and updated by the view
     */
    public void setPrep(Node root, Map<MyNode, Move> choices) {
        this.root = root;
        this.choices = choices;
        this.selectedNode = null;
        if (root == null) {
            rebuildCount++;
            canvas.getChildren().clear();
            canvas.setPrefSize(0, 0);
            visualByNode.clear();
            boxByVisual.clear();
            rootVisual = null;
            placeholder.setVisible(true);
            return;
        }
        placeholder.setVisible(false);
        rebuild(() -> whenViewportReady(this::showInitialView));
    }

    public void setPlaceholderText(String text) {
        placeholder.setText(text);
    }

    /** Called with the position node to show when the user selects a box: the position after the selected move */
    public void setOnPositionSelected(Consumer<Node> onPositionSelected) {
        this.onPositionSelected = onPositionSelected == null ? node -> {
        } : onPositionSelected;
    }

    public ReadOnlyDoubleProperty zoomProperty() {
        return zoom.getReadOnlyProperty();
    }

    public void zoomToFit() {
        if (rootVisual == null) {
            return;
        }
        setZoom(fitZoom());
        scrollTo(canvas.getPrefWidth() / 2, canvas.getPrefHeight() / 2,
                viewportWidth() / 2, viewportHeight() / 2);
    }

    /** 100% zoom, keeping the center of the view in place */
    public void zoomToActualSize() {
        setZoomAround(1, viewportWidth() / 2, viewportHeight() / 2);
    }

    // ---------- export ----------

    /**
     * A box of the shown prep, for export: the opponent's move leading here and my reply, with the user's move
     * choices applied.
     *
     * @param node             the position before my move (MyNode), or the root OpNode
     * @param opponentMove     the opponent's move leading here; null for the root
     * @param opponentMoveText e.g. "2. c4?"; null for the root
     * @param annotation       "?!", "?", "??" for the opponent's move, or ""
     * @param myMove           my move shown in the box; null if there is none (the root OpNode, a dead end)
     * @param myMoveText       e.g. "dxc4", "(no move)"; null for the root OpNode
     * @param statsNode        the node whose values the box shows
     * @param followsGenerator false if the user chose another move than the generator
     * @param statLines        the values shown in the box, e.g. "Chance 12.3%"
     * @param children         the opponent's replies to my move, the likeliest first
     */
    public record PrepBox(Node node, Move opponentMove, String opponentMoveText, String annotation, Move myMove,
                          String myMoveText, Node statsNode, boolean followsGenerator, List<String> statLines,
                          List<PrepBox> children) {
    }

    /** The picture of the shown prep and the scale it was rendered at (1 = 100%) */
    public record PrepImage(BufferedImage image, double scale) {
    }

    /** The shown prep as boxes, with the user's move choices applied; null if no prep is shown. */
    public PrepBox getShownPrep() {
        return rootVisual == null ? null : toPrepBox(rootVisual);
    }

    private static PrepBox toPrepBox(VisualNode visual) {
        Move myMove = visual.selected != null ? visual.selected.move
                : visual.node instanceof MyNode myNode && visual.options.isEmpty() ? myNode.chosenPrepMove : null;
        List<PrepBox> children = new ArrayList<>();
        for (VisualNode child : visual.children) {
            children.add(toPrepBox(child));
        }
        return new PrepBox(visual.node, visual.opponentMove, visual.opponentMoveText, annotation(visual.opponentLoss),
                myMove, visual.node instanceof MyNode ? visual.moveText : null, visual.statsNode,
                visual.followsGenerator, visual.statLines, List.copyOf(children));
    }

    /**
     * Renders the whole shown prep tree, like it's drawn on the screen at 100% zoom (without the selection), into a
     * picture. A tree bigger than {@link #MAX_EXPORT_PIXELS} is scaled down to fit. Must be called on the JavaFX
     * thread. Null if no prep is shown.
     */
    public PrepImage renderImage() {
        if (rootVisual == null) {
            return null;
        }
        double width = canvas.getPrefWidth();
        double height = canvas.getPrefHeight();
        // drawn again on a pane of its own, so the shown picture keeps its zoom, scrolling and selection
        Pane picture = new Pane();
        picture.setMinSize(width, height);
        picture.setPrefSize(width, height);
        picture.setMaxSize(width, height);
        picture.resize(width, height);
        picture.getChildren().setAll(drawPicture(new IdentityHashMap<>()));

        double exportScale = Math.min(1, Math.sqrt(MAX_EXPORT_PIXELS / (width * height)));
        picture.getTransforms().add(new Scale(exportScale, exportScale, 0, 0));
        // the group has no transforms of its own, so its coordinates are the pixels of the image
        Group scaledPicture = new Group(picture);
        // CSS (the styles of the boxes and the skins of their labels) is only applied to a node in a scene: without
        // it, only the arrows would be drawn. The scene isn't shown.
        new Scene(scaledPicture);
        scaledPicture.applyCss();
        scaledPicture.layout();
        int imageWidth = Math.max(1, (int) Math.ceil(width * exportScale));
        int imageHeight = Math.max(1, (int) Math.ceil(height * exportScale));
        BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_RGB);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(EXPORT_BACKGROUND);
        int[] pixels = new int[EXPORT_TILE_SIZE * EXPORT_TILE_SIZE];
        // in tiles: a snapshot can't be bigger than the biggest texture of the graphics card
        for (int tileY = 0; tileY < imageHeight; tileY += EXPORT_TILE_SIZE) {
            for (int tileX = 0; tileX < imageWidth; tileX += EXPORT_TILE_SIZE) {
                int tileWidth = Math.min(EXPORT_TILE_SIZE, imageWidth - tileX);
                int tileHeight = Math.min(EXPORT_TILE_SIZE, imageHeight - tileY);
                parameters.setViewport(new Rectangle2D(tileX, tileY, tileWidth, tileHeight));
                WritableImage tile = scaledPicture.snapshot(parameters, new WritableImage(tileWidth, tileHeight));
                tile.getPixelReader().getPixels(0, 0, tileWidth, tileHeight, PixelFormat.getIntArgbInstance(),
                        pixels, 0, tileWidth);
                image.setRGB(tileX, tileY, tileWidth, tileHeight, pixels, 0, tileWidth);
            }
        }
        return new PrepImage(image, exportScale);
    }

    // ---------- building the visual tree ----------

    /**
     * Builds the boxes' texts here (text measuring needs the JavaFX thread; it's fast), lays them out on the layout
     * thread, then draws them here. The old picture stays on the screen meanwhile.
     *
     * @param afterLayout runs on the JavaFX thread once the new picture is drawn (not if a newer rebuild started)
     */
    private void rebuild(Runnable afterLayout) {
        long rebuild = ++rebuildCount;
        visualByNode.clear();
        boxByVisual.clear();
        rootVisual = build(root, 0, null, null);
        double rootEval = CalculationUtil.denormalizeEval(root.normEval);
        for (VisualNode visual : visualByNode.values()) {
            prepareTexts(visual, rootEval);
        }
        Map<VisualNode, PrepTreeLayout.Item> items = new IdentityHashMap<>();
        PrepTreeLayout.Item rootItem = toItem(rootVisual, items);
        LAYOUT_EXECUTOR.execute(() -> {
            PrepTreeLayout.layout(rootItem, BOX_BASE_WIDTH);
            Platform.runLater(() -> {
                if (rebuild != rebuildCount) {
                    return;
                }
                applyLayout(items);
                render();
                afterLayout.run();
            });
        });
    }

    /**
     * @param parent       the position before the opponent's move leading here; null for the root
     * @param opponentMove the opponent's move leading here; null for the root
     */
    private VisualNode build(Node node, int depth, OpNode parent, Move opponentMove) {
        VisualNode visual = new VisualNode(node, depth);
        visualByNode.put(node, visual);
        boolean isRoot = parent == null;
        if (!isRoot) {
            visual.opponentLoss = opponentLoss(parent, node);
            visual.opponentMove = opponentMove;
            visual.opponentMoveText = moveText(parent.board(), opponentMove, false) + annotation(visual.opponentLoss);
        }
        OpNode next = null;
        if (node instanceof MyNode myNode) {
            // after the opponent's move, Black's reply goes without the move number: "2. c4 dxc4"
            for (var entry : myNode.moveToNode.entrySet()) {
                visual.options.add(new MoveOption(entry.getKey(), moveText(myNode.board(), entry.getKey(), !isRoot),
                        entry.getValue()));
            }
            MoveOption generatorChoice = findGeneratorChoice(myNode, visual.options);
            if (generatorChoice != null) {
                generatorChoice.isGeneratorChoice = true;
            }
            visual.options.sort(Comparator
                    .comparing((MoveOption o) -> !o.isGeneratorChoice)
                    .thenComparing(o -> o.child.isPruned)
                    .thenComparing(o -> -o.child.vp));
            if (visual.options.isEmpty()) {
                // a leaf: a stub leaf has the engine's move, a dead end has none
                visual.statsNode = myNode;
                visual.moveText = myNode.chosenPrepMove == null ? "(no move)"
                        : moveText(myNode.board(), myNode.chosenPrepMove, !isRoot);
            } else if (generatorChoice == null) {
                visual.statsNode = myNode;
                visual.moveText = "(all moves pruned)";
            } else {
                Move userMove = choices.get(myNode);
                MoveOption selected = userMove == null ? generatorChoice : visual.options.stream()
                        .filter(o -> o.move.equals(userMove) && !o.child.isPruned)
                        .findFirst()
                        .orElse(generatorChoice);
                visual.selected = selected;
                visual.statsNode = selected.child;
                visual.moveText = selected.text;
                visual.followsGenerator = selected == generatorChoice;
                if (selected.child instanceof OpNode opNode) {
                    next = opNode;
                }
            }
        } else if (node instanceof OpNode opNode) {
            visual.statsNode = opNode;
            next = opNode;
        }
        if (next != null) {
            for (var entry : next.getSortedMoves()) {
                visual.children.add(build(entry.getValue(), depth + 1, next, entry.getKey()));
            }
        }
        return visual;
    }

    /** The generator's move, or the best not pruned move if the generator's move got pruned later */
    private static MoveOption findGeneratorChoice(MyNode myNode, List<MoveOption> options) {
        MoveOption best = null;
        for (MoveOption option : options) {
            if (option.child.isPruned) {
                continue;
            }
            if (option.move.equals(myNode.chosenPrepMove)) {
                return option;
            }
            if (best == null || option.child.vp > best.child.vp) {
                best = option;
            }
        }
        return best;
    }

    /**
     * How much the opponent's move from the parent position to the node lowers his winning chances compared with
     * his best move: my winning chances after his move minus those after his best move (Lichess scale, -1..1).
     * NaN if the node isn't evaluated.
     */
    private static double opponentLoss(OpNode parent, Node node) {
        boolean isEvaluated = !(node instanceof MyNode myNode)
                || myNode.isStubLeafNode || !myNode.moveToNode.isEmpty() || myNode.normEval != 0;
        if (!isEvaluated) {
            return Double.NaN;
        }
        return Math.max(0, winningChances(node.normEval) - winningChances(parent.normEval));
    }

    /** Lichess winning chances (-1..1, from my side) of a normalized eval (which is Lichess Win% / 100) */
    private static double winningChances(double normEval) {
        return 2 * normEval - 1;
    }

    /** Chess annotation of the opponent's move by its loss */
    private static String annotation(double loss) {
        if (Double.isNaN(loss) || loss < INACCURACY_LOSS) {
            return "";
        }
        return loss >= BLUNDER_LOSS ? "??" : loss >= MISTAKE_LOSS ? "?" : "?!";
    }

    /**
     * "12. Nf3", "12... Nf6", or just "Nf6" when it continues White's move in the same text
     *
     * @param isContinuation true if the text follows the opponent's move in the same box
     */
    private static String moveText(Board boardBefore, Move move, boolean isContinuation) {
        boolean isWhite = boardBefore.getSideToMove() == Side.WHITE;
        String prefix = isWhite ? boardBefore.getMoveCounter() + ". "
                : isContinuation ? "" : boardBefore.getMoveCounter() + "... ";
        try {
            return prefix + MoveUtil.moveToSan(boardBefore, move);
        } catch (RuntimeException e) {
            return prefix + move;
        }
    }

    // ---------- box texts and sizes ----------

    private static void prepareTexts(VisualNode visual, double rootEval) {
        if (visual.node instanceof OpNode) {
            visual.myMoveLabel = "Opponent to move";
        } else {
            visual.myMoveLabel = visual.moveText + (visual.options.size() > 1 ? " \u25BE" : "");
        }
        Node stats = visual.statsNode;
        visual.statLines = List.of(
                "Chance " + formatPercent(stats.chance),
                "Eval " + formatEval(stats.normEval) + (stats.evalDepth > 0 ? " depth " + stats.evalDepth : ""),
                "Nodes " + nodeCount(stats) + "  Thick " + formatThickness(stats),
                "EP " + formatPercent(stats.vp),
                "LP " + formatPercent(stats.lichessVp));
        visual.gain = CalculationUtil.denormalizeEval(stats.normEval) - rootEval;

        // the header line: the opponent's move and mine, e.g. "7... Be7 8. Qf3"
        double widest = textWidth(visual.myMoveLabel, true, MOVE_FONT_SIZE) + (visual.options.size() > 1 ? 10 : 0);
        if (visual.opponentMoveText != null) {
            widest += textWidth(visual.opponentMoveText, false, MOVE_FONT_SIZE) + HEADER_SPACING;
        }
        for (String line : visual.statLines) {
            widest = Math.max(widest, textWidth(line, false, STATS_FONT_SIZE));
        }
        // padding, the widest border and a little slack
        double needed = widest + 2 * BOX_HORIZONTAL_PADDING + 6 + 4;
        visual.width = Math.max(BOX_BASE_WIDTH, Math.ceil(needed));
    }

    private static double textWidth(String text, boolean bold, double size) {
        Text measured = new Text(text);
        measured.setFont(Font.font(Font.getDefault().getFamily(), bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        return measured.getLayoutBounds().getWidth();
    }

    // ---------- layout ----------

    /** Moves the boxes to the positions found by {@link PrepTreeLayout} and sizes the canvas around them. */
    private void applyLayout(Map<VisualNode, PrepTreeLayout.Item> items) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (var entry : items.entrySet()) {
            VisualNode visual = entry.getKey();
            visual.x = entry.getValue().x;
            visual.y = entry.getValue().y;
            minX = Math.min(minX, visual.x - visual.width / 2);
            minY = Math.min(minY, visual.y - BOX_HEIGHT / 2);
            maxX = Math.max(maxX, visual.x + visual.width / 2);
            maxY = Math.max(maxY, visual.y + BOX_HEIGHT / 2);
        }
        // move everything into the positive quadrant of the canvas
        for (VisualNode visual : items.keySet()) {
            visual.x += MARGIN - minX;
            visual.y += MARGIN - minY;
        }
        double width = maxX - minX + 2 * MARGIN;
        double height = maxY - minY + 2 * MARGIN;
        canvas.setMinSize(width, height);
        canvas.setPrefSize(width, height);
        canvas.setMaxSize(width, height);
    }

    private static PrepTreeLayout.Item toItem(VisualNode visual, Map<VisualNode, PrepTreeLayout.Item> items) {
        PrepTreeLayout.Item item = new PrepTreeLayout.Item(visual.width, BOX_HEIGHT);
        items.put(visual, item);
        for (VisualNode child : visual.children) {
            item.children.add(toItem(child, items));
        }
        return item;
    }

    // ---------- drawing ----------

    /**
     * Builds the picture once; panning and zooming only move or scale it. Kept cheap to redraw: all arrows are two
     * shapes (the lines and the heads), there are no effects, the boxes are cached as bitmaps (panning only moves
     * them) and take no part in the layout of the canvas.
     */
    private void render() {
        List<javafx.scene.Node> picture = drawPicture(boxByVisual);
        selectionHalo.setVisible(false);
        // the boxes on top of the arrows (the first two shapes), the selection halo just behind the boxes
        canvas.getChildren().setAll(picture.subList(0, 2));
        canvas.getChildren().add(selectionHalo);
        canvas.getChildren().addAll(picture.subList(2, picture.size()));
    }

    /**
     * The shapes of the picture: the arrows (two shapes: the lines and the heads), then the boxes on top of them.
     *
     * @param boxes receives the box of every visual node
     */
    private List<javafx.scene.Node> drawPicture(Map<VisualNode, VBox> boxes) {
        Path lines = new Path();
        lines.setStroke(ARROW_COLOR);
        lines.setStrokeWidth(1.8);
        Path heads = new Path();
        heads.setFill(ARROW_COLOR);
        heads.setStroke(null);
        List<javafx.scene.Node> shapes = new ArrayList<>(List.of(lines, heads));
        for (VisualNode visual : visualByNode.values()) {
            for (VisualNode child : visual.children) {
                addArrow(visual, child, lines, heads);
            }
            VBox box = createBox(visual);
            boxes.put(visual, box);
            shapes.add(box);
        }
        return shapes;
    }

    /** A straight arrow from the border of the parent box to the border of the child box */
    private static void addArrow(VisualNode parent, VisualNode child, Path lines, Path heads) {
        double dx = child.x - parent.x;
        double dy = child.y - parent.y;
        double length = Math.hypot(dx, dy);
        if (length < 1) {
            return;
        }
        double ux = dx / length;
        double uy = dy / length;
        double startExit = boxExitDistance(ux, uy, parent.width) + 2;
        double endExit = boxExitDistance(ux, uy, child.width) + 2;
        if (startExit + endExit >= length) {
            return;
        }
        double startX = parent.x + ux * startExit;
        double startY = parent.y + uy * startExit;
        double endX = child.x - ux * endExit;
        double endY = child.y - uy * endExit;
        double headLength = 9;
        double headWidth = 4.5;
        // the line stops where the head starts, so the head stays sharp
        lines.getElements().addAll(new MoveTo(startX, startY),
                new LineTo(endX - ux * headLength * 0.8, endY - uy * headLength * 0.8));
        heads.getElements().addAll(new MoveTo(endX, endY),
                new LineTo(endX - ux * headLength - uy * headWidth, endY - uy * headLength + ux * headWidth),
                new LineTo(endX - ux * headLength + uy * headWidth, endY - uy * headLength - ux * headWidth),
                new ClosePath());
    }

    /** Distance from the center of a box to its border in the given direction */
    private static double boxExitDistance(double ux, double uy, double width) {
        double tx = Math.abs(ux) < 1e-9 ? Double.POSITIVE_INFINITY : width / 2 / Math.abs(ux);
        double ty = Math.abs(uy) < 1e-9 ? Double.POSITIVE_INFINITY : BOX_HEIGHT / 2 / Math.abs(uy);
        return Math.min(tx, ty);
    }

    private VBox createBox(VisualNode visual) {
        VBox box = new VBox(1);
        box.setMinSize(visual.width, BOX_HEIGHT);
        box.setPrefSize(visual.width, BOX_HEIGHT);
        box.setMaxSize(visual.width, BOX_HEIGHT);
        // positioned once, out of the canvas layout; drawn from a cached bitmap while panning
        box.setManaged(false);
        box.resize(visual.width, BOX_HEIGHT);
        box.relocate(visual.x - visual.width / 2, visual.y - BOX_HEIGHT / 2);
        box.setCache(true);
        box.setPadding(new Insets(3, BOX_HORIZONTAL_PADDING, 3, BOX_HORIZONTAL_PADDING));
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle(boxStyle(visual, visual.node == selectedNode));

        String textStyle = "-fx-text-fill: " + (isDarkBackground(visual) ? "white" : "black") + ";";
        HBox header = new HBox(HEADER_SPACING);
        header.setAlignment(Pos.CENTER_LEFT);
        if (visual.opponentMoveText != null) {
            Label opponentMove = new Label(visual.opponentMoveText);
            opponentMove.setStyle("-fx-font-size: " + MOVE_FONT_SIZE + ";" + textStyle);
            opponentMove.setMinWidth(Region.USE_PREF_SIZE);
            header.getChildren().add(opponentMove);
        }
        Label myMove = new Label(visual.myMoveLabel);
        myMove.setMinWidth(Region.USE_PREF_SIZE);
        if (visual.options.size() > 1) {
            myMove.setCursor(Cursor.HAND);
            // a changed move is marked in the selector, too
            myMove.setStyle("-fx-font-size: " + MOVE_FONT_SIZE + "; -fx-font-weight: bold; -fx-text-fill: black;"
                    + "-fx-border-color: " + (visual.followsGenerator ? "#78909c" : USER_CHOICE_BORDER_COLOR) + ";"
                    + "-fx-border-radius: 3; -fx-background-radius: 3; -fx-padding: 0 3 0 3;"
                    + "-fx-background-color: " + (visual.followsGenerator ? "rgba(255,255,255,0.85)" : "#ffe0b2") + ";");
            myMove.setTooltip(createTooltip("Your move. Click to see the moves considered here and to choose "
                    + "another one"));
            myMove.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    showMoveMenu(visual, myMove);
                    e.consume();
                }
            });
        } else {
            myMove.setStyle("-fx-font-size: " + MOVE_FONT_SIZE + "; -fx-font-weight: bold;" + textStyle);
        }
        header.getChildren().add(myMove);
        box.getChildren().add(header);
        for (String line : visual.statLines) {
            Label stat = new Label(line);
            stat.setStyle("-fx-font-size: " + STATS_FONT_SIZE + ";" + textStyle);
            box.getChildren().add(stat);
        }

        Tooltip.install(box, createTooltip(lineText(visual.statsNode) + "\n\n"
                + describeColor(visual) + "\n"
                + describeOpponentLoss(visual) + "\n\n"
                + "Chance: probability to get here if the opponents play like in the Lichess games\n"
                + "Eval: engine evaluation in pawns after your move, from your point of view\n"
                + "Nodes: size of the prep from here on, the number of its leaves plus branches\n"
                + "Thick: thickness of the line from here on, the average log2 of the number of your good moves "
                + "per decision (0 = one good move every time, 1 = two, 2 = four); \"-\" if not measured yet\n"
                + "EP: expected points (VP), the average normalized eval (Lichess Win%) at the ends of the lines\n"
                + "LP: Lichess points (Lichess VP), the average score of Lichess players at the ends of the lines"
                + (visual.followsGenerator ? ""
                : "\nDashed orange border: you chose another move than the generator's")));
        box.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                select(visual);
            }
        });
        return box;
    }

    /**
     * The background shows the eval gain, a dashed orange border the user's move choice; the selected box gets a blue
     * halo behind it. They are independent, so a selected box still shows whether its move was changed. No effects:
     * they would make panning slow.
     */
    private static String boxStyle(VisualNode visual, boolean isSelected) {
        boolean isRoot = visual.depth == 0;
        String borderColor = visual.followsGenerator ? BORDER_COLOR : USER_CHOICE_BORDER_COLOR;
        double borderWidth = !visual.followsGenerator ? 3 : isRoot ? 2.5 : 1.2;
        return "-fx-background-color: " + toCss(backgroundColor(visual)) + ";"
                + "-fx-background-radius: 6; -fx-border-radius: 6;"
                + "-fx-border-color: " + borderColor + ";"
                + "-fx-border-width: " + borderWidth + ";"
                + "-fx-border-style: " + (visual.followsGenerator ? "solid" : "dashed") + ";";
    }

    /** White up to a small eval gain over the starting position, then gradually greener */
    private static Color backgroundColor(VisualNode visual) {
        return NO_GAIN_COLOR.interpolate(FULL_GAIN_COLOR, gainShade(visual));
    }

    /** 0 (white) up to {@link #WHITE_GAIN}, 1 (dark green) from {@link #FULL_GAIN} on */
    private static double gainShade(VisualNode visual) {
        if (visual.depth == 0 || Double.isNaN(visual.gain)) {
            return 0;
        }
        return clamp((visual.gain - WHITE_GAIN) / (FULL_GAIN - WHITE_GAIN), 0, 1);
    }

    private static boolean isDarkBackground(VisualNode visual) {
        return gainShade(visual) > 0.5;
    }

    private static String describeColor(VisualNode visual) {
        if (visual.depth == 0) {
            return "The starting position of the prep";
        }
        return String.format("The eval is %+.2f compared with the starting position. White up to %+.2f, "
                + "darkest green from %+.2f on.", visual.gain, WHITE_GAIN, FULL_GAIN);
    }

    private static String describeOpponentLoss(VisualNode visual) {
        double loss = visual.opponentLoss;
        if (visual.opponentMoveText == null || Double.isNaN(loss)) {
            return "";
        }
        String verdict = loss >= BLUNDER_LOSS ? "a blunder (??)" : loss >= MISTAKE_LOSS ? "a mistake (?)"
                : loss >= INACCURACY_LOSS ? "an inaccuracy (?!)" : "fine for the opponent";
        // Lichess shows winning chances as Win% = 50 + 50 * chances
        return String.format("The opponent's move lowers his Win%% by %.1f points compared with his best move: %s "
                + "(?! from %.1f, ? from %.1f, ?? from %.1f).", loss * 50, verdict, INACCURACY_LOSS * 50,
                MISTAKE_LOSS * 50, BLUNDER_LOSS * 50);
    }

    private static String toCss(Color color) {
        return String.format("rgb(%d,%d,%d)", Math.round(color.getRed() * 255), Math.round(color.getGreen() * 255),
                Math.round(color.getBlue() * 255));
    }

    private void showMoveMenu(VisualNode visual, Label anchor) {
        ContextMenu menu = new ContextMenu();
        for (MoveOption option : visual.options) {
            Label label = new Label();
            CustomMenuItem item = new CustomMenuItem(label);
            if (option.child.isPruned) {
                String reason = describe(option.child.pruneReason);
                label.setText("      " + option.text + "    pruned: " + reason);
                label.setStyle("-fx-text-fill: #9e9e9e; -fx-font-style: italic;");
                label.setTooltip(createTooltip("Can't be chosen: this move was pruned because " + reason + "."));
                // not selectable: clicking does nothing and keeps the menu open
                item.setHideOnClick(false);
            } else {
                Node after = option.child;
                label.setText((option == visual.selected ? "✓  " : "      ") + option.text
                        + "    EP " + formatPercent(after.vp) + "  LP " + formatPercent(after.lichessVp)
                        + "  Eval " + formatEval(after.normEval) + "  Nodes " + nodeCount(after)
                        + "  Thick " + formatThickness(after)
                        + (option.isGeneratorChoice ? "    ★ generator's choice" : ""));
                if (option == visual.selected) {
                    label.setStyle("-fx-font-weight: bold;");
                }
                item.setOnAction(e -> choose(visual, option));
            }
            menu.getItems().add(item);
        }
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        menu.show(anchor, bounds.getMinX(), bounds.getMaxY());
    }

    private static String describe(PruneReason reason) {
        if (reason == null) {
            return "of an unknown reason";
        }
        return switch (reason) {
            case TOO_FEW_GAMES -> "there are too few games in this position";
            case SUBPAR_EVAL -> "its engine evaluation is too low";
            case SUBPAR_EVAL_SUBSEQUENT ->
                    "the evaluation drops too much later in the line (more than maxEvalSetback below the best line)";
            case SUBPAR_EVAL_CHAIN -> "all its continuations were pruned";
            case SUBPAR_EVAL_AND_SCORE -> "both its evaluation and its Lichess score are too low";
            case SUBPAR_EVAL_AND_PREP_EVAL -> "both its evaluation and its prep evaluation are too low";
            case SUBPAR_BOTH_VP_AND_LICHESS_SCORE ->
                    "another move has better expected points (by vpMarginForPruning) "
                            + "and better Lichess points (by lichessScoreMarginForPruning)";
            case TOO_MANY_BRANCHES -> "the line has too many branches";
        };
    }

    private static Tooltip createTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(480);
        tooltip.setShowDelay(Duration.millis(400));
        tooltip.setShowDuration(Duration.seconds(30));
        return tooltip;
    }

    // ---------- selection ----------

    private void choose(VisualNode visual, MoveOption option) {
        if (!(visual.node instanceof MyNode myNode) || option == visual.selected) {
            return;
        }
        choices.put(myNode, option.move);
        // keep the changed box where it is on the screen
        double viewportX = toViewportX(visual.x);
        double viewportY = toViewportY(visual.y);
        Node changedNode = visual.node;
        rebuild(() -> {
            VisualNode changed = visualByNode.get(changedNode);
            if (changed != null) {
                select(changed);
                scrollTo(changed.x, changed.y, viewportX, viewportY);
            }
        });
    }

    private void select(VisualNode visual) {
        selectedNode = visual.node;
        double margin = 6;
        selectionHalo.setX(visual.x - visual.width / 2 - margin);
        selectionHalo.setY(visual.y - BOX_HEIGHT / 2 - margin);
        selectionHalo.setWidth(visual.width + 2 * margin);
        selectionHalo.setHeight(BOX_HEIGHT + 2 * margin);
        selectionHalo.setVisible(true);
        onPositionSelected.accept(visual.statsNode);
    }

    // ---------- zoom and scrolling ----------

    private void showInitialView() {
        if (rootVisual == null) {
            return;
        }
        // the whole tree if it's readable that way, otherwise readable boxes around the root
        setZoom(Math.min(1, Math.max(fitZoom(), 0.75)));
        scrollTo(rootVisual.x, rootVisual.y, viewportWidth() / 2, viewportHeight() / 2);
    }

    private void whenViewportReady(Runnable action) {
        if (viewportWidth() > 0 && viewportHeight() > 0) {
            Platform.runLater(action);
            return;
        }
        ChangeListener<Bounds> listener = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Bounds> observable, Bounds oldValue,
                                Bounds newValue) {
                if (newValue.getWidth() > 0 && newValue.getHeight() > 0) {
                    scrollPane.viewportBoundsProperty().removeListener(this);
                    Platform.runLater(action);
                }
            }
        };
        scrollPane.viewportBoundsProperty().addListener(listener);
    }

    private double fitZoom() {
        double width = canvas.getPrefWidth();
        double height = canvas.getPrefHeight();
        if (width <= 0 || height <= 0 || viewportWidth() <= 0) {
            return 1;
        }
        return clamp(Math.min(viewportWidth() / width, viewportHeight() / height) * 0.98, MIN_ZOOM, 1);
    }

    private void handleScroll(ScrollEvent e) {
        e.consume();
        if (rootVisual == null || e.getDeltaY() == 0) {
            return;
        }
        ScrollBar scrollBar = findScrollBar(e.getTarget());
        if (scrollBar != null) {
            // over a scroll bar the wheel scrolls
            double pixels = -e.getDeltaY() * WHEEL_SCROLL_SPEED;
            if (scrollBar.getOrientation() == javafx.geometry.Orientation.VERTICAL) {
                double extraHeight = holderHeight() - viewportHeight();
                if (extraHeight > 0) {
                    scrollPane.setVvalue(clamp(scrollPane.getVvalue() + pixels / extraHeight, 0, 1));
                }
            } else {
                double extraWidth = holderWidth() - viewportWidth();
                if (extraWidth > 0) {
                    scrollPane.setHvalue(clamp(scrollPane.getHvalue() + pixels / extraWidth, 0, 1));
                }
            }
            return;
        }
        Point2D mouse = holder.sceneToLocal(e.getSceneX(), e.getSceneY());
        double viewportX = mouse.getX() - scrollX();
        double viewportY = mouse.getY() - scrollY();
        setZoomAround(zoom.get() * Math.pow(WHEEL_ZOOM_BASE, e.getDeltaY()), viewportX, viewportY);
    }

    /** Zooms keeping the canvas point under the given viewport point in place */
    private void setZoomAround(double newZoom, double viewportX, double viewportY) {
        double canvasX = (viewportX + scrollX() - offsetX()) / zoom.get();
        double canvasY = (viewportY + scrollY() - offsetY()) / zoom.get();
        setZoom(newZoom);
        scrollTo(canvasX, canvasY, viewportX, viewportY);
    }

    private void setZoom(double newZoom) {
        double value = clamp(newZoom, MIN_ZOOM, MAX_ZOOM);
        zoom.set(value);
        scale.setX(value);
        scale.setY(value);
    }

    /** Scrolls so the canvas point appears at the viewport point (as far as the scroll range allows) */
    private void scrollTo(double canvasX, double canvasY, double viewportX, double viewportY) {
        scrollPane.layout();
        double extraWidth = holderWidth() - viewportWidth();
        double extraHeight = holderHeight() - viewportHeight();
        double left = offsetX() + canvasX * zoom.get() - viewportX;
        double top = offsetY() + canvasY * zoom.get() - viewportY;
        scrollPane.setHvalue(extraWidth > 0 ? clamp(left / extraWidth, 0, 1) : 0);
        scrollPane.setVvalue(extraHeight > 0 ? clamp(top / extraHeight, 0, 1) : 0);
    }

    private double toViewportX(double canvasX) {
        return offsetX() + canvasX * zoom.get() - scrollX();
    }

    private double toViewportY(double canvasY) {
        return offsetY() + canvasY * zoom.get() - scrollY();
    }

    private static ScrollBar findScrollBar(Object target) {
        javafx.scene.Node node = target instanceof javafx.scene.Node targetNode ? targetNode : null;
        while (node != null) {
            if (node instanceof ScrollBar scrollBar) {
                return scrollBar;
            }
            node = node.getParent();
        }
        return null;
    }

    /**
     * The scroll pane's skin sets the arrow-button step of its scroll bars to 10% of the visible part whenever the
     * layout changes; this keeps it at {@link #SCROLL_BAR_UNIT} instead.
     */
    private void speedUpScrollBars() {
        for (javafx.scene.Node node : scrollPane.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar scrollBar) {
                Runnable update = () -> {
                    double unit = scrollBar.getVisibleAmount() * SCROLL_BAR_UNIT;
                    if (unit > 0 && Math.abs(scrollBar.getUnitIncrement() - unit) > 1e-12) {
                        scrollBar.setUnitIncrement(unit);
                    }
                };
                scrollBar.unitIncrementProperty().addListener((obs, oldValue, newValue) -> update.run());
                scrollBar.visibleAmountProperty().addListener((obs, oldValue, newValue) -> update.run());
                update.run();
            }
        }
    }

    private void handlePanStart(MouseEvent e) {
        if (e.getButton() != MouseButton.SECONDARY) {
            return;
        }
        panStart = new Point2D(e.getScreenX(), e.getScreenY());
        panStartH = scrollPane.getHvalue();
        panStartV = scrollPane.getVvalue();
        scrollPane.setCursor(Cursor.MOVE);
        e.consume();
    }

    private void handlePanDrag(MouseEvent e) {
        if (panStart == null) {
            return;
        }
        double extraWidth = holderWidth() - viewportWidth();
        double extraHeight = holderHeight() - viewportHeight();
        if (extraWidth > 0) {
            scrollPane.setHvalue(clamp(panStartH - (e.getScreenX() - panStart.getX()) / extraWidth, 0, 1));
        }
        if (extraHeight > 0) {
            scrollPane.setVvalue(clamp(panStartV - (e.getScreenY() - panStart.getY()) / extraHeight, 0, 1));
        }
        e.consume();
    }

    private void handlePanEnd(MouseEvent e) {
        if (panStart == null || e.getButton() != MouseButton.SECONDARY) {
            return;
        }
        panStart = null;
        scrollPane.setCursor(Cursor.DEFAULT);
        e.consume();
    }

    private double viewportWidth() {
        return scrollPane.getViewportBounds().getWidth();
    }

    private double viewportHeight() {
        return scrollPane.getViewportBounds().getHeight();
    }

    private double contentWidth() {
        return canvas.getPrefWidth() * zoom.get();
    }

    private double contentHeight() {
        return canvas.getPrefHeight() * zoom.get();
    }

    /** The holder is at least as big as the viewport; a smaller tree is centered in it */
    private double holderWidth() {
        return Math.max(contentWidth(), viewportWidth());
    }

    private double holderHeight() {
        return Math.max(contentHeight(), viewportHeight());
    }

    private double offsetX() {
        return (holderWidth() - contentWidth()) / 2;
    }

    private double offsetY() {
        return (holderHeight() - contentHeight()) / 2;
    }

    private double scrollX() {
        return scrollPane.getHvalue() * Math.max(0, holderWidth() - viewportWidth());
    }

    private double scrollY() {
        return scrollPane.getVvalue() * Math.max(0, holderHeight() - viewportHeight());
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------- formatting ----------

    static String lineText(Node node) {
        return node.moveHistory.getMoves().isEmpty() ? "Starting position" : node.moveHistory.toString();
    }

    static String formatPercent(double value) {
        return String.format("%.1f%%", value * 100.);
    }

    /** Size of the prep below a node: its leaves plus its branches */
    private static int nodeCount(Node node) {
        return node.leaves + node.branches;
    }

    /** Thickness of the line below a node (see {@link Node#getThickness()}), or "-" if it isn't measured */
    private static String formatThickness(Node node) {
        return node.isThicknessKnown() ? String.format("%.2f", node.getThickness()) : "-";
    }

    static String formatEval(double normEval) {
        return String.format("%+.2f", CalculationUtil.denormalizeEval(normEval));
    }
}
