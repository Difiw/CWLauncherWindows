package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.network.ServerStatus;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.NavItem;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Utils;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Оболочка главного окна: меню слева, текущая страница справа,
 * статус сервера и кнопки окна сверху. Главная и настройки — страницы этой оболочки.
 */
final class Shell extends JPanel {

    enum Target {HOME, NEWS, BUILDS, SERVERS, PROFILE, SETTINGS, ABOUT, VERSIONS}

    private final MainWindow mw;
    private final LauncherApp app;
    private final SideNav nav;
    private final ServerPill pill = new ServerPill();
    private final Button bMin = new Button("", Glyph.MIN, Kind.FLAT).size(46, 40).iconSize(18);
    private final Button bMax = new Button("", Glyph.MAX, Kind.FLAT).size(46, 40).iconSize(16);
    private final Button bClose = new Button("", Glyph.CLOSE, Kind.FLAT).size(46, 40).iconSize(18);
    private JComponent page;

    Shell(MainWindow mw, LauncherApp app) {
        super(null);
        this.mw = mw;
        this.app = app;
        setOpaque(true);
        nav = new SideNav();
        add(nav);
        add(pill);
        add(bMin);
        add(bMax);
        add(bClose);
        bMin.setToolTipText(Tr.t("Свернуть", "Minimize"));
        bMax.setToolTipText(Tr.t("Развернуть (F11)", "Maximize (F11)"));
        bClose.setToolTipText(Tr.t("Закрыть", "Close"));
        bMin.addActionListener(e -> mw.setExtendedState(Frame.ICONIFIED));
        bMax.addActionListener(e -> mw.toggleFullscreen());
        bClose.addActionListener(e -> mw.navigate(mw::quit));
    }

    void setPage(JComponent next, Target active) {
        if (page != null) remove(page);
        page = next;
        add(page);
        setComponentZOrder(page, getComponentCount() - 1);
        nav.setActive(active);
        doLayout();
        revalidate();
        repaint();
    }

    JComponent page() {
        return page;
    }

    void refresh() {
        pill.refresh();
        nav.refresh();
        if (UiLook.showFps()) repaint();
    }

