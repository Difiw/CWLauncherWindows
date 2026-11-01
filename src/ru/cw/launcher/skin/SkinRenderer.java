package ru.cw.launcher.skin;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Объёмная модель скина Minecraft без 3D-библиотек. Каждая часть тела — коробка из шести граней;
 * грань рисуется аффинным преобразованием своего участка текстуры (ортографическая проекция
 * делает его точным). Модель вращается по двум осям, умеет находить пиксель текстуры под курсором
 * для редактора. Поддерживает 64×64, старые 64×32 и тонкие руки.
 */
public final class SkinRenderer {

    /** Какие слои рисовать: основу, внешний слой (шапка, куртка, рукава, штанины). */
    public static final int BASE = 1;
    public static final int OUTER = 2;

    /** Грань после проекции: участок текстуры, преобразование на экран, глубина и слой. */
    public record Face(int u, int v, int tw, int th, AffineTransform toScreen, double depth, boolean outer) {
    }

    private SkinRenderer() {
    }

    /** Простой вид спереди (для плиток и старого кода). */
    public static void paint(Graphics2D g, BufferedImage skin, boolean slim, double angle, int cx, int top, int height) {
        paint(g, skin, slim, angle, 0, cx, top + height / 2, height, BASE | OUTER);
    }

    /**
     * Рисует фигуру с поворотом yaw (вокруг вертикали) и pitch (наклон), центр фигуры в (cx, cy),
     * высота height пикселей экрана. Возвращает грани в порядке отрисовки — для выбора пикселя.
     */
    public static List<Face> paint(Graphics2D g, BufferedImage skin, boolean slim, double yaw, double pitch,
                                   int cx, int cy, int height, int layers) {
        List<Face> faces = faces(skin, slim, yaw, pitch, cx, cy, height, layers);
        if (skin == null) return faces;
        int k = Math.max(1, skin.getWidth() / 64);
        Object hint = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        for (Face f : faces) {
            int sx = f.u() * k;
            int sy = f.v() * k;
            int sw = f.tw() * k;
            int sh = f.th() * k;
            if (sx + sw > skin.getWidth() || sy + sh > skin.getHeight()) continue;
            BufferedImage part = skin.getSubimage(sx, sy, sw, sh);
            AffineTransform t = new AffineTransform(f.toScreen());
            t.scale(1.0 / k, 1.0 / k);
            // Крошечное расширение закрывает щели между гранями.
            t.translate(-0.015 * k, -0.015 * k);
            t.scale(1 + 0.03 / f.tw(), 1 + 0.03 / f.th());
            g.drawImage(part, t, null);
        }
        if (hint != null) g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
        if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        return faces;
    }

    /**
     * Пиксель текстуры (в координатах 64×64) под точкой экрана: ближайшая к зрителю грань,
     * у которой в этом месте не прозрачный пиксель (для внешнего слоя). null — мимо модели.
     */
    public static Point pick(List<Face> faces, BufferedImage skin, int x, int y, int layers, boolean requireOpaqueOuter) {
        int k = skin == null ? 1 : Math.max(1, skin.getWidth() / 64);
        for (int i = faces.size() - 1; i >= 0; i--) {
            Face f = faces.get(i);
            if (f.outer() && (layers & OUTER) == 0) continue;
            if (!f.outer() && (layers & BASE) == 0) continue;
            Point2D local;
            try {
                local = f.toScreen().inverseTransform(new Point2D.Double(x, y), null);
            } catch (NoninvertibleTransformException e) {
                continue;
            }
            if (local.getX() < 0 || local.getY() < 0 || local.getX() >= f.tw() || local.getY() >= f.th()) continue;
            int px = f.u() + (int) Math.floor(local.getX());
            int py = f.v() + (int) Math.floor(local.getY());
            if (f.outer() && requireOpaqueOuter && skin != null) {
                int argb = skin.getRGB(Math.min(skin.getWidth() - 1, px * k), Math.min(skin.getHeight() - 1, py * k));
                if ((argb >>> 24) == 0) continue;
            }
            return new Point(px, py);
        }
        return null;
    }

