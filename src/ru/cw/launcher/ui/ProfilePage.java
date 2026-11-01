package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.skin.PlayerSkin;
import ru.cw.launcher.skin.SkinRenderer;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.MenuItem;
import ru.cw.launcher.util.Log;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Страница «Профиль игрока»: слева список аккаунтов, справа карточка аккаунта
 * и библиотека скинов с объёмным просмотром, который можно вращать мышью.
 */
final class ProfilePage extends JPanel {

    private final MainWindow mw;
    private final LauncherApp app;
    private final JLabel title = SettingsKit.label(Tr.t("Профиль игрока", "Player profile"), 40f, Font.BOLD, SettingsKit.TEXT);
    private final JLabel subtitle = SettingsKit.label(Tr.t("Аккаунты и скины", "Accounts and skins"), 17f, Font.PLAIN,
            new Color(0xB5C2D0));
    private final Accounts accounts = new Accounts();
    private final Header header = new Header();
    private final Skins skins = new Skins();
    private static final Map<String, BufferedImage> IMAGES = new HashMap<>();

    ProfilePage(MainWindow mw, LauncherApp app) {
        super(null);
        this.mw = mw;
        this.app = app;
        setOpaque(false);
        add(title);
        add(subtitle);
        add(accounts);
        add(header);
        add(skins);
        reload();
    }

    /** Перечитывает аккаунты и скины после любого действия. */
    void reload() {
        PlayerSkin.refresh(app.account());
        accounts.fill();
        header.fill();
        skins.fill();
        revalidate();
        doLayout();
        repaint();
        mw.syncFromState();
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int x0 = s(20);
        int right = w - s(20);
        title.setBounds(x0 + s(16), s(26), w - s(420), s(54));
        subtitle.setBounds(x0 + s(18), s(80), w - s(420), s(28));
        int top = s(124);
        int bottom = h - s(18);
        int accW = s(270);
        accounts.setBounds(x0, top, accW, bottom - top);
        int rx = x0 + accW + s(14);
        int headH = s(176);
        header.setBounds(rx, top, right - rx, headH);
        skins.setBounds(rx, top + headH + s(14), right - rx, bottom - top - headH - s(14));
    }

    private static void card(Graphics2D g2, int w, int h) {
        int r = SettingsKit.radius() + s(2);
        g2.setColor(SettingsKit.card());
        g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
        g2.setColor(SettingsKit.CARD_LINE);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
    }

    private static BufferedImage image(Path file) {
        if (file == null) return null;
        try {
            String key = file.toAbsolutePath() + "|" + Files.getLastModifiedTime(file).toMillis();
            BufferedImage cached = IMAGES.get(key);
            if (cached != null) return cached;
            BufferedImage img = ImageIO.read(file.toFile());
            if (img != null) IMAGES.put(key, img);
            return img;
        } catch (Exception e) {
            Log.warn("Скин " + file.getFileName() + ": " + Log.reason(e));
            return null;
        }
    }

