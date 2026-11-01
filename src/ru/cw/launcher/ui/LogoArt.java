package ru.cw.launcher.ui;

import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Логотип Common World и фон боковой панели. Картинки лежат в assets/ui рядом с лаунчером
 * и читаются один раз за запуск: пересборка окна (язык, масштаб) ничего не пересчитывает.
 */
final class LogoArt {

    private static BufferedImage logo;
    private static BufferedImage cube;
    private static BufferedImage sidebar;
    private static boolean logoLoaded;
    private static boolean sidebarLoaded;

    private LogoArt() {
    }

    /** Куб с буквами и надпись COMMON WORLD, фон прозрачный. null — файла с макетом нет. */
    static synchronized BufferedImage logo() {
        if (!logoLoaded) {
            logoLoaded = true;
            logo = cutLogo();
            cube = logo == null ? null : trim(logo.getSubimage(0, 0, logo.getWidth(), (int) (logo.getHeight() * 0.6)));
        }
        return logo;
    }

    /** Только куб, без надписи: для экрана загрузки. */
    static synchronized BufferedImage cube() {
        logo();
        return cube;
    }

    /** Фон боковой панели (ночной пейзаж). Используется как есть, только масштабируется. */
    static synchronized BufferedImage sidebar() {
        if (!sidebarLoaded) {
            sidebarLoaded = true;
            sidebar = read("sidebar-bg.png");
        }
        return sidebar;
    }

    private static Path find(String name) {
        Path rel = Path.of("assets", "ui", name);
        if (Files.isRegularFile(rel)) return rel;
        Path installed = Paths.installRoot().resolve("assets").resolve("ui").resolve(name);
        return Files.isRegularFile(installed) ? installed : null;
    }

    private static BufferedImage read(String name) {
        try {
            Path p = find(name);
            return p == null ? null : ImageIO.read(p.toFile());
        } catch (Exception e) {
            Log.warn("Картинка " + name + ": " + Log.reason(e));
            return null;
        }
    }

    /** Логотип вырезается из макета главного экрана: другого файла с ним нет. */
    private static BufferedImage cutLogo() {
        BufferedImage img = read("main-reference.png");
        if (img == null) return null;
        try {
            double sx = img.getWidth() / 1586.0;
            double sy = img.getHeight() / 991.0;
            int x = (int) (58 * sx);
            int y = (int) (26 * sy);
            int w = (int) (196 * sx);
            int h = (int) (206 * sy);
            return cutOut(img.getSubimage(x, y, Math.min(w, img.getWidth() - x), Math.min(h, img.getHeight() - y)));
        } catch (Exception e) {
            Log.warn("Логотип: " + Log.reason(e));
            return null;
        }
    }

    /** Фон вырезки делается прозрачным: логотип не должен быть прямоугольником поверх картинки. */
    private static BufferedImage cutOut(BufferedImage src) {
        int bgc = src.getRGB(2, 2);
        int br = (bgc >> 16) & 255, bgr = (bgc >> 8) & 255, bb = bgc & 255;
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y);
                int r = (p >> 16) & 255, gg = (p >> 8) & 255, b = p & 255;
                int d = Math.max(Math.abs(r - br), Math.max(Math.abs(gg - bgr), Math.abs(b - bb)));
                int a = Math.max(0, Math.min(255, (d - 8) * 9));
                if (a == 0) continue;
                // Полупрозрачный край: убираем примесь фона из цвета.
                float t = a / 255f;
                int nr = clamp(Math.round((r - br * (1 - t)) / t));
                int ng = clamp(Math.round((gg - bgr * (1 - t)) / t));
                int nb = clamp(Math.round((b - bb * (1 - t)) / t));
                out.setRGB(x, y, (a << 24) | (nr << 16) | (ng << 8) | nb);
            }
        }
        return out;
    }

    /** Обрезает прозрачные поля. */
    private static BufferedImage trim(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) < 40) continue;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return img;
        return img.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