    /** Грани модели, отсортированные от дальних к ближним. */
    public static List<Face> faces(BufferedImage skin, boolean slim, double yaw, double pitch,
                                   int cx, int cy, int height, int layers) {
        boolean legacy = skin != null && skin.getHeight() * 2 <= skin.getWidth() + 1;
        double unit = height / 32.0;
        View view = new View(yaw, pitch, cx, cy, unit);
        int arm = slim ? 3 : 4;
        double armX = 4 + arm / 2.0;
        List<Face> out = new ArrayList<>();
        boolean base = (layers & BASE) != 0;
        boolean outer = (layers & OUTER) != 0;
        if (base) box(out, view, 0, 0, 8, 8, 8, 0, 0, 0, 0, false);
        if (outer) box(out, view, 32, 0, 8, 8, 8, 0, 0, 0, 0.5, true);
        if (base) box(out, view, 16, 16, 8, 12, 4, 0, 8, 0, 0, false);
        if (outer && !legacy) box(out, view, 16, 32, 8, 12, 4, 0, 8, 0, 0.25, true);
        if (base) box(out, view, 40, 16, arm, 12, 4, -armX, 8, 0, 0, false);
        if (outer && !legacy) box(out, view, 40, 32, arm, 12, 4, -armX, 8, 0, 0.25, true);
        if (base) box(out, view, legacy ? 40 : 32, legacy ? 16 : 48, arm, 12, 4, armX, 8, 0, 0, false);
        if (outer && !legacy) box(out, view, 48, 48, arm, 12, 4, armX, 8, 0, 0.25, true);
        if (base) box(out, view, 0, 16, 4, 12, 4, -2, 20, 0, 0, false);
        if (outer && !legacy) box(out, view, 0, 32, 4, 12, 4, -2, 20, 0, 0.25, true);
        if (base) box(out, view, legacy ? 0 : 16, legacy ? 16 : 48, 4, 12, 4, 2, 20, 0, 0, false);
        if (outer && !legacy) box(out, view, 0, 48, 4, 12, 4, 2, 20, 0, 0.25, true);
        out.sort((a, b) -> Double.compare(a.depth(), b.depth()));
        return out;
    }

    /** Поворот и проекция: модель в пикселях скина, центр — середина фигуры (y = 16). */
    private record View(double yaw, double pitch, int cx, int cy, double unit) {
        double[] project(double x, double y, double z) {
            double cy0 = Math.cos(yaw);
            double sy0 = Math.sin(yaw);
            double cp = Math.cos(pitch);
            double sp = Math.sin(pitch);
            double yy = y - 16;
            double x1 = x * cy0 + z * sy0;
            double z1 = -x * sy0 + z * cy0;
            double y2 = yy * cp - z1 * sp;
            double z2 = yy * sp + z1 * cp;
            return new double[]{cx + x1 * unit, cy + y2 * unit, z2};
        }
    }

    /**
     * Коробка w×h×d с развёрткой Minecraft от (u, v): верхний ряд «крышка, дно»,
     * нижний ряд «правая, перед, левая, зад». (x, y, z) — центр по x/z и верх по y.
     */
    private static void box(List<Face> out, View view, int u, int v, int w, int h, int d,
                            double x, double y, double z, double inflate, boolean outer) {
        double l = x - w / 2.0 - inflate;
        double r = x + w / 2.0 + inflate;
        double t = y - inflate;
        double b = y + h + inflate;
        double f = z + d / 2.0 + inflate;
        double k = z - d / 2.0 - inflate;
        double lift = outer ? 0.02 : 0;
        // Перед, зад, правая (−x), левая (+x), крышка, дно. Углы: левый верх, правый верх, левый низ текстуры.
        face(out, view, u + d, v + d, w, h, new double[]{l, t, f}, new double[]{r, t, f}, new double[]{l, b, f}, outer, lift);
        face(out, view, u + d + w + d, v + d, w, h, new double[]{r, t, k}, new double[]{l, t, k}, new double[]{r, b, k}, outer, lift);
        face(out, view, u, v + d, d, h, new double[]{l, t, k}, new double[]{l, t, f}, new double[]{l, b, k}, outer, lift);
        face(out, view, u + d + w, v + d, d, h, new double[]{r, t, f}, new double[]{r, t, k}, new double[]{r, b, f}, outer, lift);
        face(out, view, u + d, v, w, d, new double[]{l, t, k}, new double[]{r, t, k}, new double[]{l, t, f}, outer, lift);
        face(out, view, u + d + w, v, w, d, new double[]{l, b, f}, new double[]{r, b, f}, new double[]{l, b, k}, outer, lift);
    }

    private static void face(List<Face> out, View view, int u, int v, int tw, int th,
                             double[] a, double[] b, double[] c, boolean outer, double lift) {
        double[] p0 = view.project(a[0], a[1], a[2]);
        double[] p1 = view.project(b[0], b[1], b[2]);
        double[] p3 = view.project(c[0], c[1], c[2]);
        double ux = p1[0] - p0[0];
        double uy = p1[1] - p0[1];
        double vx = p3[0] - p0[0];
        double vy = p3[1] - p0[1];
        // Грань смотрит от зрителя — не рисуем.
        if (ux * vy - uy * vx <= 0.0001) return;
        AffineTransform toScreen = new AffineTransform(ux / tw, uy / tw, vx / th, vy / th, p0[0], p0[1]);
        double depth = (p1[2] + p3[2]) / 2 + lift;
        out.add(new Face(u, v, tw, th, toScreen, depth, outer));
    }
}
