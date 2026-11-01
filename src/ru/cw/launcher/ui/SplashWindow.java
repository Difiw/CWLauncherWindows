package ru.cw.launcher.ui;

import ru.cw.launcher.util.Paths;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Окно подготовки: стеклянная карточка с ночной сценой, логотипом, строкой статуса
 * и полосой с процентами. Углы прозрачные, поэтому по краям нет чёрного прямоугольника.
 */
public class SplashWindow extends JFrame {

    private static final int W = 820;
    private static final int H = 430;
    private static final int ARC = 46;

    private final Card card = new Card();
    private String stage = "Загрузка данных о лаунчере…";
    private int shown;
    private int target;
    private float phase;
    private Timer motion;

    public SplashWindow() {
        super("CWLauncher");
        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setResizable(false);
        setBackground(new Color(0, 0, 0, 0));
        try {
            setIconImage(Icons.appIcon());
        } catch (Exception ignored) {
        }
        card.setOpaque(false);
        setContentPane(card);
        setSize(W, H);
        setLocationRelativeTo(null);
        motion = new Timer(32, e -> {
            if (!isShowing()) return;
            boolean moving = false;
            if (UiLook.anim()) {
                phase += 0.9f;
                if (phase > 1000f) phase -= 1000f;
                moving = true;
            }
            if (shown != target) {
                int step = Math.max(1, Math.abs(target - shown) / 5);
                shown += shown < target ? step : -step;
                if (Math.abs(target - shown) <= step) shown = target;
                moving = true;
            }
            if (moving) card.repaint();
        });
        motion.setCoalesce(true);
        motion.start();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                System.exit(0);
            }
        });
    }

    @Override
    public void dispose() {
        if (motion != null) motion.stop();
        super.dispose();
    }

    /** Реальный шаг локальной проверки, 0–100. Полоса доезжает, а не прыгает. */
    public void step(int value, String text) {
        Runnable r = () -> {
            target = Math.max(0, Math.min(100, value));
            stage = text == null || text.isBlank() ? " " : text;
            if (!UiLook.anim()) shown = target;
            card.repaint();
        };
        if (SwingUtilities.isEventDispatchThread()) r.run();
        else SwingUtilities.invokeLater(r);
    }

    /** Для проверки отрисовки без показа окна. */
    void paintTo(Graphics2D g, int percent, String text) {
        shown = percent;
        target = percent;
        stage = text;
        card.setSize(W, H);
        card.paint(g);
    }

    private final class Card extends JPanel {
        private BufferedImage scene;
        private boolean sceneTried;

        Card() {
            super(null);
        }

        /** Сцена карточки: основной фон, уже подогнанный под размер. Читается один раз. */
        private BufferedImage scene() {
            if (sceneTried) return scene;
            sceneTried = true;
            try {
                Path p = Paths.mainBackground();
                if (!Files.isRegularFile(p)) p = Path.of("assets", "background", "main.png");
                BufferedImage src = Files.isRegularFile(p) ? ImageIO.read(p.toFile()) : null;
                if (src == null) return null;
                BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = out.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = Math.max((double) W / src.getWidth(), (double) H / src.getHeight());
                int dw = (int) Math.ceil(src.getWidth() * scale);
                int dh = (int) Math.ceil(src.getHeight() * scale);
                g.drawImage(src, (W - dw) / 2, 0, dw, dh, null);
                g.dispose();
                scene = out;
            } catch (Exception ignored) {
                scene = null;
            }
            return scene;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            int w = getWidth();
            int h = getHeight();
            Shape cardShape = new RoundRectangle2D.Float(2, 2, w - 5, h - 5, ARC, ARC);

            Shape oldClip = g.getClip();
            g.clip(cardShape);
            g.setPaint(new GradientPaint(0, 0, new Color(0x13233A), 0, h, new Color(0x0A1424)));
            g.fillRect(0, 0, w, h);
            BufferedImage sc = scene();
            if (sc != null) {
                g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
                g.drawImage(sc, 0, 0, null);
                g.setComposite(AlphaComposite.SrcOver);
            }
            // Затемнение к краям: по центру текст читается, сцена остаётся видна по бокам.
            g.setPaint(new GradientPaint(0, 0, new Color(8, 16, 30, 120), 0, h, new Color(8, 16, 30, 165)));
            g.fillRect(0, 0, w, h);
            g.setPaint(new RadialGradientPaint(w / 2f, h * 0.45f, w * 0.55f, new float[]{0f, 1f},
                    new Color[]{new Color(10, 24, 44, 160), new Color(10, 24, 44, 0)}));
            g.fillRect(0, 0, w, h);
            g.setClip(oldClip);

            g.setStroke(new BasicStroke(2.2f));
            g.setPaint(new GradientPaint(0, 0, new Color(120, 190, 255, 200), 0, h, new Color(60, 120, 200, 140)));
            g.draw(cardShape);

            drawLogo(g, w);
            drawStage(g, w);
            drawBar(g, w);
            g.dispose();
        }

        private void drawLogo(Graphics2D g, int w) {
            String name = "CWLauncher";
            Font big = new Font(Theme.FAMILY, Font.BOLD, 56);
            g.setFont(big);
            FontMetrics fm = g.getFontMetrics();
            BufferedImage cube = LogoArt.cube();
            int cubeH = 108;
            int cubeW = cube == null ? cubeH : (int) Math.round(cubeH * cube.getWidth() / (double) cube.getHeight());
            int gap = 22;
            int total = cubeW + gap + fm.stringWidth(name);
            int x = (w - total) / 2;
            int top = 52;
            if (cube != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.drawImage(cube, x, top, cubeW, cubeH, null);
            } else {
                g.drawImage(Icons.appIcon(), x, top, cubeH, cubeH, null);
            }
            int tx = x + cubeW + gap;
            int base = top + 66;
            g.setPaint(new GradientPaint(tx, base - 50, new Color(0xFFFFFF), tx, base, new Color(0xB9D2EE)));
            g.drawString(name, tx, base);
            // «COMMON WORLD» с тонкими линиями по сторонам.
            Font small = new Font(Theme.FAMILY, Font.PLAIN, 15);
            g.setFont(small);
            String sub = "C O M M O N   W O R L D";
            FontMetrics sf = g.getFontMetrics();
            int sw = sf.stringWidth(sub);
            int sx = tx + (fm.stringWidth(name) - sw) / 2;
            int sy = base + 28;
            g.setColor(new Color(0xA9BCD6));
            g.drawString(sub, sx, sy);
            g.setColor(new Color(130, 160, 200, 140));
            g.drawLine(tx, sy - 5, sx - 14, sy - 5);
            g.drawLine(sx + sw + 14, sy - 5, tx + fm.stringWidth(name), sy - 5);
        }

        private void drawStage(Graphics2D g, int w) {
            Font f = new Font(Theme.FAMILY, Font.PLAIN, 26);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            int icon = 44;
            int gap = 22;
            int tw = fm.stringWidth(stage);
            int total = icon + gap + tw;
            int x = Math.max(36, (w - total) / 2);
            int y = 236;
            SettingsKit.icon(SettingsKit.Glyph.LAYERS, icon, new Color(0xC6D6EA)).paintIcon(this, g, x, y - icon / 2 - 4);
            g.setColor(new Color(0xE3ECF8));
            g.drawString(SettingsKit.ellipsize(stage, fm, w - 2 * 36 - icon - gap), x + icon + gap, y + fm.getAscent() / 2 - 4);
        }

        private void drawBar(Graphics2D g, int w) {
            int left = 38;
            int boxW = 104;
            int boxH = 56;
            int right = w - 38;
            int trackW = right - left - boxW - 22;
            int trackH = 38;
            int y = 292;
            RoundRectangle2D.Float track = new RoundRectangle2D.Float(left, y, trackW, trackH, trackH, trackH);
            g.setColor(new Color(8, 18, 32, 220));
            g.fill(track);
            g.setColor(new Color(150, 175, 210, 120));
            g.setStroke(new BasicStroke(2f));
            g.draw(track);

            int fill = Math.max(trackH - 8, (int) ((trackW - 8) * (shown / 100.0)));
            if (shown <= 0) fill = trackH - 8;
            RoundRectangle2D.Float bar = new RoundRectangle2D.Float(left + 4, y + 4, fill, trackH - 8, trackH - 8, trackH - 8);
            // Свечение под полосой.
            for (int i = 3; i >= 1; i--) {
                g.setColor(new Color(40, 140, 255, 14 * (4 - i)));
                g.fill(new RoundRectangle2D.Float(bar.x - i * 2, bar.y - i * 2, bar.width + i * 4, bar.height + i * 4,
                        bar.height + i * 4, bar.height + i * 4));
            }
            g.setPaint(new GradientPaint(0, bar.y, new Color(0x6FB4FF), 0, bar.y + bar.height, new Color(0x2F7BEA)));
            g.fill(bar);
            // Диагональные полоски, бегут вправо.
            Shape old = g.getClip();
            g.clip(bar);
            g.setColor(new Color(255, 255, 255, 52));
            int period = 34;
            int off = (int) (phase % period);
            for (int sx = (int) bar.x - period * 2 + off; sx < bar.x + bar.width + period; sx += period) {
                java.awt.geom.Path2D.Float stripe = new java.awt.geom.Path2D.Float();
                stripe.moveTo(sx, bar.y + bar.height);
                stripe.lineTo(sx + 16, bar.y + bar.height);
                stripe.lineTo(sx + 16 + bar.height, bar.y);
                stripe.lineTo(sx + bar.height, bar.y);
                stripe.closePath();
                g.fill(stripe);
            }
            g.setPaint(new GradientPaint(0, bar.y, new Color(255, 255, 255, 80), 0, bar.y + bar.height / 2, new Color(255, 255, 255, 0)));
            g.fillRect((int) bar.x, (int) bar.y, (int) bar.width, (int) bar.height / 2);
            g.setClip(old);

            RoundRectangle2D.Float box = new RoundRectangle2D.Float(right - boxW, y + trackH / 2f - boxH / 2f, boxW, boxH, 20, 20);
            g.setColor(new Color(10, 22, 40, 235));
            g.fill(box);
            g.setColor(new Color(150, 175, 210, 130));
            g.draw(box);
            g.setFont(new Font(Theme.FAMILY, Font.BOLD, 30));
            FontMetrics fm = g.getFontMetrics();
            String pct = shown + "%";
            g.setColor(Color.WHITE);
            g.drawString(pct, box.x + (box.width - fm.stringWidth(pct)) / 2f,
                    box.y + (box.height + fm.getAscent() - fm.getDescent()) / 2f);
        }
    }
}
