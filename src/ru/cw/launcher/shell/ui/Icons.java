package ru.cw.launcher.shell.ui;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Векторные значки на Java2D: не нужны файлы, масштабируются без потерь. Рисуются в квадрате size×size. */
public final class Icons {

    private Icons() {
    }

    public static void draw(Graphics2D g0, String name, int x, int y, int size, Color color) {
        Graphics2D g = (Graphics2D) g0.create();
        Kit.quality(g);
        g.translate(x, y);
        double s = size / 24.0;
        g.scale(s, s);
        g.setColor(color);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "home" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(3, 11);
                p.lineTo(12, 3);
                p.lineTo(21, 11);
                g.draw(p);
                g.draw(Kit.poly(5.5, 10, 5.5, 21, 10, 21, 10, 15, 14, 15, 14, 21, 18.5, 21, 18.5, 10, 12, 4.5));
            }
            case "layers" -> {
                g.draw(Kit.poly(12, 3, 21, 8, 12, 13, 3, 8));
                Path2D a = new Path2D.Double();
                a.moveTo(3, 12.5);
                a.lineTo(12, 17.5);
                a.lineTo(21, 12.5);
                g.draw(a);
                Path2D b = new Path2D.Double();
                b.moveTo(3, 17);
                b.lineTo(12, 22);
                b.lineTo(21, 17);
                g.draw(b);
            }
            case "puzzle" -> {
                g.draw(new RoundRectangle2D.Double(3.5, 3.5, 7, 7, 2, 2));
                g.draw(new RoundRectangle2D.Double(3.5, 13.5, 7, 7, 2, 2));
                g.draw(new RoundRectangle2D.Double(13.5, 13.5, 7, 7, 2, 2));
                g.draw(Kit.poly(17, 2.5, 21.5, 7, 17, 11.5, 12.5, 7));
            }
            case "map" -> {
                g.draw(Kit.poly(3, 6, 9, 4, 15, 6, 21, 4, 21, 18, 15, 20, 9, 18, 3, 20));
                g.draw(new Line2D.Double(9, 4, 9, 18));
                g.draw(new Line2D.Double(15, 6, 15, 20));
            }
            case "palette" -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.fill(new Ellipse2D.Double(7, 8, 2.6, 2.6));
                g.fill(new Ellipse2D.Double(11, 6, 2.6, 2.6));
                g.fill(new Ellipse2D.Double(15, 9, 2.6, 2.6));
                g.fill(new Ellipse2D.Double(8, 13, 2.6, 2.6));
            }
            case "user" -> {
                g.draw(new Ellipse2D.Double(8, 3.5, 8, 8));
                g.draw(new Arc2D.Double(4, 14, 16, 14, 0, 180, Arc2D.OPEN));
            }
            case "gear" -> {
                g.draw(new Ellipse2D.Double(8, 8, 8, 8));
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(12 + Math.cos(a) * 7.2, 12 + Math.sin(a) * 7.2,
                            12 + Math.cos(a) * 10, 12 + Math.sin(a) * 10));
                }
                g.draw(new Ellipse2D.Double(5, 5, 14, 14));
            }
            case "search" -> {
                g.draw(new Ellipse2D.Double(3.5, 3.5, 12, 12));
                g.draw(new Line2D.Double(14, 14, 20.5, 20.5));
            }
            case "filter" -> g.draw(Kit.poly(3, 5, 21, 5, 14, 13, 14, 20, 10, 18, 10, 13));
            case "download" -> {
                g.draw(new Line2D.Double(12, 3.5, 12, 15));
                Path2D p = new Path2D.Double();
                p.moveTo(7, 10.5);
                p.lineTo(12, 15.5);
                p.lineTo(17, 10.5);
                g.draw(p);
                Path2D t = new Path2D.Double();
                t.moveTo(4, 16);
                t.lineTo(4, 20);
                t.lineTo(20, 20);
                t.lineTo(20, 16);
                g.draw(t);
            }
            case "heart" -> {
                Path2D p = new Path2D.Double();
                p.moveTo(12, 20);
                p.curveTo(2, 13, 4, 5, 9, 5);
                p.curveTo(11, 5, 12, 6.5, 12, 7.5);
                p.curveTo(12, 6.5, 13, 5, 15, 5);
                p.curveTo(20, 5, 22, 13, 12, 20);
                g.draw(p);
            }
            case "arrow-left" -> {
                g.draw(new Line2D.Double(20, 12, 5, 12));
                Path2D p = new Path2D.Double();
                p.moveTo(11, 6);
                p.lineTo(5, 12);
                p.lineTo(11, 18);
                g.draw(p);
            }
            case "chevron-down", "chevron-up", "chevron-left", "chevron-right" -> {
                Path2D p = new Path2D.Double();
                switch (name) {
                    case "chevron-down" -> {
                        p.moveTo(6, 9);
                        p.lineTo(12, 15);
                        p.lineTo(18, 9);
                    }
                    case "chevron-up" -> {
                        p.moveTo(6, 15);
                        p.lineTo(12, 9);
                        p.lineTo(18, 15);
                    }
                    case "chevron-left" -> {
                        p.moveTo(14.5, 6);
                        p.lineTo(8.5, 12);
                        p.lineTo(14.5, 18);
                    }
                    default -> {
                        p.moveTo(9.5, 6);
                        p.lineTo(15.5, 12);
                        p.lineTo(9.5, 18);
                    }
                }
                g.draw(p);
            }
            case "check" -> {
                g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                Path2D p = new Path2D.Double();
                p.moveTo(5.5, 12.5);
                p.lineTo(10, 17);
                p.lineTo(18.5, 7.5);
                g.draw(p);
            }
            case "pencil" -> {
                g.draw(Kit.poly(4, 20, 5, 15, 16, 4, 20, 8, 9, 19));
                g.draw(new Line2D.Double(14, 6, 18, 10));
            }
            case "folder" -> g.draw(Kit.poly(3, 6, 10, 6, 12, 8.5, 21, 8.5, 21, 19, 3, 19));
            case "trash" -> {
                g.draw(new Line2D.Double(4, 7, 20, 7));
                g.draw(Kit.poly(6, 7, 7, 21, 17, 21, 18, 7));
                g.draw(new Line2D.Double(9, 4, 15, 4));
                g.draw(new Line2D.Double(9, 4, 9, 7));
                g.draw(new Line2D.Double(15, 4, 15, 7));
            }
            case "play" -> g.fill(Kit.poly(7, 4, 20, 12, 7, 20));
            case "plus" -> {
                g.draw(new Line2D.Double(12, 5, 12, 19));
                g.draw(new Line2D.Double(5, 12, 19, 12));
            }
            case "cube" -> {
                g.draw(Kit.poly(12, 2.5, 21, 7.5, 21, 16.5, 12, 21.5, 3, 16.5, 3, 7.5));
                g.draw(new Line2D.Double(3, 7.5, 12, 12.5));
                g.draw(new Line2D.Double(21, 7.5, 12, 12.5));
                g.draw(new Line2D.Double(12, 12.5, 12, 21.5));
            }
            case "radio" -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.fill(new Ellipse2D.Double(8, 8, 8, 8));
            }
            case "hammer" -> {
                // Молоток под 45°: залитая рукоять и боёк с гвоздодёром.
                Graphics2D h = (Graphics2D) g.create();
                h.rotate(Math.toRadians(-45), 12, 12);
                h.fill(new RoundRectangle2D.Double(10.7, 9, 2.6, 13, 2.6, 2.6));
                Path2D head = new Path2D.Double();
                head.moveTo(4.5, 4.5);
                head.lineTo(16, 4.5);
                head.curveTo(18.5, 4.5, 20, 5.5, 20.5, 7.5);
                head.lineTo(16, 9.5);
                head.lineTo(4.5, 9.5);
                head.closePath();
                h.fill(head);
                h.dispose();
            }
            case "globe" -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new Ellipse2D.Double(8, 3, 8, 18));
                g.draw(new Line2D.Double(3.5, 12, 20.5, 12));
                g.draw(new Line2D.Double(5, 7.5, 19, 7.5));
                g.draw(new Line2D.Double(5, 16.5, 19, 16.5));
            }
            case "key" -> {
                g.draw(new Ellipse2D.Double(3, 9, 8, 8));
                g.draw(new Line2D.Double(11, 13, 21, 13));
                g.draw(new Line2D.Double(17, 13, 17, 16.5));
                g.draw(new Line2D.Double(20, 13, 20, 15.5));
            }
            case "cut" -> {
                g.draw(new Ellipse2D.Double(3, 3, 18, 18));
                g.draw(new Line2D.Double(8, 8, 16, 16));
                g.draw(new Line2D.Double(16, 8, 8, 16));
            }
            default -> g.draw(new Ellipse2D.Double(5, 5, 14, 14));
        }
        g.dispose();
    }
}
