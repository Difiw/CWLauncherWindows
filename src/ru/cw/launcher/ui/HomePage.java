package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.ActivityStats;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.core.Operation;
import ru.cw.launcher.core.PlayTime;
import ru.cw.launcher.core.Progress;
import ru.cw.launcher.model.GameState;
import ru.cw.launcher.network.ServerStatus;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.updates.UpdateParser;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Главный экран по макету: приветствие, баннер Common World с запуском,
 * карточки сервера и последнего обновления, быстрые плитки. Всё рисуется кодом
 * и меняется вместе с состоянием лаунчера.
 */
final class HomePage extends JPanel {

    private final MainWindow mw;
    private final LauncherApp app;
    private final JLabel title = SettingsKit.label("", 40f, Font.BOLD, SettingsKit.TEXT);
    private final Hero hero = new Hero();
    private final ServerCard server = new ServerCard();
    private final UpdateCard update = new UpdateCard();
    private final Tile mods = new Tile(Glyph.PUZZLE, Tr.t("Моды", "Mods"),
            Tr.t("Управление модами|и сборкой сервера", "Manage mods|and the server pack"));
    private final Tile folder = new Tile(Glyph.FOLDER, Tr.t("Папка игры", "Game folder"),
            Tr.t("Открыть папку Minecraft|сборки Common World", "Open the Minecraft|folder"));
    private final Tile skins = new Tile(null, Tr.t("Скины", "Skins"),
            Tr.t("Управление скинами|и плащами", "Manage skins|and capes"));

    // Состояние, которое передаёт главное окно.
    private String playText = "";
    private boolean playEnabled = true;
    private boolean busy;
    private boolean running;
    private boolean fabricOn = true;
    /** Почему кнопка не «Запустить»: ошибка проверки, нехватка места, обновление. */
    private String why = "";
    private boolean whyError;
    private long cardsAt;

    HomePage(MainWindow mw, LauncherApp app) {
        super(null);
        this.mw = mw;
        this.app = app;
        setOpaque(false);
        add(title);
        add(hero);
        add(server);
        add(update);
        add(mods);
        add(folder);
        add(skins);
        mods.onClick(() -> mw.openSettings(SettingsWindow.Section.MODS.ordinal()));
        folder.onClick(() -> mw.openGameFolder());
        skins.onClick(() -> mw.openProfile());
        refresh();
    }

