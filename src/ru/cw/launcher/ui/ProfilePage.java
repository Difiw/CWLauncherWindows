package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.skin.ElySkin;
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
        // Скин с Ely.by догружается в фоне; при свежем кэше запроса нет вовсе.
        app.refreshElySkin(app.account(), false, this::afterElySkin);
    }

    /** Ответ Ely пришёл: перерисовать аватары и скины, не трогая остальное. */
    private void afterElySkin() {
        if (!isDisplayable()) return;
        PlayerSkin.refresh(app.account());
        accounts.repaint();
        header.repaint();
        skins.fill();
    }

    private void refreshEly() {
        Account a = app.account();
        if (!ElySkin.applies(a)) return;
        app.refreshElySkin(a, true, () -> {
            afterElySkin();
            Account now = app.account();
            if (!isDisplayable() || now == null || !a.id.equals(now.id)) return;
            if (ElySkin.state(a) == ElySkin.State.ERROR) {
                Dialogs.toast(mw, ElySkin.error(a), SettingsKit.RED);
            } else {
                Dialogs.toast(mw, ElySkin.state(a) == ElySkin.State.NONE
                        ? Tr.t("На Ely.by скин не установлен", "No skin is set on Ely.by")
                        : Tr.t("Скин Ely.by обновлён", "Ely.by skin updated"), SettingsKit.GREEN);
            }
        });
        skins.fill();
    }

    private void useEly() {
        try {
            PlayerSkin.chooseEly(app.account());
            reload();
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Скин не выбран.", "The skin was not selected."), Log.reason(e), null);
        }
    }

    /** Копия скина с Ely.by открывается в редакторе как новый локальный скин. */
    private void editEly() {
        ElySkin.Entry ely = ElySkin.cached(app.account());
        if (ely == null) return;
        mw.openSkinEditor(app.account(), null, ely.image(), "ely-" + app.account().username);
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
            // Ник Ely.by хранится на сервере Ely: лаунчер его не меняет, но объясняет почему.
            if (Dialogs.ask(mw, Tr.t("Ник аккаунта Ely.by меняется только на сайте Ely.by, лаунчер его изменить не может. "
                            + "После смены ника войдите в аккаунт заново.",
                    "An Ely.by name can only be changed on the Ely.by site. Sign in again after changing it."),
                    Tr.t("Открыть Ely.by", "Open Ely.by"), Tr.t("Закрыть", "Close"))) {
                ru.cw.launcher.util.Utils.openUrl("https://account.ely.by/");
            }
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
                // Голова в строке у неактивных Ely-аккаунтов тоже берётся с Ely.by.
                if (!r.active) ElySkin.request(a, false, list::repaint);
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
        private final Button deleteAcc = new Button(Tr.t("Удалить аккаунт", "Delete account"), Glyph.TRASH, Kind.DANGER);
        private final Button addLocal = new Button(Tr.t("Локальный аккаунт", "Local account"), Glyph.PLUS, Kind.PRIMARY);
        private final Button addEly = new Button(Tr.t("Войти через Ely.by", "Sign in with Ely.by"), Glyph.LINK, Kind.GHOST);
        private Account account;

        Header() {
            super(null);
            setOpaque(false);
            uuid.setEditable(false);
            uuid.setFont(Theme.MONO.deriveFont(SettingsKit.sf(14f)));
            for (JComponent c : new JComponent[]{name, uuidCaption, uuid, copy, switchAcc, rename, deleteAcc, addLocal, addEly}) add(c);
            deleteAcc.addActionListener(e -> {
                if (account != null) delete(account);
            });
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
            for (JComponent c : new JComponent[]{uuidCaption, uuid, copy, switchAcc, rename, deleteAcc}) c.setVisible(has);
            addLocal.setVisible(!has);
            addEly.setVisible(!has);
            // У Ely кнопка не гаснет молча: по нажатию объясняется, где меняется ник.
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
            // Все действия с аккаунтом — одной колонкой справа, удаление последним.
            int bh = s(42);
            int gap = s(8);
            int by = (getHeight() - bh * 3 - gap * 2) / 2;
            switchAcc.setBounds(w - p - bw, by, bw, bh);
            rename.setBounds(w - p - bw, by + bh + gap, bw, bh);
            deleteAcc.setBounds(w - p - bw, by + (bh + gap) * 2, bw, bh);
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

    /** Что сейчас в большом просмотре: картинка, модель и подпись снизу. */
    private record Shown(BufferedImage image, boolean slim, String caption, String note, String empty) {
    }

    private static String model(boolean slim) {
        return slim ? "Alex" : "Steve";
    }

    private final class Skins extends JPanel {
        private final JLabel head = SettingsKit.label(Tr.t("Скины", "Skins"), 22f, Font.BOLD, SettingsKit.TEXT);
        private final Button upload = new Button(Tr.t("Загрузить скин", "Upload skin"), Glyph.PLUS, Kind.PRIMARY);
        private final Button create = new Button(Tr.t("Создать скин", "Create skin"), Glyph.BRUSH, Kind.GHOST);
        private final Button refresh = new Button(Tr.t("Обновить скин", "Refresh skin"), Glyph.RESET, Kind.GHOST);
        private final Button reorder = new Button(Tr.t("Изменить порядок", "Reorder"), Glyph.LAYERS, Kind.GHOST);
        private final Preview preview = new Preview();
        private final JPanel grid = new JPanel(null);
        private final JScrollPane scroll = new JScrollPane(grid);
        private final JLabel empty = SettingsKit.label("", 15.5f, Font.PLAIN, new Color(0xB5C2D0));
        private final JLabel hint = SettingsKit.label(Tr.t(
                "Выбранный свой скин при запуске игры записывается на Ely.by.",
                "A selected custom skin is uploaded to Ely.by when the game starts."),
                13f, Font.PLAIN, SettingsKit.MUTED);
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
            empty.setHorizontalAlignment(SwingConstants.CENTER);
            empty.setVerticalAlignment(SwingConstants.CENTER);
            for (JComponent c : new JComponent[]{head, upload, create, refresh, reorder, preview, scroll, empty, hint}) add(c);
            upload.setFont(SettingsKit.font(Font.BOLD, 16f));
            upload.addActionListener(e -> upload());
            create.addActionListener(e -> createSkin());
            refresh.setToolTipText(Tr.t("Заново скачать скин с Ely.by", "Download the skin from Ely.by again"));
            refresh.addActionListener(e -> refreshEly());
            reorder.addActionListener(e -> {
                ordering = !ordering;
                reorder.setText(ordering ? Tr.t("Готово", "Done") : Tr.t("Изменить порядок", "Reorder"));
                for (Component c : grid.getComponents()) if (c instanceof Tile t) t.updateCursor();
                repaint();
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
            boolean ely = ElySkin.applies(a);
            boolean loading = ely && ElySkin.state(a) == ElySkin.State.LOADING;
            upload.setVisible(has);
            create.setVisible(has);
            refresh.setVisible(ely);
            hint.setVisible(ely);
            refresh.setEnabled(!loading);
            refresh.setText(loading ? Tr.t("Загрузка…", "Loading…") : Tr.t("Обновить скин", "Refresh skin"));
            List<PlayerSkin.SkinEntry> list = has ? PlayerSkin.all(a) : List.of();
            reorder.setVisible(has && list.size() > 1);
            if (ely) grid.add(new Tile(null));
            for (PlayerSkin.SkinEntry skin : list) grid.add(new Tile(skin));
            preview.show(used());
            empty.setText(!has ? Tr.t("Создайте аккаунт, чтобы добавить скины.", "Create an account to add skins.")
                    : grid.getComponentCount() == 0
                    ? "<html><div style='text-align:center'>" + Tr.t("Скинов пока нет", "No skins yet")
                    + "<br><span style='color:#8FA0B3'>" + Tr.t("Загрузите PNG 64×64 или нарисуйте скин в редакторе.",
                    "Upload a 64×64 PNG or draw one in the editor.") + "</span></div></html>"
                    : "");
            empty.setVisible(!empty.getText().isEmpty());
            doLayout();
            layoutGrid();
            repaint();
        }

        /** Скин, в котором аккаунт сейчас играет. */
        Shown used() {
            Account a = app.account();
            if (a == null) return new Shown(null, false, "", "", Tr.t("Нет аккаунта", "No account"));
            for (PlayerSkin.SkinEntry s : PlayerSkin.all(a)) {
                if (s.selected() && !PlayerSkin.usesEly(a)) return local(s);
            }
            if (ElySkin.applies(a)) return ely(a);
            return new Shown(null, false, "", "", Tr.t("Нет скина", "No skin"));
        }

        Shown local(PlayerSkin.SkinEntry s) {
            boolean slim = slim(s.file());
            return new Shown(image(s.file()), slim, s.name() + " · " + model(slim), "", Tr.t("Скин не читается", "Unreadable skin"));
        }

        Shown ely(Account a) {
            ElySkin.Entry e = ElySkin.cached(a);
            ElySkin.State st = ElySkin.state(a);
            String note = st == ElySkin.State.ERROR && e != null
                    ? Tr.t("Не удалось обновить, показан сохранённый скин", "Update failed, showing the saved skin")
                    : st == ElySkin.State.LOADING && e != null ? Tr.t("Проверяем обновления…", "Checking for updates…") : "";
            String none = switch (st) {
                case LOADING, IDLE -> Tr.t("Загрузка скина с Ely.by…", "Loading the Ely.by skin…");
                case NONE -> Tr.t("На Ely.by скин не установлен", "No skin is set on Ely.by");
                case ERROR -> ElySkin.error(a);
                case READY -> Tr.t("Скин не прочитан", "The skin could not be read");
            };
            return e == null ? new Shown(null, false, "Ely.by", "", none)
                    : new Shown(e.image(), e.slim(), "Ely.by · " + model(e.slim()), note, "");
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(20);
            head.setBounds(p, s(18), s(200), s(40));
            int bw = s(190);
            int x = w - p;
            for (Button b : new Button[]{upload, create, refresh, reorder}) {
                if (!b.isVisible()) continue;
                int width = b == reorder ? s(210) : b == refresh ? s(180) : bw;
                x -= width;
                b.setBounds(x, s(14), width, s(50));
                x -= s(12);
            }
            int top = s(78);
            int bodyH = h - top - p;
            int pw = Math.min(s(340), Math.max(s(240), (w - p * 2) * 34 / 100));
            preview.setBounds(p, top, pw, bodyH);
            int gx = p + pw + s(16);
            int hintH = hint.isVisible() ? s(28) : 0;
            scroll.setBounds(gx, top, w - p - gx, bodyH - hintH);
            empty.setBounds(gx, top, w - p - gx, bodyH - hintH);
            hint.setBounds(gx + s(2), top + bodyH - s(20), w - p - gx, s(20));
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
            // Плитка Ely.by всегда первая и не переставляется.
            if (target == from || (cs[target] instanceof Tile t && t.entry == null)) return;
            grid.setComponentZOrder(tile, target);
            layoutGrid();
        }

        private void saveOrder() {
            List<Path> files = new ArrayList<>();
            for (Component c : grid.getComponents()) if (c instanceof Tile t && t.entry != null) files.add(t.entry.file());
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
            g2.dispose();
        }
    }

    /** Большой объёмный просмотр выбранного скина. Тянуть мышью — поворот, двойной щелчок — анфас. */
    private final class Preview extends JComponent {
        private Shown shown = new Shown(null, false, "", "", "");
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

        void show(Shown s) {
            shown = s == null ? new Shown(null, false, "", "", "") : s;
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
            BufferedImage skin = shown.image();
            if (skin == null) {
                int is = s(56);
                SettingsKit.icon(Glyph.USER, is, new Color(0x5D6E82)).paintIcon(this, g2, (w - is) / 2, h / 2 - is - s(14));
                g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
                g2.setColor(new Color(0x8FA0B3));
                FontMetrics fm = g2.getFontMetrics();
                int y = h / 2 + s(12);
                for (String line : wrap(shown.empty(), fm, w - s(40))) {
                    g2.drawString(line, (w - fm.stringWidth(line)) / 2, y);
                    y += fm.getHeight();
                }
                g2.dispose();
                return;
            }
            int capH = shown.caption().isEmpty() ? 0 : s(shown.note().isEmpty() ? 44 : 64);
            int area = h - capH;
            int fh = (int) (Math.min(area * 0.84, w * 1.55) * zoom);
            int top = (area - fh) / 2;
            g2.setColor(new Color(0, 0, 0, 90));
            g2.fill(new Ellipse2D.Float(w / 2f - fh * 0.2f, top + fh - fh * 0.025f, fh * 0.4f, fh * 0.06f));
            Graphics2D fig = (Graphics2D) g2.create();
            fig.clip(new Rectangle(0, 0, w, area));
            fig.clip(clip);
            SkinRenderer.paint(fig, skin, shown.slim(), yaw, pitch, w / 2, top + fh / 2, fh,
                    SkinRenderer.BASE | SkinRenderer.OUTER);
            fig.dispose();
            if (capH > 0) {
                g2.setFont(SettingsKit.font(Font.BOLD, 14f));
                FontMetrics fm = g2.getFontMetrics();
                String cap = SettingsKit.ellipsize(shown.caption(), fm, w - s(40));
                int y = h - capH + s(26);
                g2.setColor(new Color(0xDCE4EC));
                g2.drawString(cap, (w - fm.stringWidth(cap)) / 2, y);
                if (!shown.note().isEmpty()) {
                    g2.setFont(SettingsKit.font(Font.PLAIN, 12.5f));
                    FontMetrics nf = g2.getFontMetrics();
                    String note = SettingsKit.ellipsize(shown.note(), nf, w - s(24));
                    g2.setColor(SettingsKit.AMBER);
                    g2.drawString(note, (w - nf.stringWidth(note)) / 2, y + s(20));
                }
            }
            g2.dispose();
        }
    }

    /** Разбивает подсказку на строки по ширине. */
    private static List<String> wrap(String text, FontMetrics fm, int max) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text).split("\\s+")) {
            if (word.isEmpty()) continue;
            String next = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(next) > max && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    /**
     * Плитка скина: фигура, имя без расширения, отметка «Используется».
     * entry == null — скин с Ely.by: его нельзя удалить, но можно выбрать или обновить.
     */
    private final class Tile extends JComponent {
        private final PlayerSkin.SkinEntry entry;
        private final Shown shown;
        private final boolean selected;
        private final String label;
        private boolean hover;

        Tile(PlayerSkin.SkinEntry entry) {
            this.entry = entry;
            Account a = app.account();
            boolean usesEly = PlayerSkin.usesEly(a);
            this.shown = entry == null ? skins.ely(a) : skins.local(entry);
            this.selected = entry == null ? usesEly : entry.selected() && !usesEly;
            this.label = entry == null ? "Ely.by" : entry.name();
            updateCursor();
            setToolTipText(entry == null ? Tr.t("Скин, установленный на Ely.by", "The skin set on Ely.by") : entry.name());
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (skins.ordering && SwingUtilities.isLeftMouseButton(e)) {
                        if (entry != null) skins.dragged = Tile.this;
                        return;
                    }
                    boolean trash = entry != null && hover && e.getX() > getWidth() - s(40) && e.getY() < s(40);
                    if (SwingUtilities.isRightMouseButton(e)) {
                        List<MenuItem> items = new ArrayList<>();
                        if (entry == null) {
                            items.add(new MenuItem(Tr.t("Использовать", "Use"), Glyph.CHECK, ProfilePage.this::useEly));
                            items.add(new MenuItem(Tr.t("Обновить скин", "Refresh skin"), Glyph.RESET, ProfilePage.this::refreshEly));
                            if (shown.image() != null) {
                                items.add(new MenuItem(Tr.t("Изменить копию", "Edit a copy"), Glyph.BRUSH, ProfilePage.this::editEly));
                            }
                        } else {
                            items.add(new MenuItem(Tr.t("Использовать", "Use"), Glyph.CHECK, () -> use(entry)));
                            items.add(new MenuItem(Tr.t("Изменить скин", "Edit skin"), Glyph.BRUSH, () -> editSkin(entry)));
                            items.add(new MenuItem(Tr.t("Удалить скин", "Delete skin"), Glyph.TRASH, () -> removeSkin(entry), true));
                        }
                        JPopupMenu m = SettingsKit.menu(s(220), items.toArray(new MenuItem[0]));
                        m.show(Tile.this, e.getX(), e.getY());
                    } else if (trash) {
                        removeSkin(entry);
                    } else if (!selected) {
                        if (entry == null) useEly();
                        else use(entry);
                    } else {
                        skins.preview.show(shown);
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
                    if (shown.image() != null) skins.preview.show(shown);
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    skins.preview.show(skins.used());
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
            setCursor(skins.ordering && entry != null ? SettingsKit.grab(false) : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
            g2.setColor(selected ? new Color(0x14243A) : hover ? new Color(0x182535) : new Color(0x121C28));
            g2.fill(box);
            g2.setStroke(new BasicStroke(selected ? 2.5f : 1f));
            g2.setColor(selected ? SettingsKit.ACCENT : hover ? SettingsKit.FIELD_HOVER : SettingsKit.CARD_LINE);
            g2.draw(box);
            BufferedImage skin = shown.image();
            if (skin != null) {
                int fh = (int) (h * 0.80);
                Shape old = g2.getClip();
                g2.clip(box);
                SkinRenderer.paint(g2, skin, shown.slim(), 0, w / 2, (h - fh) / 2 - s(4), fh);
                g2.setClip(old);
            } else if (entry == null) {
                // Скин Ely ещё не скачан: состояние вместо пустой рамки.
                ElySkin.State st = ElySkin.state(app.account());
                String t = switch (st) {
                    case LOADING, IDLE -> Tr.t("Загрузка…", "Loading…");
                    case NONE -> Tr.t("Скин не установлен", "No skin set");
                    case ERROR, READY -> Tr.t("Не загрузился", "Not loaded");
                };
                g2.setFont(SettingsKit.font(Font.PLAIN, 13f));
                g2.setColor(st == ElySkin.State.ERROR ? SettingsKit.AMBER : new Color(0x8FA0B3));
                FontMetrics fm = g2.getFontMetrics();
                int y = h / 2 - (wrap(t, fm, w - s(20)).size() - 1) * fm.getHeight() / 2;
                for (String line : wrap(t, fm, w - s(20))) {
                    g2.drawString(line, (w - fm.stringWidth(line)) / 2, y);
                    y += fm.getHeight();
                }
            }
            if (entry == null) {
                // Метка источника в углу, как значок Ely.by в карточке аккаунта.
                int d = s(22);
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new Ellipse2D.Float(s(9), s(9), d, d));
                SettingsKit.icon(Glyph.LAYERS, s(12), Color.WHITE).paintIcon(this, g2, s(9) + (d - s(12)) / 2, s(9) + (d - s(12)) / 2);
            }
            if (selected) {
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
            if (hover && entry != null) {
                int bs = s(30);
                int bx = w - bs - s(8);
                g2.setColor(new Color(0x26141A));
                g2.fill(new RoundRectangle2D.Float(bx, s(8), bs, bs, s(8), s(8)));
                SettingsKit.icon(Glyph.TRASH, s(18), new Color(0xFF8A93)).paintIcon(this, g2, bx + (bs - s(18)) / 2, s(8) + (bs - s(18)) / 2);
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xDCE4EC));
            FontMetrics fm = g2.getFontMetrics();
            String name = SettingsKit.ellipsize(label, fm, w - s(8));
            g2.drawString(name, (w - fm.stringWidth(name)) / 2, getHeight() - s(10));
            g2.dispose();
        }
    }
}
