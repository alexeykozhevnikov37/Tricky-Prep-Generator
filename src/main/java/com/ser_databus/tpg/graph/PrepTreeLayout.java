package com.ser_databus.tpg.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lays out a tree of boxes around its root in two steps:
 * <ol>
 *     <li><b>An untangled start</b> ({@link #layoutSubtree}): every node's children fan out around it, away from its
 *     own parent, in cones of directions that don't overlap, so nothing collides and no arrows cross. Like the
 *     crossing-free initialisation of Gray et al., "A Scalable Method for Readable Tree Layouts" (2023).</li>
 *     <li><b>A physical simulation</b> ({@link Simulation}) in the manner of d3-force: boxes repel each other,
 *     arrows pull their boxes together when they are too long, overlapping boxes push each other apart, boxes
 *     slide off arrows they sit on, and a weak gravity holds everything around the root. The temperature cools down
 *     over a few hundred ticks, so the picture settles into a natural, compact shape. A step that would make arrows
 *     cross is shortened or cancelled, so the untangled start stays untangled.</li>
 * </ol>
 * The start runs in a horizontally squeezed space where every box is represented by the circle around it (circles
 * stay circles when a subtree is turned); stretching back is affine, so it stays valid. The simulation uses the real
 * rectangles.
 */
final class PrepTreeLayout {
    /** A box to lay out. The layout sets x and y: the box center, the root at (0, 0). */
    static final class Item {
        final double width;
        final double height;
        final List<Item> children = new ArrayList<>();
        double x;
        double y;

        // start layout state, in the squeezed space
        private double radius;
        private Geometry geometry;
        /** Direction of this subtree from its parent, relative to the parent's outward direction */
        private double theta;
        /** Distance from the parent's box center to this box center */
        private double distance;

        Item(double width, double height) {
            this.width = width;
            this.height = height;
        }
    }

    // ---------- start layout ----------
    /** Space between the circles of a parent and a child */
    private static final double ARROW_GAP = 12;
    /** Added to each circle when sibling subtrees are kept apart: half the space between them */
    private static final double SIBLING_PAD = 4;
    /** Space between an arrow and a circle it doesn't connect */
    private static final double ARROW_CLEARANCE = 6;
    /** Children never go into this sector pointing back to the parent: the arrow coming in stays free */
    private static final double INCOMING_GAP = Math.toRadians(120);
    /** A subtree's cone stays well below a half-plane, so the arrows inside it stay inside it */
    private static final double MAX_CONE = Math.toRadians(150);
    private static final int DISTANCE_SAMPLES = 48;
    private static final int COMPACTION_PASSES = 2;
    private static final int COMPACTION_STEPS = 14;
    /** Turns tried at every distance while compacting, in the order of preference */
    private static final double[] TURNS = {0, 0.05, -0.05, 0.1, -0.1, 0.18, -0.18, 0.28, -0.28};
    private static final double EPSILON = 1e-6;

    // ---------- simulation (d3-force-like: unit time step, velocities decayed by friction) ----------
    private static final int TICKS = 300;
    /** Extra ticks at the end with only the collision and arrow forces, until nothing overlaps */
    private static final int CLEANUP_TICKS = 150;
    private static final double CLEANUP_ALPHA = 0.3;
    /** d3 default: the temperature drops to 0.001 over {@link #TICKS} ticks */
    private static final double ALPHA_MIN = 0.001;
    /** Part of the velocity lost every tick (d3 default) */
    private static final double VELOCITY_DECAY = 0.4;
    /** Spring strength of an arrow, per pixel above {@link #TARGET_ARROW} */
    private static final double LINK_STRENGTH = 0.3;
    /**
     * Springs are weaker near the root, by the depth of the arrow's child (1, 2, 3, deeper): the many likely replies
     * there get more room
     */
    private static final double[] LINK_BY_DEPTH = {0.5, 0.75, 0.9, 1};
    /** Visible arrow length (between the box borders) above which arrows pull their boxes together */
    private static final double TARGET_ARROW = 30;
    /**
     * Repulsion between boxes (d3 many-body: strength / distance). Between a parent and its child it reaches
     * {@link #CHARGE_RANGE}; between other boxes it is short-sighted, see {@link #SHORT_RANGE}.
     */
    private static final double CHARGE = -900;
    private static final double CHARGE_RANGE = 420;
    /**
     * Between boxes not connected by an arrow, the repulsion fades out with the gap between their borders, smoothly
     * (by (1 - (gap / reach)^2)^2), and is gone at a gap of SHORT_RANGE times the box size (its longer side).
     */
    private static final double SHORT_RANGE = 5;
    /**
     * Distant relatives repel more, so branches look separate: the repulsion is multiplied by
     * 1 + BRANCH_REPULSION * (steps from both boxes to their nearest common ancestor - 2), at most
     * {@link #BRANCH_REPULSION_MAX}. Siblings get the plain repulsion.
     */
    private static final double BRANCH_REPULSION = 0.35;
    private static final double BRANCH_REPULSION_MAX = 4;
    /** A box with exactly one child is pulled towards the middle of its parent and child: chains run straight */
    private static final double STRAIGHTEN = 0.25;
    /**
     * Bending stiffness: a turn between an arrow and its parent's arrow has the energy
     * TURN_STIFFNESS * TURN_SCALE * excess^2 / 2, where the excess is the turn in radians beyond the free turn. Where
     * a line just continues (the parent has one child) any turn counts, so lines run straight; at a branching box
     * the children may fan out by TURN_FREE_AT_BRANCH without resistance. TURN_SCALE (pixels^2) makes the push about
     * TURN_STIFFNESS * excess * 140 px on a typical arrow.
     */
    private static final double TURN_STIFFNESS = 0.5;
    private static final double TURN_SCALE = 20000;
    private static final double TURN_FREE_AT_BRANCH = Math.toRadians(45);
    /**
     * The shape forces (straightening and bending) never get weaker than at this temperature, so the collisions and
     * the arrow pushes, which don't cool down either, can't bend lines late in the simulation with nothing pushing back.
     * Likewise, from that temperature on, the springs don't let an arrow get longer than it was then with less than
     * this strength, so the shape forces can't stretch arrows late with nothing pulling back.
     */
    private static final double SHAPE_ALPHA_MIN = 0.2;
    /**
     * Every FLIP_EVERY ticks (in the first two thirds of the simulation) FLIP_ATTEMPTS swaps of two neighbouring
     * siblings' subtrees are tried; a swap stays if it lowers the energy and makes no arrows cross
     */
    private static final int FLIP_EVERY = 20;
    private static final int FLIP_ATTEMPTS = 10;
    /** Space kept between colliding boxes, and the part of an overlap resolved per iteration */
    private static final double COLLISION_PAD = 20;
    private static final double COLLISION_STRENGTH = 0.8;
    private static final int COLLISION_ITERATIONS = 2;
    /** Boxes closer than this to an arrow that doesn't belong to them are pushed off it */
    private static final double ARROW_BOX_CLEARANCE = 14;
    private static final double ARROW_BOX_PUSH = 0.6;
    /** Pull towards the root, per pixel of distance: weak, the picture needn't fill a rectangle */
    private static final double GRAVITY = 0.004;
    /**
     * An extra pull towards the root that fades out with the distance (by e^(-distance / CENTER_RADIUS)): the empty
     * space near the center closes, the outskirts stay loose
     */
    private static final double CENTER_GRAVITY = 0.05;
    private static final double CENTER_RADIUS = 500;
    /** Longest move of a box per tick: keeps the simulation stable and the crossing checks local */
    private static final double MAX_MOVE = 50;

    private final double squeeze;

    /**
     * The start layout squeezes x halfway (geometrically) to a box of the base size being as wide as high: enough to
     * keep the circles tight, not so much that the picture gets too wide when stretched back.
     */
    private PrepTreeLayout(double baseWidth, double height) {
        this.squeeze = Math.sqrt(height / baseWidth);
    }

    /** Sets x and y of all items; the root at (0, 0). */
    static void layout(Item root, double baseWidth) {
        PrepTreeLayout layout = new PrepTreeLayout(baseWidth, root.height);
        layout.layoutSubtree(root, 2 * Math.PI, false);
        layout.place(root, 0, 0, 0);
        layout.unsqueeze(root);
        new Simulation(root).run();
    }

    // ---------- start layout: geometry of a subtree (box circles and arrow segments) ----------

    private static final class Geometry {
        /** x, y, radius per circle */
        double[] circles = new double[3 * 4];
        int circleCount;
        /** x1, y1, x2, y2 per segment */
        double[] segments = new double[4 * 4];
        int segmentCount;

        void addCircle(double x, double y, double r) {
            if (3 * circleCount + 3 > circles.length) {
                circles = Arrays.copyOf(circles, circles.length * 2);
            }
            circles[3 * circleCount] = x;
            circles[3 * circleCount + 1] = y;
            circles[3 * circleCount + 2] = r;
            circleCount++;
        }

        void addSegment(double x1, double y1, double x2, double y2) {
            if (4 * segmentCount + 4 > segments.length) {
                segments = Arrays.copyOf(segments, segments.length * 2);
            }
            segments[4 * segmentCount] = x1;
            segments[4 * segmentCount + 1] = y1;
            segments[4 * segmentCount + 2] = x2;
            segments[4 * segmentCount + 3] = y2;
            segmentCount++;
        }

        void addAll(Geometry other) {
            for (int i = 0; i < other.circleCount; i++) {
                addCircle(other.circles[3 * i], other.circles[3 * i + 1], other.circles[3 * i + 2]);
            }
            for (int i = 0; i < other.segmentCount; i++) {
                addSegment(other.segments[4 * i], other.segments[4 * i + 1], other.segments[4 * i + 2],
                        other.segments[4 * i + 3]);
            }
        }

        /** This geometry (of a subtree in its own frame) placed at the distance in the direction theta */
        Geometry placed(double distance, double theta) {
            double cos = Math.cos(theta);
            double sin = Math.sin(theta);
            Geometry result = new Geometry();
            result.circles = new double[Math.max(3, 3 * circleCount)];
            result.segments = new double[Math.max(4, 4 * segmentCount)];
            for (int i = 0; i < circleCount; i++) {
                double x = distance + circles[3 * i];
                double y = circles[3 * i + 1];
                result.addCircle(x * cos - y * sin, x * sin + y * cos, circles[3 * i + 2]);
            }
            for (int i = 0; i < segmentCount; i++) {
                double x1 = distance + segments[4 * i];
                double y1 = segments[4 * i + 1];
                double x2 = distance + segments[4 * i + 2];
                double y2 = segments[4 * i + 3];
                result.addSegment(x1 * cos - y1 * sin, x1 * sin + y1 * cos, x2 * cos - y2 * sin, x2 * sin + y2 * cos);
            }
            return result;
        }
    }

    // ---------- start layout: bottom-up ----------

    /**
     * Lays out the subtree in its own frame (its root at the origin, facing +x).
     *
     * @param arc           the angle the children may use, centered on +x
     * @param guardIncoming if true, nothing may cover the ray from the node towards -x (the arrow from its parent)
     */
    private void layoutSubtree(Item item, double arc, boolean guardIncoming) {
        item.radius = Math.hypot(item.width * squeeze, item.height) / 2;
        item.geometry = new Geometry();
        item.geometry.addCircle(0, 0, item.radius);
        if (item.children.isEmpty()) {
            return;
        }
        for (Item child : item.children) {
            layoutSubtree(child, 2 * Math.PI - INCOMING_GAP, true);
        }
        // the most likely child (the first) in the middle, then alternately on both sides
        List<Item> order = new ArrayList<>();
        for (int i = 0; i < item.children.size(); i++) {
            if (i % 2 == 0) {
                order.add(item.children.get(i));
            } else {
                order.add(0, item.children.get(i));
            }
        }
        placeInCones(item, order, arc);
        compact(item, order, guardIncoming);
        for (Item child : order) {
            item.geometry.addAll(child.geometry.placed(child.distance, child.theta));
            item.geometry.addSegment(0, 0, child.distance * Math.cos(child.theta),
                    child.distance * Math.sin(child.theta));
            child.geometry = null;
        }
    }

    /** {lo, hi, near}: the cone of the subtree placed at the distance along +x, and its closest approach */
    private static double[] cone(Geometry geometry, double distance) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double near = Double.POSITIVE_INFINITY;
        for (int i = 0; i < geometry.circleCount; i++) {
            double x = distance + geometry.circles[3 * i];
            double y = geometry.circles[3 * i + 1];
            double r = geometry.circles[3 * i + 2];
            double dist = Math.hypot(x, y);
            double angle = Math.atan2(y, x);
            double padded = r + SIBLING_PAD;
            double half = dist > padded ? Math.asin(padded / dist) : Math.PI;
            lo = Math.min(lo, angle - half);
            hi = Math.max(hi, angle + half);
            near = Math.min(near, dist - r);
        }
        return new double[]{lo, hi, near};
    }

    /**
     * Gives every child a cone of directions no other one overlaps (so subtrees and the arrows to them can't
     * collide), at distances minimising their sum: a Lagrangian trade-off between distance and cone width.
     */
    private static void placeInCones(Item item, List<Item> order, double arc) {
        // per child: possible distances with the cones there, {distance, lo, hi}
        List<List<double[]>> table = new ArrayList<>();
        for (Item child : order) {
            double minDistance = item.radius + child.radius + ARROW_GAP;
            double distance = minDistance;
            double step = Math.max(4, minDistance * 0.06);
            List<double[]> candidates = new ArrayList<>();
            double[] cone = null;
            for (int k = 0; k < DISTANCE_SAMPLES * 4 && candidates.size() < DISTANCE_SAMPLES; k++) {
                cone = cone(child.geometry, distance);
                if (cone[2] >= item.radius + ARROW_GAP - EPSILON && cone[1] - cone[0] <= MAX_CONE) {
                    candidates.add(new double[]{distance, cone[0], cone[1]});
                }
                distance += step;
                step *= 1.07;
            }
            if (candidates.isEmpty()) {
                candidates.add(new double[]{distance, cone[0], cone[1]});
            }
            table.add(candidates);
        }
        double[][] picks = choose(table, 0);
        if (totalWidth(picks) > arc) {
            double low = 0;
            double high = 1;
            while (totalWidth(choose(table, high)) > arc && high < 1e12) {
                high *= 2;
            }
            for (int i = 0; i < 50; i++) {
                double middle = (low + high) / 2;
                if (totalWidth(choose(table, middle)) > arc) {
                    low = middle;
                } else {
                    high = middle;
                }
            }
            picks = choose(table, high);
        }
        double start = -totalWidth(picks) / 2;
        for (int i = 0; i < order.size(); i++) {
            Item child = order.get(i);
            double[] pick = picks[i];
            child.distance = pick[0];
            child.theta = start - pick[1];
            start += pick[2] - pick[1];
        }
    }

    /** For every child the candidate minimising distance + lambda * cone width */
    private static double[][] choose(List<List<double[]>> table, double lambda) {
        double[][] picks = new double[table.size()][];
        for (int i = 0; i < table.size(); i++) {
            double best = Double.POSITIVE_INFINITY;
            for (double[] candidate : table.get(i)) {
                double cost = candidate[0] + lambda * (candidate[2] - candidate[1]);
                if (cost < best) {
                    best = cost;
                    picks[i] = candidate;
                }
            }
        }
        return picks;
    }

    private static double totalWidth(double[][] picks) {
        double total = 0;
        for (double[] pick : picks) {
            total += pick[2] - pick[1];
        }
        return total;
    }

    /**
     * Cones are a sufficient condition, not a necessary one: pulls every subtree closer to the node (turning it a
     * little if needed) as far as an exact check of all circles and arrows allows.
     */
    private static void compact(Item item, List<Item> order, boolean guardIncoming) {
        for (int pass = 0; pass < COMPACTION_PASSES; pass++) {
            List<Item> farthestFirst = new ArrayList<>(order);
            farthestFirst.sort((a, b) -> Double.compare(b.distance, a.distance));
            for (Item child : farthestFirst) {
                double minDistance = item.radius + child.radius + ARROW_GAP;
                if (child.distance <= minDistance + 1) {
                    continue;
                }
                CircleIndex others = new CircleIndex();
                for (Item other : order) {
                    if (other != child) {
                        others.addAll(other.geometry.placed(other.distance, other.theta));
                        others.addSegment(0, 0, other.distance * Math.cos(other.theta),
                                other.distance * Math.sin(other.theta));
                    }
                }
                double low = minDistance;
                double high = child.distance;
                double bestDistance = child.distance;
                double bestTheta = child.theta;
                for (int step = 0; step < COMPACTION_STEPS; step++) {
                    double middle = (low + high) / 2;
                    double found = Double.NaN;
                    for (double turn : TURNS) {
                        if (fits(item, child, middle, child.theta + turn, others, guardIncoming)) {
                            found = child.theta + turn;
                            break;
                        }
                    }
                    if (Double.isNaN(found)) {
                        low = middle;
                    } else {
                        high = middle;
                        bestDistance = middle;
                        bestTheta = found;
                    }
                }
                child.distance = bestDistance;
                child.theta = bestTheta;
            }
        }
    }

    /** True if the child's subtree fits at the place without touching the node, the other subtrees or any arrow */
    private static boolean fits(Item item, Item child, double distance, double theta, CircleIndex others,
                                boolean guardIncoming) {
        Geometry geometry = child.geometry.placed(distance, theta);
        for (int i = 0; i < geometry.circleCount; i++) {
            double x = geometry.circles[3 * i];
            double y = geometry.circles[3 * i + 1];
            double r = geometry.circles[3 * i + 2];
            if (Math.hypot(x, y) < item.radius + r + ARROW_GAP - EPSILON) {
                return false;
            }
            if (guardIncoming && x < 0 && Math.abs(y) < r + ARROW_CLEARANCE) {
                return false;
            }
            if (others.circleConflicts(x, y, r)) {
                return false;
            }
        }
        for (int i = 0; i < geometry.segmentCount; i++) {
            if (others.segmentConflicts(geometry.segments[4 * i], geometry.segments[4 * i + 1],
                    geometry.segments[4 * i + 2], geometry.segments[4 * i + 3])) {
                return false;
            }
        }
        return !others.segmentConflicts(0, 0, distance * Math.cos(theta), distance * Math.sin(theta));
    }

    // ---------- start layout: top-down placement ----------

    private void place(Item item, double x, double y, double outward) {
        item.x = x;
        item.y = y;
        for (Item child : item.children) {
            double angle = outward + child.theta;
            place(child, x + child.distance * Math.cos(angle), y + child.distance * Math.sin(angle), angle);
        }
    }

    private void unsqueeze(Item item) {
        item.x /= squeeze;
        for (Item child : item.children) {
            unsqueeze(child);
        }
    }

    /** Circles and segments of the other subtrees in a grid, so a check only looks at its neighbourhood */
    private static final class CircleIndex {
        private static final double CELL = 120;
        /** Every item is inserted this much bigger, so a query only needs its own bounds */
        private static final double MARGIN = Math.max(2 * SIBLING_PAD, ARROW_CLEARANCE);
        private final Geometry geometry = new Geometry();
        /** cell -> indexes: circles as i, segments as -(i + 1) */
        private final Map<Long, List<Integer>> cells = new HashMap<>();

        void addAll(Geometry other) {
            for (int i = 0; i < other.circleCount; i++) {
                double x = other.circles[3 * i];
                double y = other.circles[3 * i + 1];
                double r = other.circles[3 * i + 2];
                geometry.addCircle(x, y, r);
                insert(geometry.circleCount - 1, x - r - MARGIN, y - r - MARGIN, x + r + MARGIN, y + r + MARGIN);
            }
            for (int i = 0; i < other.segmentCount; i++) {
                addSegment(other.segments[4 * i], other.segments[4 * i + 1], other.segments[4 * i + 2],
                        other.segments[4 * i + 3]);
            }
        }

        void addSegment(double x1, double y1, double x2, double y2) {
            geometry.addSegment(x1, y1, x2, y2);
            insert(-geometry.segmentCount, Math.min(x1, x2) - MARGIN, Math.min(y1, y2) - MARGIN,
                    Math.max(x1, x2) + MARGIN, Math.max(y1, y2) + MARGIN);
        }

        private void insert(int index, double minX, double minY, double maxX, double maxY) {
            for (long cx = cell(minX); cx <= cell(maxX); cx++) {
                for (long cy = cell(minY); cy <= cell(maxY); cy++) {
                    cells.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(index);
                }
            }
        }

        boolean circleConflicts(double x, double y, double r) {
            for (long cx = cell(x - r); cx <= cell(x + r); cx++) {
                for (long cy = cell(y - r); cy <= cell(y + r); cy++) {
                    List<Integer> indexes = cells.get(key(cx, cy));
                    if (indexes == null) {
                        continue;
                    }
                    for (int index : indexes) {
                        if (index >= 0) {
                            double ox = geometry.circles[3 * index];
                            double oy = geometry.circles[3 * index + 1];
                            double or = geometry.circles[3 * index + 2];
                            if (Math.hypot(x - ox, y - oy) < r + or + 2 * SIBLING_PAD) {
                                return true;
                            }
                        } else {
                            int s = 4 * (-index - 1);
                            if (pointToSegment(x, y, geometry.segments[s], geometry.segments[s + 1],
                                    geometry.segments[s + 2], geometry.segments[s + 3]) < r + ARROW_CLEARANCE) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        /** A segment of the subtree being placed: it may come near no other circle and cross no other segment */
        boolean segmentConflicts(double x1, double y1, double x2, double y2) {
            boolean fromParent = x1 == 0 && y1 == 0;
            for (long cx = cell(Math.min(x1, x2)); cx <= cell(Math.max(x1, x2)); cx++) {
                for (long cy = cell(Math.min(y1, y2)); cy <= cell(Math.max(y1, y2)); cy++) {
                    List<Integer> indexes = cells.get(key(cx, cy));
                    if (indexes == null) {
                        continue;
                    }
                    for (int index : indexes) {
                        if (index >= 0) {
                            double ox = geometry.circles[3 * index];
                            double oy = geometry.circles[3 * index + 1];
                            double or = geometry.circles[3 * index + 2];
                            if (pointToSegment(ox, oy, x1, y1, x2, y2) < or + ARROW_CLEARANCE) {
                                return true;
                            }
                        } else {
                            int s = 4 * (-index - 1);
                            double ox1 = geometry.segments[s];
                            double oy1 = geometry.segments[s + 1];
                            // arrows from the same parent only meet at the parent
                            boolean otherFromParent = ox1 == 0 && oy1 == 0;
                            if (!(fromParent && otherFromParent) && segmentsCross(x1, y1, x2, y2, ox1, oy1,
                                    geometry.segments[s + 2], geometry.segments[s + 3])) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        private static long cell(double coordinate) {
            return (long) Math.floor(coordinate / CELL);
        }

        private static long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xffffffffL);
        }
    }

    // ---------- the physical simulation, with the real rectangles ----------

    /**
     * A d3-force-like simulation (velocity Verlet with unit time step: forces add to the velocities, friction removes
     * {@link #VELOCITY_DECAY} of them every tick, the temperature alpha cools from 1 to {@link #ALPHA_MIN}):
     * <ul>
     *     <li>arrows act as springs that only pull, when the arrow between the box borders is longer than
     *     {@link #TARGET_ARROW}; the box with more connections moves less (as in d3's link force);</li>
     *     <li>springs are weaker near the root ({@link #LINK_BY_DEPTH}), so the many likely replies there spread
     *     out;</li>
     *     <li>boxes repel each other (d3's many-body force: strength / distance), the more the more distant
     *     relatives they are ({@link #BRANCH_REPULSION}), so branches look separate; boxes not connected by an
     *     arrow only repel when they are near each other ({@link #SHORT_RANGE});</li>
     *     <li>a box with one child is pulled into the line between its parent and child: chains run straight;</li>
     *     <li>joints resist bending: lines run straight, children of a branching box fan out a little
     *     ({@link #TURN_STIFFNESS});</li>
     *     <li>overlapping boxes (with {@link #COLLISION_PAD}) are pushed apart along the axis of the smaller
     *     overlap;</li>
     *     <li>a box near an arrow that isn't its own is pushed off it, and the arrow gives way, too (the node-edge
     *     force of Gray et al.);</li>
     *     <li>a weak gravity pulls everything towards the root, which stays fixed, and a stronger one fading out with
     *     the distance ({@link #CENTER_GRAVITY}) closes the empty space near the center.</li>
     * </ul>
     * A move that would make two arrows cross is shortened, or cancelled, so the untangled start stays untangled.
     * Now and then, swaps of neighbouring siblings' subtrees are tried, and kept if they lower the energy
     * ({@link #FLIP_EVERY}).
     * Afterwards, cleanup ticks with only the collision and arrow forces run until no boxes overlap and no arrow
     * runs through a box.
     */
    private static final class Simulation {
        private static final double CELL = 200;

        private final Item[] items;
        private final int[] parent;
        private final int[][] children;
        private final double[] x;
        private final double[] y;
        private final double[] vx;
        private final double[] vy;
        private final double[] width;
        private final int[] degree;
        private final int[] depth;
        /** Items are numbered in preorder: the subtree of i is [i, subtreeEnd[i]) */
        private final int[] subtreeEnd;
        private final double height;
        private final double maxWidth;
        /** Grids built at the start of a tick: boxes by their centers, arrows by their (widened) bounds */
        private final Map<Long, List<Integer>> boxGrid = new HashMap<>();
        private final Map<Long, List<Integer>> arrowGrid = new HashMap<>();
        private final int[] seen;
        private int stamp;
        /**
         * Visible length of the arrow into each box (at least {@link #TARGET_ARROW}) when the temperature fell to
         * {@link #SHAPE_ALPHA_MIN}; set once {@link #lengthsHeld}
         */
        private final double[] heldLength;
        private boolean lengthsHeld;

        Simulation(Item root) {
            List<Item> list = new ArrayList<>();
            List<Integer> parents = new ArrayList<>();
            collect(root, -1, list, parents);
            int n = list.size();
            items = list.toArray(new Item[0]);
            parent = new int[n];
            children = new int[n][];
            x = new double[n];
            y = new double[n];
            vx = new double[n];
            vy = new double[n];
            width = new double[n];
            degree = new int[n];
            depth = new int[n];
            subtreeEnd = new int[n];
            seen = new int[n];
            heldLength = new double[n];
            height = root.height;
            Map<Item, Integer> index = new IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                index.put(items[i], i);
            }
            double widest = 0;
            for (int i = 0; i < n; i++) {
                parent[i] = parents.get(i);
                x[i] = items[i].x;
                y[i] = items[i].y;
                width[i] = items[i].width;
                widest = Math.max(widest, width[i]);
                children[i] = new int[items[i].children.size()];
                for (int k = 0; k < children[i].length; k++) {
                    children[i][k] = index.get(items[i].children.get(k));
                }
                degree[i] = children[i].length + (parent[i] >= 0 ? 1 : 0);
                // preorder: the parent comes first
                depth[i] = parent[i] < 0 ? 0 : depth[parent[i]] + 1;
            }
            maxWidth = widest;
            for (int i = n - 1; i >= 0; i--) {
                subtreeEnd[i] = i + 1;
                for (int child : children[i]) {
                    subtreeEnd[i] = Math.max(subtreeEnd[i], subtreeEnd[child]);
                }
            }
        }

        private static void collect(Item item, int parentIndex, List<Item> list, List<Integer> parents) {
            int index = list.size();
            list.add(item);
            parents.add(parentIndex);
            for (Item child : item.children) {
                collect(child, index, list, parents);
            }
        }

        void run() {
            double alpha = 1;
            double alphaDecay = 1 - Math.pow(ALPHA_MIN, 1.0 / TICKS);
            Random random = new Random(items.length);
            for (int tick = 0; tick < TICKS; tick++) {
                if (tick > 0 && tick % FLIP_EVERY == 0 && tick < TICKS * 2 / 3) {
                    tryFlips(random);
                }
                alpha -= alpha * alphaDecay;
                if (!lengthsHeld && alpha < SHAPE_ALPHA_MIN) {
                    for (int child = 1; child < items.length; child++) {
                        heldLength[child] = Math.max(visibleArrow(child), TARGET_ARROW);
                    }
                    lengthsHeld = true;
                }
                tick(alpha, false);
            }
            Arrays.fill(vx, 0);
            Arrays.fill(vy, 0);
            for (int tick = 0; tick < CLEANUP_TICKS && hasProblems(); tick++) {
                tick(CLEANUP_ALPHA, true);
            }
            for (int i = 0; i < items.length; i++) {
                items[i].x = x[i];
                items[i].y = y[i];
            }
        }

        private void tick(double alpha, boolean cleanup) {
            int n = items.length;
            buildGrids();
            if (!cleanup) {
                applyLinks(alpha);
                applyCharge(alpha);
                applyStraightening(Math.max(alpha, SHAPE_ALPHA_MIN));
                applyTurnSmoothing(Math.max(alpha, SHAPE_ALPHA_MIN));
                for (int i = 0; i < n; i++) {
                    double dx = x[0] - x[i];
                    double dy = y[0] - y[i];
                    double pull = GRAVITY + CENTER_GRAVITY * Math.exp(-Math.hypot(dx, dy) / CENTER_RADIUS);
                    vx[i] += dx * pull * alpha;
                    vy[i] += dy * pull * alpha;
                }
            }
            applyArrowBoxForce(Math.max(alpha, 0.2));
            // friction, then the collisions on the predicted positions
            double[] nx = new double[n];
            double[] ny = new double[n];
            for (int i = 0; i < n; i++) {
                vx[i] *= 1 - VELOCITY_DECAY;
                vy[i] *= 1 - VELOCITY_DECAY;
                nx[i] = x[i] + vx[i];
                ny[i] = y[i] + vy[i];
            }
            resolveCollisions(nx, ny);
            nx[0] = x[0];
            ny[0] = y[0];
            // apply the moves one by one; a move that makes arrows cross is shortened or cancelled
            for (int i = 0; i < n; i++) {
                double dx = nx[i] - x[i];
                double dy = ny[i] - y[i];
                double length = Math.hypot(dx, dy);
                if (length == 0) {
                    continue;
                }
                if (length > MAX_MOVE) {
                    dx *= MAX_MOVE / length;
                    dy *= MAX_MOVE / length;
                }
                double oldX = x[i];
                double oldY = y[i];
                boolean done = false;
                for (double fraction : new double[]{1, 0.5, 0.25}) {
                    x[i] = oldX + dx * fraction;
                    y[i] = oldY + dy * fraction;
                    if (!crossesAnyArrow(i)) {
                        done = true;
                        break;
                    }
                }
                if (done) {
                    vx[i] = x[i] - oldX;
                    vy[i] = y[i] - oldY;
                } else {
                    x[i] = oldX;
                    y[i] = oldY;
                    vx[i] = 0;
                    vy[i] = 0;
                }
            }
        }

        /** Length of the arrow into the box between the borders of it and its parent */
        private double visibleArrow(int child) {
            int p = parent[child];
            double dx = x[child] - x[p];
            double dy = y[child] - y[p];
            double length = Math.max(Math.hypot(dx, dy), EPSILON);
            return length - exit(dx / length, dy / length, width[p]) - exit(dx / length, dy / length, width[child]);
        }

        /**
         * Arrows pull their boxes together when they are longer than the target; the busier box moves less. Once the
         * lengths are held, an arrow longer than its held length is pulled back with at least
         * {@link #SHAPE_ALPHA_MIN}.
         */
        private void applyLinks(double alpha) {
            for (int child = 1; child < items.length; child++) {
                int p = parent[child];
                double dx = x[child] - x[p];
                double dy = y[child] - y[p];
                double length = Math.max(Math.hypot(dx, dy), EPSILON);
                double ux = dx / length;
                double uy = dy / length;
                double visible = length - exit(ux, uy, width[p]) - exit(ux, uy, width[child]);
                if (visible <= TARGET_ARROW) {
                    continue;
                }
                double strength = LINK_STRENGTH * LINK_BY_DEPTH[Math.min(depth[child], LINK_BY_DEPTH.length) - 1];
                double force = strength * alpha * (visible - TARGET_ARROW);
                if (lengthsHeld && visible > heldLength[child]) {
                    force += strength * SHAPE_ALPHA_MIN * (visible - heldLength[child]);
                }
                double bias = (double) degree[p] / (degree[p] + degree[child]);
                vx[child] -= ux * force * bias;
                vy[child] -= uy * force * bias;
                vx[p] += ux * force * (1 - bias);
                vy[p] += uy * force * (1 - bias);
            }
        }

        /** Every pair of boxes within the range repels, like charges */
        private void applyCharge(double alpha) {
            double range = chargeQueryRange();
            for (int i = 0; i < items.length; i++) {
                for (long cx = cell(x[i] - range); cx <= cell(x[i] + range); cx++) {
                    for (long cy = cell(y[i] - range); cy <= cell(y[i] + range); cy++) {
                        List<Integer> entries = boxGrid.get(key(cx, cy));
                        if (entries == null) {
                            continue;
                        }
                        for (int j : entries) {
                            if (j <= i) {
                                continue;
                            }
                            double strength = repulsionStrength(i, j);
                            if (strength == 0) {
                                continue;
                            }
                            double dx = x[j] - x[i];
                            double dy = y[j] - y[i];
                            double force = CHARGE * strength * alpha / Math.max(dx * dx + dy * dy, 400);
                            vx[i] += dx * force;
                            vy[i] += dy * force;
                            vx[j] -= dx * force;
                            vy[j] -= dy * force;
                        }
                    }
                }
            }
        }

        private double chargeQueryRange() {
            return Math.max(CHARGE_RANGE, SHORT_RANGE * Math.max(maxWidth, height) + maxWidth);
        }

        /**
         * How strongly boxes i and j repel (a factor of {@link #CHARGE}): more for distant relatives, and for boxes
         * not connected by an arrow fading out with the gap between them; 0 when out of range.
         */
        private double repulsionStrength(int i, int j) {
            double dx = x[j] - x[i];
            double dy = y[j] - y[i];
            int steps = stepsToCommonAncestor(i, j);
            double taper = 1;
            if (steps == 1) {
                // a parent and its child
                if (dx * dx + dy * dy > CHARGE_RANGE * CHARGE_RANGE) {
                    return 0;
                }
            } else {
                double gapX = Math.max(0, Math.abs(dx) - (width[i] + width[j]) / 2);
                double gapY = Math.max(0, Math.abs(dy) - height);
                double gap = Math.hypot(gapX, gapY);
                double reach = SHORT_RANGE * (Math.max(width[i], height) + Math.max(width[j], height)) / 2;
                if (gap >= reach) {
                    return 0;
                }
                double fade = 1 - (gap / reach) * (gap / reach);
                taper = fade * fade;
            }
            return taper * Math.min(BRANCH_REPULSION_MAX, 1 + BRANCH_REPULSION * (steps - 2));
        }

        /** The turn of the arrow into i from the direction of the arrow into its parent, in (-pi, pi]; 0 if none */
        private double turnAt(int i) {
            int p = parent[i];
            if (p < 0 || parent[p] < 0) {
                return 0;
            }
            int g = parent[p];
            double incoming = Math.atan2(y[p] - y[g], x[p] - x[g]);
            double outgoing = Math.atan2(y[i] - y[p], x[i] - x[p]);
            return Math.IEEEremainder(outgoing - incoming, 2 * Math.PI);
        }

        /** The turn at the parent of i that bends freely: none where a line just continues, some at a branching */
        private double freeTurn(int i) {
            return children[parent[i]].length == 1 ? 0 : TURN_FREE_AT_BRANCH;
        }

        /**
         * Bending: the gradient of the turn energy. The box, its parent and its grandparent are pushed so that the
         * turn straightens, with no net force and no net torque, so the forces bend the joint and never spin a whole
         * branch around (a push only on the box and its parent spins the branch, which wound lines into spirals).
         * The push doesn't grow with the arrow's length; a sideways push on a long arrow would also stretch it.
         */
        private void applyTurnSmoothing(double alpha) {
            for (int i = 1; i < items.length; i++) {
                double turn = turnAt(i);
                double excess = Math.abs(turn) - (parent[i] > 0 ? freeTurn(i) : 0);
                if (parent[i] <= 0 || excess <= 0) {
                    continue;
                }
                int p = parent[i];
                int g = parent[p];
                double ax = x[p] - x[g];
                double ay = y[p] - y[g];
                double bx = x[i] - x[p];
                double by = y[i] - y[p];
                double aSquared = Math.max(ax * ax + ay * ay, EPSILON);
                double bSquared = Math.max(bx * bx + by * by, EPSILON);
                // force = -k * d(turn)/d(position); d(turn)/d(box) = (-by, bx) / |b|^2,
                // d(turn)/d(grandparent) = (-ay, ax) / |a|^2, the parent gets minus their sum
                double k = TURN_STIFFNESS * TURN_SCALE * Math.signum(turn) * excess * alpha;
                double fix = k * by / bSquared;
                double fiy = -k * bx / bSquared;
                double fgx = k * ay / aSquared;
                double fgy = -k * ax / aSquared;
                vx[i] += fix;
                vy[i] += fiy;
                if (g != 0) {
                    vx[g] += fgx;
                    vy[g] += fgy;
                }
                if (p != 0) {
                    vx[p] -= fix + fgx;
                    vy[p] -= fiy + fgy;
                }
            }
        }

        /**
         * The potential energy of the forces (without the temperature): what the flips try to lower. Springs and
         * turns as squares, the gravity of the root, and the repulsion as -charge * ln(distance).
         */
        private double energy() {
            buildGrids();
            double energy = 0;
            double range = chargeQueryRange();
            for (int i = 0; i < items.length; i++) {
                int p = parent[i];
                if (p >= 0) {
                    double dx = x[i] - x[p];
                    double dy = y[i] - y[p];
                    double length = Math.max(Math.hypot(dx, dy), EPSILON);
                    double visible = length - exit(dx / length, dy / length, width[p])
                            - exit(dx / length, dy / length, width[i]);
                    if (visible > TARGET_ARROW) {
                        double strength = LINK_STRENGTH * LINK_BY_DEPTH[Math.min(depth[i], LINK_BY_DEPTH.length) - 1];
                        energy += 0.5 * strength * (visible - TARGET_ARROW) * (visible - TARGET_ARROW);
                    }
                    if (p > 0) {
                        double excess = Math.abs(turnAt(i)) - freeTurn(i);
                        if (excess > 0) {
                            energy += 0.5 * TURN_STIFFNESS * TURN_SCALE * excess * excess;
                        }
                    }
                }
                // the potentials of the gravity forces: pull = d * (GRAVITY + CENTER_GRAVITY * e^(-d/R))
                double toRoot = Math.hypot(x[i] - x[0], y[i] - y[0]);
                energy += 0.5 * GRAVITY * toRoot * toRoot + CENTER_GRAVITY * CENTER_RADIUS
                        * (CENTER_RADIUS - (toRoot + CENTER_RADIUS) * Math.exp(-toRoot / CENTER_RADIUS));
                for (long cx = cell(x[i] - range); cx <= cell(x[i] + range); cx++) {
                    for (long cy = cell(y[i] - range); cy <= cell(y[i] + range); cy++) {
                        List<Integer> entries = boxGrid.get(key(cx, cy));
                        if (entries == null) {
                            continue;
                        }
                        for (int j : entries) {
                            if (j <= i) {
                                continue;
                            }
                            double strength = repulsionStrength(i, j);
                            if (strength > 0) {
                                double distance = Math.max(Math.hypot(x[j] - x[i], y[j] - y[i]), 20);
                                energy += -CHARGE * strength * Math.log(1000 / distance);
                            }
                        }
                    }
                }
            }
            return energy;
        }

        /**
         * Tries swapping the subtrees of two siblings next to each other (in the order of their directions from
         * the parent); keeps a swap that lowers the energy and makes no arrows cross.
         */
        private void tryFlips(Random random) {
            List<Integer> parents = new ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                if (children[i].length >= 2) {
                    parents.add(i);
                }
            }
            if (parents.isEmpty()) {
                return;
            }
            for (int attempt = 0; attempt < FLIP_ATTEMPTS; attempt++) {
                int p = parents.get(random.nextInt(parents.size()));
                double outward = parent[p] < 0 ? 0 : Math.atan2(y[p] - y[parent[p]], x[p] - x[parent[p]]);
                Integer[] kids = new Integer[children[p].length];
                for (int k = 0; k < kids.length; k++) {
                    kids[k] = children[p][k];
                }
                Arrays.sort(kids, (a, b) -> Double.compare(directionFrom(p, a, outward), directionFrom(p, b, outward)));
                // around the root the order is a circle; elsewhere the first and the last aren't neighbours
                int k = random.nextInt(parent[p] < 0 ? kids.length : kids.length - 1);
                int a = kids[k];
                int b = kids[(k + 1) % kids.length];
                if (a == b) {
                    continue;
                }
                double before = energy();
                double dx = x[b] - x[a];
                double dy = y[b] - y[a];
                shiftSubtree(a, dx, dy);
                shiftSubtree(b, -dx, -dy);
                buildGrids();
                if (!subtreeArrowsCross(a) && !subtreeArrowsCross(b) && energy() < before) {
                    // a's arrow now has b's old length, and the other way round
                    double held = heldLength[a];
                    heldLength[a] = heldLength[b];
                    heldLength[b] = held;
                    for (int m : new int[]{a, b}) {
                        for (int i = m; i < subtreeEnd[m]; i++) {
                            vx[i] = 0;
                            vy[i] = 0;
                        }
                    }
                } else {
                    shiftSubtree(a, -dx, -dy);
                    shiftSubtree(b, dx, dy);
                }
            }
        }

        /** Direction of child c from p, relative to p's outward direction, in [0, 2 pi) */
        private double directionFrom(int p, int c, double outward) {
            double angle = Math.atan2(y[c] - y[p], x[c] - x[p]) - outward + Math.PI;
            return angle - 2 * Math.PI * Math.floor(angle / (2 * Math.PI));
        }

        private void shiftSubtree(int top, double dx, double dy) {
            for (int i = top; i < subtreeEnd[top]; i++) {
                x[i] += dx;
                y[i] += dy;
            }
        }

        /** True if an arrow of the subtree, or the arrow into it, crosses another arrow (the grids must be fresh) */
        private boolean subtreeArrowsCross(int top) {
            for (int i = top; i < subtreeEnd[top]; i++) {
                if (arrowCrosses(parent[i], i)) {
                    return true;
                }
            }
            return false;
        }

        /** Steps from i and from j up to their nearest common ancestor, together (siblings: 2) */
        private int stepsToCommonAncestor(int i, int j) {
            int a = i;
            int b = j;
            while (depth[a] > depth[b]) {
                a = parent[a];
            }
            while (depth[b] > depth[a]) {
                b = parent[b];
            }
            while (a != b) {
                a = parent[a];
                b = parent[b];
            }
            return depth[i] + depth[j] - 2 * depth[a];
        }

        /** A box with exactly one child moves towards the middle of its parent and child; those give way */
        private void applyStraightening(double alpha) {
            double k = STRAIGHTEN * alpha;
            for (int i = 1; i < items.length; i++) {
                if (children[i].length != 1) {
                    continue;
                }
                int p = parent[i];
                int c = children[i][0];
                double mx = (x[p] + x[c]) / 2 - x[i];
                double my = (y[p] + y[c]) / 2 - y[i];
                vx[i] += mx * k;
                vy[i] += my * k;
                for (int end : new int[]{p, c}) {
                    if (end != 0) {
                        vx[end] -= mx * k / 2;
                        vy[end] -= my * k / 2;
                    }
                }
            }
        }

        /** A box close to an arrow that isn't its own slides off it, and the arrow's boxes give way */
        private void applyArrowBoxForce(double strength) {
            for (int child = 1; child < items.length; child++) {
                int p = parent[child];
                double x1 = x[p];
                double y1 = y[p];
                double x2 = x[child];
                double y2 = y[child];
                double reachX = maxWidth / 2 + ARROW_BOX_CLEARANCE;
                double reachY = height / 2 + ARROW_BOX_CLEARANCE;
                stamp++;
                for (long cx = cell(Math.min(x1, x2) - reachX); cx <= cell(Math.max(x1, x2) + reachX); cx++) {
                    for (long cy = cell(Math.min(y1, y2) - reachY); cy <= cell(Math.max(y1, y2) + reachY); cy++) {
                        List<Integer> entries = boxGrid.get(key(cx, cy));
                        if (entries == null) {
                            continue;
                        }
                        for (int k : entries) {
                            if (k == p || k == child || seen[k] == stamp) {
                                continue;
                            }
                            seen[k] = stamp;
                            if (!segmentHitsBox(x1, y1, x2, y2, x[k] - width[k] / 2 - ARROW_BOX_CLEARANCE,
                                    y[k] - height / 2 - ARROW_BOX_CLEARANCE, x[k] + width[k] / 2 + ARROW_BOX_CLEARANCE,
                                    y[k] + height / 2 + ARROW_BOX_CLEARANCE)) {
                                continue;
                            }
                            double ex = x2 - x1;
                            double ey = y2 - y1;
                            double lengthSquared = Math.max(ex * ex + ey * ey, EPSILON);
                            double t = Math.max(0, Math.min(1, ((x[k] - x1) * ex + (y[k] - y1) * ey) / lengthSquared));
                            double px = x[k] - (x1 + t * ex);
                            double py = y[k] - (y1 + t * ey);
                            double pl = Math.hypot(px, py);
                            if (pl < EPSILON) {
                                // exactly on the arrow: sideways
                                px = -ey;
                                py = ex;
                                pl = Math.sqrt(lengthSquared);
                            }
                            double push = ARROW_BOX_PUSH * (width[k] / 2 + ARROW_BOX_CLEARANCE) * strength;
                            vx[k] += px / pl * push;
                            vy[k] += py / pl * push;
                            for (int end : new int[]{p, child}) {
                                if (end != 0) {
                                    vx[end] -= px / pl * push * 0.5;
                                    vy[end] -= py / pl * push * 0.5;
                                }
                            }
                        }
                    }
                }
            }
        }

        /** Pushes overlapping boxes (with the pad) apart along the axis of the smaller overlap */
        private void resolveCollisions(double[] nx, double[] ny) {
            double reach = maxWidth + COLLISION_PAD + 2 * MAX_MOVE;
            for (int iteration = 0; iteration < COLLISION_ITERATIONS; iteration++) {
                for (int i = 0; i < items.length; i++) {
                    for (long cx = cell(x[i] - reach); cx <= cell(x[i] + reach); cx++) {
                        for (long cy = cell(y[i] - reach); cy <= cell(y[i] + reach); cy++) {
                            List<Integer> entries = boxGrid.get(key(cx, cy));
                            if (entries == null) {
                                continue;
                            }
                            for (int j : entries) {
                                if (j <= i) {
                                    continue;
                                }
                                double dx = nx[j] - nx[i];
                                double dy = ny[j] - ny[i];
                                double overlapX = (width[i] + width[j]) / 2 + COLLISION_PAD - Math.abs(dx);
                                double overlapY = height + COLLISION_PAD - Math.abs(dy);
                                if (overlapX <= 0 || overlapY <= 0) {
                                    continue;
                                }
                                if (overlapX < overlapY) {
                                    double shift = (dx >= 0 ? 1 : -1) * overlapX * COLLISION_STRENGTH / 2;
                                    nx[i] -= shift;
                                    nx[j] += shift;
                                } else {
                                    double shift = (dy >= 0 ? 1 : -1) * overlapY * COLLISION_STRENGTH / 2;
                                    ny[i] -= shift;
                                    ny[j] += shift;
                                }
                            }
                        }
                    }
                }
            }
        }

        /** True if an arrow of box i (to its parent or its children) crosses another arrow */
        private boolean crossesAnyArrow(int i) {
            for (int child : children[i]) {
                if (arrowCrosses(i, child)) {
                    return true;
                }
            }
            return parent[i] >= 0 && arrowCrosses(parent[i], i);
        }

        /** True if the arrow from a to b crosses another arrow (one not sharing a box with it) */
        private boolean arrowCrosses(int a, int b) {
            double x1 = x[a];
            double y1 = y[a];
            double x2 = x[b];
            double y2 = y[b];
            stamp++;
            for (long cx = cell(Math.min(x1, x2)); cx <= cell(Math.max(x1, x2)); cx++) {
                for (long cy = cell(Math.min(y1, y2)); cy <= cell(Math.max(y1, y2)); cy++) {
                    List<Integer> entries = arrowGrid.get(key(cx, cy));
                    if (entries == null) {
                        continue;
                    }
                    for (int other : entries) {
                        if (seen[other] == stamp) {
                            continue;
                        }
                        seen[other] = stamp;
                        int q = parent[other];
                        if (other == a || other == b || q == a || q == b) {
                            continue;
                        }
                        if (segmentsCross(x1, y1, x2, y2, x[q], y[q], x[other], y[other])) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** Some boxes overlap, or an arrow runs through a box */
        private boolean hasProblems() {
            buildGrids();
            for (int i = 0; i < items.length; i++) {
                for (long cx = cell(x[i] - maxWidth); cx <= cell(x[i] + maxWidth); cx++) {
                    for (long cy = cell(y[i] - height); cy <= cell(y[i] + height); cy++) {
                        List<Integer> entries = boxGrid.get(key(cx, cy));
                        if (entries == null) {
                            continue;
                        }
                        for (int j : entries) {
                            if (j > i && Math.abs(x[j] - x[i]) < (width[i] + width[j]) / 2
                                    && Math.abs(y[j] - y[i]) < height) {
                                return true;
                            }
                        }
                    }
                }
            }
            for (int child = 1; child < items.length; child++) {
                int p = parent[child];
                for (long cx = cell(Math.min(x[p], x[child]) - maxWidth / 2);
                     cx <= cell(Math.max(x[p], x[child]) + maxWidth / 2); cx++) {
                    for (long cy = cell(Math.min(y[p], y[child]) - height / 2);
                         cy <= cell(Math.max(y[p], y[child]) + height / 2); cy++) {
                        List<Integer> entries = boxGrid.get(key(cx, cy));
                        if (entries == null) {
                            continue;
                        }
                        for (int k : entries) {
                            if (k != p && k != child && segmentHitsBox(x[p], y[p], x[child], y[child],
                                    x[k] - width[k] / 2, y[k] - height / 2, x[k] + width[k] / 2, y[k] + height / 2)) {
                                return true;
                            }
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Boxes by the cell of their center; arrows by every cell of their bounds widened by the longest move, so
         * the grid still finds an arrow after its boxes moved during the tick.
         */
        private void buildGrids() {
            boxGrid.clear();
            arrowGrid.clear();
            for (int i = 0; i < items.length; i++) {
                boxGrid.computeIfAbsent(key(cell(x[i]), cell(y[i])), k -> new ArrayList<>()).add(i);
                int p = parent[i];
                if (p < 0) {
                    continue;
                }
                for (long cx = cell(Math.min(x[p], x[i]) - MAX_MOVE); cx <= cell(Math.max(x[p], x[i]) + MAX_MOVE);
                     cx++) {
                    for (long cy = cell(Math.min(y[p], y[i]) - MAX_MOVE); cy <= cell(Math.max(y[p], y[i]) + MAX_MOVE);
                         cy++) {
                        arrowGrid.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }

        private double exit(double ux, double uy, double boxWidth) {
            double tx = Math.abs(ux) < 1e-9 ? Double.POSITIVE_INFINITY : boxWidth / 2 / Math.abs(ux);
            double ty = Math.abs(uy) < 1e-9 ? Double.POSITIVE_INFINITY : height / 2 / Math.abs(uy);
            return Math.min(tx, ty);
        }

        private static long cell(double coordinate) {
            return (long) Math.floor(coordinate / CELL);
        }

        private static long key(long cx, long cy) {
            return (cx << 32) ^ (cy & 0xffffffffL);
        }
    }

    // ---------- geometry ----------

    /** True if the segment passes through the box (Liang-Barsky clipping) */
    private static boolean segmentHitsBox(double x1, double y1, double x2, double y2,
                                          double left, double top, double right, double bottom) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double t0 = 0;
        double t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x1 - left, right - x1, y1 - top, bottom - y1};
        for (int k = 0; k < 4; k++) {
            if (p[k] == 0) {
                if (q[k] < 0) {
                    return false;
                }
            } else {
                double t = q[k] / p[k];
                if (p[k] < 0) {
                    t0 = Math.max(t0, t);
                } else {
                    t1 = Math.min(t1, t);
                }
            }
        }
        return t0 < t1;
    }

    private static double pointToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lengthSquared));
        return Math.hypot(x1 + t * dx - px, y1 + t * dy - py);
    }

    /** Proper crossing; segments on one line (a straight continuation) don't count */
    private static boolean segmentsCross(double x1, double y1, double x2, double y2,
                                         double x3, double y3, double x4, double y4) {
        double tolerance = EPSILON * (Math.abs(x2 - x1) + Math.abs(y2 - y1)) * (Math.abs(x4 - x3) + Math.abs(y4 - y3));
        double d1 = orientation(x3, y3, x4, y4, x1, y1);
        double d2 = orientation(x3, y3, x4, y4, x2, y2);
        double d3 = orientation(x1, y1, x2, y2, x3, y3);
        double d4 = orientation(x1, y1, x2, y2, x4, y4);
        return ((d1 > tolerance && d2 < -tolerance) || (d1 < -tolerance && d2 > tolerance))
                && ((d3 > tolerance && d4 < -tolerance) || (d3 < -tolerance && d4 > tolerance));
    }

    private static double orientation(double ax, double ay, double bx, double by, double cx, double cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }
}