    int navWidth() {
        return Math.max(s(250), Math.min(s(300), getWidth() * 19 / 100));
    }

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int navW = navWidth();
        nav.setBounds(0, 0, navW, h);
        if (page != null) page.setBounds(navW, 0, w - navW, h);
        bClose.setBounds(w - s(52), s(4), s(46), s(40));
        bMax.setBounds(w - s(100), s(4), s(46), s(40));
        bMin.setBounds(w - s(148), s(4), s(46), s(40));
        Dimension pp = pill.getPreferredSize();
        pill.setBounds(w - s(20) - pp.width, s(48), pp.width, pp.height);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth();
        int h = getHeight();
        g2.setPaint(new GradientPaint(0, 0, new Color(0x0D1621), 0, h, new Color(0x0A111A)));
        g2.fillRect(0, 0, w, h);
        if (SettingsKit.backdrop()) {
            BufferedImage frame = backdropFrame(w, h);
            if (frame != null) g2.drawImage(frame, 0, 0, null);
        }
        g2.dispose();
    }

    private BufferedImage backdrop;
    private String backdropKey = "";

    /** Фон под интерфейсом: картинка по размеру окна, размытая и затемнённая. Считается один раз на размер. */
    private BufferedImage backdropFrame(int w, int h) {
        BufferedImage pic = app.background().picture();
        if (pic == null || w <= 0 || h <= 0) return null;
        String key = System.identityHashCode(pic) + "|" + w + "x" + h + "|" + SettingsKit.backdropDim() + "|" + SettingsKit.backdropBlur();
        if (key.equals(backdropKey) && backdrop != null) return backdrop;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double scale = Math.max((double) w / pic.getWidth(), (double) h / pic.getHeight());
        int dw = (int) Math.ceil(pic.getWidth() * scale);
        int dh = (int) Math.ceil(pic.getHeight() * scale);
        if (SettingsKit.backdropBlur()) {
            // Размытие уменьшением: картинка сжимается в 12 раз и растягивается обратно, дважды.
            int sw = Math.max(8, w / 12);
            int sh = Math.max(8, h / 12);
            BufferedImage small = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D sg = small.createGraphics();
            sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            sg.drawImage(pic, (sw - dw * sw / w) / 2, (sh - dh * sh / h) / 2, dw * sw / w, dh * sh / h, null);
            sg.dispose();
            BufferedImage mid = new BufferedImage(sw * 3, sh * 3, BufferedImage.TYPE_INT_RGB);
            Graphics2D mg = mid.createGraphics();
            mg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            mg.drawImage(small, 0, 0, sw * 3, sh * 3, null);
            mg.dispose();
            g.drawImage(mid, 0, 0, w, h, null);
        } else {
            g.drawImage(pic, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        }
        int alpha = Math.min(235, SettingsKit.backdropDim() * 235 / 100);
        g.setColor(new Color(8, 13, 20, alpha));
        g.fillRect(0, 0, w, h);
        g.dispose();
        backdrop = out;
        backdropKey = key;
        return out;
    }

    @Override
    protected void paintChildren(Graphics g) {
        super.paintChildren(g);
        if (!UiLook.showFps()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        SettingsKit.smooth(g2);
        // Плашка в правом нижнем углу: не налезает на логотип и меню.
        String fps = UiLook.fpsTick() + " FPS";
        g2.setFont(SettingsKit.font(Font.BOLD, 13f));
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(fps) + s(18);
        int bh = s(24);
        int bx = getWidth() - bw - s(10);
        int by = getHeight() - bh - s(8);
        g2.setColor(new Color(8, 13, 20, 200));
        g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
        g2.setColor(SettingsKit.GREEN);
        g2.drawString(fps, bx + s(9), by + (bh + fm.getAscent() - fm.getDescent()) / 2);
        g2.dispose();
    }

    // ------------------------------------------------------------------ меню слева

    private final class SideNav extends JPanel {
        private final NavItem home = new SideItem(Tr.t("Главная", "Home"), Glyph.HOME, 20f, 30);
        private final NavItem news = new SideItem(Tr.t("Новости", "News"), Glyph.NEWS, 20f, 30);
        private final NavItem buildsNav = new SideItem(Tr.t("Сборки", "Builds"), Glyph.LAYERS, 20f, 30);
        private final NavItem servers = new SideItem(Tr.t("Серверы", "Servers"), Glyph.SERVERS, 20f, 30);
        private final NavItem profile = new SideItem(Tr.t("Профиль", "Profile"), Glyph.USER, 20f, 30);
        private final NavItem settings = new SideItem(Tr.t("Настройки", "Settings"), Glyph.GEAR, 20f, 30);
        private final NavItem about = new SideItem(Tr.t("О лаунчере", "About"), Glyph.INFO, 20f, 30);
        private final Social site = new Social(Tr.t("Сайт сервера", "Server website"), Brand.SITE);
        private final Social telegram = new Social("Telegram", Brand.TELEGRAM);
        private final Social discord = new Social("Discord", Brand.DISCORD);
        private final Social support = new Social(Tr.t("Тех. поддержка", "Support"), Brand.SUPPORT);
        private String accountKey = "";
        private final AccountCard account = new AccountCard();
        private final BufferedImage logo;

        SideNav() {
            super(null);
            setOpaque(false);
            home.addActionListener(e -> mw.navigate(mw::showHome));
            news.addActionListener(e -> mw.navigate(mw::openNews));
            buildsNav.addActionListener(e -> mw.navigate(mw::openBuilds));
            servers.addActionListener(e -> mw.navigate(mw::openServers));
            profile.addActionListener(e -> mw.navigate(mw::openProfile));
            settings.addActionListener(e -> mw.openSettings(0));
            about.addActionListener(e -> mw.navigate(mw::openAbout));
            site.addActionListener(e -> mw.openUrl("https://commonworld.ru/index.php"));
            telegram.addActionListener(e -> mw.openUrl(app.cfg.telegramUrl));
            discord.addActionListener(e -> mw.openUrl(app.cfg.discordUrl));
            support.addActionListener(e -> mw.openUrl(app.cfg.supportUrl));
            for (JComponent c : new JComponent[]{home, news, buildsNav, servers, profile, settings, about, site, telegram, discord, support, account}) {
                add(c);
            }
            logo = LogoArt.logo();
            refresh();
        }

        void setActive(Target t) {
            home.setActive(t == Target.HOME);
            news.setActive(t == Target.NEWS);
            buildsNav.setActive(t == Target.BUILDS);
            servers.setActive(t == Target.SERVERS);
            profile.setActive(t == Target.PROFILE);
            settings.setActive(t == Target.SETTINGS);
            about.setActive(t == Target.ABOUT);
        }

        void refresh() {
            // Локальные серверы — инструмент разработчика.
            boolean dev = app.cfg.developerMode;
            if (servers.isVisible() != dev) {
                servers.setVisible(dev);
                doLayout();
            }
            // Карточка перерисовывается, только когда сменился игрок или скин.
            Account ac = app.account();
            String key = ac == null ? "" : ac.username + '|' + ac.type + '|'
                    + System.identityHashCode(ru.cw.launcher.skin.PlayerSkin.face());
            if (!key.equals(accountKey)) {
                accountKey = key;
                account.repaint();
            }
        }


        private int logoBottom() {
            return getHeight() < s(860) ? s(196) : s(240);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            boolean tight = h < s(860);
            int y = logoBottom() + (tight ? s(8) : s(20));
            int step = tight ? s(60) : s(70);
            int ih = tight ? s(52) : s(60);
            for (NavItem item : new NavItem[]{home, news, buildsNav, servers, profile, settings, about}) {
                if (!item.isVisible()) continue;
                item.setBounds(s(16), y, w - s(32), ih);
                y += step;
            }
            int ah = tight ? s(84) : s(100);
            account.setBounds(s(14), h - ah - s(8), w - s(28), ah);
            // Под кнопками-ссылками остаётся строка для версии лаунчера.
            int sy = account.getY() - s(24) - s(42) - versionRow();
            int sw = s(42);
            int gap = Math.max(s(6), (w - s(32) - sw * 4) / 3);
            int sx = s(16);
            for (Social b : new Social[]{site, telegram, discord, support}) {
                b.setBounds(sx, sy, sw, sw);
                sx += sw + gap;
            }
            boolean room = sy > y + s(8);
            for (Social b : new Social[]{site, telegram, discord, support}) b.setVisible(room);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(new Color(0x0B1520));
            g2.fillRect(0, 0, w, h);
            BufferedImage scene = scene(w, h);
            if (scene != null) {
                g2.drawImage(scene, 0, 0, null);
                // Затемнение только при рисовании: сама картинка не меняется. Сверху темнее — читается логотип.
                g2.setPaint(new GradientPaint(0, 0, new Color(6, 12, 22, 150), 0, h * 0.5f, new Color(6, 12, 22, 70)));
                g2.fillRect(0, 0, w, h / 2);
                g2.setPaint(new GradientPaint(0, h * 0.5f, new Color(6, 12, 22, 70), 0, h, new Color(6, 12, 22, 120)));
                g2.fillRect(0, h / 2, w, h - h / 2);
            } else {
                g2.setPaint(new GradientPaint(0, 0, new Color(0x0F1B2A), 0, h, new Color(0x0A121C)));
                g2.fillRect(0, 0, w, h);
            }
            g2.setColor(new Color(0x2A9FE0 + 0x66000000, true));
            g2.drawLine(w - 1, 0, w - 1, h);
            int lb = logoBottom();
            if (logo != null) {
                int lh = lb - s(52);
                int lw = (int) Math.round(lh * logo.getWidth() / (double) logo.getHeight());
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(logo, (w - lw) / 2, s(18), lw, lh, null);
                // Линия с ромбом под логотипом.
                int ly = lb - s(24);
                int mid = w / 2;
                g2.setColor(new Color(56, 198, 255, 190));
                g2.drawLine(s(40), ly, mid - s(14), ly);
                g2.drawLine(mid + s(14), ly, w - s(40), ly);
                g2.drawPolygon(new int[]{mid, mid + s(6), mid, mid - s(6)}, new int[]{ly - s(6), ly, ly + s(6), ly}, 4);
            } else {
                int size = lb - s(120);
                g2.drawImage(Icons.appIcon(), (w - size) / 2, s(26), size, size, null);
                g2.setFont(SettingsKit.font(Font.BOLD, 30f));
                g2.setColor(Color.WHITE);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString("COMMON", (w - fm.stringWidth("COMMON")) / 2, lb - s(50));
                g2.drawString("WORLD", (w - fm.stringWidth("WORLD")) / 2, lb - s(16));
            }
            // Версия: подпись по центру, по бокам тонкие линии.
            int ay = account.getY() - s(12);
            String ver = "CWLauncher v" + Log.VERSION;
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            FontMetrics vm = g2.getFontMetrics();
            int vw = vm.stringWidth(ver);
            int vy = ay - s(8);
            g2.setColor(new Color(0xDCE8F5));
            g2.drawString(ver, (w - vw) / 2, vy);
            g2.setColor(new Color(56, 198, 255, 150));
            int lineY = vy - vm.getAscent() / 2 + s(2);
            g2.drawLine(s(18), lineY, (w - vw) / 2 - s(12), lineY);
            g2.drawLine((w + vw) / 2 + s(12), lineY, w - s(18), lineY);
            g2.dispose();
        }

        private BufferedImage sceneCache;
        private int sceneW;
        private int sceneH;

        /** Картинка панели по размеру: заполняет её целиком, лишнее по бокам обрезается. Считается один раз на размер. */
        private BufferedImage scene(int w, int h) {
            BufferedImage src = LogoArt.sidebar();
            if (src == null || w <= 0 || h <= 0) return null;
            if (sceneCache != null && sceneW == w && sceneH == h) return sceneCache;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            double scale = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
            int dw = (int) Math.ceil(src.getWidth() * scale);
            int dh = (int) Math.ceil(src.getHeight() * scale);
            g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
            g.dispose();
            sceneCache = out;
            sceneW = w;
            sceneH = h;
            return out;
        }

        private int versionRow() {
            return s(22);
        }
    }

    /**
     * Кнопка бокового меню: тёмное стекло с голубой рамкой, значок в круге слева.
     * Активная светится и заливается голубым, справа у неё небольшая засечка.
     */
    private static final class SideItem extends NavItem {

        SideItem(String text, Glyph glyph, float textSize, int iconPx) {
            super(text, glyph, textSize, iconPx);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            boolean on = isActive();
            boolean over = hovered();
            float arc = SettingsKit.rr() + s(6);
            RoundRectangle2D.Float body = new RoundRectangle2D.Float(s(2), s(2), w - s(4) - 1, h - s(4) - 1, arc, arc);
            if (on) {
                // Мягкое свечение вокруг активной кнопки.
                for (int i = 3; i >= 1; i--) {
                    g2.setColor(new Color(40, 190, 255, 22 * (4 - i)));
                    g2.setStroke(new BasicStroke(s(2) + i * s(2)));
                    g2.draw(body);
                }
                g2.setPaint(new GradientPaint(0, 0, new Color(18, 120, 190, 235), w, 0, new Color(10, 70, 120, 235)));
            } else {
                g2.setColor(over ? new Color(16, 46, 74, 225) : new Color(8, 20, 34, 185));
            }
            g2.fill(body);
            g2.setStroke(new BasicStroke(on ? 1.8f : 1.2f));
            g2.setColor(on ? new Color(70, 215, 255) : over ? new Color(56, 198, 255, 220) : new Color(40, 150, 220, 150));
            g2.draw(body);

            int badge = Math.min(h - s(14), s(46));
            int bx = s(14);
            int by = (h - badge) / 2;
            g2.setPaint(new GradientPaint(0, by, on ? new Color(40, 150, 220) : new Color(24, 70, 108),
                    0, by + badge, on ? new Color(16, 90, 150) : new Color(12, 38, 64)));
            g2.fill(new java.awt.geom.Ellipse2D.Float(bx, by, badge, badge));
            g2.setColor(on ? new Color(120, 225, 255, 200) : new Color(60, 160, 225, 130));
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(new java.awt.geom.Ellipse2D.Float(bx + 0.5f, by + 0.5f, badge - 1, badge - 1));
            int ip = badge * 56 / 100;
            SettingsKit.icon(glyph(), ip, on || over ? Color.WHITE : new Color(0xDCE8F5))
                    .paintIcon(this, g2, bx + (badge - ip) / 2, by + (badge - ip) / 2);

            g2.setFont(SettingsKit.font(Font.PLAIN, textSize()));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            int tx = bx + badge + s(16);
            g2.drawString(SettingsKit.ellipsize(getText(), fm, w - tx - s(22)), tx, (h + fm.getAscent() - fm.getDescent()) / 2);
            if (on) {
                // Засечка на правом краю.
                g2.setColor(new Color(70, 215, 255));
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int ex = w - s(8);
                g2.drawPolyline(new int[]{ex, ex - s(6), ex}, new int[]{h / 2 - s(8), h / 2, h / 2 + s(8)}, 3);
            }
            g2.dispose();
        }
    }

    /** Значки ссылок внизу меню. */
    private enum Brand {SITE, TELEGRAM, DISCORD, SUPPORT}

    /** Маленькая круглая кнопка-ссылка: сайт, Telegram, Discord, поддержка. */
    private static final class Social extends JButton {
        private final Brand brand;
        private boolean hover;

        Social(String tip, Brand brand) {
            this.brand = brand;
            setToolTipText(tip);
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

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            // Тёмное стекло с голубой рамкой, как у остальных элементов панели.
            g2.setColor(hover ? new Color(20, 70, 110, 215) : new Color(8, 20, 34, 185));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(hover ? new Color(56, 198, 255, 230) : new Color(40, 150, 220, 170));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, SettingsKit.rr(), SettingsKit.rr()));
            int is = w * 58 / 100;
            g2.translate((w - is) / 2.0, (h - is) / 2.0);
            g2.scale(is / 24.0, is / 24.0);
            g2.setColor(hover ? Color.WHITE : new Color(0xB3C0CF));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            switch (brand) {
                case SITE -> globe(g2);
                case TELEGRAM -> g2.fill(telegram());
                case DISCORD -> g2.fill(discord());
                case SUPPORT -> headset(g2);
            }
            g2.dispose();
        }

        /** Глобус: контур, экватор, две параллели и меридианы. */
        private static void globe(Graphics2D g) {
            g.draw(new Ellipse2D.Float(2.5f, 2.5f, 19, 19));
            g.draw(new Ellipse2D.Float(7.6f, 2.5f, 8.8f, 19));
            g.draw(new java.awt.geom.Line2D.Float(2.8f, 12, 21.2f, 12));
            g.draw(new java.awt.geom.Line2D.Float(4.4f, 7.3f, 19.6f, 7.3f));
            g.draw(new java.awt.geom.Line2D.Float(4.4f, 16.7f, 19.6f, 16.7f));
        }

        /** Бумажный самолётик Telegram, залитый, с прорезью крыла. */
        private static Shape telegram() {
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            p.moveTo(2.6, 11.1);
            p.lineTo(20.2, 4.3);
            p.curveTo(21.1, 4.0, 21.8, 4.6, 21.6, 5.6);
            p.lineTo(18.6, 19.5);
            p.curveTo(18.4, 20.5, 17.7, 20.8, 16.9, 20.3);
            p.lineTo(12.3, 16.9);
            p.lineTo(10.1, 19.0);
            p.curveTo(9.8, 19.3, 9.5, 19.5, 9.1, 19.5);
            p.lineTo(9.4, 14.8);
            p.lineTo(17.9, 7.1);
            p.curveTo(18.3, 6.8, 17.8, 6.6, 17.3, 6.9);
            p.lineTo(6.8, 13.5);
            p.lineTo(2.6, 12.2);
            p.curveTo(1.7, 11.9, 1.7, 11.4, 2.6, 11.1);
            p.closePath();
            return p;
        }

        /** Голова-геймпад Discord с двумя глазами. */
        private static Shape discord() {
            java.awt.geom.Path2D p = new java.awt.geom.Path2D.Float();
            p.moveTo(8.6, 4.6);
            p.curveTo(6.8, 4.9, 5.2, 5.5, 3.9, 6.3);
            p.curveTo(1.9, 9.3, 1.2, 12.6, 1.5, 16.8);
            p.curveTo(3.1, 18.0, 4.8, 18.8, 6.5, 19.3);
            p.lineTo(7.7, 17.5);
            p.curveTo(9.9, 18.2, 14.1, 18.2, 16.3, 17.5);
            p.lineTo(17.5, 19.3);
            p.curveTo(19.2, 18.8, 20.9, 18.0, 22.5, 16.8);
            p.curveTo(22.8, 12.6, 22.1, 9.3, 20.1, 6.3);
            p.curveTo(18.8, 5.5, 17.2, 4.9, 15.4, 4.6);
            p.lineTo(14.9, 5.7);
            p.curveTo(13.0, 5.4, 11.0, 5.4, 9.1, 5.7);
            p.closePath();
            java.awt.geom.Area a = new java.awt.geom.Area(p);
            a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(6.9f, 10.6f, 3.4f, 3.8f)));
            a.subtract(new java.awt.geom.Area(new Ellipse2D.Float(13.7f, 10.6f, 3.4f, 3.8f)));
            return a;
        }

        /** Гарнитура поддержки: дуга, два наушника и микрофон. */
        private static void headset(Graphics2D g) {
            g.draw(new java.awt.geom.Arc2D.Float(4, 3.5f, 16, 16, 0, 180, java.awt.geom.Arc2D.OPEN));
            g.draw(new java.awt.geom.Line2D.Float(4, 11.5f, 4, 13));
            g.draw(new java.awt.geom.Line2D.Float(20, 11.5f, 20, 13));
            g.fill(new RoundRectangle2D.Float(2.5f, 11, 5, 7.5f, 2.4f, 2.4f));
            g.fill(new RoundRectangle2D.Float(16.5f, 11, 5, 7.5f, 2.4f, 2.4f));
            java.awt.geom.Path2D mic = new java.awt.geom.Path2D.Float();
            mic.moveTo(19, 18.5);
            mic.curveTo(19, 20.6, 17.4, 21.2, 14.5, 21.2);
            g.draw(mic);
            g.fill(new RoundRectangle2D.Float(10.5f, 19.9f, 4.6f, 2.6f, 2.6f, 2.6f));
        }
    }

    /** Карточка игрока: скин, ник, статус. По щелчку — меню профиля и лаунчера. */
    private final class AccountCard extends JComponent {
        private boolean hover;

        AccountCard() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    JPopupMenu m = menu();
                    m.show(AccountCard.this, 0, -m.getPreferredSize().height - s(6));
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

        private JPopupMenu menu() {
            return SettingsKit.menu(getWidth(),
                    new SettingsKit.MenuItem(Tr.t("Профиль и скины", "Profile and skins"), Glyph.USER, () -> mw.navigate(mw::openProfile)),
                    new SettingsKit.MenuItem(Tr.t("Перезапустить лаунчер", "Restart launcher"), Glyph.RESET, () -> mw.navigate(mw::restartLauncher)),
                    new SettingsKit.MenuItem(Tr.t("Закрыть лаунчер", "Quit launcher"), Glyph.EXIT, () -> mw.navigate(mw::quit), true));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            // Стеклянная карточка с голубой рамкой.
            g2.setColor(hover ? new Color(14, 44, 70, 225) : new Color(8, 20, 34, 195));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr() + s(4), SettingsKit.rr() + s(4)));
            g2.setColor(hover ? new Color(56, 198, 255, 235) : new Color(40, 150, 220, 190));
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, SettingsKit.rr() + s(4), SettingsKit.rr() + s(4)));
            int face = Math.min(s(60), h - s(26));
            int fy = (h - face) / 2;
            BufferedImage skin = null;
            try {
                skin = ru.cw.launcher.skin.PlayerSkin.face();
            } catch (Exception ignored) {
            }
            Shape box = new RoundRectangle2D.Float(s(12), fy, face, face, s(10), s(10));
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            if (skin != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                Shape old = g2.getClip();
                g2.clip(box);
                g2.drawImage(skin, s(12), fy, face, face, null);
                g2.setClip(old);
            } else {
                int is = face - s(20);
                SettingsKit.icon(Glyph.USER, is, new Color(0x8FA0B3)).paintIcon(this, g2, s(12) + s(10), fy + s(10));
            }
            Account ac = app.account();
            String name = ac == null || Utils.isBlank(ac.username) ? Tr.t("Гость", "Guest") : ac.username;
            int tx = s(12) + face + s(16);
            g2.setFont(SettingsKit.font(Font.BOLD, 18f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(name, fm, w - tx - s(40)), tx, h / 2 - s(4));
            boolean signed = ac != null;
            g2.setColor(signed ? SettingsKit.GREEN : SettingsKit.DIM);
            g2.fill(new Ellipse2D.Float(tx, h / 2f + s(8), s(11), s(11)));
            g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
            g2.setColor(new Color(0xB5C2D0));
            String state = !signed ? Tr.t("Не вошли", "Signed out")
                    : ac.type == Account.Type.ELY ? "Ely.by" : Tr.t("Онлайн", "Online");
            g2.drawString(state, tx + s(18), h / 2 + s(19));
            SettingsKit.icon(Glyph.CHEVRON, s(22), new Color(0x9AA9BA)).paintIcon(this, g2, w - s(36), (h - s(22)) / 2);
            g2.dispose();
        }
    }

    /** Плашка статуса сервера справа сверху. По щелчку — повторная проверка. */
    private final class ServerPill extends JComponent {
        private String text = Tr.t("Проверка сервера…", "Checking server…");
        private Color tone = SettingsKit.DIM;

        ServerPill() {
            setPreferredSize(new Dimension(s(200), s(46)));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    app.checkServer();
                }
            });
        }

        void refresh() {
            ServerStatus.Info srv = app.serverInfo();
            String t;
            Color c;
            if (srv == null || !srv.available()) {
                t = Tr.t("Сервер недоступен", "Server unavailable");
                c = SettingsKit.DIM;
                setToolTipText(null);
            } else if (srv.online()) {
                t = Tr.t("Сервер в сети", "Server online");
                c = SettingsKit.GREEN;
                setToolTipText(Tr.t("Игроков: ", "Players: ") + srv.players() + "/" + srv.maxPlayers() + " · " + app.cfg.serverIp);
            } else {
                t = Tr.t("Сервер не отвечает", "Server not responding");
                c = SettingsKit.AMBER;
                setToolTipText(null);
            }
            if (t.equals(text) && c.equals(tone)) return;
            text = t;
            tone = c;
            FontMetrics fm = getFontMetrics(SettingsKit.font(Font.PLAIN, 17.5f));
            Dimension d = new Dimension(fm.stringWidth(text) + s(72), s(46));
            if (!d.equals(getPreferredSize())) {
                setPreferredSize(d);
                Shell.this.doLayout();
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            g2.setColor(SettingsKit.card());
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(tone);
            g2.fill(new Ellipse2D.Float(s(18), h / 2f - s(7), s(14), s(14)));
            g2.setFont(SettingsKit.font(Font.PLAIN, 17.5f));
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(tone == SettingsKit.DIM ? SettingsKit.MUTED : tone);
            g2.drawString(text, s(42), (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }
}
