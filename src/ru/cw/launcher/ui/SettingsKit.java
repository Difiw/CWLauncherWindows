package ru.cw.launcher.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Виджеты экрана настроек по макету: тёмные карточки, бирюзовые переключатели,
 * выпадающие списки, ползунки и векторные иконки. Всё рисуется вручную, без Nimbus.
 */
public final class SettingsKit {

    public static final Color PAGE = new Color(0x0B131D);
    public static final Color NAV = new Color(0x0F1924);
    public static final Color CARD = new Color(0x111B27);
    public static final Color CARD_LINE = new Color(0x1E2B3B);
    public static final Color FIELD = new Color(0x0D1520);
    public static final Color FIELD_LINE = new Color(0x293A4F);
    public static final Color FIELD_HOVER = new Color(0x3A5470);
    public static final Color TEXT = new Color(0xF1F5FA);
    public static final Color MUTED = new Color(0x93A3B6);
    public static final Color DIM = new Color(0x5D6E82);
    public static Color ACCENT = new Color(0x18A9E6);
    public static Color ACCENT_2 = new Color(0x1590CC);
    public static Color ACTIVE = new Color(0x135E86);
    public static Color ACTIVE_2 = new Color(0x0F4C6E);
    public static final Color GREEN = new Color(0x41D95C);
    public static final Color RED = new Color(0xFF5E68);
    public static final Color AMBER = new Color(0xFFB84A);

    private SettingsKit() {
    }

    /** Итоговый масштаб: выбор пользователя, базовый размер под окно 1440 px и компактный режим. */
    private static float scale = 0.88f;
    /** Скругление карточек, уже с масштабом. */
    private static int radius = 14;
    /** Непрозрачность карточек 0–255. */
    private static int cardAlpha = 255;

    /** Применяет масштаб и тему из настроек. Интерфейс после этого нужно построить заново. */
    public static void applyTheme(ru.cw.launcher.core.Settings cfg) {
        boolean compact = "compact".equals(cfg.uiStyle);
        scale = 0.88f * cfg.uiScale / 100f * (compact ? 0.9f : 1f);
        preview(cfg);
        installTooltips();
    }

    /** Фон под интерфейсом: включён ли, затемнение 0–100, размытие. */
    private static boolean backdrop;
    private static int backdropDim = 40;
    private static boolean backdropBlur = true;
    /** Насколько темнеет левая часть баннера главной под текстом, 0–100. */
    private static int heroShade = 75;

    /**
     * Цвет, скругление, прозрачность и фон без пересборки интерфейса. Экран настроек
     * вызывает это при каждом изменении, чтобы оформление было видно до сохранения.
     */
    public static void preview(ru.cw.launcher.core.Settings cfg) {
        radius = Math.round(cfg.cornerRadius * scale / 0.88f);
        backdrop = cfg.shellBackground;
        cardAlpha = backdrop ? Math.round(cfg.panelOpacity * 2.55f) : 255;
        backdropDim = Math.max(0, Math.min(100, cfg.backgroundDim * 100 / 80));
        backdropBlur = cfg.uiBlur;
        heroShade = cfg.heroShade;
        Color a;
        try {
            a = Color.decode(cfg.accentColor);
        } catch (Exception e) {
            a = new Color(0x18A9E6);
        }
        ACCENT = a;
        ACCENT_2 = Motion.mix(a, Color.BLACK, 0.16f);
        ACTIVE = Motion.mix(a, new Color(0x0B131D), 0.52f);
        ACTIVE_2 = Motion.mix(a, new Color(0x0B131D), 0.64f);
    }

    public static boolean backdrop() {
        return backdrop;
    }

    public static int backdropDim() {
        return backdropDim;
    }

    public static boolean backdropBlur() {
        return backdropBlur;
    }

    public static int heroShade() {
        return heroShade;
    }

    /** Подсказки в стиле лаунчера: тёмная плашка вместо жёлтой Nimbus с белым текстом. */
    private static void installTooltips() {
        Color bg = new Color(0x18263A);
        UIManager.put("info", bg);
        UIManager.put("ToolTip.background", bg);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("ToolTip.font", font(Font.PLAIN, 14f));
        UIManager.put("ToolTip.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(FIELD_LINE), BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        UIManager.put("ToolTip.contentMargins", new Insets(6, 10, 6, 10));
        UIManager.put("ToolTip[Enabled].backgroundPainter", (javax.swing.Painter<JComponent>) (g, c, w, h) -> {
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            g.setColor(FIELD_LINE);
            g.drawRect(0, 0, w - 1, h - 1);
        });
        ToolTipManager.sharedInstance().setInitialDelay(450);
    }

    /** Тонкая полоса прокрутки в цвет темы, без стрелок. */
    public static final class ScrollBarUI extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zero();
        }

