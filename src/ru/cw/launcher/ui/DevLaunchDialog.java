package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.Settings;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Запуск в режиме разработчика: из какой папки игры запускать (своя папка — свои моды,
 * миры и настройки) и, для второго окна, временный ник. Карточки сборок рисуются
 * векторными иконками в стиле лаунчера.
 */
public final class DevLaunchDialog {

    public static final class Choice {
        public final Path dir;
        public final String nick;

        Choice(Path dir, String nick) {
            this.dir = dir;
            this.nick = nick;
        }
    }

    private DevLaunchDialog() {
    }

    public static Choice ask(Component parent, boolean askNick) {
        return ask(parent, new Settings(), askNick);
    }

    public static Choice ask(Component parent, Settings cfg, boolean askNick) {
        Window owner = parent instanceof Window w ? w : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "CWLauncher", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        d.setBackground(new Color(0, 0, 0, 0));
        Body body = new Body(d, cfg, askNick);
        d.setContentPane(body);
        d.setSize(s(640), s(askNick ? 720 : 620));
        if (owner != null && owner.isShowing()) {
            d.setLocation(owner.getX() + (owner.getWidth() - d.getWidth()) / 2,
                    owner.getY() + (owner.getHeight() - d.getHeight()) / 2);
        } else {
            d.setLocationRelativeTo(parent);
        }
        Motion.present(d);
        return body.result;
    }

    private static Path folderOf(String name) {
        return name == null || name.isEmpty() ? Paths.gameDir() : Paths.playInstance(name);
    }

    /** Содержимое окна: шапка, список сборок, создание новой, ник, кнопки. */
    private static final class Body extends JPanel {
        private final JDialog d;
        private final Settings cfg;
        private final boolean askNick;
        private final JPanel list = new JPanel(null);
        private final JScrollPane scroll = new JScrollPane(list);
        private final JTextField folderName = SettingsKit.field("");
        private final Button create = new Button(Tr.t("Создать", "Create"), Glyph.PLUS, Kind.GHOST);
        private final JLabel nickLabel = SettingsKit.label(Tr.t("Временный ник для этого окна", "Temporary nickname for this window"),
                15f, Font.PLAIN, new Color(0xD5DEE8));
        private final JTextField nick = SettingsKit.field("");
        private final JLabel status = SettingsKit.label(" ", 13.5f, Font.PLAIN, new Color(0xFF8A93));
        private final Button close = new Button("", Glyph.CLOSE, Kind.FLAT).size(42, 42).iconSize(18);
        private final Button cancel = new Button(Tr.t("Отмена", "Cancel"), null, Kind.GHOST);
        private final Button go;
        private final List<Item> items = new ArrayList<>();
        private String selected = "";
        Choice result;

