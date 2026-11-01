package ru.cw.launcher.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.RoundRectangle2D;

/**
 * Единая современная форма кнопок и карточек.
 */
public final class CutPaint {

    private CutPaint() {
    }

    public static int cutFor(int w, int h) {
        return Math.max(8, Math.min(16, Math.min(w, h) / 4));
    }

    public static Shape shape(int w, int h, int cut) {
        int radius = Math.max(12, Math.min(22, Math.min(w, h) / 2));
        return new RoundRectangle2D.Float(0.5f, 0.5f,
                Math.max(1, w - 1), Math.max(1, h - 1), radius, radius);
    }

    public static void button(Graphics2D g2, int w, int h, Color fill, Color line) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Shape s = shape(Math.max(1, w - 1), Math.max(1, h - 1), cutFor(w, h));
        g2.setColor(fill);
        g2.fill(s);
        g2.setColor(line);
        g2.setStroke(new BasicStroke(1f));
        g2.draw(s);
    }
}