    static String greeting() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 5) return Tr.t("Доброй ночи", "Good night");
        if (hour < 12) return Tr.t("Доброе утро", "Good morning");
        if (hour < 17) return Tr.t("Добрый день", "Good afternoon");
        return Tr.t("Добрый вечер", "Good evening");
    }

    void reason(String text, boolean error) {
        why = text == null ? "" : text;
        whyError = error;
    }

    void state(String playText, boolean playEnabled, boolean busy, boolean running, boolean fabricOn) {
        this.playText = playText;
        this.playEnabled = playEnabled;
        this.busy = busy;
        this.running = running;
        this.fabricOn = fabricOn;
    }

    /** Подтягивает тексты из состояния. Вызывается главным окном несколько раз в секунду. */
    void refresh() {
        Account ac = app.account();
        String name = ac == null || Utils.isBlank(ac.username) ? Tr.t("игрок", "player") : ac.username;
        String t = greeting() + ", " + name;
        if (!t.equals(title.getText())) title.setText(t);
        hero.sync();
        // Карточки меняются редко: перерисовка раз в две секунды вместо пяти раз в секунду.
        long now = System.currentTimeMillis();
        if (now - cardsAt >= 2000) {
            cardsAt = now;
            server.repaint();
            update.repaint();
            skins.repaint();
        }
        if (mods.isVisible() != fabricOn) {
            mods.setVisible(fabricOn);
            revalidate();
            doLayout();
        }
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int pad = s(20);
        title.setBounds(pad + s(18), s(30), w - s(420), s(56));
        int top = s(96);
        int gap = s(12);
        int tilesH = s(100);
        int infoH = s(100);
        int bottom = s(18);
        int heroH = Math.max(s(250), h - top - gap - infoH - gap - tilesH - bottom);
        int cw = w - pad * 2;
        hero.setBounds(pad, top, cw, heroH);
        int y = top + heroH + gap;
        int serverW = (cw - gap) * 49 / 100;
        server.setBounds(pad, y, serverW, infoH);
        update.setBounds(pad + serverW + gap, y, cw - serverW - gap, infoH);
        y += infoH + gap;
        List<Tile> tiles = new ArrayList<>();
        if (mods.isVisible()) tiles.add(mods);
        tiles.add(folder);
        tiles.add(skins);
        int tw = (cw - gap * (tiles.size() - 1)) / tiles.size();
        int x = pad;
        for (int i = 0; i < tiles.size(); i++) {
            int width = i == tiles.size() - 1 ? pad + cw - x : tw;
            tiles.get(i).setBounds(x, y, width, tilesH);
            x += tw + gap;
        }
    }

    private static void card(Graphics2D g2, int w, int h) {
        int r = SettingsKit.radius() + s(2);
        g2.setColor(SettingsKit.card());
        g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
        g2.setColor(SettingsKit.CARD_LINE);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
    }

    private static void cover(Graphics2D g2, BufferedImage img, int x, int y, int w, int h) {
        cover(g2, img, x, y, w, h, 0.5f);
    }

    /** Как CSS cover; fy — какая часть по высоте видна: 0 — верх картинки, 0.5 — середина. */
    private static void cover(Graphics2D g2, BufferedImage img, int x, int y, int w, int h, float fy) {
        if (img == null) return;
        double scale = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.ceil(img.getWidth() * scale);
        int dh = (int) Math.ceil(img.getHeight() * scale);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(img, x + (w - dw) / 2, y + Math.round((h - dh) * fy), dw, dh, null);
    }

    /** Перенос строки по словам; | — принудительный перенос. Возвращает y следующей строки. */
    private static int wrap(Graphics2D g2, String text, int x, int y, int width, int maxLines, int lineH) {
        FontMetrics fm = g2.getFontMetrics();
        int lines = 0;
        for (String para : text.split("\\|")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String next = line.length() == 0 ? word : line + " " + word;
                if (fm.stringWidth(next) > width && line.length() > 0) {
                    if (++lines > maxLines) return y;
                    g2.drawString(line.toString(), x, y);
                    y += lineH;
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(next);
                }
            }
            if (line.length() > 0) {
                if (++lines > maxLines) return y;
                g2.drawString(SettingsKit.ellipsize(line.toString(), fm, width), x, y);
                y += lineH;
            }
        }
        return y;
    }

    // ------------------------------------------------------------------ баннер

    private final class Hero extends JPanel {
        private final Button play = new Button("", Glyph.PLAY, Kind.PRIMARY);
        private final Button version = new Button(Tr.t("Версия", "Version"), Glyph.LAYERS, Kind.GHOST);
        private final Button closeGame = new Button(Tr.t("Закрыть игру", "Close game"), Glyph.CLOSE, Kind.DANGER);
        private final Button cancel = new Button(Tr.t("Отмена", "Cancel"), Glyph.CLOSE, Kind.DANGER);
        private final Button updateLauncher = new Button(Tr.t("Обновить лаунчер", "Update launcher"), Glyph.RESET, Kind.GHOST);
        private final Activity activity = new Activity();
        private String stage = "";
        private double fraction = -1;

        /** Высота текста с кнопками: с описанием и без него. */
        private int textLong() {
            return s(332);
        }

        private int textShort() {
            return s(224);
        }

        private int actFull() {
            return s(306);
        }

        private int actCompact() {
            return s(166);
        }

        Hero() {
            super(null);
            setOpaque(false);
            play.setFont(SettingsKit.font(Font.BOLD, 22f));
            play.iconSize(26).arc(SettingsKit.radius() + s(4));
            version.setToolTipText(Tr.t("Выбор версии Minecraft и сборки", "Choose the Minecraft version"));
            for (Button b : new Button[]{play, version, closeGame, cancel, updateLauncher}) add(b);
            add(activity);
            play.addActionListener(e -> mw.onMain());
            version.addActionListener(e -> mw.openVersions());
            closeGame.addActionListener(e -> app.closeRunningGame());
            cancel.addActionListener(e -> app.cancelCurrent());
            updateLauncher.addActionListener(e -> app.beginLauncherUpdate());
        }

        private String syncKey = "";
        private long activityAt;

        void sync() {
            // Сравнение с тем же регистром: иначе текст кнопки сбрасывался каждый тик.
            String upper = playText.toUpperCase(java.util.Locale.ROOT);
            if (!upper.equals(play.getText())) play.setText(upper);
            play.setEnabled(playEnabled);
            play.setVisible(!busy);
            version.setVisible(!busy && !running);
            closeGame.setVisible(running && !busy);
            cancel.setVisible(busy);
            updateLauncher.setVisible(!busy && app.launcherUpdateButton());
            if (busy) {
                Operation op = app.currentOperation();
                Progress p = op == null ? null : op.progress();
                stage = p == null || p.stage() == null ? app.state().detail() : p.stage();
                fraction = p == null || p.total() <= 0 ? -1 : Math.min(1, p.done() / (double) p.total());
            }
            // Раскладка и перерисовка — только когда что-то поменялось. Во время загрузки
            // баннер перерисовывается всегда: на нём идёт полоса прогресса.
            String key = upper + '|' + playEnabled + '|' + busy + '|' + running + '|' + updateLauncher.isVisible()
                    + '|' + why + '|' + whyError + '|' + fabricOn + '|' + versionLine() + '|' + season()
                    + '|' + getWidth() + 'x' + getHeight();
            boolean changed = !key.equals(syncKey);
            if (changed) {
                syncKey = key;
                doLayout();
            }
            long now = System.currentTimeMillis();
            if (changed || now - activityAt >= 5000) {
                activityAt = now;
                activity.repaint();
            }
            if (changed || busy) repaint();
        }

        /**
         * Что помещается в баннер по высоте, от полного к простому:
         * описание и полная активность; без описания; активность без нижних строк; без активности.
         */
        private boolean longText() {
            return getHeight() >= textLong() + actFull() + s(40);
        }

        private int activityH() {
            int h = getHeight();
            if (h >= textShort() + actFull() + s(40)) return actFull();
            if (h >= textShort() + actCompact() + s(40)) return actCompact();
            return 0;
        }

        private int top0() {
            // Кнопки стоят сразу над активностью, свободное место уходит наверх.
            if (activityH() > 0) return Math.max(s(8), getHeight() - activityH() - s(12) - s(16) - (longText() ? textLong() : textShort()));
            int text = getHeight() >= textLong() + s(60) ? textLong() : textShort();
            return Math.max(0, (getHeight() - text) / 2);
        }

        private int buttonsY() {
            return top0() + (longText() || (activityH() == 0 && getHeight() >= textLong() + s(60)) ? textLong() : textShort()) - s(66);
        }

        @Override
        public void doLayout() {
            int ah = activityH();
            activity.setVisible(ah > 0);
            if (ah > 0) {
                int aw = Math.min(s(780), Math.max(s(560), getWidth() * 50 / 100));
                activity.setBounds(s(12), getHeight() - ah - s(12), Math.min(aw, getWidth() - s(24)), ah);
            }
            int x = s(32);
            int bh = s(64);
            int by = buttonsY();
            int pw = Math.max(s(300), getWidth() * 27 / 100);
            play.setBounds(x, by, pw, bh);
            int nx = x + pw + s(14);
            // Все кнопки баннера одной высоты с «Запустить».
            int sh = bh;
            int sy = by;
            if (closeGame.isVisible()) {
                closeGame.setBounds(nx, sy, s(200), sh);
                nx += s(214);
            }
            if (version.isVisible()) {
                version.setBounds(nx, sy, s(190), sh);
                nx += s(204);
            }
            if (updateLauncher.isVisible()) updateLauncher.setBounds(nx, sy, s(230), sh);
            cancel.setBounds(x + Math.max(s(300), getWidth() * 34 / 100) + s(16), sy, s(160), sh);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.radius() + s(4);
            Shape clip = new RoundRectangle2D.Float(0, 0, w, h, r, r);
            g2.setColor(new Color(0x0D1621));
            g2.fill(clip);
            g2.clip(clip);
            BufferedImage pic = app.background().picture();
            boolean glass = SettingsKit.backdrop();
            // С фоном под интерфейсом картинка занимает весь баннер, иначе — правую часть, как на макете.
            int picX = glass ? 0 : w * 22 / 100;
            cover(g2, pic, picX, 0, w - picX, h, glass ? 0.3f : 0.5f);
            // Плавный переход от тёмной стороны с текстом к картинке. Без сплошных блоков:
            // сила затемнения задаётся в настройках («Затемнение баннера»).
            int shade = SettingsKit.heroShade();
            int edge = glass ? Math.min(240, shade * 24 / 10) : Math.round(255 * Math.min(1f, 0.55f + shade / 220f));
            float reach = picX + (w - picX) * (0.25f + shade / 200f);
            if (picX > 0) {
                g2.setColor(new Color(13, 22, 33));
                g2.fillRect(0, 0, picX, h);
            }
            g2.setPaint(new LinearGradientPaint(picX - 1, 0, Math.max(picX + 2, reach), 0, new float[]{0f, 0.55f, 1f},
                    new Color[]{new Color(13, 22, 33, edge), new Color(13, 22, 33, edge * 45 / 100), new Color(13, 22, 33, 0)}));
            g2.fillRect(picX - 1, 0, w - picX + 1, h);
            if (shade > 0) {
                g2.setPaint(new GradientPaint(0, h * 0.55f, new Color(13, 22, 33, 0), 0, h, new Color(13, 22, 33, shade * 120 / 100)));
                g2.fillRect(0, 0, w, h);
            }
            g2.setClip(null);
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));

            int x = s(32);
            int textW = Math.max(s(420), w * 40 / 100);
            boolean roomy = buttonsY() - top0() > textShort();
            int y = top0() + s(40);
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xB7C6D8));
            String season = season();
            g2.drawString(spaced(season == null ? "COMMON WORLD"
                    : Tr.t("COMMON WORLD  ·  СЕЗОН " + season, "COMMON WORLD  ·  SEASON " + season)), x, y);
            y += s(58);
            g2.setFont(SettingsKit.font(Font.BOLD, 54f));
            g2.setColor(Color.WHITE);
            g2.drawString("COMMON WORLD", x, y);
            y += s(32);
            g2.setFont(SettingsKit.font(Font.PLAIN, 18f));
            g2.setColor(SettingsKit.ACCENT);
            g2.drawString(versionLine(), x, y);
            if (roomy) {
                y += s(22);
                g2.setColor(new Color(0x2A3A4C));
                g2.drawLine(x, y, x + Math.min(textW, s(340)), y);
                y += s(34);
                g2.setFont(SettingsKit.font(Font.BOLD, 20f));
                g2.setColor(Color.WHITE);
                g2.drawString(Tr.t("Исследуйте мир, созданный игроками", "Explore a world built by players"), x, y);
                y += s(28);
                g2.setFont(SettingsKit.font(Font.PLAIN, 16f));
                g2.setColor(new Color(0xB5C2D0));
                wrap(g2, Tr.t("Выживание, города, экономика, уникальные постройки и дружное сообщество. "
                                + "Твой новый дом в Minecraft.",
                        "Survival, towns, economy, unique builds and a friendly community. "
                                + "Your new home in Minecraft."), x, y, Math.min(textW, s(470)), 2, s(24));
            }
            if (!busy && !why.isBlank()) {
                int by = buttonsY();
                g2.setFont(SettingsKit.font(Font.PLAIN, 15.5f));
                FontMetrics fm = g2.getFontMetrics();
                int iy = by - s(16);
                Color tone = whyError ? SettingsKit.AMBER : new Color(0xB5C2D0);
                g2.setColor(tone);
                g2.fill(new Ellipse2D.Float(x, iy - s(10), s(9), s(9)));
                g2.drawString(SettingsKit.ellipsize(why, fm, Math.max(s(300), w - x - s(60))), x + s(18), iy);
            }
            if (busy) {
                int by = buttonsY();
                int bw = Math.max(s(300), w * 34 / 100);
                g2.setFont(SettingsKit.font(Font.BOLD, 17f));
                g2.setColor(Color.WHITE);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(SettingsKit.ellipsize(stage == null ? "" : stage, fm, bw - s(70)), x, by + s(22));
                if (fraction >= 0) {
                    String pct = Math.round(fraction * 100) + "%";
                    g2.setColor(SettingsKit.ACCENT);
                    g2.drawString(pct, x + bw - fm.stringWidth(pct), by + s(22));
                }
                int barY = by + s(40);
                g2.setColor(new Color(0x253446));
                g2.fill(new RoundRectangle2D.Float(x, barY, bw, s(12), s(12), s(12)));
                g2.setColor(SettingsKit.ACCENT);
                if (fraction >= 0) {
                    g2.fill(new RoundRectangle2D.Float(x, barY, Math.max(s(12), (float) (bw * fraction)), s(12), s(12), s(12)));
                } else {
                    long t = System.currentTimeMillis() / 6 % (bw + s(120));
                    g2.clip(new RoundRectangle2D.Float(x, barY, bw, s(12), s(12), s(12)));
                    g2.fill(new RoundRectangle2D.Float(x + t - s(120), barY, s(120), s(12), s(12), s(12)));
                }
            }
            g2.dispose();
        }

        private String versionLine() {
            boolean cw = app.cfg.commonWorld;
            boolean fabric = cw || app.cfg.fabricFor(app.cfg.minecraftVersion);
            return "Minecraft " + app.cfg.minecraftVersion + (fabric ? " • Fabric" : "")
                    + (cw ? "" : Tr.t(" • своя версия", " • custom"));
        }

        private String spaced(String s) {
            StringBuilder sb = new StringBuilder();
            for (char c : s.toCharArray()) sb.append(c).append(c == ' ' ? "" : " ");
            return sb.toString();
        }
    }

    // ------------------------------------------------------------------ активность

    /** «Ваша активность»: время в Common World, неделя по дням и шесть счётчиков. */
    private final class Activity extends JComponent {
        private Rectangle link = new Rectangle();
        private boolean linkHover;

        Activity() {
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (link.contains(e.getPoint())) new StatsDialog().open();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean over = link.contains(e.getPoint());
                    if (over != linkHover) {
                        linkHover = over;
                        setCursor(Cursor.getPredefinedCursor(over ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                        repaint();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.radius() + s(2);
            g2.setColor(new Color(10, 17, 26, 215));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            ActivityStats.Snapshot st = ActivityStats.snapshot();
            int p = s(14);
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Ваша активность", "Your activity"), p + s(8), s(32));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            FontMetrics fm = g2.getFontMetrics();
            String more = Tr.t("Подробная статистика", "Detailed stats");
            int lw = fm.stringWidth(more) + s(26);
            link = new Rectangle(w - p - lw, s(10), lw, s(30));
            g2.setColor(linkHover ? SettingsKit.ACCENT.brighter() : SettingsKit.ACCENT);
            g2.drawString(more, link.x, s(30));
            arrow(g2, link.x + lw - s(16), s(25));

            int y = s(46);
            int inner = w - p * 2;
            int gap = s(8);
            int bigW = (inner - gap) * 56 / 100;
            int rowH = s(104);
            bigTile(g2, p, y, bigW, rowH, st);
            weekTile(g2, p + bigW + gap, y, inner - bigW - gap, rowH, st);
            if (h < s(250)) {
                g2.dispose();
                return;
            }
            y += rowH + gap;
            int cw = (inner - gap * 2) / 3;
            int sh = s(64);
            int x3 = p + (cw + gap) * 2;
            int w3 = inner - (cw + gap) * 2;
            stat(g2, p, y, cw, sh, null, Tr.t("Minecraft всего", "Minecraft total"), hm(st.minecraft()), null);
            stat(g2, p + cw + gap, y, cw, sh, Glyph.WINDOW, Tr.t("В лаунчере", "In launcher"), hm(st.launcher()), null);
            stat(g2, x3, y, w3, sh, Glyph.CLOCK, Tr.t("Общее время", "Total time"), hm(PlayTime.shownSeconds()), null);
            y += sh + gap;
            stat(g2, p, y, cw, sh, Glyph.SESSION, Tr.t("Текущая сессия", "Current session"), hm(st.session()),
                    st.playing() ? SettingsKit.GREEN : new Color(0x5C6B7C));
            stat(g2, p + cw + gap, y, cw, sh, Glyph.ROCKET, Tr.t("Запусков игры", "Game launches"), String.valueOf(st.launches()), null);
            stat(g2, x3, y, w3, sh, Glyph.CALENDAR, Tr.t("Последний запуск", "Last launch"), when(st.lastLaunch()), null);
            g2.dispose();
        }

        private void arrow(Graphics2D g2, int x, int y) {
            Stroke old = g2.getStroke();
            g2.setStroke(new BasicStroke(s(2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x, y, x + s(12), y);
            g2.drawPolyline(new int[]{x + s(7), x + s(12), x + s(7)}, new int[]{y - s(5), y, y + s(5)}, 3);
            g2.setStroke(old);
        }

        private void box(Graphics2D g2, int x, int y, int w, int h) {
            g2.setColor(new Color(22, 34, 48, 220));
            g2.fill(new RoundRectangle2D.Float(x, y, w, h, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(new Color(255, 255, 255, 18));
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
        }

        private void bigTile(Graphics2D g2, int x, int y, int w, int h, ActivityStats.Snapshot st) {
            Shape shape = new RoundRectangle2D.Float(x, y, w, h, SettingsKit.rr(), SettingsKit.rr());
            Color base = mix(new Color(0x0E1824), SettingsKit.ACCENT, 0.22f);
            g2.setColor(base);
            g2.fill(shape);
            BufferedImage pic = app.background().picture();
            if (pic != null) {
                Shape old = g2.getClip();
                g2.clip(shape);
                int pw = w * 45 / 100;
                g2.clipRect(x + w - pw, y, pw, h);
                cover(g2, pic, x + w - pw, y, pw, h, 0.6f);
                g2.setPaint(new LinearGradientPaint(x + w - pw, 0, x + w, 0, new float[]{0f, 1f},
                        new Color[]{base, new Color(base.getRed(), base.getGreen(), base.getBlue(), 60)}));
                g2.fillRect(x + w - pw, y, pw, h);
                g2.setClip(old);
            }
            g2.setColor(SettingsKit.ACCENT);
            g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
            int d = s(62);
            int cx = x + s(16);
            int cy = y + (h - d) / 2;
            g2.setColor(mix(base, SettingsKit.ACCENT, 0.35f));
            g2.fill(new Ellipse2D.Float(cx, cy, d, d));
            int is = s(34);
            SettingsKit.icon(Glyph.GAMEPAD, is, Color.WHITE).paintIcon(this, g2, cx + (d - is) / 2, cy + (d - is) / 2);
            int tx = cx + d + s(18);
            g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
            g2.setColor(new Color(0xD4DEE8));
            g2.drawString(Tr.t("В Common World", "In Common World"), tx, y + s(30));
            g2.setFont(SettingsKit.font(Font.BOLD, 27f));
            g2.setColor(Color.WHITE);
            g2.drawString(SettingsKit.ellipsize(hm(st.commonWorld()), g2.getFontMetrics(), x + w - tx - s(10)), tx, y + s(64));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(MUTED_TEXT);
            g2.drawString(Tr.t("за всё время", "all time"), tx, y + s(88));
        }

        private void weekTile(Graphics2D g2, int x, int y, int w, int h, ActivityStats.Snapshot st) {
            box(g2, x, y, w, h);
            int p = s(12);
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0xD4DEE8));
            FontMetrics fm = g2.getFontMetrics();
            String total = hm(st.total());
            g2.setFont(SettingsKit.font(Font.BOLD, 15f));
            FontMetrics bold = g2.getFontMetrics();
            int totalW = bold.stringWidth(total);
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            String caption = Tr.t("Активность за 7 дней", "Last 7 days");
            if (fm.stringWidth(caption) > w - p * 2 - totalW - s(8)) caption = Tr.t("За 7 дней", "7 days");
            g2.drawString(SettingsKit.ellipsize(caption, fm, w - p * 2 - totalW - s(8)), x + p, y + s(24));
            g2.setFont(SettingsKit.font(Font.BOLD, 15f));
            g2.setColor(Color.WHITE);
            g2.drawString(total, x + w - p - totalW, y + s(24));
            String[] names = Tr.t("Пн Вт Ср Чт Пт Сб Вс", "Mo Tu We Th Fr Sa Su").split(" ");
            long max = 1;
            for (long v : st.week()) max = Math.max(max, v);
            int today = java.time.LocalDate.now().getDayOfWeek().getValue() - 1;
            int slot = (w - p * 2) / 7;
            int bw = Math.min(s(22), slot * 60 / 100);
            int top = y + s(36);
            int bottom = y + h - s(24);
            g2.setFont(SettingsKit.font(Font.PLAIN, 12f));
            fm = g2.getFontMetrics();
            for (int i = 0; i < 7; i++) {
                int bx = x + p + slot * i + (slot - bw) / 2;
                g2.setColor(new Color(0x253446));
                g2.fill(new RoundRectangle2D.Float(bx, top, bw, bottom - top, s(4), s(4)));
                long v = st.week()[i];
                if (v > 0) {
                    int bh = Math.max(s(5), (int) ((bottom - top) * (v / (double) max)));
                    g2.setColor(i == today ? SettingsKit.ACCENT.brighter() : SettingsKit.ACCENT);
                    g2.fill(new RoundRectangle2D.Float(bx, bottom - bh, bw, bh, s(4), s(4)));
                }
                g2.setColor(i == today ? Color.WHITE : new Color(0x9AA9BA));
                g2.drawString(names[i], bx + (bw - fm.stringWidth(names[i])) / 2, y + h - s(8));
            }
        }

        private void stat(Graphics2D g2, int x, int y, int w, int h, Glyph glyph, String name, String value, Color dot) {
            box(g2, x, y, w, h);
            int is = s(30);
            int ix = x + s(14);
            int iy = y + (h - is) / 2;
            if (glyph == null) {
                grass(g2, ix, iy, is);
            } else {
                SettingsKit.icon(glyph, is, SettingsKit.ACCENT).paintIcon(this, g2, ix, iy);
            }
            int tx = ix + is + s(14);
            g2.setFont(SettingsKit.font(Font.PLAIN, 13.5f));
            g2.setColor(new Color(0xC3CEDA));
            FontMetrics fm = g2.getFontMetrics();
            String label = SettingsKit.ellipsize(name, fm, x + w - tx - s(24));
            g2.drawString(label, tx, y + s(25));
            if (dot != null) {
                g2.setColor(dot);
                g2.fill(new Ellipse2D.Float(tx + fm.stringWidth(label) + s(8), y + s(16), s(9), s(9)));
            }
            // Длинное значение («Сегодня, 16:10») уменьшается, а не обрезается.
            float size = 19f;
            while (size > 14f && g2.getFontMetrics(SettingsKit.font(Font.BOLD, size)).stringWidth(value) > x + w - tx - s(8)) size -= 1f;
            g2.setFont(SettingsKit.font(Font.BOLD, size));
            g2.setColor(Color.WHITE);
            g2.drawString(SettingsKit.ellipsize(value, g2.getFontMetrics(), x + w - tx - s(8)), tx, y + s(50));
        }

        /** Блок травы как в Minecraft: зелёная крышка и земля. */
        private void grass(Graphics2D g2, int x, int y, int size) {
            int half = size / 2;
            int q = size / 4;
            int e = size / 8;
            g2.setColor(new Color(0x6DBE45));
            g2.fillPolygon(new int[]{x + half, x + size, x + half, x}, new int[]{y, y + q, y + half, y + q}, 4);
            g2.setColor(new Color(0x8B5A2B));
            g2.fillPolygon(new int[]{x, x + half, x + half, x}, new int[]{y + q, y + half, y + size, y + size - q}, 4);
            g2.setColor(new Color(0x6E4421));
            g2.fillPolygon(new int[]{x + half, x + size, x + size, x + half}, new int[]{y + half, y + q, y + size - q, y + size}, 4);
            g2.setColor(new Color(0x5AA537));
            g2.fillPolygon(new int[]{x, x + half, x + half, x}, new int[]{y + q, y + half, y + half + e, y + q + e}, 4);
            g2.setColor(new Color(0x4A8F2C));
            g2.fillPolygon(new int[]{x + half, x + size, x + size, x + half}, new int[]{y + half, y + q, y + q + e, y + half + e}, 4);
        }
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** 86 ч 24 мин; меньше часа — 24 мин. */
    static String hm(long seconds) {
        long m = Math.max(0, seconds) / 60;
        long h = m / 60;
        if (h == 0) return m + Tr.t(" мин", " min");
        return h + Tr.t(" ч ", " h ") + String.format("%02d", m % 60) + Tr.t(" мин", " min");
    }

    /** Сегодня, 14:32 / Вчера, 09:05 / 12.10, 18:40. */
    static String when(long millis) {
        if (millis <= 0) return "—";
        java.time.LocalDateTime t = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis),
                java.time.ZoneId.systemDefault());
        java.time.LocalDate today = java.time.LocalDate.now();
        String time = String.format("%02d:%02d", t.getHour(), t.getMinute());
        if (t.toLocalDate().equals(today)) return Tr.t("Сегодня, ", "Today, ") + time;
        if (t.toLocalDate().equals(today.minusDays(1))) return Tr.t("Вчера, ", "Yesterday, ") + time;
        return String.format("%02d.%02d, ", t.getDayOfMonth(), t.getMonthValue()) + time;
    }

    // ------------------------------------------------------------------ подробная статистика

    private final class StatsDialog {
        private final JDialog d = new JDialog(mw, "CWLauncher", Dialog.ModalityType.APPLICATION_MODAL);

        StatsDialog() {
            d.setUndecorated(true);
            d.setBackground(new Color(0, 0, 0, 0));
            JComponent body = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    paintStats((Graphics2D) g, getWidth(), getHeight());
                }
            };
            body.setLayout(null);
            Button reset = new Button(Tr.t("Сбросить", "Reset"), Glyph.TRASH, Kind.GHOST);
            Button close = new Button(Tr.t("Закрыть", "Close"), null, Kind.PRIMARY);
            reset.setToolTipText(Tr.t("Обнулить статистику лаунчера. Общее время не меняется.",
                    "Clear launcher stats. Total time stays."));
            reset.addActionListener(e -> {
                if (Dialogs.ask(d, Tr.t("Сбросить статистику активности?", "Reset activity stats?"),
                        Tr.t("Сбросить", "Reset"), Tr.t("Отмена", "Cancel"))) {
                    ActivityStats.reset();
                    body.repaint();
                }
            });
            close.addActionListener(e -> d.dispose());
            body.add(reset);
            body.add(close);
            int w = s(720);
            int h = s(92) + s(150) + s(52) + s(40) * 5 + s(64);
            body.setPreferredSize(new Dimension(w, h));
            close.setBounds(w - s(26) - s(150), h - s(68), s(150), s(46));
            reset.setBounds(close.getX() - s(12) - s(170), h - s(68), s(170), s(46));
            d.setContentPane(body);
            d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                    KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        private void paintStats(Graphics2D g0, int w, int h) {
            Graphics2D g2 = (Graphics2D) g0.create();
            SettingsKit.smooth(g2);
            int r = SettingsKit.radius() + s(4);
            g2.setColor(new Color(0x111B27));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            g2.setColor(SettingsKit.FIELD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            int p = s(26);
            g2.setFont(SettingsKit.font(Font.BOLD, 21f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Статистика", "Statistics"), p, s(44));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(MUTED_TEXT);
            g2.drawString(Tr.t("Время игры по дням за последние 30 дней", "Play time per day, last 30 days"), p, s(70));
            long[] days = ActivityStats.lastDays(30);
            long max = 1;
            long sum = 0;
            int active = 0;
            for (long v : days) {
                max = Math.max(max, v);
                sum += v;
                if (v > 0) active++;
            }
            int cx = p;
            int cy = s(92);
            int cw = w - p * 2;
            int ch = s(150);
            int slot = cw / 30;
            for (int i = 0; i < 30; i++) {
                int bx = cx + slot * i + s(2);
                int bw = slot - s(4);
                g2.setColor(new Color(0x1B2836));
                g2.fill(new RoundRectangle2D.Float(bx, cy, bw, ch, s(4), s(4)));
                if (days[i] > 0) {
                    int bh = Math.max(s(4), (int) (ch * days[i] / (double) max));
                    g2.setColor(i == 29 ? SettingsKit.ACCENT.brighter() : SettingsKit.ACCENT);
                    g2.fill(new RoundRectangle2D.Float(bx, cy + ch - bh, bw, bh, s(4), s(4)));
                }
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 12.5f));
            g2.setColor(new Color(0x8C9BAD));
            g2.drawString(Tr.t("30 дней назад", "30 days ago"), cx, cy + ch + s(20));
            String now = Tr.t("сегодня", "today");
            g2.drawString(now, cx + cw - g2.getFontMetrics().stringWidth(now), cy + ch + s(20));

            ActivityStats.Snapshot st = ActivityStats.snapshot();
            String[][] rows = {
                    {Tr.t("В Common World", "In Common World"), hm(st.commonWorld())},
                    {Tr.t("Minecraft всего", "Minecraft total"), hm(st.minecraft())},
                    {Tr.t("В лаунчере", "In launcher"), hm(st.launcher())},
                    {Tr.t("Общее время", "Total time"), hm(PlayTime.shownSeconds())},
                    {Tr.t("За 30 дней", "Last 30 days"), hm(sum)},
                    {Tr.t("Дней с игрой", "Days played"), active + " / 30"},
                    {Tr.t("В среднем за игровой день", "Average per play day"), hm(active == 0 ? 0 : sum / active)},
                    {Tr.t("Запусков игры", "Game launches"), String.valueOf(st.launches())},
                    {Tr.t("Последний запуск", "Last launch"), when(st.lastLaunch())},
            };
            int y = cy + ch + s(52);
            int colW = (cw - s(16)) / 2;
            for (int i = 0; i < rows.length; i++) {
                int x = cx + (i % 2) * (colW + s(16));
                int ry = y + (i / 2) * s(40);
                g2.setColor(new Color(0x16212E));
                g2.fill(new RoundRectangle2D.Float(x, ry - s(24), colW, s(34), SettingsKit.rr(), SettingsKit.rr()));
                g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
                g2.setColor(new Color(0xC3CEDA));
                g2.drawString(rows[i][0], x + s(12), ry - s(2));
                g2.setFont(SettingsKit.font(Font.BOLD, 15f));
                g2.setColor(Color.WHITE);
                g2.drawString(rows[i][1], x + colW - s(12) - g2.getFontMetrics().stringWidth(rows[i][1]), ry - s(2));
            }
            g2.dispose();
        }

        void open() {
            d.pack();
            d.setLocation(mw.getX() + (mw.getWidth() - d.getWidth()) / 2, mw.getY() + (mw.getHeight() - d.getHeight()) / 2);
            Motion.present(d);
        }
    }

    // ------------------------------------------------------------------ сервер

    private final class ServerCard extends JPanel {
        private final Button copy = new Button("", Glyph.COPY, Kind.FLAT).size(40, 40).iconSize(20);

        ServerCard() {
            super(null);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(Tr.t("Обновить статус сервера", "Refresh server status"));
            copy.setToolTipText(Tr.t("Скопировать адрес", "Copy address"));
            copy.addActionListener(e -> {
                mw.copyServerAddress();
                Dialogs.toast(this, Tr.t("Адрес сервера скопирован", "Server address copied"), SettingsKit.GREEN);
            });
            add(copy);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    app.checkServer();
                }
            });
        }

        @Override
        public void doLayout() {
            copy.setBounds(getWidth() - s(50), getHeight() - s(48), s(36), s(36));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            card(g2, w, h);
            int p = s(14);
            int th = h - p * 2;
            int tw = th * 14 / 10;
            Shape thumb = new RoundRectangle2D.Float(p, p, tw, th, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(SettingsKit.FIELD);
            g2.fill(thumb);
            BufferedImage pic = app.background().picture();
            if (pic != null) {
                Shape old = g2.getClip();
                g2.clip(thumb);
                // Правая часть картинки: там обычно постройки, а не небо.
                int cw = pic.getWidth() / 4;
                int cx = pic.getWidth() - cw - pic.getWidth() / 10;
                int chh = Math.min(pic.getHeight(), cw * th / Math.max(1, tw));
                BufferedImage crop = pic.getSubimage(Math.max(0, cx), Math.max(0, pic.getHeight() - chh - pic.getHeight() / 12), cw, chh);
                cover(g2, crop, p, p, tw, th);
                g2.setClip(old);
            }
            ServerStatus.Info srv = app.serverInfo();
            String state;
            Color tone;
            String players = "";
            double fill = 0;
            if (srv == null || !srv.available()) {
                state = Tr.t("Статус недоступен", "Status unavailable");
                tone = SettingsKit.DIM;
            } else if (srv.online()) {
                state = Tr.t("Сервер в сети", "Server online");
                tone = SettingsKit.GREEN;
                players = Tr.t(srv.players() + " из " + srv.maxPlayers() + " игроков",
                        srv.players() + " of " + srv.maxPlayers() + " players");
                fill = srv.maxPlayers() <= 0 ? 0 : Math.min(1, srv.players() / (double) srv.maxPlayers());
            } else {
                state = Tr.t("Сервер не отвечает", "Server not responding");
                tone = SettingsKit.AMBER;
            }
            int x = p + tw + s(20);
            int right = w - p;
            g2.setColor(tone);
            g2.fill(new Ellipse2D.Float(x, p + s(7), s(12), s(12)));
            g2.setFont(SettingsKit.font(Font.BOLD, 17f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            int playersW = players.isEmpty() ? 0 : g2.getFontMetrics(SettingsKit.font(Font.PLAIN, 14.5f)).stringWidth(players) + s(12);
            g2.drawString(SettingsKit.ellipsize(state, fm, right - x - s(24) - playersW), x + s(22), p + s(18));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xB5C2D0));
            if (!players.isEmpty()) g2.drawString(players, right - g2.getFontMetrics().stringWidth(players), p + s(18));
            int barY = p + s(32);
            g2.setColor(new Color(0x253446));
            g2.fill(new RoundRectangle2D.Float(x, barY, right - x, s(8), s(8), s(8)));
            if (fill > 0) {
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new RoundRectangle2D.Float(x, barY, Math.max(s(8), (float) ((right - x) * fill)), s(8), s(8), s(8)));
            }
            String ip = Utils.isBlank(app.cfg.serverIp) ? "" : app.cfg.serverIp;
            g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
            g2.setColor(new Color(0xDCE4EC));
            fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(ip, fm, right - x - s(48)), x, h - s(22));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ последнее обновление

    private final class UpdateCard extends JComponent {
        private boolean hover;

        UpdateCard() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    mw.openNews(NewsPage.Tab.UPDATES);
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
            card(g2, w, h);
            if (hover) {
                g2.setColor(new Color(255, 255, 255, 8));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, SettingsKit.radius(), SettingsKit.radius()));
            }
            int p = s(14);
            int box = Math.min(s(76), h - p * 2);
            int by = (h - box) / 2;
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(p, by, box, box, SettingsKit.rr(), SettingsKit.rr()));
            int is = box * 55 / 100;
            SettingsKit.icon(Glyph.FILE, is, new Color(0xD4DCE6)).paintIcon(this, g2, p + (box - is) / 2, by + (box - is) / 2);
            List<UpdateParser.Note> notes = app.updateInfo().cached();
            String head = notes.isEmpty() ? Tr.t("Обновлений пока нет", "No updates yet") : notes.get(0).title();
            String text = notes.isEmpty() ? Tr.t("Список обновлений появится, когда источник ответит.",
                    "The update list appears when the source responds.") : firstLines(notes.get(0).text());
            int x = p + box + s(22);
            int right = w - s(56);
            g2.setFont(SettingsKit.font(Font.PLAIN, 13.5f));
            g2.setColor(MUTED_TEXT);
            g2.drawString(Tr.t("Последнее обновление", "Latest update"), x, by + s(13));
            g2.setFont(SettingsKit.font(Font.BOLD, 18f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(head, fm, right - x), x, by + s(36));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0xB5C2D0));
            wrap(g2, text, x, by + s(56), right - x, 2, s(19));
            SettingsKit.icon(Glyph.RIGHT, s(24), new Color(0x9AA9BA)).paintIcon(this, g2, w - s(44), (h - s(24)) / 2);
            g2.dispose();
        }

        private String firstLines(String text) {
            if (text == null) return "";
            return text.replace("\r", "").replaceAll("\n+", " ").replaceAll("^[-•*\\s]+", "").trim();
        }
    }

    private static final Color MUTED_TEXT = new Color(0xAEBBCA);

    private String season;
    private long seasonAt;

    /**
     * Сезон — первая цифра версии сборки: 5.2.6.7 → 5, 4.3.6 → 4. Берётся версия с сервера,
     * без сети — установленная. Нет версии — null, и на баннере остаётся только COMMON WORLD.
     * Диск читается не чаще раза в пять секунд.
     */
    private String season() {
        long now = System.currentTimeMillis();
        if (now - seasonAt < 5000) return season;
        seasonAt = now;
        String version = app.cfg.remoteBuildVersion;
        if (version == null || version.isBlank()) {
            try {
                version = app.mods().installedBuildVersion(app.cfg.instanceId());
            } catch (Exception e) {
                version = null;
            }
        }
        season = null;
        if (version != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*v?(\\d+)").matcher(version);
            if (m.find()) season = String.valueOf(Integer.parseInt(m.group(1)));
        }
        return season;
    }

    // ------------------------------------------------------------------ плитки

    private final class Tile extends JComponent {
        private final Glyph glyph;
        private final String name;
        private final String hint;
        private boolean hover;
        private Runnable action;

        Tile(Glyph glyph, String name, String hint) {
            this.glyph = glyph;
            this.name = name;
            this.hint = hint;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (action != null) action.run();
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

        void onClick(Runnable r) {
            action = r;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            card(g2, w, h);
            if (hover) {
                g2.setColor(new Color(255, 255, 255, 8));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, SettingsKit.radius(), SettingsKit.radius()));
            }
            int p = s(14);
            int box = Math.min(s(76), h - p * 2);
            int by = (h - box) / 2;
            g2.setColor(new Color(0x1B2836));
            Shape boxShape = new RoundRectangle2D.Float(p, by, box, box, SettingsKit.rr(), SettingsKit.rr());
            g2.fill(boxShape);
            if (glyph != null) {
                int is = box * 52 / 100;
                SettingsKit.icon(glyph, is, new Color(0xD4DCE6)).paintIcon(this, g2, p + (box - is) / 2, by + (box - is) / 2);
            } else {
                BufferedImage face = null;
                try {
                    face = ru.cw.launcher.skin.PlayerSkin.face();
                } catch (Exception ignored) {
                }
                int fs = box * 62 / 100;
                if (face != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.drawImage(face, p + (box - fs) / 2, by + (box - fs) / 2, fs, fs, null);
                } else {
                    SettingsKit.icon(Glyph.USER, fs, new Color(0xD4DCE6)).paintIcon(this, g2, p + (box - fs) / 2, by + (box - fs) / 2);
                }
            }
            int x = p + box + s(20);
            int right = w - s(48);
            g2.setFont(SettingsKit.font(Font.BOLD, 18.5f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(name, fm, right - x), x, by + s(28));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0xB5C2D0));
            wrap(g2, hint, x, by + s(50), right - x, 2, s(19));
            SettingsKit.icon(Glyph.RIGHT, s(22), new Color(0x9AA9BA)).paintIcon(this, g2, w - s(40), (h - s(22)) / 2);
            g2.dispose();
        }
    }
}
