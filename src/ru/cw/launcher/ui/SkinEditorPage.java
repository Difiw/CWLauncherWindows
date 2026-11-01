package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.skin.PlayerSkin;
import ru.cw.launcher.skin.SkinRenderer;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.Toggle;
import ru.cw.launcher.util.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Редактор скина: рисование прямо по объёмной модели и по развёртке 64×64.
 * Внешний слой (шапка, куртка, рукава, штанины) можно скрыть или рисовать только на нём.
 * Модель крутится в любую сторону: правой кнопкой мыши или потянув за пустое место.
 */
final class SkinEditorPage extends JPanel {

    private enum Tool {BRUSH, ERASER, PICKER, FILL}

    /** Коробка развёртки: начало (u, v), размеры w×h×d, слой. */
    private record Box(int u, int v, int w, int h, int d, boolean outer) {
    }

    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFC8C8C8, 0xFF7A7A7A, 0xFF3A3A3A, 0xFF101010, 0xFFF4C7A1, 0xFFC68E63, 0xFF8A5A3B,
            0xFFE53935, 0xFFFF8F00, 0xFFFDD835, 0xFF43A047, 0xFF00ACC1, 0xFF1E88E5, 0xFF5E35B1, 0xFFD81B60};

    private final MainWindow mw;
    private final Account account;
    private final Path existing;
    private BufferedImage img;
    private Tool tool = Tool.BRUSH;
    private Color color = new Color(0x1E88E5);
    private boolean outerTarget;
    private boolean showOuter = true;
    private boolean slim;
    private boolean grid = true;
    private boolean dirty;
    private final Deque<BufferedImage> undo = new ArrayDeque<>();
    private final Deque<BufferedImage> redo = new ArrayDeque<>();

    private final JLabel title = SettingsKit.label("", 40f, Font.BOLD, SettingsKit.TEXT);
    private final JTextField name = SettingsKit.field("");
    private final Tools tools = new Tools();
    private final View3D view = new View3D();
    private final Texture2D texture = new Texture2D();
    private final Button cancel = new Button(Tr.t("Отмена", "Cancel"), Glyph.CLOSE, Kind.GHOST);
    private final Button save = new Button(Tr.t("Сохранить скин", "Save skin"), Glyph.SAVE, Kind.PRIMARY);

    SkinEditorPage(MainWindow mw, LauncherApp app, Account account, Path existing, BufferedImage start, String skinName) {
        super(null);
        this.mw = mw;
        this.account = account;
        this.existing = existing;
        this.img = normalize(start == null ? template() : start);
        this.slim = existing != null && slimFile(existing);
        setOpaque(false);
        title.setText(existing == null ? Tr.t("Новый скин", "New skin") : Tr.t("Редактор скина", "Skin editor"));
        name.setText(skinName == null ? "" : skinName);
        name.setEnabled(existing == null);
        name.setToolTipText(existing == null ? Tr.t("Имя файла скина", "Skin file name") : Tr.t("Имя меняется только у нового скина", "Only new skins can be named"));
        for (JComponent c : new JComponent[]{title, name, tools, view, texture, cancel, save}) add(c);
        save.iconSize(22);
        cancel.addActionListener(e -> leave());
        save.addActionListener(e -> save());
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Z, KeyEvent.CTRL_DOWN_MASK), this::undo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_Y, KeyEvent.CTRL_DOWN_MASK), this::redo);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK), this::save);
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_B, 0), () -> tools.pick(Tool.BRUSH));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_E, 0), () -> tools.pick(Tool.ERASER));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), () -> tools.pick(Tool.PICKER));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_G, 0), () -> tools.pick(Tool.FILL));
    }

    private void bind(KeyStroke key, Runnable r) {
        String id = "cw-" + key;
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(key, id);
        getActionMap().put(id, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (!(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JTextField)
                        || key.getModifiers() != 0) r.run();
            }
        });
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int x0 = s(20);
        int right = w - s(20);
        title.setBounds(x0 + s(16), s(26), s(520), s(54));
        name.setBounds(x0 + s(18), s(82), s(320), s(38));
        int top = s(134);
        int footerH = s(56);
        int footerY = h - footerH - s(18);
        int bodyH = footerY - s(14) - top;
        int toolsW = s(250);
        tools.setBounds(x0, top, toolsW, bodyH);
        int rest = right - x0 - toolsW - s(14);
        int texW = Math.min(bodyH, rest * 45 / 100);
        int viewW = rest - texW - s(14);
        view.setBounds(x0 + toolsW + s(14), top, viewW, bodyH);
        texture.setBounds(right - texW, top, texW, bodyH);
        cancel.setBounds(x0, footerY, s(200), footerH);
        int saveW = s(300);
        save.setBounds(right - saveW, footerY, saveW, footerH);
    }

    // ------------------------------------------------------------------ данные

    /** 64×64 ARGB: старые 64×32 дополняются левыми руками и ногами, HD сжимается. */
    private static BufferedImage normalize(BufferedImage src) {
        BufferedImage out = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        int k = Math.max(1, src.getWidth() / 64);
        boolean legacy = src.getHeight() * 2 <= src.getWidth() + 1;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, 64, legacy ? 32 : 64, null);
        g.dispose();
        if (legacy) {
            copy(out, 0, 16, 16, 48, 16, 16);
            copy(out, 40, 16, 32, 48, 16, 16);
        }
        if (k > 1) Log.info("Скин " + src.getWidth() + "×" + src.getHeight() + " сжат до 64×64 для редактора");
        return out;
    }

    private static void copy(BufferedImage img, int sx, int sy, int dx, int dy, int w, int h) {
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(dx + x, dy + y, img.getRGB(sx + x, sy + y));
    }

    private static boolean slimFile(Path p) {
        try {
            return PlayerSkin.slim(p);
        } catch (Exception e) {
            return false;
        }
    }

    /** Простой шаблон для нового скина: кожа, волосы, глаза, футболка, джинсы, обувь. */
    private static BufferedImage template() {
        BufferedImage t = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = t.createGraphics();
        Color skin = new Color(0xE0AC84);
        Color hair = new Color(0x4A2E1C);
        Color shirt = new Color(0x1E88E5);
        Color pants = new Color(0x283A6B);
        Color shoes = new Color(0x3A3A3A);
        g.setColor(skin);
        g.fillRect(0, 0, 32, 16);
        g.setColor(hair);
        g.fillRect(8, 0, 8, 8);
        g.fillRect(24, 8, 8, 8);
        g.fillRect(0, 8, 8, 3);
        g.fillRect(16, 8, 8, 3);
        g.fillRect(8, 8, 8, 2);
        g.setColor(Color.WHITE);
        g.fillRect(9, 12, 2, 1);
        g.fillRect(13, 12, 2, 1);
        g.setColor(new Color(0x2F5BA8));
        g.fillRect(10, 12, 1, 1);
        g.fillRect(13, 12, 1, 1);
        g.setColor(new Color(0x9B5E45));
        g.fillRect(11, 14, 2, 1);
        g.setColor(shirt);
        g.fillRect(16, 16, 24, 16);
        g.fillRect(40, 16, 16, 16);
        g.fillRect(32, 48, 16, 16);
        g.setColor(skin);
        g.fillRect(40, 26, 16, 6);
        g.fillRect(32, 58, 16, 6);
        g.setColor(pants);
        g.fillRect(0, 16, 16, 16);
        g.fillRect(16, 48, 16, 16);
        g.setColor(shoes);
        g.fillRect(0, 29, 16, 3);
        g.fillRect(16, 61, 16, 3);
        g.dispose();
        return t;
    }

    private static List<Box> boxes(boolean slim) {
        int arm = slim ? 3 : 4;
        List<Box> b = new ArrayList<>();
        b.add(new Box(0, 0, 8, 8, 8, false));
        b.add(new Box(16, 16, 8, 12, 4, false));
        b.add(new Box(40, 16, arm, 12, 4, false));
        b.add(new Box(32, 48, arm, 12, 4, false));
        b.add(new Box(0, 16, 4, 12, 4, false));
        b.add(new Box(16, 48, 4, 12, 4, false));
        b.add(new Box(32, 0, 8, 8, 8, true));
        b.add(new Box(16, 32, 8, 12, 4, true));
        b.add(new Box(40, 32, arm, 12, 4, true));
        b.add(new Box(48, 48, arm, 12, 4, true));
        b.add(new Box(0, 32, 4, 12, 4, true));
        b.add(new Box(0, 48, 4, 12, 4, true));
        return b;
    }

    /** Грань развёртки, в которой лежит пиксель: заливка не выходит за её края. */
    private Rectangle faceRect(int x, int y) {
        for (Box b : boxes(slim)) {
            Rectangle[] faces = {
                    new Rectangle(b.u() + b.d(), b.v(), b.w(), b.d()),
                    new Rectangle(b.u() + b.d() + b.w(), b.v(), b.w(), b.d()),
                    new Rectangle(b.u(), b.v() + b.d(), b.d(), b.h()),
                    new Rectangle(b.u() + b.d(), b.v() + b.d(), b.w(), b.h()),
                    new Rectangle(b.u() + b.d() + b.w(), b.v() + b.d(), b.d(), b.h()),
                    new Rectangle(b.u() + b.d() + b.w() + b.d(), b.v() + b.d(), b.w(), b.h())};
            for (Rectangle r : faces) if (r.contains(x, y)) return r;
        }
        return null;
    }

    private boolean outerArea(int x, int y) {
        for (Box b : boxes(slim)) {
            if (!b.outer()) continue;
            if (new Rectangle(b.u(), b.v(), 2 * (b.w() + b.d()), b.d() + b.h()).contains(x, y)) return true;
        }
        return false;
    }

    private BufferedImage snapshot() {
        BufferedImage c = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = c.createGraphics();
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return c;
    }

    private void beginStroke() {
        undo.push(snapshot());
        while (undo.size() > 60) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(snapshot());
        img = undo.pop();
        changed();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(snapshot());
        img = redo.pop();
        changed();
    }

    private void changed() {
        dirty = true;
        view.repaint();
        texture.repaint();
        tools.repaint();
    }

    /** Применяет инструмент к пикселю развёртки. */
    private void apply(int x, int y) {
        if (x < 0 || y < 0 || x >= 64 || y >= 64) return;
        switch (tool) {
            case BRUSH -> img.setRGB(x, y, color.getRGB() | 0xFF000000);
            case ERASER -> img.setRGB(x, y, 0);
            case PICKER -> {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) != 0) {
                    color = new Color(argb, false);
                    tools.pick(Tool.BRUSH);
                }
                return;
            }
            case FILL -> fill(x, y);
        }
        changed();
    }

    private void fill(int x, int y) {
        Rectangle area = faceRect(x, y);
        if (area == null) return;
        int from = img.getRGB(x, y);
        int to = color.getRGB() | 0xFF000000;
        if (from == to) return;
        Deque<Point> stack = new ArrayDeque<>();
        stack.push(new Point(x, y));
        while (!stack.isEmpty()) {
            Point p = stack.pop();
            if (!area.contains(p) || img.getRGB(p.x, p.y) != from) continue;
            img.setRGB(p.x, p.y, to);
            stack.push(new Point(p.x + 1, p.y));
            stack.push(new Point(p.x - 1, p.y));
            stack.push(new Point(p.x, p.y + 1));
            stack.push(new Point(p.x, p.y - 1));
        }
    }

    private void save() {
        String n = name.getText().trim();
        if (existing == null && n.isEmpty()) {
            n = Dialogs.input(mw, Tr.t("Имя скина", "Skin name"), Tr.t("Так скин будет подписан в профиле.",
                    "This is how the skin is labeled in the profile."), "", Tr.t("Сохранить", "Save"));
            if (n == null || n.isBlank()) return;
            name.setText(n);
        }
        try {
            PlayerSkin.save(account, existing, img, n);
            dirty = false;
            Dialogs.toast(mw, Tr.t("Скин сохранён", "Skin saved"), SettingsKit.GREEN);
            mw.closeSkinEditor();
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Скин не сохранён.", "The skin was not saved."), Log.reason(e), null);
        }
    }

    /** Уход из редактора с несохранёнными правками спрашивает подтверждение. */
    void leave() {
        leave(mw::closeSkinEditor);
    }

    void leave(Runnable then) {
        if (dirty && !Dialogs.ask(mw, Tr.t("Выйти без сохранения? Нарисованное пропадёт.",
                "Leave without saving? Your drawing will be lost."), Tr.t("Выйти", "Leave"), Tr.t("Остаться", "Stay"))) return;
        dirty = false;
        if (then != null) then.run();
    }

    boolean dirty() {
        return dirty;
    }

    // ------------------------------------------------------------------ инструменты

    private final class Tools extends JPanel {
        private final ToolButton[] buttons = {
                new ToolButton(Tool.BRUSH, Glyph.BRUSH, Tr.t("Кисть (B)", "Brush (B)")),
                new ToolButton(Tool.ERASER, Glyph.TRASH, Tr.t("Ластик (E)", "Eraser (E)")),
                new ToolButton(Tool.PICKER, Glyph.EYE, Tr.t("Пипетка (I)", "Picker (I)")),
                new ToolButton(Tool.FILL, Glyph.LAYERS, Tr.t("Заливка (G)", "Fill (G)"))};
        private final Toggle outer = new Toggle(false);
        private final Toggle shown = new Toggle(true);
        private final Toggle thin = new Toggle(false);
        private final Toggle gridToggle = new Toggle(true);
        private final Button undoB = new Button("", Glyph.RESET, Kind.GHOST);
        private final Button redoB = new Button("", Glyph.RIGHT, Kind.GHOST);
        private final Button custom = new Button(Tr.t("Свой цвет", "Custom color"), Glyph.PLUS, Kind.GHOST);
        private final JComponent[] labels;

        Tools() {
            super(null);
            setOpaque(false);
            for (ToolButton b : buttons) add(b);
            thin.setOn(slim);
            labels = new JComponent[]{
                    row(Tr.t("Рисовать на внешнем слое", "Paint the outer layer"), outer),
                    row(Tr.t("Показывать внешний слой", "Show the outer layer"), shown),
                    row(Tr.t("Тонкие руки (Alex)", "Slim arms (Alex)"), thin),
                    row(Tr.t("Сетка на развёртке", "Grid on the texture"), gridToggle)};
            for (JComponent l : labels) add(l);
            outer.onChange(() -> {
                outerTarget = outer.isOn();
                if (outerTarget && !shown.isOn()) shown.setOn(true);
                showOuter = shown.isOn();
                changedView();
            });
            shown.onChange(() -> {
                showOuter = shown.isOn();
                if (!showOuter && outer.isOn()) {
                    outer.setOn(false);
                    outerTarget = false;
                }
                changedView();
            });
            thin.onChange(() -> {
                slim = thin.isOn();
                changedView();
            });
            gridToggle.onChange(() -> {
                grid = gridToggle.isOn();
                changedView();
            });
            undoB.setToolTipText(Tr.t("Отменить (Ctrl+Z)", "Undo (Ctrl+Z)"));
            redoB.setToolTipText(Tr.t("Повторить (Ctrl+Y)", "Redo (Ctrl+Y)"));
            undoB.addActionListener(e -> undo());
            redoB.addActionListener(e -> redo());
            custom.setFont(SettingsKit.font(Font.BOLD, 14f));
            custom.addActionListener(e -> {
                Color c = JColorChooser.showDialog(mw, Tr.t("Цвет кисти", "Brush color"), color);
                if (c != null) {
                    color = c;
                    pick(Tool.BRUSH);
                }
            });
            add(undoB);
            add(redoB);
            add(custom);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int i = swatchAt(e.getPoint());
                    if (i >= 0) {
                        color = new Color(PALETTE[i], true);
                        pick(Tool.BRUSH);
                    }
                }
            });
        }

        private JComponent row(String text, Toggle t) {
            JPanel p = new JPanel(new BorderLayout(s(8), 0));
            p.setOpaque(false);
            JLabel l = SettingsKit.label(text, 14f, Font.PLAIN, SettingsKit.TEXT);
            p.add(l, BorderLayout.CENTER);
            p.add(t, BorderLayout.EAST);
            return p;
        }

        private void changedView() {
            view.repaint();
            texture.repaint();
        }

        void pick(Tool t) {
            tool = t;
            for (ToolButton b : buttons) b.repaint();
            repaint();
        }

        private int sw() {
            return (getWidth() - s(28) - s(6) * 3) / 4;
        }

        private int paletteTop() {
            return s(16) + sw() + s(16) + s(60);
        }

        private int swatchAt(Point p) {
            int size = (getWidth() - s(28) - s(4) * 7) / 8;
            int top = paletteTop();
            for (int i = 0; i < PALETTE.length; i++) {
                int x = s(14) + (i % 8) * (size + s(4));
                int y = top + (i / 8) * (size + s(4));
                if (new Rectangle(x, y, size, size).contains(p)) return i;
            }
            return -1;
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int sw = sw();
            for (int i = 0; i < buttons.length; i++) buttons[i].setBounds(s(14) + i * (sw + s(6)), s(16), sw, sw);
            int size = (w - s(28) - s(4) * 7) / 8;
            int y = paletteTop() + 2 * (size + s(4)) + s(8);
            custom.setBounds(s(14), y, w - s(28), s(40));
            y += s(56);
            for (JComponent l : labels) {
                l.setBounds(s(14), y, w - s(28), s(40));
                y += s(46);
            }
            int bw = (w - s(28) - s(8)) / 2;
            undoB.setBounds(s(14), y + s(6), bw, s(44));
            redoB.setBounds(s(14) + bw + s(8), y + s(6), bw, s(44));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int r = SettingsKit.radius() + s(2);
            g2.setColor(SettingsKit.card());
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, getHeight() - 1, r, r));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, getHeight() - 2, r, r));
            int cy = s(16) + sw() + s(16);
            g2.setColor(color);
            g2.fill(new RoundRectangle2D.Float(s(14), cy, s(48), s(48), SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(Color.WHITE);
            g2.draw(new RoundRectangle2D.Float(s(14), cy, s(48), s(48), SettingsKit.rr(), SettingsKit.rr()));
            g2.setFont(SettingsKit.font(Font.BOLD, 15f));
            g2.drawString(Tr.t("Цвет", "Color"), s(74), cy + s(20));
            g2.setFont(SettingsKit.font(Font.PLAIN, 13.5f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(SettingsKit.hex(color), s(74), cy + s(40));
            int size = (w - s(28) - s(4) * 7) / 8;
            int top = paletteTop();
            for (int i = 0; i < PALETTE.length; i++) {
                int x = s(14) + (i % 8) * (size + s(4));
                int y = top + (i / 8) * (size + s(4));
                g2.setColor(new Color(PALETTE[i], true));
                g2.fill(new RoundRectangle2D.Float(x, y, size, size, s(6), s(6)));
                if ((PALETTE[i] & 0xFFFFFF) == (color.getRGB() & 0xFFFFFF)) {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f));
                    g2.draw(new RoundRectangle2D.Float(x - 1, y - 1, size + 2, size + 2, s(6), s(6)));
                }
            }
            g2.dispose();
        }
    }

    private final class ToolButton extends JComponent {
        private final Tool t;
        private final Glyph glyph;

        ToolButton(Tool t, Glyph glyph, String tip) {
            this.t = t;
            this.glyph = glyph;
            setToolTipText(tip);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    tools.pick(t);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            boolean on = tool == t;
            g2.setColor(on ? SettingsKit.ACTIVE : SettingsKit.FIELD);
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, getHeight() - 1, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(on ? SettingsKit.ACCENT : SettingsKit.FIELD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, getHeight() - 2, SettingsKit.rr(), SettingsKit.rr()));
            int is = w * 45 / 100;
            SettingsKit.icon(glyph, is, Color.WHITE).paintIcon(this, g2, (w - is) / 2, (getHeight() - is) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ 3D

    private final class View3D extends JComponent {
        private double yaw = -0.5;
        private double pitch = -0.15;
        private double zoom = 1;
        private Point press;
        private double pressYaw;
        private double pressPitch;
        private boolean rotating;
        private boolean painting;
        private List<SkinRenderer.Face> faces = List.of();

        View3D() {
            setCursor(SettingsKit.grab(false));
            setToolTipText(Tr.t("ЛКМ — рисовать, ПКМ или пустое место — вращать, колесо — масштаб",
                    "Left — paint, right or empty space — rotate, wheel — zoom"));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    Point px = SwingUtilities.isLeftMouseButton(e) ? hit(e) : null;
                    if (px != null) {
                        painting = true;
                        beginStroke();
                        apply(px.x, px.y);
                        return;
                    }
                    rotating = true;
                    press = e.getPoint();
                    pressYaw = yaw;
                    pressPitch = pitch;
                    setCursor(SettingsKit.grab(true));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (painting) {
                        Point px = hit(e);
                        if (px != null && tool != Tool.FILL && tool != Tool.PICKER) apply(px.x, px.y);
                        return;
                    }
                    if (rotating && press != null) {
                        yaw = pressYaw + (e.getX() - press.x) * 0.012;
                        pitch = Math.max(-1.45, Math.min(1.45, pressPitch - (e.getY() - press.y) * 0.012));
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    painting = false;
                    rotating = false;
                    setCursor(SettingsKit.grab(false));
                }

                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    zoom = Math.max(0.5, Math.min(2.6, zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1)));
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        private Point hit(MouseEvent e) {
            int layers = outerTarget ? SkinRenderer.OUTER : SkinRenderer.BASE;
            return SkinRenderer.pick(faces, img, e.getX(), e.getY(), layers, false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.radius() + s(2);
            Shape clip = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r);
            g2.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Float(w / 2f, h * 0.4f), Math.max(w, h) * 0.7f,
                    new float[]{0f, 1f}, new Color[]{new Color(0x37424F), new Color(0x10171F)}));
            g2.fill(clip);
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(clip);
            g2.clip(clip);
            int fh = (int) (Math.min(h * 0.62, w * 1.1) * zoom);
            int layers = SkinRenderer.BASE | (showOuter ? SkinRenderer.OUTER : 0);
            g2.setColor(new Color(0, 0, 0, 70));
            g2.fill(new Ellipse2D.Float(w / 2f - fh * 0.22f, h / 2f + fh * 0.48f, fh * 0.44f, fh * 0.06f));
            faces = SkinRenderer.paint(g2, img, slim, yaw, pitch, w / 2, h / 2, fh, layers);
            if (outerTarget) {
                // Рамки внешнего слоя: видно, куда ляжет краска, даже на прозрачных местах.
                g2.setColor(new Color(255, 255, 255, 40));
                for (SkinRenderer.Face f : faces) {
                    if (!f.outer()) continue;
                    g2.draw(f.toScreen().createTransformedShape(new Rectangle(0, 0, f.tw(), f.th())));
                }
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 13f));
            g2.setColor(new Color(0x9AA9BA));
            g2.drawString(Tr.t("ПКМ — вращать · колесо — масштаб", "Right drag — rotate · wheel — zoom"), s(14), h - s(14));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ 2D

    private final class Texture2D extends JComponent {
        private Point last;

        Texture2D() {
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    Point p = cell(e.getPoint());
                    if (p == null) return;
                    beginStroke();
                    apply(p.x, p.y);
                    last = p;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    Point p = cell(e.getPoint());
                    if (p == null || last == null || tool == Tool.FILL || tool == Tool.PICKER) return;
                    // Линия между событиями, чтобы быстрый штрих не рвался.
                    int n = Math.max(Math.abs(p.x - last.x), Math.abs(p.y - last.y));
                    for (int i = 1; i <= n; i++) {
                        int x = last.x + (p.x - last.x) * i / n;
                        int y = last.y + (p.y - last.y) * i / n;
                        if (img.getRGB(x, y) != (color.getRGB() | 0xFF000000) || tool == Tool.ERASER) apply(x, y);
                    }
                    last = p;
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    last = null;
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private Rectangle area() {
            int size = Math.min(getWidth(), getHeight()) - s(24);
            int cell = Math.max(1, size / 64);
            int side = cell * 64;
            return new Rectangle((getWidth() - side) / 2, (getHeight() - side) / 2, side, side);
        }

        private Point cell(Point p) {
            Rectangle a = area();
            if (!a.contains(p)) return null;
            int c = a.width / 64;
            return new Point((p.x - a.x) / c, (p.y - a.y) / c);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int w = getWidth();
            int h = getHeight();
            SettingsKit.smooth(g2);
            int r = SettingsKit.radius() + s(2);
            g2.setColor(SettingsKit.card());
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            Rectangle a = area();
            int c = a.width / 64;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    boolean used = faceRect(x, y) != null;
                    g2.setColor(!used ? new Color(0x0B1118) : ((x + y) % 2 == 0 ? new Color(0x2A3440) : new Color(0x222B35)));
                    g2.fillRect(a.x + x * c, a.y + y * c, c, c);
                }
            }
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(img, a.x, a.y, a.width, a.height, null);
            // Слой, на котором сейчас не рисуют, слегка приглушён.
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    if (faceRect(x, y) == null) continue;
                    if (outerArea(x, y) != outerTarget) {
                        g2.setColor(new Color(11, 17, 24, 120));
                        g2.fillRect(a.x + x * c, a.y + y * c, c, c);
                    }
                }
            }
            if (grid && c >= 5) {
                g2.setColor(new Color(255, 255, 255, 18));
                for (int i = 0; i <= 64; i++) {
                    g2.drawLine(a.x + i * c, a.y, a.x + i * c, a.y + a.height);
                    g2.drawLine(a.x, a.y + i * c, a.x + a.width, a.y + i * c);
                }
                g2.setColor(new Color(255, 255, 255, 50));
                for (Box b : boxes(slim)) {
                    g2.drawRect(a.x + b.u() * c, a.y + b.v() * c, 2 * (b.w() + b.d()) * c, (b.d() + b.h()) * c);
                }
            }
            g2.dispose();
        }
    }
}
