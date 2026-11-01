package ru.cw.launcher.ui;

import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Сообщения лаунчера в новом стиле: тёмная скруглённая карточка поверх окна,
 * ошибки с кнопками «Повторить», «Поддержка», «Подробности», уведомления внутри окна.
 */
public final class Dialogs {

    private static final List<String> shownToasts = new ArrayList<>();

    private Dialogs() {
    }

    public static Runnable supportOpener = () -> Utils.openUrl("https://discord.gg/vEkuksnUQ4");

    /** Ошибка с повтором. Возвращает true, если пользователь нажал «Повторить». */
    public static boolean error(Component parent, String message, String details, Runnable retry) {
        Log.error(message + (details == null ? "" : " | " + details.replaceAll("\\s+", " ")));
        final boolean[] again = {false};
        Sheet sheet = new Sheet(parent, Glyph.SHIELD, SettingsKit.AMBER,
                Tr.t("Что-то пошло не так", "Something went wrong"), message);
        if (retry != null) sheet.button(Tr.t("Повторить", "Retry"), Glyph.RESET, Kind.PRIMARY, () -> again[0] = true, true);
        sheet.button(Tr.t("Поддержка", "Support"), Glyph.CHAT, Kind.GHOST, supportOpener, false);
        if (details != null && !details.isBlank()) {
            sheet.button(Tr.t("Подробности", "Details"), Glyph.FILE, Kind.GHOST, () -> showDetails(parent, details), false);
        }
        sheet.button(Tr.t("Закрыть", "Close"), null, Kind.GHOST, null, true);
        sheet.open();
        if (again[0]) retry.run();
        return again[0];
    }

