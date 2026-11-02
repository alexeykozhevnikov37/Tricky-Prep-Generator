package com.ser_databus.tpg.graph;

import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Transform;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Objects;

/**
 * Renders simple SVG files (a viewBox plus filled {@code <path>} elements) into JavaFX images
 * using JavaFX's built-in {@link SVGPath}, so no third-party SVG library is needed.
 * Must be called on the JavaFX application thread.
 */
public final class SvgImageLoader {
    private SvgImageLoader() {
    }

    /**
     * @param resourcePath classpath resource, e.g. "/images/white_king.svg"
     * @param size         width/height in pixels of the resulting (square) image
     */
    public static Image load(String resourcePath, double size) {
        try (InputStream in = Objects.requireNonNull(
                SvgImageLoader.class.getResourceAsStream(resourcePath),
                "SVG resource not found: " + resourcePath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            Document doc = factory.newDocumentBuilder().parse(in);
            Element svg = doc.getDocumentElement();

            double[] viewBox = parseViewBox(svg);
            double minX = viewBox[0], minY = viewBox[1], width = viewBox[2], height = viewBox[3];

            Group group = new Group();
            // invisible rectangle covering the viewBox, so the snapshot keeps the original framing
            Rectangle frame = new Rectangle(minX, minY, width, height);
            frame.setFill(Color.TRANSPARENT);
            group.getChildren().add(frame);

            NodeList paths = svg.getElementsByTagName("path");
            for (int i = 0; i < paths.getLength(); i++) {
                Element p = (Element) paths.item(i);
                SVGPath path = new SVGPath();
                path.setContent(p.getAttribute("d"));
                String fill = p.getAttribute("fill");
                path.setFill(fill.isEmpty() ? Color.BLACK
                        : "none".equals(fill) ? Color.TRANSPARENT : Color.web(fill));
                group.getChildren().add(path);
            }

            double scale = size / Math.max(width, height);
            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            params.setTransform(Transform.scale(scale, scale));
            params.setViewport(new Rectangle2D(minX * scale, minY * scale, width * scale, height * scale));
            return group.snapshot(params, null);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load SVG " + resourcePath, e);
        }
    }

    private static double[] parseViewBox(Element svg) {
        String vb = svg.getAttribute("viewBox");
        if (!vb.isBlank()) {
            String[] parts = vb.trim().split("[\\s,]+");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]), Double.parseDouble(parts[3])};
        }
        return new double[]{0, 0,
                Double.parseDouble(svg.getAttribute("width").replaceAll("[^0-9.]", "")),
                Double.parseDouble(svg.getAttribute("height").replaceAll("[^0-9.]", ""))};
    }
}