        Body(JDialog d, Settings cfg, boolean askNick) {
            super(null);
            this.d = d;
            this.cfg = cfg;
            this.askNick = askNick;
            setOpaque(false);
            go = new Button(askNick ? Tr.t("Запустить ещё окно", "Launch another window") : Tr.t("Запустить", "Launch"),
                    Glyph.PLAY, Kind.PRIMARY);
            go.iconSize(20);
            list.setOpaque(false);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setViewportBorder(null);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(s(24));
            folderName.putClientProperty("cw.hint", Tr.t("Имя новой сборки, например «Тест модов»", "New instance name, e.g. “Mod test”"));
            nick.putClientProperty("cw.hint", Tr.t("Пусто — играть своим аккаунтом", "Empty — play with your account"));
            for (JComponent c : new JComponent[]{scroll, folderName, create, nickLabel, nick, status, close, cancel, go}) add(c);
            nickLabel.setVisible(askNick);
            nick.setVisible(askNick);

            fill();
            close.setToolTipText(Tr.t("Закрыть", "Close"));
            close.addActionListener(e -> d.dispose());
            cancel.addActionListener(e -> d.dispose());
            create.addActionListener(e -> createInstance());
            folderName.addActionListener(e -> createInstance());
            go.addActionListener(e -> launch());
            nick.addActionListener(e -> launch());
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
            getActionMap().put("close", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    d.dispose();
                }
            });
            d.getRootPane().setDefaultButton(go);
        }

        private void fill() {
            list.removeAll();
            items.clear();
            List<String> names = new ArrayList<>();
            names.add("");
            names.addAll(Paths.playInstanceNames());
            for (String n : names) {
                Item it = new Item(n);
                items.add(it);
                list.add(it);
            }
            layoutList();
        }

        private void layoutList() {
            int w = scroll.getViewport().getWidth() > 0 ? scroll.getViewport().getWidth() : scroll.getWidth();
            int y = 0;
            int h = s(64);
            for (Item it : items) {
                it.setBounds(0, y, Math.max(10, w - s(4)), h);
                y += h + s(8);
            }
            list.setPreferredSize(new Dimension(w, Math.max(0, y - s(8))));
            list.revalidate();
            list.repaint();
        }

        private void select(String name) {
            selected = name;
            for (Item it : items) it.repaint();
        }

        private void createInstance() {
            String safe = Paths.playFolderName(folderName.getText());
            if (safe.isEmpty()) {
                status.setText(Tr.t("Введите имя новой сборки", "Enter a name for the new instance"));
                folderName.requestFocusInWindow();
                return;
            }
            try {
                Paths.createPlayInstance(safe);
                folderName.setText("");
                status.setText(" ");
                fill();
                select(safe);
                Log.info("Папка игры: " + safe);
                SwingUtilities.invokeLater(() -> {
                    for (Item it : items) if (it.name.equals(safe)) list.scrollRectToVisible(it.getBounds());
                });
            } catch (Exception ex) {
                status.setText(Log.reason(ex));
            }
        }

        private void launch() {
            String chosenNick = null;
            if (askNick) {
                String typed = nick.getText() == null ? "" : nick.getText().trim();
                if (!typed.isEmpty() && !Account.validNick(typed)) {
                    status.setText(Lang.t("dev_bad_nick"));
                    nick.requestFocusInWindow();
                    return;
                }
                chosenNick = typed.isEmpty() ? null : typed;
            }
            result = new Choice(folderOf(selected), chosenNick);
            d.dispose();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(28);
            close.setBounds(w - p - s(34), s(20), s(42), s(42));
            int listTop = s(150);
            int footer = s(50);
            int footerY = h - p - footer;
            int bottomBlock = s(48) + s(40) + (askNick ? s(96) : 0);
            int listH = footerY - s(26) - bottomBlock - listTop;
            scroll.setBounds(p, listTop, w - p * 2, listH);
            int y = listTop + listH + s(14);
            create.setBounds(w - p - s(150), y, s(150), s(48));
            folderName.setBounds(p, y, w - p * 2 - s(162), s(48));
            y += s(56);
            if (askNick) {
                nickLabel.setBounds(p, y, w - p * 2, s(24));
                nick.setBounds(p, y + s(30), w - p * 2, s(48));
                y += s(96);
            }
            status.setBounds(p, y - s(4), w - p * 2, s(24));
            cancel.setBounds(p, footerY, s(160), footer);
            go.setBounds(w - p - s(280), footerY, s(280), footer);
            layoutList();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.radius() + s(8);
            int p = s(28);
            g2.setColor(new Color(0x111B27));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            g2.setColor(Motion.mix(SettingsKit.ACCENT, SettingsKit.FIELD_LINE, 0.6f));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            // Значок, заголовок, подзаголовок.
            int is = s(52);
            g2.setColor(SettingsKit.ACCENT);
            g2.fill(new RoundRectangle2D.Float(p, s(28), is, is, SettingsKit.rr() + s(4), SettingsKit.rr() + s(4)));
            SettingsKit.icon(askNick ? Glyph.LAYERS : Glyph.PLAY, s(24), Color.WHITE).paintIcon(this, g2, p + (is - s(24)) / 2, s(28) + (is - s(24)) / 2);
            int tx = p + is + s(18);
            g2.setFont(SettingsKit.font(Font.BOLD, 23f));
            g2.setColor(Color.WHITE);
            g2.drawString(askNick ? Tr.t("Ещё одно окно Minecraft", "Another Minecraft window")
                    : Tr.t("Откуда запустить", "Where to launch from"), tx, s(52));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(Tr.t("У каждой сборки свои моды, миры и настройки", "Each instance has its own mods, worlds and settings"), tx, s(76));
            String version = "Minecraft " + (cfg == null || cfg.minecraftVersion == null ? "" : cfg.minecraftVersion)
                    + (cfg != null && (cfg.commonWorld || cfg.fabricFor(cfg.minecraftVersion)) ? " · Fabric" : "");
            g2.setFont(SettingsKit.font(Font.BOLD, 13f));
            FontMetrics fm = g2.getFontMetrics();
            int bw = fm.stringWidth(version) + s(20);
            int bh = s(28);
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(tx, s(92), bw, bh, bh, bh));
            g2.setColor(SettingsKit.ACCENT);
            g2.drawString(version, tx + s(10), s(92) + (bh + fm.getAscent() - fm.getDescent()) / 2);
            g2.setFont(SettingsKit.font(Font.BOLD, 15f));
            g2.setColor(new Color(0xD5DEE8));
            g2.drawString(Tr.t("Сборка", "Instance"), p, s(140));
            g2.dispose();
        }

        /** Карточка сборки: значок папки, имя, путь, отметка выбора и кнопка «открыть папку». */
        private final class Item extends JComponent {
            private final String name;
            private boolean hover;

            Item(String name) {
                this.name = name;
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(folderOf(name).toString());
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (e.getX() > getWidth() - s(56)) {
                            Path dir = folderOf(name);
                            try {
                                Files.createDirectories(dir);
                            } catch (Exception ignored) {
                            }
                            Utils.openFolder(dir);
                            return;
                        }
                        select(name);
                        if (e.getClickCount() == 2) launch();
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
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                int w = getWidth();
                int h = getHeight();
                int r = SettingsKit.rr() + s(2);
                boolean on = selected.equals(name);
                boolean main = name.isEmpty();
                g2.setColor(on ? new Color(SettingsKit.ACTIVE.getRed(), SettingsKit.ACTIVE.getGreen(), SettingsKit.ACTIVE.getBlue(), 150)
                        : hover ? new Color(0x182535) : SettingsKit.FIELD);
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
                g2.setStroke(new BasicStroke(on ? 2f : 1f));
                g2.setColor(on ? SettingsKit.ACCENT : SettingsKit.FIELD_LINE);
                g2.draw(new RoundRectangle2D.Float(on ? 1 : 0.5f, on ? 1 : 0.5f, w - (on ? 3 : 2), h - (on ? 3 : 2), r, r));
                int box = h - s(20);
                g2.setColor(on ? SettingsKit.ACCENT : new Color(0x1B2836));
                g2.fill(new RoundRectangle2D.Float(s(10), s(10), box, box, SettingsKit.rr(), SettingsKit.rr()));
                int is = box * 55 / 100;
                SettingsKit.icon(main ? Glyph.HOME : Glyph.FOLDER, is, Color.WHITE).paintIcon(this, g2, s(10) + (box - is) / 2, s(10) + (box - is) / 2);
                int tx = s(10) + box + s(16);
                g2.setFont(SettingsKit.font(Font.BOLD, 16.5f));
                g2.setColor(Color.WHITE);
                FontMetrics fm = g2.getFontMetrics();
                String title = main ? Tr.t("Основная сборка", "Main instance") : name;
                g2.drawString(SettingsKit.ellipsize(title, fm, w - tx - s(110)), tx, h / 2 - s(2));
                g2.setFont(SettingsKit.font(Font.PLAIN, 13f));
                g2.setColor(new Color(0x9AA9BA));
                fm = g2.getFontMetrics();
                String sub = main ? Tr.t("Обычная папка игры", "The regular game folder") : folderOf(name).toString();
                g2.drawString(SettingsKit.ellipsize(sub, fm, w - tx - s(110)), tx, h / 2 + s(17));
                if (on) {
                    int cs = s(24);
                    int cx = w - s(56) - cs - s(8);
                    g2.setColor(SettingsKit.ACCENT);
                    g2.fill(new Ellipse2D.Float(cx, (h - cs) / 2f, cs, cs));
                    SettingsKit.icon(Glyph.CHECK, s(14), Color.WHITE).paintIcon(this, g2, cx + (cs - s(14)) / 2, (h - s(14)) / 2);
                }
                int fb = s(40);
                int fx = w - s(48);
                g2.setColor(hover ? new Color(0x223246) : new Color(0x16222F));
                g2.fill(new RoundRectangle2D.Float(fx, (h - fb) / 2f, fb, fb, SettingsKit.rr(), SettingsKit.rr()));
                SettingsKit.icon(Glyph.FOLDER, s(18), new Color(0xC9D3DE)).paintIcon(this, g2, fx + (fb - s(18)) / 2, (h - s(18)) / 2);
                g2.dispose();
            }
        }
    }
}