    private static void showDetails(Component parent, String details) {
        JTextArea ta = new JTextArea(details, 16, 64);
        ta.setEditable(false);
        ta.setFont(Theme.MONO);
        ta.setBackground(SettingsKit.FIELD);
        ta.setForeground(SettingsKit.TEXT);
        ta.setCaretColor(SettingsKit.TEXT);
        ta.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(ta);
        scroll.setBorder(BorderFactory.createLineBorder(SettingsKit.FIELD_LINE));
        scroll.getVerticalScrollBar().setUI(new SettingsKit.ScrollBarUI());
        Sheet sheet = new Sheet(parent, Glyph.FILE, SettingsKit.ACCENT, Tr.t("Подробности", "Details"), null);
        sheet.extra(scroll);
        sheet.button(Tr.t("Скопировать", "Copy"), Glyph.ARCHIVE, Kind.GHOST, () -> Toolkit.getDefaultToolkit()
                .getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(details), null), false);
        sheet.button(Tr.t("Закрыть", "Close"), null, Kind.PRIMARY, null, true);
        sheet.open();
    }

    /** Вопрос с двумя ответами. true — нажата первая кнопка. */
    public static boolean ask(Component parent, String message, String okText, String cancelText) {
        final boolean[] res = {false};
        Sheet sheet = new Sheet(parent, Glyph.BELL, SettingsKit.ACCENT, null, message);
        sheet.button(okText, null, Kind.PRIMARY, () -> res[0] = true, true);
        sheet.button(cancelText, null, Kind.GHOST, null, true);
        sheet.open();
        return res[0];
    }

    /** Ввод строки: ник, имя. null — пользователь отменил. */
    public static String input(Component parent, String title, String hint, String initial, String okText) {
        JTextField field = SettingsKit.field(initial == null ? "" : initial);
        field.setPreferredSize(new Dimension(SettingsKit.s(400), SettingsKit.s(46)));
        final String[] res = {null};
        Sheet sheet = new Sheet(parent, Glyph.USER, SettingsKit.ACCENT, title, hint);
        sheet.extra(field);
        sheet.button(okText, Glyph.CHECK, Kind.PRIMARY, () -> res[0] = field.getText().trim(), true);
        sheet.button(Tr.t("Отмена", "Cancel"), null, Kind.GHOST, null, true);
        SwingUtilities.invokeLater(() -> {
            field.requestFocusInWindow();
            field.selectAll();
        });
        sheet.open();
        return res[0];
    }

    public static void info(Component parent, String message) {
        Sheet sheet = new Sheet(parent, Glyph.BELL, SettingsKit.ACCENT, null, message);
        sheet.button(Tr.t("Понятно", "OK"), null, Kind.PRIMARY, null, true);
        sheet.open();
    }

    /**
     * Куда скачать выбранную версию: папка CWLauncher или своя папка через проводник Windows.
     * null — пользователь отменил установку.
     */
    public static java.nio.file.Path chooseInstallRoot(Component parent, String version) {
        java.nio.file.Path launcherDir = ru.cw.launcher.util.Paths.gamesRoot()
                .resolve(ru.cw.launcher.util.Paths.sanitize(version));
        final java.nio.file.Path[] chosen = {null};
        final boolean[] pickOwn = {false};
        Sheet sheet = new Sheet(parent, Glyph.FOLDER, SettingsKit.ACCENT,
                Tr.t("Куда скачать Minecraft " + version + "?", "Where to install Minecraft " + version + "?"),
                Tr.t("Обычное место: " + launcherDir + "\nУ каждой версии свои моды, миры, звуки и языки.",
                        "Default location: " + launcherDir + "\nEach version keeps its own mods, worlds and languages."));
        sheet.button(Tr.t("В папку CWLauncher", "CWLauncher folder"), Glyph.CHECK, Kind.PRIMARY, () -> chosen[0] = launcherDir, true);
        sheet.button(Tr.t("Своя папка", "Choose folder"), Glyph.FOLDER, Kind.GHOST, () -> pickOwn[0] = true, true);
        sheet.button(Tr.t("Отмена", "Cancel"), null, Kind.GHOST, null, true);
        sheet.open();
        if (pickOwn[0]) return Utils.chooseDirectory(parent, Tr.t("Выберите папку", "Choose a folder"));
        return chosen[0];
    }

    /** Уведомление внизу справа внутри окна. Не повторяет одно и то же, пока оно видно. */
    public static boolean toast(Component anchor, String message, Color accent) {
        if (shownToasts.contains(message)) return false;
        Window w = anchor instanceof Window win ? win : SwingUtilities.getWindowAncestor(anchor);
        if (w == null || !w.isShowing()) return false;
        shownToasts.add(message);
        if (shownToasts.size() > 40) shownToasts.remove(0);
        JWindow tw = new JWindow(w);
        tw.setBackground(new Color(0, 0, 0, 0));
        int r = SettingsKit.radius() + SettingsKit.s(2);
        JPanel body = new JPanel(new BorderLayout(SettingsKit.s(12), 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                g2.setColor(new Color(0x142131));
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, r, r));
                g2.dispose();
            }
        };
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(SettingsKit.s(14), SettingsKit.s(18), SettingsKit.s(14), SettingsKit.s(22)));
        body.add(new JLabel(SettingsKit.icon(Glyph.CHECK, SettingsKit.s(20), accent)), BorderLayout.WEST);
        JLabel label = new JLabel("<html><div style='width:" + SettingsKit.s(260) + "px'>" + esc(message) + "</div></html>");
        label.setForeground(SettingsKit.TEXT);
        label.setFont(SettingsKit.font(Font.PLAIN, 15f));
        body.add(label, BorderLayout.CENTER);
        tw.setContentPane(body);
        tw.pack();
        Point p = w.getLocationOnScreen();
        tw.setLocation(p.x + w.getWidth() - tw.getWidth() - SettingsKit.s(28),
                p.y + w.getHeight() - tw.getHeight() - SettingsKit.s(100));
        Motion.present(tw);
        Timer t = new Timer(4000, e -> {
            tw.dispose();
            shownToasts.remove(message);
        });
        t.setRepeats(false);
        t.start();
        return true;
    }

    public static void clearToastHistory() {
        shownToasts.clear();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    /** Модальная карточка поверх окна: иконка, заголовок, текст и кнопки справа внизу. */
    private static final class Sheet {
        private final JDialog d;
        private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, SettingsKit.s(10), 0));
        private final JPanel center = new JPanel(new BorderLayout(0, SettingsKit.s(12)));
        private final Component parent;
        private Button first;

        Sheet(Component parent, Glyph glyph, Color tone, String title, String message) {
            this.parent = parent;
            Window owner = parent instanceof Window w ? w : parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            d = new JDialog(owner, "CWLauncher", Dialog.ModalityType.APPLICATION_MODAL);
            d.setUndecorated(true);
            d.setBackground(new Color(0, 0, 0, 0));
            int r = SettingsKit.radius() + SettingsKit.s(4);
            JPanel body = new JPanel(new BorderLayout(SettingsKit.s(18), SettingsKit.s(20))) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    SettingsKit.smooth(g2);
                    g2.setColor(new Color(0x111B27));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
                    g2.setColor(SettingsKit.FIELD_LINE);
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, r, r));
                    g2.dispose();
                }
            };
            body.setOpaque(false);
            int p = SettingsKit.s(26);
            body.setBorder(BorderFactory.createEmptyBorder(p, p, SettingsKit.s(22), p));
            JLabel ic = new JLabel(SettingsKit.icon(glyph, SettingsKit.s(34), tone));
            ic.setVerticalAlignment(SwingConstants.TOP);
            body.add(ic, BorderLayout.WEST);
            center.setOpaque(false);
            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            if (title != null) {
                texts.add(SettingsKit.label(title, 19f, Font.BOLD, SettingsKit.TEXT));
                texts.add(Box.createVerticalStrut(SettingsKit.s(8)));
            }
            if (message != null) {
                JLabel m = new JLabel("<html><div style='width:" + SettingsKit.s(400) + "px'>" + esc(message) + "</div></html>");
                m.setFont(SettingsKit.font(Font.PLAIN, 16f));
                m.setForeground(title == null ? SettingsKit.TEXT : new Color(0xC3CEDA));
                texts.add(m);
            }
            center.add(texts, BorderLayout.NORTH);
            body.add(center, BorderLayout.CENTER);
            buttons.setOpaque(false);
            body.add(buttons, BorderLayout.SOUTH);
            d.setContentPane(body);
            d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                    KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        void extra(JComponent c) {
            center.add(c, BorderLayout.CENTER);
        }

        void button(String text, Glyph glyph, Kind kind, Runnable action, boolean closes) {
            Button b = new Button(text, glyph, kind);
            b.setFont(SettingsKit.font(Font.BOLD, 15f));
            FontMetrics fm = b.getFontMetrics(b.getFont());
            int w = fm.stringWidth(text) + SettingsKit.s(glyph == null ? 48 : 78);
            Dimension size = new Dimension(Math.max(SettingsKit.s(120), w), SettingsKit.s(46));
            b.setPreferredSize(size);
            b.setMaximumSize(size);
            b.addActionListener(e -> {
                if (action != null) action.run();
                if (closes) d.dispose();
            });
            if (first == null) {
                first = b;
                d.getRootPane().setDefaultButton(b);
            }
            buttons.add(b);
        }

        void open() {
            d.pack();
            Window w = d.getOwner();
            if (w != null && w.isShowing()) {
                d.setLocation(w.getX() + (w.getWidth() - d.getWidth()) / 2, w.getY() + (w.getHeight() - d.getHeight()) / 2);
            } else {
                d.setLocationRelativeTo(parent);
            }
            Motion.present(d);
        }
    }

    /** Рамка со срезанными углами для старых окон, которые ещё не переведены на новый стиль. */
    public static final class CutBorder extends javax.swing.border.AbstractBorder {
        private final Color color;
        private final int cut;

        public CutBorder(Color color, int cut) {
            this.color = color;
            this.cut = cut;
        }

        public static Shape shape(int w, int h, int cut) {
            java.awt.geom.GeneralPath p = new java.awt.geom.GeneralPath();
            p.moveTo(cut, 0);
            p.lineTo(w, 0);
            p.lineTo(w, h - cut);
            p.lineTo(w - cut, h);
            p.lineTo(0, h);
            p.lineTo(0, cut);
            p.closePath();
            return p;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(1.3f));
            g2.draw(shape(width - 1, height - 1, cut));
            g2.dispose();
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(3, 3, 3, 3);
        }
    }
}