        private static JButton zero() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = Math.max(4, r.width - 4);
            Color thumb = isDragging || isThumbRollover() ? ACCENT : Motion.mix(ACCENT, new Color(0x2A3A4C), 0.55f);
            g2.setColor(thumb);
            g2.fill(new RoundRectangle2D.Float(r.x + (r.width - w) / 2f, r.y + 2, w, r.height - 4, w, w));
            g2.dispose();
        }
    }

    private static Cursor grabOpen;
    private static Cursor grabClosed;

    /** Курсор «рука»: раскрытая ладонь над моделью и сжатая, пока её крутят. */
    public static Cursor grab(boolean closed) {
        if (closed && grabClosed != null) return grabClosed;
        if (!closed && grabOpen != null) return grabOpen;
        Cursor c;
        try {
            Dimension best = Toolkit.getDefaultToolkit().getBestCursorSize(32, 32);
            int n = Math.max(16, best.width);
            BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            smooth(g);
            g.scale(n / 32.0, n / 32.0);
            java.awt.geom.Area hand = new java.awt.geom.Area(new RoundRectangle2D.Float(8, 14, 17, 14, 7, 7));
            if (closed) {
                for (int i = 0; i < 4; i++) hand.add(new java.awt.geom.Area(new RoundRectangle2D.Float(8 + i * 4.2f, 11, 4.4f, 7, 4, 4)));
            } else {
                float[] len = {9, 11, 10.5f, 8.5f};
                for (int i = 0; i < 4; i++) {
                    hand.add(new java.awt.geom.Area(new RoundRectangle2D.Float(8.4f + i * 4.2f, 15 - len[i], 3.6f, len[i] + 3, 3.6f, 3.6f)));
                }
                hand.add(new java.awt.geom.Area(new RoundRectangle2D.Float(3, 17, 9, 4, 4, 4)));
            }
            g.setColor(Color.WHITE);
            g.fill(hand);
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke(1.4f));
            g.draw(hand);
            g.dispose();
            c = Toolkit.getDefaultToolkit().createCustomCursor(img, new Point(n / 2, n / 2), closed ? "cw-grabbing" : "cw-grab");
        } catch (Exception e) {
            c = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR);
        }
        if (closed) grabClosed = c;
        else grabOpen = c;
        return c;
    }

    /** Пункт всплывающего меню: текст, иконка, действие. */
    public record MenuItem(String text, Glyph glyph, Runnable action, boolean danger) {
        public MenuItem(String text, Glyph glyph, Runnable action) {
            this(text, glyph, action, false);
        }
    }

    /**
     * Всплывающее меню в стиле лаунчера. Пункты лежат на одной сплошной панели,
     * поэтому у окна меню не остаётся серых участков стандартной темы.
     */
    public static JPopupMenu menu(int width, MenuItem... items) {
        JPopupMenu m = new JPopupMenu();
        m.setLayout(new BorderLayout());
        m.setBorder(BorderFactory.createLineBorder(FIELD_LINE));
        m.setBackground(FIELD);
        m.setOpaque(true);
        JPanel list = new JPanel(new GridLayout(items.length, 1));
        list.setBackground(FIELD);
        list.setOpaque(true);
        int rowH = s(44);
        for (MenuItem it : items) {
            JButton b = new JButton(it.text()) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    smooth(g2);
                    g2.setColor(getModel().isRollover() ? ACTIVE : FIELD);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    int is = s(20);
                    Color fg = it.danger() ? new Color(0xFF8A93) : TEXT;
                    if (it.glyph() != null) icon(it.glyph(), is, fg).paintIcon(this, g2, s(16), (getHeight() - is) / 2);
                    g2.setFont(font(Font.PLAIN, 15.5f));
                    g2.setColor(fg);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(ellipsize(getText(), fm, getWidth() - s(60)), s(50),
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            };
            b.setRolloverEnabled(true);
            b.setContentAreaFilled(false);
            b.setBorderPainted(false);
            b.setFocusPainted(false);
            b.setOpaque(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.addActionListener(e -> {
                m.setVisible(false);
                SwingUtilities.invokeLater(it.action());
            });
            list.add(b);
        }
        list.setPreferredSize(new Dimension(width, rowH * items.length));
        m.add(list, BorderLayout.CENTER);
        m.pack();
        return m;
    }

    public static void styleScroll(JScrollPane scroll) {
        scroll.getVerticalScrollBar().setUI(new ScrollBarUI());
        scroll.getVerticalScrollBar().setOpaque(false);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(s(10), s(10)));
    }

    public static int s(int v) {
        return Math.round(v * scale);
    }

    public static float sf(float v) {
        return v * scale;
    }

    public static int radius() {
        return radius;
    }

    /** Скругление полей и кнопок внутри карточек. */
    public static int rr() {
        return Math.max(2, radius - s(4));
    }

    public static Color card() {
        return new Color(CARD.getRed(), CARD.getGreen(), CARD.getBlue(), cardAlpha);
    }

    public static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    public static Font font(int style, float size) {
        return Theme.BODY.deriveFont(style, size * scale);
    }

    static void smooth(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    }

    public static JLabel label(String text, float size, int style, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    // ------------------------------------------------------------------ иконки

    public enum Glyph {
        HOME, NEWS, SERVERS, USER, GEAR, GAMEPAD, PUZZLE, MONITOR, SLIDERS, KEYBOARD, GLOBE, CHIP, JAVA,
        BARS, FULLSCREEN, SAVE, RESET, FOLDER, BRUSH, SOUND, MOUSE, EYE, ROCKET, NET, TRASH, FILE, BUG,
        CHEVRON, CLOSE, MIN, MAX, CHECK, IMAGE, SHIELD, CLOCK, BELL, CHAT, PLUS, ARCHIVE, LAYERS, PLAY, COPY, EXIT, RIGHT, LINK, LEFT, SEARCH, CUBE,
        CALENDAR, WINDOW, SESSION
    }

    /** Векторная иконка в квадрате 24×24, растянутом до size. */
    public static Icon icon(Glyph glyph, int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.translate(x, y);
                g2.scale(size / 24.0, size / 24.0);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2, glyph);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case HOME -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 11);
                p.lineTo(12, 3);
                p.lineTo(21, 11);
                p.lineTo(19, 11);
                p.lineTo(19, 21);
                p.lineTo(14, 21);
                p.lineTo(14, 15);
                p.lineTo(10, 15);
                p.lineTo(10, 21);
                p.lineTo(5, 21);
                p.lineTo(5, 11);
                p.closePath();
                g.fill(p);
            }
            case NEWS, FILE -> {
                Path2D p = new Path2D.Float();
                p.moveTo(5, 2.5);
                p.lineTo(15, 2.5);
                p.lineTo(20, 7.5);
                p.lineTo(20, 21.5);
                p.lineTo(5, 21.5);
                p.closePath();
                g.fill(p);
                g.setColor(PAGE);
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(8, 11, 17, 11);
                g.drawLine(8, 15, 17, 15);
                g.drawLine(8, 18, 14, 18);
            }
            case SERVERS -> {
                for (int i = 0; i < 3; i++) {
                    g.fill(new RoundRectangle2D.Float(3, 3 + i * 6.5f, 18, 5, 2, 2));
                }
                g.setColor(PAGE);
                for (int i = 0; i < 3; i++) g.fill(new Ellipse2D.Float(16, 4.5f + i * 6.5f, 2, 2));
            }
            case USER -> {
                g.fill(new Ellipse2D.Float(7.5f, 2.5f, 9, 9));
                Path2D p = new Path2D.Float();
                p.moveTo(3.5, 21.5);
                p.curveTo(3.5, 15, 7.5, 13, 12, 13);
                p.curveTo(16.5, 13, 20.5, 15, 20.5, 21.5);
                p.closePath();
                g.fill(p);
            }
            case GEAR -> {
                Path2D p = new Path2D.Float();
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * i / teeth;
                    double r1 = (i % 2 == 0) ? 10.5 : 8.2;
                    double a1 = a - Math.PI / teeth / 2.2;
                    double a2 = a + Math.PI / teeth / 2.2;
                    double x1 = 12 + Math.cos(a1) * r1;
                    double y1 = 12 + Math.sin(a1) * r1;
                    double x2 = 12 + Math.cos(a2) * r1;
                    double y2 = 12 + Math.sin(a2) * r1;
                    if (i == 0) p.moveTo(x1, y1);
                    else p.lineTo(x1, y1);
                    p.lineTo(x2, y2);
                }
                p.closePath();
                java.awt.geom.Area a = new java.awt.geom.Area(p);
                a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(8.3f, 8.3f, 7.4f, 7.4f)));
                g.fill(a);
            }
            case GAMEPAD -> {
                Path2D p = new Path2D.Float();
                p.moveTo(7, 6);
                p.lineTo(17, 6);
                p.curveTo(21, 6, 23, 10, 23, 15);
                p.curveTo(23, 19, 20, 20, 18, 18);
                p.lineTo(16, 16);
                p.lineTo(8, 16);
                p.lineTo(6, 18);
                p.curveTo(4, 20, 1, 19, 1, 15);
                p.curveTo(1, 10, 3, 6, 7, 6);
                p.closePath();
                java.awt.geom.Area a = new java.awt.geom.Area(p);
                a.subtract(new java.awt.geom.Area(new Rectangle(6, 9, 2, 6)));
                a.subtract(new java.awt.geom.Area(new Rectangle(4, 11, 6, 2)));
                a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(15.5f, 8.5f, 2.6f, 2.6f)));
                a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(17.5f, 11.5f, 2.6f, 2.6f)));
                g.fill(a);
            }
            case PUZZLE -> {
                java.awt.geom.Area a = new java.awt.geom.Area(new RoundRectangle2D.Float(3, 7, 14, 14, 2, 2));
                a.add(new java.awt.geom.Area(new Ellipse2D.Float(7, 2, 6, 6)));
                a.add(new java.awt.geom.Area(new Ellipse2D.Float(15, 11, 6, 6)));
                a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(0, 11, 5, 6)));
                g.fill(a);
            }
            case MONITOR -> {
                g.draw(new RoundRectangle2D.Float(2.5f, 3.5f, 19, 13, 2.5f, 2.5f));
                g.drawLine(12, 17, 12, 20);
                g.drawLine(8, 21, 16, 21);
            }
            case SLIDERS -> {
                g.drawLine(3, 6, 21, 6);
                g.drawLine(3, 12, 21, 12);
                g.drawLine(3, 18, 21, 18);
                g.setColor(g.getColor());
                fillDot(g, 8, 6);
                fillDot(g, 15, 12);
                fillDot(g, 10, 18);
            }
            case KEYBOARD -> {
                g.draw(new RoundRectangle2D.Float(2, 6, 20, 13, 3, 3));
                for (int i = 0; i < 5; i++) g.fill(new Rectangle2Df(5 + i * 3.2f, 9, 1.8f, 1.8f));
                for (int i = 0; i < 4; i++) g.fill(new Rectangle2Df(6.5f + i * 3.2f, 12, 1.8f, 1.8f));
                g.drawLine(8, 16, 16, 16);
            }
            case GLOBE -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
                g.draw(new Ellipse2D.Float(7.5f, 2.5f, 9, 19));
                g.drawLine(3, 9, 21, 9);
                g.drawLine(3, 15, 21, 15);
                g.drawLine(12, 3, 12, 21);
            }
            case CHIP -> {
                g.draw(new RoundRectangle2D.Float(5, 5, 14, 14, 2, 2));
                g.fill(new RoundRectangle2D.Float(8.5f, 8.5f, 7, 7, 1, 1));
                for (int i = 0; i < 3; i++) {
                    float o = 8.5f + i * 3.5f;
                    g.drawLine((int) o, 1, (int) o, 4);
                    g.drawLine((int) o, 20, (int) o, 23);
                    g.drawLine(1, (int) o, 4, (int) o);
                    g.drawLine(20, (int) o, 23, (int) o);
                }
            }
            case JAVA -> {
                Path2D cup = new Path2D.Float();
                cup.moveTo(5, 12);
                cup.lineTo(17, 12);
                cup.lineTo(16, 18);
                cup.curveTo(15.5, 20, 14, 21, 12, 21);
                cup.lineTo(10, 21);
                cup.curveTo(8, 21, 6.5, 20, 6, 18);
                cup.closePath();
                g.draw(cup);
                g.draw(new java.awt.geom.Arc2D.Float(14.5f, 13, 6, 5, -90, 180, java.awt.geom.Arc2D.OPEN));
                Path2D steam = new Path2D.Float();
                steam.moveTo(11, 10);
                steam.curveTo(8, 7.5, 13, 6, 10.5, 2.5);
                steam.moveTo(14, 10);
                steam.curveTo(12.5, 8, 15.5, 7, 14, 5);
                g.draw(steam);
                g.drawLine(4, 23, 18, 23);
            }
            case BARS -> {
                g.fill(new RoundRectangle2D.Float(3, 14, 4.5f, 7, 1, 1));
                g.fill(new RoundRectangle2D.Float(9.75f, 9, 4.5f, 12, 1, 1));
                g.fill(new RoundRectangle2D.Float(16.5f, 3, 4.5f, 18, 1, 1));
            }
            case FULLSCREEN -> {
                g.drawPolyline(new int[]{3, 3, 9}, new int[]{9, 3, 3}, 3);
                g.drawPolyline(new int[]{15, 21, 21}, new int[]{3, 3, 9}, 3);
                g.drawPolyline(new int[]{21, 21, 15}, new int[]{15, 21, 21}, 3);
                g.drawPolyline(new int[]{9, 3, 3}, new int[]{21, 21, 15}, 3);
            }
            case SAVE -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 3);
                p.lineTo(17, 3);
                p.lineTo(21, 7);
                p.lineTo(21, 21);
                p.lineTo(3, 21);
                p.closePath();
                java.awt.geom.Area a = new java.awt.geom.Area(p);
                a.subtract(new java.awt.geom.Area(new Rectangle(7, 5, 9, 5)));
                a.subtract(new java.awt.geom.Area(new Rectangle(6, 13, 12, 6)));
                g.fill(a);
            }
            case RESET -> {
                g.draw(new java.awt.geom.Arc2D.Float(4, 4, 16, 16, 40, 290, java.awt.geom.Arc2D.OPEN));
                g.fill(new Polygon(new int[]{19, 21, 14}, new int[]{2, 10, 8}, 3));
            }
            case FOLDER -> {
                Path2D p = new Path2D.Float();
                p.moveTo(2.5, 5);
                p.lineTo(9, 5);
                p.lineTo(11, 7.5);
                p.lineTo(21.5, 7.5);
                p.lineTo(21.5, 19.5);
                p.lineTo(2.5, 19.5);
                p.closePath();
                g.draw(p);
                g.drawLine(3, 10, 21, 10);
            }
            case BRUSH -> {
                Path2D p = new Path2D.Float();
                p.moveTo(20.5, 3.5);
                p.lineTo(11, 13);
                g.draw(p);
                Path2D tip = new Path2D.Float();
                tip.moveTo(10, 12);
                tip.curveTo(6, 12, 6, 16, 5.5, 18);
                tip.curveTo(5, 20, 3.5, 20.5, 3, 21);
                tip.curveTo(8, 21.5, 12.5, 19.5, 12, 14.5);
                tip.closePath();
                g.fill(tip);
            }
            case SOUND -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 9);
                p.lineTo(7, 9);
                p.lineTo(12, 4.5);
                p.lineTo(12, 19.5);
                p.lineTo(7, 15);
                p.lineTo(3, 15);
                p.closePath();
                g.fill(p);
                g.draw(new java.awt.geom.Arc2D.Float(10, 7.5f, 7, 9, -55, 110, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(10, 4, 11, 16, -55, 110, java.awt.geom.Arc2D.OPEN));
            }
            case MOUSE -> {
                g.draw(new RoundRectangle2D.Float(6, 2.5f, 12, 19, 12, 12));
                g.drawLine(12, 6, 12, 10);
            }
            case EYE -> {
                Path2D p = new Path2D.Float();
                p.moveTo(2, 12);
                p.curveTo(5, 6, 9, 5, 12, 5);
                p.curveTo(15, 5, 19, 6, 22, 12);
                p.curveTo(19, 18, 15, 19, 12, 19);
                p.curveTo(9, 19, 5, 18, 2, 12);
                p.closePath();
                g.draw(p);
                g.fill(new Ellipse2D.Float(9, 9, 6, 6));
            }
            case ROCKET -> {
                Path2D p = new Path2D.Float();
                p.moveTo(12, 2);
                p.curveTo(16.5, 5, 17, 10, 16, 15);
                p.lineTo(8, 15);
                p.curveTo(7, 10, 7.5, 5, 12, 2);
                p.closePath();
                g.draw(p);
                g.drawLine(8, 13, 5, 18);
                g.drawLine(16, 13, 19, 18);
                g.drawLine(10, 18, 10, 21);
                g.drawLine(14, 18, 14, 21);
                g.fill(new Ellipse2D.Float(10.3f, 7, 3.4f, 3.4f));
            }
            case NET -> {
                for (int i = 0; i < 4; i++) {
                    float r = 4.5f + i * 4.6f;
                    if (i == 0) g.fill(new Ellipse2D.Float(12 - 1.8f, 19 - 1.8f, 3.6f, 3.6f));
                    else g.draw(new java.awt.geom.Arc2D.Float(12 - r, 19 - r, r * 2, r * 2, 45, 90,
                            java.awt.geom.Arc2D.OPEN));
                }
            }
            case TRASH -> {
                g.drawLine(3, 6, 21, 6);
                g.drawPolyline(new int[]{9, 9, 15, 15}, new int[]{6, 3, 3, 6}, 4);
                Path2D p = new Path2D.Float();
                p.moveTo(5.5, 6);
                p.lineTo(6.5, 21);
                p.lineTo(17.5, 21);
                p.lineTo(18.5, 6);
                g.draw(p);
                g.drawLine(10, 10, 10, 17);
                g.drawLine(14, 10, 14, 17);
            }
            case BUG -> {
                g.draw(new RoundRectangle2D.Float(7, 7, 10, 14, 10, 10));
                g.drawLine(12, 11, 12, 21);
                g.draw(new java.awt.geom.Arc2D.Float(9, 3, 6, 6, 0, 180, java.awt.geom.Arc2D.OPEN));
                g.drawLine(3, 12, 7, 12);
                g.drawLine(17, 12, 21, 12);
                g.drawLine(4, 19, 8, 17);
                g.drawLine(20, 19, 16, 17);
                g.drawLine(4, 6, 8, 8);
                g.drawLine(20, 6, 16, 8);
            }
            case CHEVRON -> g.drawPolyline(new int[]{6, 12, 18}, new int[]{9, 15, 9}, 3);
            case CLOSE -> {
                g.drawLine(6, 6, 18, 18);
                g.drawLine(18, 6, 6, 18);
            }
            case MIN -> g.drawLine(5, 12, 19, 12);
            case MAX -> g.draw(new Rectangle2Df(5.5f, 5.5f, 13, 13));
            case CHECK -> g.drawPolyline(new int[]{4, 10, 20}, new int[]{12, 18, 6}, 3);
            case IMAGE -> {
                g.draw(new RoundRectangle2D.Float(2.5f, 4, 19, 16, 2.5f, 2.5f));
                g.fill(new Ellipse2D.Float(6, 7.5f, 3.6f, 3.6f));
                g.drawPolyline(new int[]{3, 9, 13, 16, 21}, new int[]{18, 12, 16, 13, 17}, 5);
            }
            case SHIELD -> {
                Path2D p = new Path2D.Float();
                p.moveTo(12, 2.5);
                p.lineTo(20, 5.5);
                p.curveTo(20, 13, 17, 18.5, 12, 21.5);
                p.curveTo(7, 18.5, 4, 13, 4, 5.5);
                p.closePath();
                g.draw(p);
                g.drawPolyline(new int[]{8, 11, 16}, new int[]{12, 15, 9}, 3);
            }
            case CLOCK -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
                g.drawPolyline(new int[]{12, 12, 16}, new int[]{6, 12, 15}, 3);
            }
            case BELL -> {
                Path2D p = new Path2D.Float();
                p.moveTo(5, 17);
                p.lineTo(5, 11);
                p.curveTo(5, 6.5, 8, 4, 12, 4);
                p.curveTo(16, 4, 19, 6.5, 19, 11);
                p.lineTo(19, 17);
                p.lineTo(21, 18.5);
                p.lineTo(3, 18.5);
                p.closePath();
                g.draw(p);
                g.drawLine(10, 22, 14, 22);
            }
            case CHAT -> {
                Path2D p = new Path2D.Float();
                p.moveTo(3, 4);
                p.lineTo(21, 4);
                p.lineTo(21, 16);
                p.lineTo(10, 16);
                p.lineTo(5, 20.5);
                p.lineTo(5, 16);
                p.lineTo(3, 16);
                p.closePath();
                g.draw(p);
                g.drawLine(7, 9, 17, 9);
                g.drawLine(7, 12, 14, 12);
            }
            case PLUS -> {
                g.drawLine(12, 4, 12, 20);
                g.drawLine(4, 12, 20, 12);
            }
            case ARCHIVE -> {
                g.draw(new RoundRectangle2D.Float(2.5f, 4, 19, 5, 1.5f, 1.5f));
                g.draw(new Rectangle2Df(4, 9, 16, 11.5f));
                g.drawLine(10, 13, 14, 13);
            }
            case PLAY -> g.fill(new Polygon(new int[]{6, 20, 6}, new int[]{3, 12, 21}, 3));
            case COPY -> {
                g.draw(new RoundRectangle2D.Float(8, 8, 13, 13, 3, 3));
                g.drawPolyline(new int[]{16, 16, 3, 3, 8}, new int[]{5, 3, 3, 16, 16}, 5);
            }
            case EXIT -> {
                g.drawPolyline(new int[]{10, 4, 4, 10}, new int[]{3, 3, 21, 21}, 4);
                g.drawLine(9, 12, 21, 12);
                g.drawPolyline(new int[]{16, 21, 16}, new int[]{7, 12, 17}, 3);
            }
            case RIGHT -> g.drawPolyline(new int[]{9, 15, 9}, new int[]{6, 12, 18}, 3);
            case LEFT -> {
                g.drawLine(5, 12, 19, 12);
                g.drawPolyline(new int[]{11, 5, 11}, new int[]{6, 12, 18}, 3);
            }
            case SEARCH -> {
                g.draw(new Ellipse2D.Float(4, 4, 12, 12));
                g.drawLine(14, 14, 20, 20);
            }
            case CUBE -> {
                g.draw(new Polygon(new int[]{12, 20, 12, 4}, new int[]{3, 7, 11, 7}, 4));
                g.drawPolyline(new int[]{4, 4, 12, 20, 20}, new int[]{7, 16, 21, 16, 7}, 5);
                g.drawLine(12, 11, 12, 21);
            }
            case LINK -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
                g.drawLine(12, 3, 12, 21);
                g.draw(new Ellipse2D.Float(7.5f, 2.5f, 9, 19));
            }
            case LAYERS -> {
                g.draw(new Polygon(new int[]{12, 22, 12, 2}, new int[]{3, 8, 13, 8}, 4));
                g.drawPolyline(new int[]{2, 12, 22}, new int[]{13, 18, 13}, 3);
            }
            case CALENDAR -> {
                g.draw(new RoundRectangle2D.Float(3, 5, 18, 16, 3, 3));
                g.drawLine(3, 10, 21, 10);
                g.drawLine(8, 3, 8, 7);
                g.drawLine(16, 3, 16, 7);
                for (int i = 0; i < 3; i++) {
                    fillDot(g, 8 + i * 4, 14);
                    fillDot(g, 8 + i * 4, 17.5f);
                }
            }
            case WINDOW -> {
                g.draw(new RoundRectangle2D.Float(3, 4, 18, 16, 3, 3));
                g.fill(new Rectangle2Df(3, 4, 18, 4.5f));
            }
            case SESSION -> {
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
                g.fill(new Polygon(new int[]{10, 16, 10}, new int[]{8, 12, 16}, 3));
            }
        }
    }

    private static void fillDot(Graphics2D g, float x, float y) {
        Color c = g.getColor();
        g.fill(new Ellipse2D.Float(x - 2.8f, y - 2.8f, 5.6f, 5.6f));
        g.setColor(CARD);
        g.fill(new Ellipse2D.Float(x - 1.2f, y - 1.2f, 2.4f, 2.4f));
        g.setColor(c);
    }

    private static final class Rectangle2Df extends java.awt.geom.Rectangle2D.Float {
        Rectangle2Df(float x, float y, float w, float h) {
            super(x, y, w, h);
        }
    }

    // ------------------------------------------------------------------ карточка

    /** Карточка раздела: иконка, заголовок, подзаголовок и строки настроек под ними. */
    public static final class Card extends JPanel {
        private final JPanel body = new JPanel();
        private boolean wide;

        public Card(Glyph glyph, String title, String subtitle) {
            setOpaque(false);
            setLayout(new BorderLayout(s(0), s(16)));
            setBorder(new EmptyBorder(s(20), s(22), s(22), s(22)));
            JPanel head = new JPanel(new BorderLayout(s(16), s(0)));
            head.setOpaque(false);
            JLabel ic = new JLabel(icon(glyph, s(34), new Color(0xB9C6D6)));
            ic.setVerticalAlignment(SwingConstants.TOP);
            head.add(ic, BorderLayout.WEST);
            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            texts.add(label(title, 21f, Font.BOLD, TEXT));
            if (subtitle != null && !subtitle.isBlank()) {
                texts.add(Box.createVerticalStrut(s(4)));
                texts.add(label(subtitle, 15.5f, Font.PLAIN, MUTED));
            }
            head.add(texts, BorderLayout.CENTER);
            add(head, BorderLayout.NORTH);
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            add(body, BorderLayout.CENTER);
        }

        public Card wide() {
            wide = true;
            return this;
        }

        public boolean isWide() {
            return wide;
        }

        /** Добавляет строку с отступом сверху. */
        public Card add(JComponent row) {
            if (body.getComponentCount() > 0) body.add(Box.createVerticalStrut(s(14)));
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(row);
            return this;
        }

        public JPanel body() {
            return body;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int r = radius + s(2);
            g2.setColor(card());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
            g2.setColor(CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, r, r));
            g2.dispose();
        }
    }

    /** Подпись над полем: «Язык интерфейса» и под ней список. */
    public static JComponent labeled(String title, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(s(0), s(8)));
        p.setOpaque(false);
        p.add(label(title, 16.5f, Font.PLAIN, new Color(0xD5DEE8)), BorderLayout.NORTH);
        p.add(control, BorderLayout.CENTER);
        return fullWidth(p);
    }

    /** Строка «иконка — заголовок/пояснение — переключатель». */
    public static JComponent row(Glyph glyph, String title, String hint, JComponent control) {
        JPanel p = new JPanel(new BorderLayout(s(14), s(0)));
        p.setOpaque(false);
        if (glyph != null) {
            JLabel ic = new JLabel(icon(glyph, s(26), new Color(0xB9C6D6)));
            ic.setPreferredSize(new Dimension(s(30), s(30)));
            p.add(ic, BorderLayout.WEST);
        }
        JPanel texts = new JPanel();
        texts.setOpaque(false);
        texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
        texts.add(label(title, 17f, Font.PLAIN, TEXT));
        if (hint != null && !hint.isBlank()) {
            texts.add(Box.createVerticalStrut(s(3)));
            texts.add(label(hint, 14f, Font.PLAIN, MUTED));
        }
        JPanel mid = new JPanel(new GridBagLayout());
        mid.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        mid.add(texts, c);
        p.add(mid, BorderLayout.CENTER);
        if (control != null) {
            JPanel east = new JPanel(new GridBagLayout());
            east.setOpaque(false);
            east.add(control);
            p.add(east, BorderLayout.EAST);
        }
        return fullWidth(p);
    }

    /** Несколько контролов в линию с равным шагом. */
    public static JComponent line(JComponent... parts) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new BoxLayout(p, BoxLayout.X_AXIS));
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) p.add(Box.createHorizontalStrut(s(10)));
            parts[i].setAlignmentY(Component.CENTER_ALIGNMENT);
            p.add(parts[i]);
        }
        return fullWidth(p);
    }

    static JComponent fullWidth(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    /** Плашка состояния: цветная точка и текст. */
    public static final class Status extends JComponent {
        private String text;
        private Color tone;

        public Status(String text, Color tone) {
            this.text = text;
            this.tone = tone;
            setPreferredSize(new Dimension(s(200), s(48)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, s(48)));
            setAlignmentX(LEFT_ALIGNMENT);
        }

        public void set(String text, Color tone) {
            this.text = text;
            this.tone = tone;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, rr(), rr()));
            g2.setColor(CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, rr(), rr()));
            g2.setColor(tone);
            g2.fill(new Ellipse2D.Float(s(18), h / 2f - s(6), s(12), s(12)));
            g2.setFont(font(Font.PLAIN, 16.5f));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(ellipsize(text, fm, w - s(60)), s(44), (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    static String ellipsize(String s, FontMetrics fm, int max) {
        if (s == null) return "";
        if (fm.stringWidth(s) <= max) return s;
        String dots = "…";
        int n = s.length();
        while (n > 0 && fm.stringWidth(s.substring(0, n) + dots) > max) n--;
        return s.substring(0, Math.max(0, n)) + dots;
    }

    // ------------------------------------------------------------------ переключатель

    public static final class Toggle extends JComponent {
        private boolean on;
        private float pos;
        private final List<Runnable> listeners = new ArrayList<>();
        private Timer anim;

        public Toggle(boolean on) {
            this.on = on;
            this.pos = on ? 1 : 0;
            setPreferredSize(new Dimension(s(64), s(36)));
            setMinimumSize(getPreferredSize());
            setMaximumSize(getPreferredSize());
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled()) toggle();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (isEnabled() && (e.getKeyCode() == KeyEvent.VK_SPACE || e.getKeyCode() == KeyEvent.VK_ENTER)) {
                        toggle();
                    }
                }
            });
        }

        private void toggle() {
            setOn(!on);
            for (Runnable r : listeners) r.run();
        }

        public boolean isOn() {
            return on;
        }

        /** Меняет положение без вызова слушателей (загрузка значения). */
        public void setOn(boolean value) {
            if (on == value) return;
            on = value;
            if (anim != null) anim.stop();
            if (!isShowing()) {
                pos = on ? 1 : 0;
                repaint();
                return;
            }
            anim = new Timer(12, e -> {
                pos += on ? 0.18f : -0.18f;
                if (pos >= 1 || pos <= 0) {
                    pos = on ? 1 : 0;
                    ((Timer) e.getSource()).stop();
                }
                repaint();
            });
            anim.start();
        }

        public void onChange(Runnable r) {
            listeners.add(r);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int th = s(34);
            int tw = s(62);
            int x = (w - tw) / 2;
            int y = (h - th) / 2;
            Color off = new Color(0x253446);
            Color track = Motion.mix(off, ACCENT, pos);
            if (!isEnabled()) track = Motion.mix(track, CARD, 0.55f);
            g2.setColor(track);
            g2.fill(new RoundRectangle2D.Float(x, y, tw, th, th, th));
            float knob = th - s(8);
            float kx = x + s(4) + (tw - th) * pos;
            g2.setColor(isEnabled() ? Motion.mix(new Color(0xC3CDD8), Color.WHITE, pos) : new Color(0x6B7685));
            g2.fill(new Ellipse2D.Float(kx, y + s(4), knob, knob));
            if (isFocusOwner()) {
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 90));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(x - 2, y - 2, tw + 4, th + 4, th + 4, th + 4));
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ выпадающий список

    public static final class Dropdown extends JComponent {
        private String[] items;
        private int selected;
        private boolean hover;
        private final List<Runnable> listeners = new ArrayList<>();

        public Dropdown(String[] items, int selected) {
            this.items = items;
            this.selected = Math.max(0, Math.min(items.length - 1, selected));
            setPreferredSize(new Dimension(s(260), s(50)));
            setMinimumSize(new Dimension(s(120), s(50)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, s(50)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (isEnabled()) open();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (!isEnabled()) return;
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_DOWN -> pick(Math.min(items.length - 1, selected + 1));
                        case KeyEvent.VK_UP -> pick(Math.max(0, selected - 1));
                        case KeyEvent.VK_SPACE, KeyEvent.VK_ENTER -> open();
                        default -> {
                        }
                    }
                }
            });
        }

        public int getSelectedIndex() {
            return selected;
        }

        public void setSelectedIndex(int i) {
            selected = Math.max(0, Math.min(items.length - 1, i));
            repaint();
        }

        public void setItems(String[] items, int selected) {
            this.items = items;
            setSelectedIndex(selected);
        }

        public void onChange(Runnable r) {
            listeners.add(r);
        }

        private void pick(int i) {
            if (i == selected) return;
            selected = i;
            repaint();
            for (Runnable r : listeners) r.run();
        }

        private void open() {
            JPopupMenu popup = new JPopupMenu();
            popup.setBorder(BorderFactory.createLineBorder(FIELD_LINE));
            popup.setBackground(FIELD);
            JList<String> list = new JList<>(items);
            list.setSelectedIndex(selected);
            list.setBackground(FIELD);
            list.setForeground(TEXT);
            list.setFont(font(Font.PLAIN, 15f));
            list.setFixedCellHeight(s(40));
            list.setCellRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                              boolean isSelected, boolean focus) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, isSelected, false);
                    c.setBorder(new EmptyBorder(s(0), s(18), s(0), s(18)));
                    c.setOpaque(true);
                    c.setBackground(isSelected ? ACTIVE : FIELD);
                    c.setForeground(TEXT);
                    return c;
                }
            });
            list.addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if (i >= 0) list.setSelectedIndex(i);
                }
            });
            list.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    popup.setVisible(false);
                    if (i >= 0) pick(i);
                }
            });
            list.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                        popup.setVisible(false);
                        pick(list.getSelectedIndex());
                    } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        popup.setVisible(false);
                    }
                }
            });
            JScrollPane scroll = new JScrollPane(list);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUI(new ScrollBarUI());
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(s(8), s(8)));
            int visible = Math.min(items.length, 8);
            scroll.setPreferredSize(new Dimension(getWidth(), visible * s(40) + 2));
            popup.add(scroll);
            popup.show(this, 0, getHeight() + 4);
            list.ensureIndexIsVisible(selected);
            list.requestFocusInWindow();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, rr(), rr()));
            g2.setColor(hover || isFocusOwner() ? FIELD_HOVER : FIELD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, rr(), rr()));
            g2.setFont(font(Font.PLAIN, 17f));
            g2.setColor(isEnabled() ? TEXT : DIM);
            FontMetrics fm = g2.getFontMetrics();
            String text = items.length == 0 ? "" : items[selected];
            g2.drawString(ellipsize(text, fm, w - s(70)), s(20), (h + fm.getAscent() - fm.getDescent()) / 2);
            icon(Glyph.CHEVRON, s(20), isEnabled() ? new Color(0xC9D3DE) : DIM).paintIcon(this, g2, w - s(40), (h - s(20)) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ ползунок

    public static final class Slider extends JComponent {
        private final int min;
        private final int max;
        private int value;
        private int[] ticks = new int[0];
        private java.util.function.IntFunction<String> tickLabel = String::valueOf;
        private final List<Runnable> listeners = new ArrayList<>();
        private final List<Runnable> views = new ArrayList<>();
        private boolean dragging;

        public Slider(int min, int max, int value) {
            this.min = min;
            this.max = Math.max(min + 1, max);
            this.value = clamp(value);
            setPreferredSize(new Dimension(s(300), s(40)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, s(40)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFocusable(true);
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (!isEnabled()) return;
                    requestFocusInWindow();
                    dragging = true;
                    pick(valueAt(e.getX()));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging && isEnabled()) pick(valueAt(e.getX()));
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = false;
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(e -> {
                if (!isEnabled() || !isFocusOwner()) {
                    getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
                    return;
                }
                pick(value - e.getWheelRotation());
            });
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (!isEnabled()) return;
                    if (e.getKeyCode() == KeyEvent.VK_LEFT || e.getKeyCode() == KeyEvent.VK_DOWN) pick(value - 1);
                    if (e.getKeyCode() == KeyEvent.VK_RIGHT || e.getKeyCode() == KeyEvent.VK_UP) pick(value + 1);
                    if (e.getKeyCode() == KeyEvent.VK_HOME) pick(min);
                    if (e.getKeyCode() == KeyEvent.VK_END) pick(max);
                }
            });
        }

        public Slider ticks(java.util.function.IntFunction<String> label, int... values) {
            this.ticks = values;
            this.tickLabel = label;
            setPreferredSize(new Dimension(s(300), values.length > 0 ? s(66) : s(40)));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredSize().height));
            return this;
        }

        private int clamp(int v) {
            return Math.max(min, Math.min(max, v));
        }

        private int trackX() {
            return s(12);
        }

        private int trackW() {
            return Math.max(10, getWidth() - s(24));
        }

        private int valueAt(int x) {
            double t = (x - trackX()) / (double) trackW();
            return clamp((int) Math.round(min + t * (max - min)));
        }

        private float xOf(int v) {
            return trackX() + (float) (v - min) / (max - min) * trackW();
        }

        public int getValue() {
            return value;
        }

        public void setValue(int v) {
            value = clamp(v);
            repaint();
            for (Runnable r : views) r.run();
        }

        /** Вызывается при любом изменении значения, в том числе программном. */
        public void onView(Runnable r) {
            views.add(r);
        }

        private void pick(int v) {
            v = clamp(v);
            if (v == value) return;
            value = v;
            repaint();
            for (Runnable r : views) r.run();
            for (Runnable r : listeners) r.run();
        }

        public void onChange(Runnable r) {
            listeners.add(r);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int cy = s(18);
            int x0 = trackX();
            int w = trackW();
            g2.setColor(new Color(0x253446));
            g2.fill(new RoundRectangle2D.Float(x0, cy - s(5), w, s(10), s(10), s(10)));
            float kx = xOf(value);
            if (isEnabled()) g2.setPaint(new GradientPaint(x0, 0, ACCENT_2, x0 + w, 0, ACCENT));
            else g2.setColor(new Color(0x3A4A5C));
            g2.fill(new RoundRectangle2D.Float(x0, cy - s(5), Math.max(s(10), kx - x0), s(10), s(10), s(10)));
            if (ticks.length > 0) {
                g2.setFont(font(Font.PLAIN, 13.5f));
                FontMetrics fm = g2.getFontMetrics();
                for (int i = 0; i < ticks.length; i++) {
                    float tx = xOf(ticks[i]);
                    g2.setColor(new Color(0x4A5B70));
                    g2.drawLine(Math.round(tx), cy + s(14), Math.round(tx), cy + s(20));
                    String s = tickLabel.apply(ticks[i]);
                    int sw = fm.stringWidth(s);
                    int sx = Math.round(tx - sw / 2f);
                    if (i == 0) sx = Math.round(tx) - 2;
                    if (i == ticks.length - 1) sx = Math.round(tx) - sw + 2;
                    g2.setColor(MUTED);
                    g2.drawString(s, sx, cy + s(42));
                }
            }
            float r = dragging ? s(15) : s(14);
            g2.setColor(new Color(0, 0, 0, 70));
            g2.fill(new Ellipse2D.Float(kx - r, cy - r + 2, r * 2, r * 2));
            g2.setColor(isEnabled() ? Color.WHITE : new Color(0x8792A0));
            g2.fill(new Ellipse2D.Float(kx - r, cy - r, r * 2, r * 2));
            if (isFocusOwner()) {
                g2.setColor(new Color(ACCENT.getRed(), ACCENT.getGreen(), ACCENT.getBlue(), 120));
                g2.setStroke(new BasicStroke(3f));
                g2.draw(new Ellipse2D.Float(kx - r - 2, cy - r - 2, r * 2 + 4, r * 2 + 4));
            }
            g2.dispose();
        }
    }

    /** Ползунок с заголовком слева и значением справа: «Дальность прорисовки … 12 чанков». */
    public static JComponent slider(String title, Slider s, java.util.function.IntFunction<String> value) {
        JPanel p = new JPanel(new BorderLayout(s(0), s(8)));
        p.setOpaque(false);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(label(title, 16.5f, Font.PLAIN, TEXT), BorderLayout.WEST);
        JLabel v = label(value.apply(s.getValue()), 17f, Font.BOLD, ACCENT);
        top.add(v, BorderLayout.EAST);
        s.onView(() -> v.setText(value.apply(s.getValue())));
        p.add(top, BorderLayout.NORTH);
        p.add(s, BorderLayout.CENTER);
        return fullWidth(p);
    }

    // ------------------------------------------------------------------ поля и кнопки

    public static JTextField field(String text) {
        JTextField f = new JTextField(text == null ? "" : text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                smooth(g2);
                g2.setColor(isEnabled() ? FIELD : new Color(0x0C131C));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, rr(), rr()));
                g2.setColor(isFocusOwner() ? ACCENT : FIELD_LINE);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, rr(), rr()));
                g2.dispose();
                super.paintComponent(g);
                Object hint = getClientProperty("cw.hint");
                if (hint != null && getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D h = (Graphics2D) g.create();
                    smooth(h);
                    h.setFont(getFont());
                    h.setColor(DIM);
                    FontMetrics fm = h.getFontMetrics();
                    h.drawString(ellipsize(hint.toString(), fm, getWidth() - s(32)), getInsets().left,
                            (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    h.dispose();
                }
            }
        };
        f.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        f.setOpaque(false);
        f.setFont(font(Font.PLAIN, 16f));
        f.setForeground(TEXT);
        f.setDisabledTextColor(MUTED);
        f.setCaretColor(TEXT);
        f.setSelectionColor(ACTIVE);
        f.setSelectedTextColor(Color.WHITE);
        f.setBorder(new EmptyBorder(s(0), s(16), s(0), s(16)));
        f.setPreferredSize(new Dimension(s(200), s(46)));
        f.setMaximumSize(new Dimension(Integer.MAX_VALUE, s(46)));
        f.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                f.repaint();
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                f.repaint();
            }
        });
        return f;
    }

    public static void onText(JTextField f, Runnable r) {
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                r.run();
            }
        });
    }

    public enum Kind {PRIMARY, GHOST, DANGER, FLAT}

    /** Кнопка с иконкой. PRIMARY — бирюзовая как «Сохранить изменения». */
    public static final class Button extends JButton {
        private final Kind kind;
        private final Glyph glyph;
        private boolean hover;
        private boolean down;
        private float iconSize = 22;
        private boolean sized;
        private int arc = -1;

        public Button(String text, Glyph glyph, Kind kind) {
            super(text);
            this.kind = kind;
            this.glyph = glyph;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFont(font(Font.BOLD, kind == Kind.PRIMARY ? 17f : 15.5f));
            setForeground(TEXT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(text == null ? "" : text) + s(glyph == null ? 44 : 80);
            if (text == null || text.isEmpty()) w = s(48);
            setPreferredSize(new Dimension(w, s(46)));
            setMaximumSize(getPreferredSize());
            setMinimumSize(getPreferredSize());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    down = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    down = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    down = false;
                    repaint();
                }
            });
        }

        /** Размер в пикселях макета, масштаб применяется здесь. */
        public Button size(int w, int h) {
            w = s(w);
            h = s(h);
            setPreferredSize(new Dimension(w, h));
            setMaximumSize(new Dimension(w, h));
            setMinimumSize(new Dimension(Math.min(w, s(40)), h));
            return this;
        }

        public Button iconSize(float s) {
            iconSize = s;
            return this;
        }

        public Button arc(int a) {
            arc = a;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int arc = this.arc >= 0 ? this.arc : radius;
            RoundRectangle2D shape = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, arc, arc);
            boolean on = isEnabled();
            switch (kind) {
                case PRIMARY -> {
                    Color a = on ? Motion.mix(ACCENT, Color.WHITE, 0.06f) : Motion.mix(ACCENT, PAGE, 0.62f);
                    Color b = on ? ACCENT_2 : Motion.mix(ACCENT_2, PAGE, 0.62f);
                    if (on && hover) {
                        a = Motion.mix(a, Color.WHITE, 0.14f);
                        b = Motion.mix(b, Color.WHITE, 0.08f);
                    }
                    if (down) {
                        a = b;
                    }
                    g2.setPaint(new GradientPaint(0, 0, a, 0, h, b));
                    g2.fill(shape);
                }
                case GHOST, FLAT -> {
                    if (kind == Kind.GHOST || hover) {
                        g2.setColor(hover && on ? new Color(0x182535) : new Color(0x121C28));
                        g2.fill(shape);
                    }
                    if (kind == Kind.GHOST) {
                        g2.setColor(hover && on ? FIELD_HOVER : FIELD_LINE);
                        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, arc, arc));
                    }
                }
                case DANGER -> {
                    g2.setColor(hover && on ? new Color(0x3A1820) : new Color(0x26141A));
                    g2.fill(shape);
                    g2.setColor(new Color(0x7A2E3A));
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, arc, arc));
                }
            }
            Color fg = !on ? DIM : kind == Kind.DANGER ? new Color(0xFF8A93) : TEXT;
            String text = getText();
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int tw = text == null || text.isEmpty() ? 0 : fm.stringWidth(text);
            int iw = glyph == null ? 0 : s(Math.round(iconSize));
            int gap = glyph != null && tw > 0 ? s(14) : 0;
            int room = w - iw - gap - s(16);
            if (tw > room) {
                text = ellipsize(text, fm, Math.max(s(20), room));
                tw = fm.stringWidth(text);
            }
            int x = (w - tw - iw - gap) / 2;
            if (glyph != null) {
                icon(glyph, iw, fg).paintIcon(this, g2, x, (h - iw) / 2);
            }
            if (tw > 0) {
                g2.setColor(fg);
                g2.drawString(text, x + iw + gap, (h + fm.getAscent() - fm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ навигация

    /** Пункт меню: иконка и текст, активный подсвечен синим. */
    public static final class NavItem extends JButton {
        private final Glyph glyph;
        private boolean active;
        private boolean hover;
        private final float textSize;
        private final int iconPx;

        public NavItem(String text, Glyph glyph, float textSize, int iconPx) {
            super(text);
            this.glyph = glyph;
            this.textSize = textSize;
            this.iconPx = iconPx;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
        }

        public void setActive(boolean a) {
            active = a;
            repaint();
        }

        public boolean isActive() {
            return active;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth();
            int h = getHeight();
            if (active) {
                g2.setPaint(new GradientPaint(0, 0, ACTIVE, w, 0, ACTIVE_2));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, rr(), rr()));
            } else if (hover) {
                g2.setColor(new Color(0x16222F));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, rr(), rr()));
            }
            int ip = s(iconPx);
            int pad = Math.max(s(14), ip / 2 + s(8));
            Color ic = new Color(0xB3C0CF);
            icon(glyph, ip, active ? Color.WHITE : ic).paintIcon(this, g2, pad, (h - ip) / 2);
            g2.setFont(font(Font.PLAIN, textSize));
            g2.setColor(active ? Color.WHITE : new Color(0xDCE4EC));
            FontMetrics fm = g2.getFontMetrics();
            int tx = pad + ip + Math.max(s(14), ip / 2);
            g2.drawString(ellipsize(getText(), fm, w - tx - 8), tx, (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    /** Колонки карточек: каждая следующая встаёт в более короткую колонку, широкая — на всю ширину. */
    public static final class Columns implements LayoutManager {
        private final int gap;

        public Columns(int gap) {
            this.gap = gap;
        }

        private int columns(int width) {
            return width >= s(820) ? 2 : 1;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            int width = parent.getWidth() > 0 ? parent.getWidth() : 900;
            return new Dimension(width, place(parent, width, false));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            place(parent, parent.getWidth(), true);
        }

        private int place(Container parent, int width, boolean apply) {
            Insets in = parent.getInsets();
            int inner = width - in.left - in.right;
            int cols = columns(inner);
            int colW = cols == 1 ? inner : (inner - gap) / 2;
            int[] heights = new int[cols];
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                int h = c.getPreferredSize().height;
                boolean wide = c instanceof Card card && card.isWide();
                if (wide || cols == 1) {
                    int y = 0;
                    for (int v : heights) y = Math.max(y, v);
                    if (apply) c.setBounds(in.left, in.top + y, inner, h);
                    for (int i = 0; i < cols; i++) heights[i] = y + h + gap;
                } else {
                    int col = heights[0] <= heights[1] ? 0 : 1;
                    if (apply) c.setBounds(in.left + col * (colW + gap), in.top + heights[col], colW, h);
                    heights[col] += h + gap;
                }
            }
            int max = 0;
            for (int v : heights) max = Math.max(max, v);
            return in.top + in.bottom + Math.max(0, max - gap);
        }
    }

    /** Панель, которая растягивается по ширине прокрутки и растёт по высоте. */
    public static final class ScrollPage extends JPanel implements Scrollable {
        public ScrollPage(LayoutManager layout) {
            super(layout);
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 28;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(28, visibleRect.height - 60);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