    private static boolean slim(Path file) {
        try {
            return file != null && PlayerSkin.slim(file);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ действия

    private void addLocal() {
        String nick = Dialogs.input(mw, Tr.t("Новый локальный аккаунт", "New local account"),
                Lang.t("prof_nick_bad"), "", Tr.t("Создать", "Create"));
        if (nick == null) return;
        if (!Account.validNick(nick)) {
            Dialogs.info(mw, Lang.t("prof_nick_bad"));
            return;
        }
        Account created = app.accounts().addOffline(nick);
        if (created == null) {
            Dialogs.info(mw, Lang.t("prof_nick_taken"));
            return;
        }
        app.selectAccountForProfile(created.id);
        reload();
    }

    private void addEly() {
        ProfileWindow.loginEly(mw, app, this::reload);
    }

    private void select(Account a) {
        app.selectAccountForProfile(a.id);
        reload();
    }

    private void rename(Account a) {
        if (a.type == Account.Type.ELY) {
            Dialogs.info(mw, Lang.t("prof_ely_name"));
            return;
        }
        String nick = Dialogs.input(mw, Tr.t("Переименовать аккаунт", "Rename account"), Lang.t("prof_nick_bad"),
                a.username, Tr.t("Сохранить", "Save"));
        if (nick == null || nick.equals(a.username)) return;
        if (!Account.validNick(nick)) {
            Dialogs.info(mw, Lang.t("prof_nick_bad"));
            return;
        }
        if (!app.accounts().renameOffline(a.id, nick)) {
            Dialogs.info(mw, Lang.t("prof_nick_taken"));
            return;
        }
        reload();
    }

    private void delete(Account a) {
        if (!Dialogs.ask(mw, Tr.t("Удалить аккаунт " + a.username + "? Его скины тоже будут удалены из лаунчера.",
                        "Delete account " + a.username + "? Its skins are removed from the launcher too."),
                Tr.t("Удалить", "Delete"), Tr.t("Отмена", "Cancel"))) return;
        app.accounts().remove(a.id);
        if (app.account() != null) app.selectAccountForProfile(app.account().id);
        reload();
    }

    private void upload() {
        Account a = app.account();
        if (a == null) return;
        FileDialog dialog = new FileDialog(mw, Tr.t("Загрузить скин Minecraft", "Upload a Minecraft skin"), FileDialog.LOAD);
        dialog.setFile("*.png");
        dialog.setVisible(true);
        if (dialog.getFile() == null || dialog.getDirectory() == null) return;
        try {
            PlayerSkin.assign(a, Path.of(dialog.getDirectory(), dialog.getFile()));
            reload();
            Dialogs.toast(mw, Tr.t("Скин добавлен и выбран", "Skin added and selected"), SettingsKit.GREEN);
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Скин не добавлен.", "The skin was not added."), Log.reason(e), null);
        }
    }

    private void createSkin() {
        Account a = app.account();
        if (a != null) mw.openSkinEditor(a, null, null, "");
    }

    private void editSkin(PlayerSkin.SkinEntry skin) {
        BufferedImage source = image(skin.file());
        if (source == null) {
            Dialogs.info(mw, Tr.t("Скин не удалось прочитать.", "The skin could not be read."));
            return;
        }
        mw.openSkinEditor(app.account(), skin.file(), source, skin.name());
    }

    private void use(PlayerSkin.SkinEntry skin) {
        try {
            PlayerSkin.choose(app.account(), skin.file());
            reload();
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Скин не выбран.", "The skin was not selected."), Log.reason(e), null);
        }
    }

    private void removeSkin(PlayerSkin.SkinEntry skin) {
        if (!Dialogs.ask(mw, Tr.t("Удалить скин «" + skin.name() + "»?", "Delete skin “" + skin.name() + "”?"),
                Tr.t("Удалить", "Delete"), Tr.t("Отмена", "Cancel"))) return;
        try {
            PlayerSkin.remove(app.account(), skin.file());
            reload();
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Скин не удалён.", "The skin was not deleted."), Log.reason(e), null);
        }
    }

    // ------------------------------------------------------------------ аккаунты

    private final class Accounts extends JPanel {
        private final JLabel head = SettingsKit.label(Tr.t("Аккаунты", "Accounts"), 20f, Font.BOLD, SettingsKit.TEXT);
        private final JPanel list = new JPanel(null);
        private final JScrollPane scroll = new JScrollPane(list);
        private final Button add = new Button(Tr.t("Добавить аккаунт", "Add account"), Glyph.PLUS, Kind.GHOST);

        Accounts() {
            super(null);
            setOpaque(false);
            list.setOpaque(false);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(scroll);
            add(head);
            add(scroll);
            add(add);
            add.addActionListener(e -> {
                JPopupMenu m = SettingsKit.menu(add.getWidth(),
                        new MenuItem(Tr.t("Локальный аккаунт", "Local account"), Glyph.USER, ProfilePage.this::addLocal),
                        new MenuItem(Tr.t("Войти через Ely.by", "Sign in with Ely.by"), Glyph.LINK, ProfilePage.this::addEly));
                m.show(add, 0, add.getHeight() + s(4));
            });
        }

        void fill() {
            list.removeAll();
            Account active = app.account();
            int y = 0;
            for (Account a : app.accounts().all()) {
                Row r = new Row(a, active != null && a.id.equals(active.id));
                list.add(r);
                r.setBounds(0, y, 10, s(70));
                y += s(78);
            }
            list.setPreferredSize(new Dimension(10, Math.max(0, y)));
            doLayout();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(14);
            head.setBounds(p + s(4), s(16), w - p * 2, s(30));
            int listTop = s(58);
            int listH = list.getPreferredSize().height;
            int maxH = h - listTop - s(80);
            scroll.setBounds(p, listTop, w - p * 2, Math.min(listH, Math.max(s(80), maxH)) + 2);
            for (Component c : list.getComponents()) c.setSize(w - p * 2 - s(4), c.getHeight());
            add.setBounds(p, scroll.getY() + scroll.getHeight() + s(10), w - p * 2, s(48));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            g2.dispose();
        }
    }

    /** Строка аккаунта: голова скина, ник, тип и меню «⋮». */
    private final class Row extends JComponent {
        private final Account a;
        private final boolean active;
        private boolean hover;

        Row(Account a, boolean active) {
            this.a = a;
            this.active = active;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(active ? null : Tr.t("Сделать активным", "Make active"));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.getX() > getWidth() - s(40) || SwingUtilities.isRightMouseButton(e)) {
                        List<MenuItem> items = new java.util.ArrayList<>();
                        if (!active) items.add(new MenuItem(Tr.t("Сделать активным", "Make active"), Glyph.CHECK, () -> select(a)));
                        if (a.type != Account.Type.ELY) items.add(new MenuItem(Tr.t("Переименовать", "Rename"), Glyph.BRUSH, () -> rename(a)));
                        items.add(new MenuItem(Tr.t("Удалить аккаунт", "Delete account"), Glyph.TRASH, () -> delete(a), true));
                        JPopupMenu m = SettingsKit.menu(s(230), items.toArray(new MenuItem[0]));
                        m.show(Row.this, getWidth() - s(230), getHeight());
                        return;
                    }
                    if (!active) select(a);
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
            if (active) {
                g2.setColor(new Color(SettingsKit.ACTIVE.getRed(), SettingsKit.ACTIVE.getGreen(), SettingsKit.ACTIVE.getBlue(), 120));
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
                g2.setColor(SettingsKit.ACCENT);
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r));
            } else if (hover) {
                g2.setColor(new Color(0x16222F));
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            }
            int face = h - s(22);
            int fy = (h - face) / 2;
            BufferedImage head = PlayerSkin.headOfAccount(a);
            Shape box = new RoundRectangle2D.Float(s(12), fy, face, face, s(8), s(8));
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            if (head != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                Shape old = g2.getClip();
                g2.clip(box);
                g2.drawImage(head, s(12), fy, face, face, null);
                g2.setClip(old);
            } else {
                int is = face - s(16);
                SettingsKit.icon(Glyph.USER, is, new Color(0x8FA0B3)).paintIcon(this, g2, s(12) + s(8), fy + s(8));
            }
            int tx = s(12) + face + s(14);
            g2.setFont(SettingsKit.font(Font.BOLD, 17f));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Color.WHITE);
            String name = SettingsKit.ellipsize(a.username, fm, w - tx - s(active ? 120 : 40));
            g2.drawString(name, tx, h / 2 - s(2));
            if (active) {
                g2.setFont(SettingsKit.font(Font.BOLD, 12f));
                FontMetrics bf = g2.getFontMetrics();
                String badge = Tr.t("Активный", "Active");
                int bx = tx + fm.stringWidth(name) + s(10);
                int bw = bf.stringWidth(badge) + s(16);
                int bh = s(22);
                int by = h / 2 - s(2) - fm.getAscent() + (fm.getAscent() - bh) / 2 + s(2);
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
                g2.setColor(Color.WHITE);
                g2.drawString(badge, bx + s(8), by + (bh + bf.getAscent() - bf.getDescent()) / 2);
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(a.type == Account.Type.ELY ? "Ely.by" : Tr.t("Локальный аккаунт", "Local account"), tx, h / 2 + s(18));
            g2.setColor(new Color(0x9AA9BA));
            int dx = w - s(22);
            for (int i = -1; i <= 1; i++) g2.fill(new Ellipse2D.Float(dx, h / 2f + i * s(6) - s(2), s(4), s(4)));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ карточка аккаунта

    private final class Header extends JPanel {
        private final JLabel name = SettingsKit.label("", 30f, Font.BOLD, Color.WHITE);
        private final JLabel uuidCaption = SettingsKit.label("UUID", 14f, Font.PLAIN, new Color(0xB5C2D0));
        private final JTextField uuid = SettingsKit.field("");
        private final Button copy = new Button("", Glyph.COPY, Kind.FLAT).size(40, 40).iconSize(18);
        private final Button switchAcc = new Button(Tr.t("Сменить аккаунт", "Switch account"), Glyph.RESET, Kind.GHOST);
        private final Button rename = new Button(Tr.t("Переименовать", "Rename"), Glyph.BRUSH, Kind.GHOST);
        private final Button addLocal = new Button(Tr.t("Локальный аккаунт", "Local account"), Glyph.PLUS, Kind.PRIMARY);
        private final Button addEly = new Button(Tr.t("Войти через Ely.by", "Sign in with Ely.by"), Glyph.LINK, Kind.GHOST);
        private Account account;

        Header() {
            super(null);
            setOpaque(false);
            uuid.setEditable(false);
            uuid.setFont(Theme.MONO.deriveFont(SettingsKit.sf(14f)));
            for (JComponent c : new JComponent[]{name, uuidCaption, uuid, copy, switchAcc, rename, addLocal, addEly}) add(c);
            copy.setToolTipText(Tr.t("Скопировать UUID", "Copy UUID"));
            copy.addActionListener(e -> {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(uuid.getText()), null);
                Dialogs.toast(mw, Tr.t("UUID скопирован", "UUID copied"), SettingsKit.GREEN);
            });
            switchAcc.addActionListener(e -> {
                List<MenuItem> items = new java.util.ArrayList<>();
                for (Account a : app.accounts().all()) {
                    if (account != null && a.id.equals(account.id)) continue;
                    items.add(new MenuItem(a.username + (a.type == Account.Type.ELY ? " · Ely.by" : ""), Glyph.USER, () -> select(a)));
                }
                items.add(new MenuItem(Tr.t("Добавить аккаунт", "Add account"), Glyph.PLUS, ProfilePage.this::addLocal));
                JPopupMenu m = SettingsKit.menu(switchAcc.getWidth() + s(40), items.toArray(new MenuItem[0]));
                m.show(switchAcc, -s(40), switchAcc.getHeight() + s(4));
            });
            rename.addActionListener(e -> {
                if (account != null) ProfilePage.this.rename(account);
            });
            addLocal.addActionListener(e -> ProfilePage.this.addLocal());
            addEly.addActionListener(e -> ProfilePage.this.addEly());
        }

        void fill() {
            account = app.account();
            boolean has = account != null;
            name.setText(has ? account.username : Tr.t("Нет аккаунта", "No account"));
            uuid.setText(has ? account.uuid : "");
            for (JComponent c : new JComponent[]{uuidCaption, uuid, copy, switchAcc, rename}) c.setVisible(has);
            addLocal.setVisible(!has);
            addEly.setVisible(!has);
            rename.setEnabled(has && account.type != Account.Type.ELY);
            rename.setToolTipText(has && account.type == Account.Type.ELY ? Lang.t("prof_ely_name") : null);
            doLayout();
            repaint();
        }

        private int face() {
            return getHeight() - s(40);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int p = s(20);
            int fx = p + face() + s(26);
            int bw = s(210);
            int textRight = w - p - bw - s(20);
            name.setBounds(fx, s(18), textRight - fx, s(44));
            uuidCaption.setBounds(fx, s(104), s(200), s(20));
            uuid.setBounds(fx, s(126), Math.max(s(200), Math.min(s(440), textRight - fx - s(48))), s(38));
            copy.setBounds(uuid.getX() + uuid.getWidth() + s(6), s(125), s(40), s(40));
            switchAcc.setBounds(w - p - bw, s(28), bw, s(52));
            rename.setBounds(w - p - bw, s(92), bw, s(52));
            addLocal.setBounds(fx, s(92), s(230), s(52));
            addEly.setBounds(fx + s(244), s(92), s(250), s(52));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            int p = s(20);
            int f = face();
            Shape box = new RoundRectangle2D.Float(p, p, f, f, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            BufferedImage head = account == null ? null : PlayerSkin.headOfAccount(account);
            if (head != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                Shape old = g2.getClip();
                g2.clip(box);
                g2.drawImage(head, p, p, f, f, null);
                g2.setClip(old);
            } else {
                int is = f * 55 / 100;
                SettingsKit.icon(Glyph.USER, is, new Color(0x8FA0B3)).paintIcon(this, g2, p + (f - is) / 2, p + (f - is) / 2);
            }
            if (account != null) {
                int fx = p + f + s(26);
                String badge = account.type == Account.Type.ELY ? "Ely.by" : Tr.t("Локальный аккаунт", "Local account");
                g2.setFont(SettingsKit.font(Font.BOLD, 15f));
                FontMetrics fm = g2.getFontMetrics();
                int bh = s(34);
                int bw = fm.stringWidth(badge) + s(52);
                int by = s(64);
                g2.setColor(new Color(0x1B2836));
                g2.fill(new RoundRectangle2D.Float(fx, by, bw, bh, SettingsKit.rr(), SettingsKit.rr()));
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new Ellipse2D.Float(fx + s(10), by + (bh - s(20)) / 2f, s(20), s(20)));
                SettingsKit.icon(account.type == Account.Type.ELY ? Glyph.LAYERS : Glyph.USER, s(12), Color.WHITE)
                        .paintIcon(this, g2, fx + s(14), by + (bh - s(12)) / 2);
                g2.setColor(Color.WHITE);
                g2.drawString(badge, fx + s(40), by + (bh + fm.getAscent() - fm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ скины

    private final class Skins extends JPanel {
        private final JLabel head = SettingsKit.label(Tr.t("Скины", "Skins"), 22f, Font.BOLD, SettingsKit.TEXT);
        private final Button upload = new Button(Tr.t("Загрузить скин", "Upload skin"), Glyph.PLUS, Kind.PRIMARY);
        private final Button create = new Button(Tr.t("Создать скин", "Create skin"), Glyph.BRUSH, Kind.GHOST);
        private final Button reorder = new Button(Tr.t("Изменить порядок", "Reorder"), Glyph.LAYERS, Kind.GHOST);
        private final Button deleteAcc = new Button(Tr.t("Удалить аккаунт", "Delete account"), Glyph.TRASH, Kind.DANGER);
        private final Preview preview = new Preview();
        private final JPanel grid = new JPanel(null);
        private final JScrollPane scroll = new JScrollPane(grid);
        private final JLabel empty = SettingsKit.label("", 15.5f, Font.PLAIN, new Color(0xB5C2D0));
        private boolean ordering;
        private Tile dragged;

        Skins() {
            super(null);
            setOpaque(false);
            grid.setOpaque(false);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setViewportBorder(null);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(s(30));
            for (JComponent c : new JComponent[]{head, upload, create, reorder, deleteAcc, preview, scroll, empty}) add(c);
            upload.setFont(SettingsKit.font(Font.BOLD, 16f));
            upload.addActionListener(e -> upload());
            create.addActionListener(e -> createSkin());
            reorder.addActionListener(e -> {
                ordering = !ordering;
                reorder.setText(ordering ? Tr.t("Готово", "Done") : Tr.t("Изменить порядок", "Reorder"));
                for (Component c : grid.getComponents()) if (c instanceof Tile t) t.updateCursor();
                repaint();
            });
            deleteAcc.addActionListener(e -> {
                if (app.account() != null) delete(app.account());
            });
            scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    layoutGrid();
                }
            });
        }

        void fill() {
            grid.removeAll();
            Account a = app.account();
            boolean has = a != null;
            upload.setVisible(has);
            create.setVisible(has);
            deleteAcc.setVisible(has);
            List<PlayerSkin.SkinEntry> list = has ? PlayerSkin.all(a) : List.of();
            reorder.setVisible(has && list.size() > 1);
            PlayerSkin.SkinEntry used = null;
            for (PlayerSkin.SkinEntry skin : list) {
                grid.add(new Tile(skin));
                if (skin.selected()) used = skin;
            }
            if (used == null && !list.isEmpty()) used = list.get(0);
            preview.show(used == null ? null : used.file());
            empty.setText(!has ? Tr.t("Создайте аккаунт, чтобы добавить скины.", "Create an account to add skins.")
                    : list.isEmpty() ? Tr.t("Скинов пока нет. Нажмите «Загрузить скин».", "No skins yet. Press “Upload skin”.") : "");
            empty.setVisible(!empty.getText().isEmpty());
            doLayout();
            layoutGrid();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(20);
            head.setBounds(p, s(18), s(300), s(40));
            int bw = s(190);
            upload.setBounds(w - p - bw, s(14), bw, s(50));
            create.setBounds(upload.getX() - s(12) - bw, s(14), bw, s(50));
            reorder.setBounds(create.getX() - s(12) - s(210), s(14), s(210), s(50));
            int top = s(78);
            int footer = s(76);
            int bodyH = h - top - footer - s(14);
            int pw = Math.min(s(330), Math.max(s(220), (w - p * 2) * 34 / 100));
            preview.setBounds(p, top, pw, bodyH);
            int gx = p + pw + s(16);
            scroll.setBounds(gx, top, w - p - gx, bodyH);
            empty.setBounds(gx + s(6), top + s(10), w - p - gx, s(30));
            deleteAcc.setBounds(p, h - footer + s(14), s(220), s(48));
            layoutGrid();
        }

        private void move(Tile tile, Point screen) {
            Point p = SwingUtilities.convertPoint(tile, screen, grid);
            Component[] cs = grid.getComponents();
            int from = -1;
            for (int i = 0; i < cs.length; i++) if (cs[i] == tile) from = i;
            if (from < 0) return;
            int target = from;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < cs.length; i++) {
                Rectangle b = cs[i].getBounds();
                double d = Point.distance(p.x, p.y, b.getCenterX(), b.getCenterY());
                if (d < best) { best = d; target = i; }
            }
            if (target == from) return;
            grid.setComponentZOrder(tile, target);
            layoutGrid();
        }

        private void saveOrder() {
            List<Path> files = new ArrayList<>();
            for (Component c : grid.getComponents()) if (c instanceof Tile t) files.add(t.entry.file());
            try {
                PlayerSkin.saveOrder(app.account(), files);
            } catch (Exception e) {
                Dialogs.error(mw, Tr.t("Порядок не сохранён.", "The order was not saved."), Log.reason(e), null);
            }
        }

        /** Плитки переносятся по ширине, как в проводнике. */
        private void layoutGrid() {
            int w = scroll.getViewport().getWidth();
            if (w <= 0) w = scroll.getWidth();
            int tw = s(140);
            int th = s(230);
            int gap = s(12);
            int cols = Math.max(1, (w + gap) / (tw + gap));
            int i = 0;
            for (Component c : grid.getComponents()) {
                int col = i % cols;
                int row = i / cols;
                c.setBounds(col * (tw + gap), row * (th + gap), tw, th);
                i++;
            }
            int rows = (grid.getComponentCount() + cols - 1) / cols;
            grid.setPreferredSize(new Dimension(w, Math.max(0, rows * (th + gap) - gap)));
            grid.revalidate();
            grid.repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            int y = getHeight() - s(76);
            g2.setColor(SettingsKit.CARD_LINE);
            g2.drawLine(s(20), y, getWidth() - s(20), y);
            g2.dispose();
        }
    }

    /** Большой объёмный просмотр выбранного скина. Тянуть мышью — поворот, двойной щелчок — анфас. */
    private final class Preview extends JComponent {
        private BufferedImage skin;
        private boolean slimArms;
        private double yaw = -0.45;
        private double pitch = -0.12;
        private double zoom = 1;
        private Point press;
        private double pressYaw;
        private double pressPitch;

        Preview() {
            setCursor(SettingsKit.grab(false));
            setToolTipText(Tr.t("Зажмите и тяните в любую сторону · колесо — масштаб", "Drag in any direction · wheel — zoom"));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    press = e.getPoint();
                    pressYaw = yaw;
                    pressPitch = pitch;
                    setCursor(SettingsKit.grab(true));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (press == null) return;
                    yaw = pressYaw + (e.getX() - press.x) * 0.012;
                    pitch = Math.max(-1.45, Math.min(1.45, pressPitch - (e.getY() - press.y) * 0.012));
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    press = null;
                    setCursor(SettingsKit.grab(false));
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        yaw = 0;
                        pitch = 0;
                        repaint();
                    }
                }


                @Override
                public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                    zoom = Math.max(0.6, Math.min(2.2, zoom * (e.getWheelRotation() < 0 ? 1.1 : 1 / 1.1)));
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        void show(Path file) {
            skin = image(file);
            slimArms = slim(file);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.rr() + s(2);
            Shape clip = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r);
            g2.setPaint(new RadialGradientPaint(new java.awt.geom.Point2D.Float(w / 2f, h * 0.35f), Math.max(w, h) * 0.75f,
                    new float[]{0f, 1f}, new Color[]{new Color(0x3A4656), new Color(0x10171F)}));
            g2.fill(clip);
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(clip);
            if (skin == null) {
                g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
                g2.setColor(new Color(0x8FA0B3));
                String t = Tr.t("Нет скина", "No skin");
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(t, (w - fm.stringWidth(t)) / 2, h / 2);
                g2.dispose();
                return;
            }
            int fh = (int) (Math.min(h * 0.80, w * 1.55) * zoom);
            int top = (h - fh) / 2 - s(6);
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fill(new Ellipse2D.Float(w / 2f - fh * 0.2f, top + fh - fh * 0.025f, fh * 0.4f, fh * 0.06f));
            g2.clip(clip);
            SkinRenderer.paint(g2, skin, slimArms, yaw, pitch, w / 2, top + fh / 2, fh,
                    SkinRenderer.BASE | SkinRenderer.OUTER);
            g2.dispose();
        }
    }

    /** Плитка скина: фигура, имя файла без расширения, отметка «Используется». */
    private final class Tile extends JComponent {
        private final PlayerSkin.SkinEntry entry;
        private final BufferedImage skin;
        private final boolean slimArms;
        private boolean hover;

        Tile(PlayerSkin.SkinEntry entry) {
            this.entry = entry;
            this.skin = image(entry.file());
            this.slimArms = slim(entry.file());
            updateCursor();
            setToolTipText(entry.name());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (skins.ordering && SwingUtilities.isLeftMouseButton(e)) {
                        skins.dragged = Tile.this;
                        return;
                    }
                    boolean trash = hover && e.getX() > getWidth() - s(40) && e.getY() < s(40);
                    if (SwingUtilities.isRightMouseButton(e)) {
                        JPopupMenu m = SettingsKit.menu(s(220),
                                new MenuItem(Tr.t("Использовать", "Use"), Glyph.CHECK, () -> use(entry)),
                                new MenuItem(Tr.t("Изменить скин", "Edit skin"), Glyph.BRUSH, () -> editSkin(entry)),
                                new MenuItem(Tr.t("Удалить скин", "Delete skin"), Glyph.TRASH, () -> removeSkin(entry), true));
                        m.show(Tile.this, e.getX(), e.getY());
                    } else if (trash) {
                        removeSkin(entry);
                    } else if (!entry.selected()) {
                        use(entry);
                    } else {
                        skins.preview.show(entry.file());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (skins.dragged == Tile.this) {
                        skins.dragged = null;
                        skins.saveOrder();
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    skins.preview.show(entry.file());
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    PlayerSkin.SkinEntry used = null;
                    for (PlayerSkin.SkinEntry s : PlayerSkin.all(app.account())) if (s.selected()) used = s;
                    skins.preview.show(used == null ? entry.file() : used.file());
                    repaint();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (skins.ordering && skins.dragged == Tile.this) skins.move(Tile.this, e.getPoint());
                }
            });
        }

        void updateCursor() {
            setCursor(skins.ordering ? SettingsKit.grab(false) : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int nameH = s(34);
            int h = getHeight() - nameH;
            int r = SettingsKit.rr() + s(2);
            Shape box = new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r);
            g2.setColor(entry.selected() ? new Color(0x14243A) : hover ? new Color(0x182535) : new Color(0x121C28));
            g2.fill(box);
            g2.setStroke(new BasicStroke(entry.selected() ? 2.5f : 1f));
            g2.setColor(entry.selected() ? SettingsKit.ACCENT : hover ? SettingsKit.FIELD_HOVER : SettingsKit.CARD_LINE);
            g2.draw(box);
            if (skin != null) {
                int fh = (int) (h * 0.80);
                Shape old = g2.getClip();
                g2.clip(box);
                SkinRenderer.paint(g2, skin, slimArms, 0, w / 2, (h - fh) / 2 - s(4), fh);
                g2.setClip(old);
            }
            if (entry.selected()) {
                g2.setFont(SettingsKit.font(Font.BOLD, 12.5f));
                FontMetrics fm = g2.getFontMetrics();
                String t = Tr.t("Используется", "In use");
                int bw = fm.stringWidth(t) + s(18);
                int bh = s(24);
                int bx = (w - bw) / 2;
                int by = h - bh - s(10);
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
                g2.setColor(Color.WHITE);
                g2.drawString(t, bx + s(9), by + (bh + fm.getAscent() - fm.getDescent()) / 2);
            }
            if (hover) {
                int bs = s(30);
                int bx = w - bs - s(8);
                g2.setColor(new Color(0x26141A));
                g2.fill(new RoundRectangle2D.Float(bx, s(8), bs, bs, s(8), s(8)));
                SettingsKit.icon(Glyph.TRASH, s(18), new Color(0xFF8A93)).paintIcon(this, g2, bx + (bs - s(18)) / 2, s(8) + (bs - s(18)) / 2);
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xDCE4EC));
            FontMetrics fm = g2.getFontMetrics();
            String name = SettingsKit.ellipsize(entry.name(), fm, w - s(8));
            g2.drawString(name, (w - fm.stringWidth(name)) / 2, getHeight() - s(10));
            g2.dispose();
        }
    }
}
