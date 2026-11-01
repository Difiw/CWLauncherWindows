package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.server.LocalServerManager;
import ru.cw.launcher.server.LocalServerManager.Players;
import ru.cw.launcher.server.LocalServerManager.Server;
import ru.cw.launcher.server.LocalServerManager.Stats;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Dropdown;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.MenuItem;
import ru.cw.launcher.ui.SettingsKit.Slider;
import ru.cw.launcher.ui.SettingsKit.Toggle;
import ru.cw.launcher.util.Json;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Страница «Серверы» (режим разработчика): локальные миры, которые лаунчер создаёт и держит.
 * Сверху карточки серверов, ниже выбранный сервер с нагрузкой, вкладки «Консоль, Файлы, Игроки,
 * Миры, Моды, Резервные копии, Настройки», справа игроки онлайн и быстрые действия.
 */
final class ServersPage extends JPanel {

    private enum Tab {CONSOLE, FILES, PLAYERS, WORLDS, MODS, BACKUPS, SETTINGS}

    private final MainWindow mw;
    private final LauncherApp app;
    private final LocalServerManager manager;
    private final ExecutorService poller = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cw-servers-page");
        t.setDaemon(true);
        return t;
    });
    private final Timer timer;
    private volatile boolean polling;

    private final JLabel title = SettingsKit.label(Tr.t("Серверы", "Servers"), 40f, Font.BOLD, SettingsKit.TEXT);
    private final JLabel subtitle = SettingsKit.label(Tr.t("Локальные миры для игры с друзьями", "Local worlds to play with friends"),
            17f, Font.PLAIN, new Color(0xB5C2D0));
    private final Button create = new Button(Tr.t("Создать сервер", "Create server"), Glyph.PLUS, Kind.PRIMARY);
    private final Button importBtn = new Button(Tr.t("Импортировать", "Import"), Glyph.FOLDER, Kind.GHOST);
    private final JPanel strip = new JPanel(null);
    private final JScrollPane stripScroll = new JScrollPane(strip);
    private final Detail detail = new Detail();
    private final StatTile cpuTile = new StatTile(Glyph.CHIP, "CPU");
    private final StatTile ramTile = new StatTile(Glyph.LAYERS, "RAM");
    private final StatTile diskTile = new StatTile(Glyph.ARCHIVE, Tr.t("Диск", "Disk"));
    private final StatTile tpsTile = new StatTile(Glyph.CLOCK, "TPS");
    private final List<TabButton> tabs = new ArrayList<>();
    private final JPanel content = new JPanel(new BorderLayout());
    private final PlayersBox playersBox = new PlayersBox();
    private final QuickActions quick = new QuickActions();
    private final Empty empty = new Empty();

    private String selected;
    private Tab tab = Tab.CONSOLE;
    private Stats stats;
    private Players players = new Players(0, 0, List.of());
    private List<String> log = List.of();
    private final Map<String, Players> listPlayers = new HashMap<>();
    private final Map<String, Deque<double[]>> history = new HashMap<>();
    private final List<String> echo = new ArrayList<>();
    private String clearedAfter;
    private final ConsolePane console = new ConsolePane();

    ServersPage(MainWindow mw, LauncherApp app) {
        super(null);
        this.mw = mw;
        this.app = app;
        this.manager = app.localServers;
        setOpaque(false);
        strip.setOpaque(false);
        stripScroll.setOpaque(false);
        stripScroll.getViewport().setOpaque(false);
        stripScroll.setBorder(BorderFactory.createEmptyBorder());
        stripScroll.setViewportBorder(null);
        stripScroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        stripScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        stripScroll.getHorizontalScrollBar().setUI(new SettingsKit.ScrollBarUI());
        stripScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(s(8), s(8)));
        stripScroll.getHorizontalScrollBar().setOpaque(false);
        content.setOpaque(false);
        create.setFont(SettingsKit.font(Font.BOLD, 16f));
        create.addActionListener(e -> createServer());
        importBtn.addActionListener(e -> importServer());
        Object[][] tabDefs = {
                {Tab.CONSOLE, Tr.t("Консоль", "Console"), Glyph.CHAT}, {Tab.FILES, Tr.t("Файлы", "Files"), Glyph.FOLDER},
                {Tab.PLAYERS, Tr.t("Игроки", "Players"), Glyph.USER}, {Tab.WORLDS, Tr.t("Миры", "Worlds"), Glyph.GLOBE},
                {Tab.MODS, Tr.t("Моды", "Mods"), Glyph.PUZZLE}, {Tab.BACKUPS, Tr.t("Резервные копии", "Backups"), Glyph.ARCHIVE},
                {Tab.SETTINGS, Tr.t("Настройки", "Settings"), Glyph.GEAR}};
        for (Object[] d : tabDefs) tabs.add(new TabButton((Tab) d[0], (String) d[1], (Glyph) d[2]));
        for (JComponent c : new JComponent[]{title, subtitle, create, importBtn, stripScroll, detail, cpuTile, ramTile,
                diskTile, tpsTile, content, playersBox, quick, empty}) add(c);
        for (TabButton t : tabs) add(t);
        List<Server> all = manager.servers();
        if (!all.isEmpty()) selected = all.get(0).id;
        fillStrip();
        showTab(Tab.CONSOLE);
        timer = new Timer(2000, e -> poll());
        timer.start();
        poll();
    }

    void dispose() {
        timer.stop();
        poller.shutdownNow();
    }

    private Server current() {
        return selected == null ? null : manager.server(selected);
    }

    // ------------------------------------------------------------------ данные

    private void poll() {
        if (polling || !isDisplayable()) return;
        polling = true;
        String id = selected;
        List<Server> all = manager.servers();
        poller.submit(() -> {
            try {
                Stats st = id == null ? null : manager.stats(id);
                Players pl = id == null ? new Players(0, 0, List.of()) : manager.players(id);
                List<String> lines = id == null ? List.of() : manager.tail(id, 400);
                Map<String, Players> counts = new HashMap<>();
                for (Server s : all) {
                    counts.put(s.id, s.id.equals(id) ? pl : manager.players(s.id));
                    if (manager.alive(s.id)) liveIds.add(s.id);
                    else liveIds.remove(s.id);
                    if (manager.rconEnabled(s.id)) noRcon.remove(s.id);
                    else noRcon.add(s.id);
                }
                SwingUtilities.invokeLater(() -> apply(id, st, pl, lines, counts));
            } catch (Exception e) {
                Log.debug("Опрос серверов: " + Log.reason(e));
            } finally {
                polling = false;
            }
        });
    }

    private void apply(String id, Stats st, Players pl, List<String> lines, Map<String, Players> counts) {
        listPlayers.putAll(counts);
        if (id != null && id.equals(selected)) {
            stats = st;
            players = pl;
            log = lines;
            if (st != null) {
                Deque<double[]> h = history.computeIfAbsent(id, k -> new ArrayDeque<>());
                h.addLast(new double[]{st.cpu(), st.ramMaxBytes() <= 0 ? 0 : st.ramBytes() * 100.0 / st.ramMaxBytes(),
                        st.diskBytes(), st.tps()});
                while (h.size() > 40) h.removeFirst();
            }
            console.refresh();
            playersBox.fill();
        }
        for (Component c : strip.getComponents()) c.repaint();
        detail.sync();
        for (StatTile t : new StatTile[]{cpuTile, ramTile, diskTile, tpsTile}) t.repaint();
        for (TabButton t : tabs) t.repaint();
        quick.repaint();
    }

    private void select(String id) {
        selected = id;
        stats = null;
        players = new Players(0, 0, List.of());
        log = List.of();
        echo.clear();
        clearedAfter = null;
        for (Component c : strip.getComponents()) c.repaint();
        showTab(tab);
        detail.sync();
        poll();
    }

    private void reloadAll() {
        List<Server> all = manager.servers();
        if (selected != null && manager.server(selected) == null) selected = all.isEmpty() ? null : all.get(0).id;
        if (selected == null && !all.isEmpty()) selected = all.get(0).id;
        fillStrip();
        showTab(tab);
        detail.sync();
        poll();
    }

    // ------------------------------------------------------------------ раскладка

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int x0 = s(20);
        int right = w - s(20);
        boolean none = manager.servers().isEmpty();
        title.setBounds(x0 + s(16), s(26), s(400), s(54));
        subtitle.setBounds(x0 + s(18), s(80), s(520), s(28));
        // Кнопки — левее плашки «Сервер в сети» и кнопок окна.
        int bRight = right - s(240);
        create.setBounds(bRight - s(230), s(42), s(230), s(52));
        importBtn.setBounds(bRight - s(230) - s(12) - s(200), s(42), s(200), s(52));
        for (Component c : getComponents()) {
            if (c == title || c == subtitle || c == create || c == importBtn) continue;
            c.setVisible(none ? c == empty : c != empty);
        }
        if (none) {
            empty.setBounds(x0, s(130), right - x0, h - s(150));
            return;
        }
        int y = s(122);
        stripScroll.setBounds(x0, y, right - x0, s(96) + s(10));
        layoutStrip();
        y += s(110);
        int detailH = s(176);
        int statsW = Math.min(s(760), (right - x0) * 52 / 100);
        detail.setBounds(x0, y, right - x0, detailH);
        int tw = (statsW - s(10) * 3) / 4;
        int tx = right - s(16) - statsW;
        int ty = y + detailH - s(16) - s(86);
        for (StatTile t : new StatTile[]{cpuTile, ramTile, diskTile, tpsTile}) {
            t.setBounds(tx, ty, tw, s(86));
            tx += tw + s(10);
        }
        setComponentZOrder(detail, getComponentCount() - 1);
        y += detailH + s(12);
        int tabX = x0;
        for (TabButton t : tabs) {
            int bw = t.preferredWidth();
            t.setBounds(tabX, y, bw, s(46));
            tabX += bw + s(6);
        }
        y += s(56);
        int sideW = Math.max(s(330), (right - x0) * 28 / 100);
        content.setBounds(x0, y, right - x0 - sideW - s(14), h - y - s(18));
        int qH = s(190);
        playersBox.setBounds(right - sideW, y, sideW, h - y - s(18) - qH - s(12));
        quick.setBounds(right - sideW, h - s(18) - qH, sideW, qH);
    }

    private void fillStrip() {
        strip.removeAll();
        for (Server s : manager.servers()) strip.add(new ServerCard(s.id));
        layoutStrip();
        doLayout();
        repaint();
    }

    private void layoutStrip() {
        int n = strip.getComponentCount();
        if (n == 0) return;
        int avail = stripScroll.getWidth();
        int gap = s(14);
        int cw = Math.max(s(280), (avail - gap * (n - 1)) / Math.min(n, 3));
        int x = 0;
        for (Component c : strip.getComponents()) {
            c.setBounds(x, 0, cw, s(96));
            x += cw + gap;
        }
        strip.setPreferredSize(new Dimension(x - gap, s(96)));
        strip.revalidate();
    }

    private static void card(Graphics2D g2, int w, int h) {
        int r = SettingsKit.radius() + s(2);
        g2.setColor(SettingsKit.card());
        g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
        g2.setColor(SettingsKit.CARD_LINE);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
    }

    /** Состояние сервера для интерфейса: текст и цвет. */
    private String[] state(Server s) {
        String st = s == null ? "" : s.status();
        if (s != null && liveIds.contains(s.id) && !st.startsWith("Останавлива") && !st.startsWith("Перезапуск")) st = "Работает";
        else if (s != null && !liveIds.contains(s.id) && st.startsWith("Работает")) st = "Остановлен";
        if (st.startsWith("Работает")) return new String[]{Tr.t("Запущен", "Running"), "green"};
        if (st.startsWith("Запуска") || st.startsWith("Проверка") || st.startsWith("Подготовка") || st.startsWith("Перезапуск")) {
            return new String[]{Tr.t("Запускается", "Starting"), "amber"};
        }
        if (st.startsWith("Останавлива")) return new String[]{Tr.t("Останавливается", "Stopping"), "amber"};
        if (st.startsWith("Ошибка") || st.startsWith("Не запущен")) return new String[]{st, "red"};
        return new String[]{Tr.t("Остановлен", "Stopped"), "dim"};
    }

    private static Color tone(String key) {
        return switch (key) {
            case "green" -> SettingsKit.GREEN;
            case "amber" -> SettingsKit.AMBER;
            case "red" -> SettingsKit.RED;
            default -> new Color(0x8A99AB);
        };
    }

    private boolean alive() {
        Server s = current();
        return s != null && (s.running() || stats != null && stats.uptimeMs() > 0 || liveIds.contains(s.id));
    }

    /** Серверы, чей процесс найден при последнем опросе (даже без RCON). */
    private final java.util.Set<String> liveIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Серверы с выключенным RCON: лаунчер видит процесс, но не может им управлять. */
    private final java.util.Set<String> noRcon = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Кусок фона как миниатюра сервера: у каждого сервера свой участок картинки. */
    private BufferedImage thumb(String id) {
        BufferedImage pic = app.background().picture();
        if (pic == null) return null;
        int index = Math.floorMod(id.hashCode(), 4);
        int cw = pic.getWidth() / 3;
        int chh = pic.getHeight() / 2;
        int cx = (pic.getWidth() - cw) * index / 3;
        int cy = pic.getHeight() - chh - (index % 2) * pic.getHeight() / 10;
        return pic.getSubimage(Math.max(0, cx), Math.max(0, cy), cw, chh);
    }

    private final Map<String, BufferedImage> thumbCache = new HashMap<>();

    /**
     * Миниатюра готового размера со скруглёнными углами. Масштабирование большого фона
     * со скруглённой обрезкой на каждом кадре роняло страницу до нескольких FPS —
     * теперь картинка считается один раз на размер и рисуется как есть.
     */
    private BufferedImage thumbFrame(String id, int w, int h) {
        BufferedImage pic = app.background().picture();
        if (pic == null || w <= 0 || h <= 0) return null;
        String key = id + '|' + w + 'x' + h + '|' + System.identityHashCode(pic) + '|' + SettingsKit.rr();
        BufferedImage cached = thumbCache.get(key);
        if (cached != null) return cached;
        if (thumbCache.size() > 32) thumbCache.clear();
        BufferedImage img = thumb(id);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        SettingsKit.smooth(g);
        g.setColor(Color.WHITE);
        g.fill(new RoundRectangle2D.Float(0, 0, w, h, SettingsKit.rr(), SettingsKit.rr()));
        g.setComposite(AlphaComposite.SrcIn);
        double scale = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.ceil(img.getWidth() * scale);
        int dh = (int) Math.ceil(img.getHeight() * scale);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
        g.dispose();
        thumbCache.put(key, out);
        return out;
    }

    private static String uptime(long ms) {
        long m = ms / 60000;
        long hh = m / 60;
        if (hh > 0) return Tr.t(hh + " ч " + (m % 60) + " мин", hh + " h " + (m % 60) + " min");
        return Tr.t(Math.max(1, m) + " мин", Math.max(1, m) + " min");
    }

    // ------------------------------------------------------------------ действия

    private void run(String okText, Runnable action) {
        action.run();
        if (okText != null) Dialogs.toast(mw, okText, SettingsKit.GREEN);
        poll();
    }

    private void command(String cmd, String okText) {
        Server s = current();
        if (s == null) return;
        poller.submit(() -> {
            String res = manager.command(s.id, cmd);
            SwingUtilities.invokeLater(() -> {
                addEcho("> " + cmd, res);
                if (res == null) Dialogs.toast(mw, Tr.t("Сервер не ответил. Он запущен?", "The server did not respond. Is it running?"), SettingsKit.AMBER);
                else if (okText != null) Dialogs.toast(mw, okText, SettingsKit.GREEN);
                poll();
            });
        });
    }

    private void addEcho(String line, String response) {
        String time = "[" + java.time.LocalTime.now().withNano(0) + "]";
        echo.add(time + " " + line);
        if (response != null && !response.isBlank()) for (String r : response.split("\n")) echo.add(time + " " + r);
        while (echo.size() > 200) echo.remove(0);
        console.refresh();
    }

    private void createServer() {
        new CreateDialog().open();
    }

    private void importServer() {
        Path dir = Utils.chooseDirectory(mw, Tr.t("Папка существующего сервера", "Existing server folder"));
        if (dir == null) return;
        String name = Dialogs.input(mw, Tr.t("Название сервера", "Server name"),
                Tr.t("Так сервер будет подписан в лаунчере.", "This is how the server is shown in the launcher."),
                dir.getFileName().toString(), Tr.t("Далее", "Next"));
        if (name == null) return;
        // Версию берём из файлов сервера, спрашиваем только если её не видно.
        String version = LocalServerManager.detectVersion(dir);
        if (version == null) version = Dialogs.input(mw, Tr.t("Версия Minecraft сервера", "Server Minecraft version"),
                Tr.t("Не удалось определить по файлам. Например 1.20.1.", "Could not detect it from the files. For example 1.20.1."),
                app.cfg.minecraftVersion, Tr.t("Импортировать", "Import"));
        if (version == null) return;
        try {
            Server s = manager.importServer(name, dir, version.trim());
            selected = s.id;
            reloadAll();
            Dialogs.toast(mw, Tr.t("Сервер добавлен", "Server added"), SettingsKit.GREEN);
        } catch (Exception e) {
            Dialogs.error(mw, Tr.t("Не удалось импортировать сервер.", "Could not import the server."), Log.reason(e), null);
        }
    }

    // ------------------------------------------------------------------ вкладки

    private void showTab(Tab t) {
        tab = t;
        content.removeAll();
        Server s = current();
        if (s != null) {
            JComponent page = switch (t) {
                case CONSOLE -> console;
                case FILES -> new FilesPane(s.directory);
                case PLAYERS -> new PlayersPane();
                case WORLDS -> new WorldsPane();
                case MODS -> new ModsPane();
                case BACKUPS -> new BackupsPane();
                case SETTINGS -> new SettingsPane();
            };
            content.add(page, BorderLayout.CENTER);
            if (t == Tab.CONSOLE) console.refresh();
        }
        for (TabButton b : tabs) b.repaint();
        content.revalidate();
        content.repaint();
    }

    private final class TabButton extends JComponent {
        private final Tab t;
        private final String text;
        private final Glyph glyph;
        private boolean hover;

        TabButton(Tab t, String text, Glyph glyph) {
            this.t = t;
            this.text = text;
            this.glyph = glyph;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    showTab(t);
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

        private String label() {
            return t == Tab.PLAYERS && players.online() > 0 ? text + "  " + players.online() : text;
        }

        int preferredWidth() {
            return getFontMetrics(SettingsKit.font(Font.PLAIN, 15f)).stringWidth(text + "  00") + s(64);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            boolean on = tab == t;
            Shape shape = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(on ? SettingsKit.ACTIVE : hover ? new Color(0x182535) : SettingsKit.card());
            g2.fill(shape);
            g2.setColor(on ? SettingsKit.ACCENT : SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, SettingsKit.rr(), SettingsKit.rr()));
            int is = s(18);
            SettingsKit.icon(glyph, is, on ? Color.WHITE : new Color(0xB3C0CF)).paintIcon(this, g2, s(14), (h - is) / 2);
            g2.setFont(SettingsKit.font(on ? Font.BOLD : Font.PLAIN, 15f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(label(), fm, w - s(50)), s(42), (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ карточки серверов

    private final class ServerCard extends JComponent {
        private final String id;
        private boolean hover;

        ServerCard(String id) {
            this.id = id;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    select(id);
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
            Server s = manager.server(id);
            if (s == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            boolean on = id.equals(selected);
            card(g2, w, h);
            if (hover && !on) {
                g2.setColor(new Color(255, 255, 255, 8));
                g2.fill(new RoundRectangle2D.Float(0, 0, w, h, SettingsKit.radius(), SettingsKit.radius()));
            }
            if (on) {
                int r = SettingsKit.radius() + s(2);
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(SettingsKit.ACCENT);
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r));
            }
            int p = s(12);
            int tw = s(96);
            Shape box = new RoundRectangle2D.Float(p, p, tw, h - p * 2, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            g2.drawImage(thumbFrame(id, tw, h - p * 2), p, p, null);
            int x = p + tw + s(16);
            g2.setFont(SettingsKit.font(Font.BOLD, 17f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(s.name, fm, w - x - s(12)), x, p + s(22));
            String[] st = state(s);
            g2.setColor(tone(st[1]));
            g2.fill(new Ellipse2D.Float(x, p + s(34), s(11), s(11)));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(st[0], fm, w - x - s(30)), x + s(18), p + s(44));
            Players pl = listPlayers.get(id);
            g2.setColor(new Color(0xB5C2D0));
            String count = pl == null ? "" : pl.online() + " / " + pl.max() + Tr.t(" игроков", " players");
            g2.drawString(count, x, p + s(68));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ выбранный сервер

    private final class Detail extends JPanel {
        private final Button start = new Button(Tr.t("Запустить", "Start"), Glyph.PLAY, Kind.PRIMARY);
        private final Button restart = new Button(Tr.t("Перезапустить", "Restart"), Glyph.RESET, Kind.PRIMARY);
        private final Button stop = new Button(Tr.t("Остановить", "Stop"), Glyph.CLOSE, Kind.DANGER);
        private final Button kill = new Button("", Glyph.EXIT, Kind.GHOST).size(52, 52).iconSize(20);
        private final Button more = new Button("", Glyph.SLIDERS, Kind.GHOST).size(52, 52).iconSize(20);
        private final Button copy = new Button("", Glyph.COPY, Kind.FLAT).size(34, 34).iconSize(16);

        Detail() {
            super(null);
            setOpaque(false);
            for (JComponent c : new JComponent[]{start, restart, stop, kill, more, copy}) add(c);
            kill.setToolTipText(Tr.t("Принудительно завершить (если сервер завис)", "Force kill (if the server hangs)"));
            more.setToolTipText(Tr.t("Ещё", "More"));
            copy.setToolTipText(Tr.t("Скопировать адрес", "Copy address"));
            start.addActionListener(e -> run(Tr.t("Сервер запускается", "Server is starting"), () -> manager.start(selected)));
            restart.addActionListener(e -> run(Tr.t("Сервер перезапускается", "Server is restarting"), () -> manager.restart(selected)));
            stop.addActionListener(e -> run(Tr.t("Сервер останавливается", "Server is stopping"), () -> manager.stop(selected)));
            kill.addActionListener(e -> {
                if (Dialogs.ask(mw, Tr.t("Принудительно завершить сервер? Несохранённый прогресс мира может пропасть.",
                                "Force kill the server? Unsaved world progress may be lost."),
                        Tr.t("Завершить", "Kill"), Tr.t("Отмена", "Cancel"))) run(null, () -> manager.kill(selected));
            });
            copy.addActionListener(e -> {
                Server s = current();
                if (s == null) return;
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s.address()), null);
                Dialogs.toast(mw, Tr.t("Адрес скопирован: ", "Address copied: ") + s.address(), SettingsKit.GREEN);
            });
            more.addActionListener(e -> {
                Server s = current();
                if (s == null) return;
                JPopupMenu m = SettingsKit.menu(s(260),
                        new MenuItem(Tr.t("Открыть папку сервера", "Open server folder"), Glyph.FOLDER, () -> mw.openFolder(s.directory)),
                        new MenuItem(Tr.t("Открыть server.log", "Open server.log"), Glyph.FILE, () -> openFile(s.directory.resolve("server.log"))),
                        new MenuItem(Tr.t("Убрать из лаунчера", "Remove from launcher"), Glyph.TRASH, this::forget, true));
                m.show(more, more.getWidth() - s(260), more.getHeight() + s(4));
            });
        }

        private void forget() {
            Server s = current();
            if (s == null) return;
            if (!Dialogs.ask(mw, Tr.t("Убрать «" + s.name + "» из лаунчера? Папка сервера с мирами останется на диске.",
                            "Remove “" + s.name + "” from the launcher? The server folder stays on disk."),
                    Tr.t("Убрать", "Remove"), Tr.t("Отмена", "Cancel"))) return;
            try {
                if (alive()) manager.stop(s.id);
                manager.forget(s.id);
                selected = null;
                reloadAll();
            } catch (Exception e) {
                Dialogs.error(mw, Tr.t("Не удалось убрать сервер.", "Could not remove the server."), Log.reason(e), null);
            }
        }

        void sync() {
            boolean on = alive();
            start.setVisible(!on);
            restart.setVisible(on);
            stop.setVisible(on);
            kill.setVisible(on);
            doLayout();
            repaint();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int p = s(16);
            int by = p;
            int x = w - p - s(52);
            more.setBounds(x, by, s(52), s(52));
            if (kill.isVisible()) {
                x -= s(62);
                kill.setBounds(x, by, s(52), s(52));
            }
            if (stop.isVisible()) {
                x -= s(10) + s(190);
                stop.setBounds(x, by, s(190), s(52));
            }
            if (restart.isVisible()) {
                x -= s(10) + s(220);
                restart.setBounds(x, by, s(220), s(52));
            }
            if (start.isVisible()) {
                x -= s(10) + s(200);
                start.setBounds(x, by, s(200), s(52));
            }
            int thumbW = Math.min(s(250), (getHeight() - p * 2) * 16 / 10);
            int tx = p + thumbW + s(20);
            Server s = current();
            if (s != null) {
                FontMetrics fm = getFontMetrics(SettingsKit.font(Font.PLAIN, 15.5f));
                copy.setBounds(tx + s(30) + fm.stringWidth(s.address()) + s(8), p + s(54), s(34), s(34));
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Server s = current();
            if (s == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            card(g2, w, h);
            int p = s(16);
            int thumbW = Math.min(s(250), (h - p * 2) * 16 / 10);
            Shape box = new RoundRectangle2D.Float(p, p, thumbW, h - p * 2, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            g2.drawImage(thumbFrame(s.id, thumbW, h - p * 2), p, p, null);
            int x = p + thumbW + s(20);
            int textMax = Math.max(s(200), (start.isVisible() ? start.getX() : restart.getX()) - x - s(16));
            g2.setFont(SettingsKit.font(Font.BOLD, 28f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(s.name, fm, textMax), x, p + s(32));
            g2.setFont(SettingsKit.font(Font.PLAIN, 15.5f));
            g2.setColor(new Color(0xD5DEE8));
            SettingsKit.icon(Glyph.SERVERS, s(18), new Color(0xB3C0CF)).paintIcon(this, g2, x, p + s(61));
            g2.drawString(s.address(), x + s(30), p + s(76));
            SettingsKit.icon(Glyph.CUBE, s(18), new Color(0xB3C0CF)).paintIcon(this, g2, x, p + s(91));
            g2.drawString(s.minecraftVersion + "  ·  Fabric", x + s(30), p + s(106));
            String[] st = state(s);
            g2.setColor(tone(st[1]));
            g2.fill(new Ellipse2D.Float(x + s(2), p + s(124), s(14), s(14)));
            String line = st[0];
            if (stats != null && stats.uptimeMs() > 0 && "green".equals(st[1])) {
                line = Tr.t("Работает ", "Running for ") + uptime(stats.uptimeMs());
            }
            if (noRcon.contains(s.id) && liveIds.contains(s.id)) {
                // Сервер запущен без RCON (например, папка скопирована с другого сервера).
                line += Tr.t("  ·  нужен перезапуск", "  ·  restart needed");
                g2.setColor(SettingsKit.AMBER);
            }
            // нижняя строка не должна заходить под плитки нагрузки
            int lineMax = Math.max(s(160), cpuTile.getX() - getX() - x - s(46));
            g2.drawString(SettingsKit.ellipsize(line, g2.getFontMetrics(), lineMax), x + s(30), p + s(136));
            g2.dispose();
        }
    }

    /** Плитка нагрузки: значение, полоса и график за последние минуты. */
    private final class StatTile extends JComponent {
        private final Glyph glyph;
        private final String name;

        StatTile(Glyph glyph, String name) {
            this.glyph = glyph;
            this.name = name;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.rr() + s(2);
            g2.setColor(new Color(0x131E2B));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            int p = s(12);
            SettingsKit.icon(glyph, s(18), new Color(0xC9D3DE)).paintIcon(this, g2, p, p);
            g2.setFont(SettingsKit.font(Font.PLAIN, 13.5f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(name, p + s(26), p + s(14));
            int metric = this == cpuTile ? 0 : this == ramTile ? 1 : this == diskTile ? 2 : 3;
            boolean on = stats != null && stats.uptimeMs() > 0;
            String value;
            double frac;
            Color tone;
            if (!on) {
                value = "—";
                frac = 0;
                tone = new Color(0x5D6E82);
            } else if (metric == 0) {
                value = Math.round(stats.cpu()) + " %";
                frac = stats.cpu() / 100;
                tone = SettingsKit.ACCENT;
            } else if (metric == 1) {
                value = String.format(java.util.Locale.ROOT, "%.1f / %.0f %s", stats.ramBytes() / 1073741824.0,
                        stats.ramMaxBytes() / 1073741824.0, Tr.t("ГБ", "GB"));
                frac = stats.ramMaxBytes() <= 0 ? 0 : (double) stats.ramBytes() / stats.ramMaxBytes();
                tone = new Color(0x8B5CF6);
            } else if (metric == 2) {
                value = Utils.humanSize(stats.diskBytes());
                frac = Math.min(1, stats.diskBytes() / (10.0 * 1073741824.0));
                tone = SettingsKit.GREEN;
            } else {
                value = String.format(java.util.Locale.ROOT, "%.1f", stats.tps());
                frac = stats.tps() / 20.0;
                tone = stats.tps() >= 18 ? SettingsKit.GREEN : stats.tps() >= 12 ? SettingsKit.AMBER : SettingsKit.RED;
            }
            g2.setFont(SettingsKit.font(Font.BOLD, 17f));
            g2.setColor(Color.WHITE);
            g2.drawString(value, p, p + s(42));
            int barW = w * 45 / 100;
            int barY = h - p - s(6);
            g2.setColor(new Color(0x253446));
            g2.fill(new RoundRectangle2D.Float(p, barY, barW, s(6), s(6), s(6)));
            g2.setColor(tone);
            g2.fill(new RoundRectangle2D.Float(p, barY, Math.max(on ? s(6) : 0, (float) (barW * Math.max(0, Math.min(1, frac)))), s(6), s(6), s(6)));
            Deque<double[]> hist = selected == null ? null : history.get(selected);
            if (on && hist != null && hist.size() > 1) {
                double max = metric == 0 || metric == 1 ? 100 : metric == 3 ? 20 : 0;
                if (metric == 2) for (double[] v : hist) max = Math.max(max, v[2]);
                if (max <= 0) max = 1;
                int gx = p + barW + s(12);
                int gw = w - gx - p;
                int gh = h - p * 2 - s(22);
                int gy = p + s(22);
                Path2D line = new Path2D.Float();
                int i = 0;
                int n = hist.size();
                for (double[] v : hist) {
                    float px = gx + gw * i / (float) (n - 1);
                    float py = gy + gh - (float) (gh * Math.max(0, Math.min(1, v[metric] / max)));
                    if (i == 0) line.moveTo(px, py);
                    else line.lineTo(px, py);
                    i++;
                }
                g2.setColor(tone);
                g2.setStroke(new BasicStroke(s(2), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(line);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ консоль

    private final class ConsolePane extends JPanel {
        private final JTextArea area = new JTextArea();
        private final JScrollPane scroll = new JScrollPane(area);
        private final Toggle autoScroll = new Toggle(true);
        private final JLabel autoLabel = SettingsKit.label(Tr.t("Автопрокрутка", "Auto-scroll"), 14f, Font.PLAIN, SettingsKit.TEXT);
        private final Button clear = new Button(Tr.t("Очистить", "Clear"), Glyph.TRASH, Kind.GHOST);
        private final JTextField input = SettingsKit.field("");
        private final Button send = new Button(Tr.t("Отправить", "Send"), null, Kind.PRIMARY);
        private String shown = "";

        ConsolePane() {
            super(null);
            setOpaque(false);
            area.setUI(new javax.swing.plaf.basic.BasicTextAreaUI());
            area.setEditable(false);
            area.setOpaque(false);
            area.setForeground(new Color(0xD5DEE8));
            area.setFont(Theme.MONO.deriveFont(SettingsKit.sf(13.5f)));
            area.setSelectionColor(SettingsKit.ACTIVE);
            area.setBorder(BorderFactory.createEmptyBorder(s(6), s(6), s(6), s(6)));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setViewportBorder(null);
            SettingsKit.styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(s(20));
            input.putClientProperty("cw.hint", Tr.t("Введите команду, например say Привет", "Type a command, e.g. say Hello"));
            input.setFont(Theme.MONO.deriveFont(SettingsKit.sf(14f)));
            for (JComponent c : new JComponent[]{scroll, autoScroll, autoLabel, clear, input, send}) add(c);
            clear.addActionListener(e -> {
                clearedAfter = log.isEmpty() ? "" : log.get(log.size() - 1);
                echo.clear();
                refresh();
            });
            Runnable go = () -> {
                String cmd = input.getText().trim();
                if (cmd.isEmpty()) return;
                input.setText("");
                command(cmd, null);
            };
            send.addActionListener(e -> go.run());
            input.addActionListener(e -> go.run());
        }

        void refresh() {
            List<String> lines = new ArrayList<>();
            int start = 0;
            if (clearedAfter != null) {
                int idx = log.lastIndexOf(clearedAfter);
                start = idx < 0 ? 0 : idx + 1;
            }
            for (int i = start; i < log.size(); i++) lines.add(log.get(i));
            lines.addAll(echo);
            // Ответы команд встают по времени среди строк журнала.
            lines.sort((a, b) -> stamp(a).compareTo(stamp(b)));
            String text = String.join("\n", lines);
            if (current() != null && lines.isEmpty()) {
                text = alive() ? Tr.t("Журнал пока пуст.", "The log is empty yet.")
                        : Tr.t("Сервер остановлен. Нажмите «Запустить».", "The server is stopped. Press “Start”.");
            }
            if (text.equals(shown)) return;
            shown = text;
            area.setText(text);
            if (autoScroll.isOn()) SwingUtilities.invokeLater(() -> area.setCaretPosition(area.getDocument().getLength()));
        }

        private String stamp(String line) {
            return line.length() >= 10 && line.charAt(0) == '[' && line.charAt(9) == ']' ? line.substring(1, 9) : "";
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(14);
            clear.setBounds(w - p - s(150), p, s(150), s(40));
            autoLabel.setBounds(clear.getX() - s(14) - s(130), p, s(130), s(40));
            autoScroll.setBounds(autoLabel.getX() - s(68), p + s(2), s(64), s(36));
            int inputY = h - p - s(48);
            send.setBounds(w - p - s(160), inputY, s(160), s(48));
            input.setBounds(p, inputY, send.getX() - s(10) - p, s(48));
            scroll.setBounds(p, p + s(52), w - p * 2, inputY - s(12) - p - s(52));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            int p = s(14);
            g2.setColor(new Color(8, 13, 20, 160));
            int top = p + s(52);
            g2.fill(new RoundRectangle2D.Float(p, top, getWidth() - p * 2, scroll.getHeight(), SettingsKit.rr(), SettingsKit.rr()));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ общие строки списков

    /** Карточка-список для вкладок: заголовок, кнопки сверху и строки с прокруткой. */
    private class ListPane extends JPanel {
        final JPanel rows = new JPanel();
        final JScrollPane scroll = new JScrollPane(rows);
        final JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, s(10), 0));
        final JLabel caption = SettingsKit.label("", 18f, Font.BOLD, SettingsKit.TEXT);

        ListPane(String title) {
            super(null);
            setOpaque(false);
            caption.setText(title);
            rows.setOpaque(false);
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setViewportBorder(null);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(s(24));
            bar.setOpaque(false);
            add(caption);
            add(bar);
            add(scroll);
        }

        void addRow(Glyph glyph, String name, String sub, JComponent... actions) {
            JPanel r = new JPanel(new BorderLayout(s(14), 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    SettingsKit.smooth(g2);
                    g2.setColor(new Color(0x131E2B));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, SettingsKit.rr(), SettingsKit.rr()));
                    g2.dispose();
                }
            };
            r.setOpaque(false);
            r.setBorder(BorderFactory.createEmptyBorder(s(8), s(12), s(8), s(12)));
            r.add(new JLabel(SettingsKit.icon(glyph, s(22), new Color(0xC9D3DE))), BorderLayout.WEST);
            JPanel texts = new JPanel();
            texts.setOpaque(false);
            texts.setLayout(new BoxLayout(texts, BoxLayout.Y_AXIS));
            texts.add(SettingsKit.label(name, 15.5f, Font.BOLD, Color.WHITE));
            if (sub != null && !sub.isBlank()) texts.add(SettingsKit.label(sub, 13f, Font.PLAIN, new Color(0x9AA9BA)));
            r.add(texts, BorderLayout.CENTER);
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, s(8), 0));
            east.setOpaque(false);
            for (JComponent a : actions) east.add(a);
            JPanel eastWrap = new JPanel(new GridBagLayout());
            eastWrap.setOpaque(false);
            eastWrap.add(east);
            r.add(eastWrap, BorderLayout.EAST);
            r.setAlignmentX(LEFT_ALIGNMENT);
            r.setMaximumSize(new Dimension(Integer.MAX_VALUE, s(60)));
            r.setPreferredSize(new Dimension(s(400), s(60)));
            rows.add(r);
            rows.add(Box.createVerticalStrut(s(8)));
        }

        void note(String text) {
            JLabel l = SettingsKit.label(text, 15f, Font.PLAIN, new Color(0xB5C2D0));
            l.setAlignmentX(LEFT_ALIGNMENT);
            rows.add(l);
        }

        Button small(String text, Glyph glyph, Kind kind, Runnable action) {
            Button b = new Button(text, glyph, kind);
            b.setFont(SettingsKit.font(Font.BOLD, 13.5f));
            FontMetrics fm = b.getFontMetrics(b.getFont());
            int w = fm.stringWidth(text == null ? "" : text) + s(glyph == null ? 32 : 58);
            if (text == null || text.isEmpty()) w = s(40);
            b.setPreferredSize(new Dimension(w, s(38)));
            b.addActionListener(e -> action.run());
            return b;
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int p = s(16);
            caption.setBounds(p + s(2), p, w / 2, s(36));
            Dimension bp = bar.getPreferredSize();
            bar.setBounds(w - p - bp.width, p, bp.width, s(40));
            scroll.setBounds(p, p + s(52), w - p * 2, h - p * 2 - s(52));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private void openFile(Path file) {
        try {
            Desktop.getDesktop().open(file.toFile());
        } catch (Exception e) {
            mw.openFolder(file.getParent());
        }
    }

    private final class FilesPane extends ListPane {
        private final Path root;
        private Path dir;

        FilesPane(Path root) {
            super(Tr.t("Файлы сервера", "Server files"));
            this.root = root;
            this.dir = root;
            fill();
        }

        private void fill() {
            rows.removeAll();
            bar.removeAll();
            caption.setText(root.equals(dir) ? Tr.t("Файлы сервера", "Server files") : root.relativize(dir).toString());
            if (!dir.equals(root)) bar.add(small(Tr.t("Назад", "Up"), Glyph.LEFT, Kind.GHOST, () -> {
                dir = dir.getParent();
                fill();
            }));
            bar.add(small(Tr.t("Открыть в проводнике", "Open in Explorer"), Glyph.FOLDER, Kind.GHOST, () -> mw.openFolder(dir)));
            List<Path> entries = new ArrayList<>();
            try (var list = Files.list(dir)) {
                list.sorted((a, b) -> {
                    boolean da = Files.isDirectory(a);
                    boolean db = Files.isDirectory(b);
                    if (da != db) return da ? -1 : 1;
                    return a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString());
                }).forEach(entries::add);
            } catch (Exception e) {
                note(Tr.t("Папка недоступна: ", "Folder unavailable: ") + Log.reason(e));
            }
            for (Path p : entries) {
                boolean folder = Files.isDirectory(p);
                String sub;
                try {
                    sub = folder ? Tr.t("Папка", "Folder") : Utils.humanSize(Files.size(p));
                } catch (Exception e) {
                    sub = "";
                }
                addRow(folder ? Glyph.FOLDER : Glyph.FILE, p.getFileName().toString(), sub,
                        small(folder ? Tr.t("Открыть", "Open") : Tr.t("Открыть файл", "Open file"), null, Kind.GHOST, () -> {
                            if (folder) {
                                dir = p;
                                fill();
                            } else {
                                openFile(p);
                            }
                        }));
            }
            rows.revalidate();
            rows.repaint();
            doLayout();
        }
    }

    private final class PlayersPane extends ListPane {
        PlayersPane() {
            super(Tr.t("Игроки", "Players"));
            JTextField nick = SettingsKit.field("");
            nick.putClientProperty("cw.hint", Tr.t("Ник игрока", "Player name"));
            nick.setPreferredSize(new Dimension(s(200), s(38)));
            bar.add(nick);
            bar.add(small(Tr.t("В белый список", "Whitelist"), Glyph.PLUS, Kind.GHOST, () -> {
                if (!nick.getText().isBlank()) command("whitelist add " + nick.getText().trim(), Tr.t("Добавлен в белый список", "Whitelisted"));
            }));
            bar.add(small(Tr.t("Сделать оператором", "Make operator"), Glyph.SHIELD, Kind.GHOST, () -> {
                if (!nick.getText().isBlank()) command("op " + nick.getText().trim(), Tr.t("Выданы права оператора", "Operator granted"));
            }));
            if (players.names().isEmpty()) note(alive() ? Tr.t("Сейчас никого нет на сервере.", "Nobody is online.")
                    : Tr.t("Сервер остановлен.", "The server is stopped."));
            for (String n : players.names()) {
                addRow(Glyph.USER, n, role(n), small(Tr.t("Действия", "Actions"), Glyph.SLIDERS, Kind.GHOST, () -> {
                }));
                // Кнопка «Действия» открывает то же меню, что и в списке справа.
                JComponent row = (JComponent) rows.getComponent(rows.getComponentCount() - 2);
                Button b = findButton(row);
                if (b != null) {
                    for (var l : b.getActionListeners()) b.removeActionListener(l);
                    b.addActionListener(e -> playerMenu(n).show(b, 0, b.getHeight()));
                }
            }
        }
    }

    private static Button findButton(Container c) {
        for (Component k : c.getComponents()) {
            if (k instanceof Button b) return b;
            if (k instanceof Container cc) {
                Button b = findButton(cc);
                if (b != null) return b;
            }
        }
        return null;
    }

    private final class WorldsPane extends ListPane {
        WorldsPane() {
            super(Tr.t("Миры", "Worlds"));
            Server s = current();
            String level = manager.properties(s.id).getOrDefault("level-name", "world");
            List<Path> worlds = manager.worlds(s.id);
            if (worlds.isEmpty()) note(Tr.t("Мир появится после первого запуска сервера.", "The world appears after the first start."));
            for (Path w : worlds) {
                boolean active = w.getFileName().toString().equals(level);
                String sub = (active ? Tr.t("Основной мир · ", "Main world · ") : "") + Utils.humanSize(Utils.dirSize(w));
                List<JComponent> actions = new ArrayList<>();
                if (!active) actions.add(small(Tr.t("Сделать основным", "Make main"), Glyph.CHECK, Kind.GHOST, () -> {
                    try {
                        manager.saveProperties(s.id, Map.of("level-name", w.getFileName().toString()));
                        Dialogs.toast(mw, Tr.t("Мир выбран. Перезапустите сервер.", "World selected. Restart the server."), SettingsKit.GREEN);
                        showTab(Tab.WORLDS);
                    } catch (Exception e) {
                        Dialogs.error(mw, Tr.t("Не удалось выбрать мир.", "Could not select the world."), Log.reason(e), null);
                    }
                }));
                actions.add(small("", Glyph.FOLDER, Kind.GHOST, () -> mw.openFolder(w)));
                addRow(Glyph.GLOBE, w.getFileName().toString(), sub, actions.toArray(new JComponent[0]));
            }
        }
    }

    private final class ModsPane extends ListPane {
        ModsPane() {
            super(Tr.t("Моды сервера", "Server mods"));
            Server s = current();
            Path mods = s.directory.resolve("mods");
            bar.add(small(Tr.t("Добавить мод", "Add mod"), Glyph.PLUS, Kind.GHOST, () -> {
                FileDialog fd = new FileDialog(mw, Tr.t("Мод (.jar)", "Mod (.jar)"), FileDialog.LOAD);
                fd.setFile("*.jar");
                fd.setVisible(true);
                if (fd.getFile() == null) return;
                try {
                    Files.createDirectories(mods);
                    Files.copy(Path.of(fd.getDirectory(), fd.getFile()), mods.resolve(fd.getFile()),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    Dialogs.toast(mw, Tr.t("Мод добавлен. Перезапустите сервер.", "Mod added. Restart the server."), SettingsKit.GREEN);
                    showTab(Tab.MODS);
                } catch (Exception e) {
                    Dialogs.error(mw, Tr.t("Мод не добавлен.", "Mod not added."), Log.reason(e), null);
                }
            }));
            bar.add(small(Tr.t("Папка модов", "Mods folder"), Glyph.FOLDER, Kind.GHOST, () -> mw.openFolder(mods)));
            List<Path> jars = new ArrayList<>();
            try (var list = Files.list(mods)) {
                list.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    return n.endsWith(".jar") || n.endsWith(".jar.disabled");
                }).sorted().forEach(jars::add);
            } catch (Exception ignored) {
            }
            if (jars.isEmpty()) note(Tr.t("Модов нет. Сервер работает на чистом Fabric.", "No mods. The server runs plain Fabric."));
            for (Path jar : jars) {
                boolean on = jar.getFileName().toString().endsWith(".jar");
                Toggle t = new Toggle(on);
                t.setToolTipText(Tr.t("Включить или выключить мод (после перезапуска)", "Enable or disable (after restart)"));
                t.onChange(() -> {
                    try {
                        String n = jar.getFileName().toString();
                        Path target = t.isOn() ? jar.resolveSibling(n.replaceFirst("\\.disabled$", "")) : jar.resolveSibling(n + ".disabled");
                        Files.move(jar, target);
                        showTab(Tab.MODS);
                    } catch (Exception e) {
                        Dialogs.error(mw, Tr.t("Не удалось переключить мод.", "Could not toggle the mod."), Log.reason(e), null);
                    }
                });
                String name = jar.getFileName().toString().replaceFirst("\\.jar(\\.disabled)?$", "");
                long size = 0;
                try {
                    size = Files.size(jar);
                } catch (Exception ignored) {
                }
                addRow(Glyph.PUZZLE, name, (on ? "" : Tr.t("Выключен · ", "Disabled · ")) + Utils.humanSize(size), t);
            }
        }
    }

    private final class BackupsPane extends ListPane {
        BackupsPane() {
            super(Tr.t("Резервные копии", "Backups"));
            Server s = current();
            bar.add(small(Tr.t("Создать бэкап", "Create backup"), Glyph.ARCHIVE, Kind.PRIMARY, this::make));
            bar.add(small(Tr.t("Папка", "Folder"), Glyph.FOLDER, Kind.GHOST, () -> mw.openFolder(manager.backupsDir(s.id))));
            List<Path> list = manager.backups(s.id);
            if (list.isEmpty()) note(Tr.t("Копий пока нет. Автобэкап включается в настройках лаунчера → Серверы.",
                    "No backups yet. Auto-backup is in launcher settings → Servers."));
            for (Path zip : list) {
                long size = 0;
                try {
                    size = Files.size(zip);
                } catch (Exception ignored) {
                }
                addRow(Glyph.ARCHIVE, zip.getFileName().toString().replace(".zip", "").replace('_', ' '), Utils.humanSize(size),
                        small(Tr.t("Восстановить", "Restore"), Glyph.RESET, Kind.GHOST, () -> restore(zip)),
                        small("", Glyph.TRASH, Kind.DANGER, () -> {
                            if (!Dialogs.ask(mw, Tr.t("Удалить копию " + zip.getFileName() + "?", "Delete backup " + zip.getFileName() + "?"),
                                    Tr.t("Удалить", "Delete"), Tr.t("Отмена", "Cancel"))) return;
                            try {
                                Files.deleteIfExists(zip);
                            } catch (Exception ignored) {
                            }
                            showTab(Tab.BACKUPS);
                        }));
            }
        }

        private void make() {
            Server s = current();
            Dialogs.toast(mw, Tr.t("Создаю резервную копию…", "Creating a backup…"), SettingsKit.ACCENT);
            poller.submit(() -> {
                try {
                    Path zip = manager.backup(s.id);
                    SwingUtilities.invokeLater(() -> {
                        Dialogs.toast(mw, Tr.t("Копия готова: ", "Backup ready: ") + zip.getFileName(), SettingsKit.GREEN);
                        if (tab == Tab.BACKUPS) showTab(Tab.BACKUPS);
                    });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> Dialogs.error(mw, Tr.t("Копия не создана.", "Backup failed."), Log.reason(e), null));
                }
            });
        }

        private void restore(Path zip) {
            Server s = current();
            if (alive()) {
                Dialogs.info(mw, Tr.t("Сначала остановите сервер.", "Stop the server first."));
                return;
            }
            if (!Dialogs.ask(mw, Tr.t("Восстановить миры из " + zip.getFileName() + "? Текущие миры сохранятся рядом с пометкой before-restore.",
                            "Restore worlds from " + zip.getFileName() + "? Current worlds are kept with a before-restore mark."),
                    Tr.t("Восстановить", "Restore"), Tr.t("Отмена", "Cancel"))) return;
            try {
                manager.restore(s.id, zip);
                Dialogs.toast(mw, Tr.t("Мир восстановлен", "World restored"), SettingsKit.GREEN);
            } catch (Exception e) {
                Dialogs.error(mw, Tr.t("Не удалось восстановить.", "Restore failed."), Log.reason(e), null);
            }
        }
    }

    /** Настройки сервера: server.properties, память и автозапуск. */
    private final class SettingsPane extends JPanel {
        SettingsPane() {
            super(new BorderLayout());
            setOpaque(false);
            Server s = current();
            Map<String, String> props = manager.properties(s.id);
            JTextField motd = SettingsKit.field(props.getOrDefault("motd", s.name));
            Slider maxPlayers = new Slider(1, 100, (int) Utils.parseInt(props.getOrDefault("max-players", "20"), 20));
            Slider view = new Slider(3, 32, (int) Utils.parseInt(props.getOrDefault("view-distance", "10"), 10));
            Slider spawn = new Slider(0, 64, (int) Utils.parseInt(props.getOrDefault("spawn-protection", "16"), 16));
            String[] diffKeys = {"peaceful", "easy", "normal", "hard"};
            Dropdown difficulty = new Dropdown(new String[]{Tr.t("Мирная", "Peaceful"), Tr.t("Лёгкая", "Easy"),
                    Tr.t("Нормальная", "Normal"), Tr.t("Сложная", "Hard")}, index(diffKeys, props.getOrDefault("difficulty", "easy")));
            String[] modeKeys = {"survival", "creative", "adventure", "spectator"};
            Dropdown mode = new Dropdown(new String[]{Tr.t("Выживание", "Survival"), Tr.t("Творчество", "Creative"),
                    Tr.t("Приключение", "Adventure"), Tr.t("Наблюдатель", "Spectator")}, index(modeKeys, props.getOrDefault("gamemode", "survival")));
            Toggle pvp = new Toggle(!"false".equals(props.getOrDefault("pvp", "true")));
            Toggle online = new Toggle(!"false".equals(props.getOrDefault("online-mode", "true")));
            Toggle whitelist = new Toggle("true".equals(props.getOrDefault("white-list", "false")));
            Toggle flight = new Toggle("true".equals(props.getOrDefault("allow-flight", "false")));
            int capGb = Math.max(2, ru.cw.launcher.settings.SettingsManager.maxGameRamMb() / 1024);
            Slider ram = new Slider(1, capGb, Math.max(1, s.ramMb() / 1024));
            Toggle autoStart = new Toggle(s.autoStart());
            Button save = new Button(Tr.t("Сохранить настройки сервера", "Save server settings"), Glyph.SAVE, Kind.PRIMARY);
            save.addActionListener(e -> {
                Map<String, String> ch = new LinkedHashMap<>();
                ch.put("motd", motd.getText().trim());
                ch.put("max-players", String.valueOf(maxPlayers.getValue()));
                ch.put("view-distance", String.valueOf(view.getValue()));
                ch.put("spawn-protection", String.valueOf(spawn.getValue()));
                ch.put("difficulty", diffKeys[difficulty.getSelectedIndex()]);
                ch.put("gamemode", modeKeys[mode.getSelectedIndex()]);
                ch.put("pvp", String.valueOf(pvp.isOn()));
                ch.put("online-mode", String.valueOf(online.isOn()));
                ch.put("white-list", String.valueOf(whitelist.isOn()));
                ch.put("allow-flight", String.valueOf(flight.isOn()));
                try {
                    manager.saveProperties(s.id, ch);
                    manager.configure(s.id, ram.getValue() * 1024, autoStart.isOn());
                    Dialogs.toast(mw, alive() ? Tr.t("Сохранено. Изменения вступят в силу после перезапуска.",
                            "Saved. Changes apply after a restart.") : Tr.t("Настройки сервера сохранены", "Server settings saved"), SettingsKit.GREEN);
                } catch (Exception ex) {
                    Dialogs.error(mw, Tr.t("Не удалось сохранить.", "Could not save."), Log.reason(ex), null);
                }
            });
            String gb = Tr.t(" ГБ", " GB");
            SettingsKit.Card game = new SettingsKit.Card(Glyph.GAMEPAD, Tr.t("Игра", "Gameplay"), Tr.t("Правила мира", "World rules"))
                    .add(SettingsKit.labeled(Tr.t("Сложность", "Difficulty"), difficulty))
                    .add(SettingsKit.labeled(Tr.t("Режим игры", "Game mode"), mode))
                    .add(SettingsKit.row(null, "PvP", Tr.t("Игроки могут бить друг друга", "Players can hurt each other"), pvp))
                    .add(SettingsKit.row(null, Tr.t("Разрешить полёт", "Allow flight"), Tr.t("Не кикать за полёт (моды)", "Don't kick for flying (mods)"), flight))
                    .add(SettingsKit.slider(Tr.t("Защита спавна", "Spawn protection"), spawn, v -> v + Tr.t(" блоков", " blocks")));
            SettingsKit.Card access = new SettingsKit.Card(Glyph.SHIELD, Tr.t("Доступ", "Access"), Tr.t("Кто может зайти", "Who can join"))
                    .add(SettingsKit.labeled(Tr.t("Описание в списке серверов (MOTD)", "Server list description (MOTD)"), motd))
                    .add(SettingsKit.slider(Tr.t("Максимум игроков", "Max players"), maxPlayers, String::valueOf))
                    .add(SettingsKit.row(null, Tr.t("Белый список", "Whitelist"), Tr.t("Заходят только добавленные", "Only listed players"), whitelist))
                    .add(SettingsKit.row(null, Tr.t("Проверка лицензии", "Online mode"), Tr.t("Выключите для игры без лицензии", "Turn off for offline accounts"), online));
            SettingsKit.Card perf = new SettingsKit.Card(Glyph.CHIP, Tr.t("Производительность", "Performance"), Tr.t("Память и прорисовка", "Memory and distance"))
                    .add(SettingsKit.slider(Tr.t("Память сервера", "Server memory"), ram, v -> v + gb))
                    .add(SettingsKit.slider(Tr.t("Дальность прорисовки", "View distance"), view, v -> v + Tr.t(" чанков", " chunks")))
                    .add(SettingsKit.row(null, Tr.t("Запускать вместе с лаунчером", "Start with the launcher"),
                            Tr.t("По умолчанию выключено: сервер запускается только кнопкой", "Off by default: the server starts only with the button"), autoStart))
                    .add(SettingsKit.line(save));
            SettingsKit.ScrollPage page = new SettingsKit.ScrollPage(new SettingsKit.Columns(s(14)));
            page.setBorder(BorderFactory.createEmptyBorder(0, 0, s(8), s(12)));
            page.add(access);
            page.add(game);
            page.add(perf);
            JScrollPane sp = new JScrollPane(page);
            sp.setOpaque(false);
            sp.getViewport().setOpaque(false);
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.setViewportBorder(null);
            sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(sp);
            sp.getVerticalScrollBar().setUnitIncrement(s(28));
            add(sp, BorderLayout.CENTER);
        }

        private int index(String[] keys, String value) {
            for (int i = 0; i < keys.length; i++) if (keys[i].equalsIgnoreCase(value)) return i;
            return 0;
        }
    }

    // ------------------------------------------------------------------ игроки справа

    private String role(String name) {
        Account ac = app.account();
        if (ac != null && name.equalsIgnoreCase(ac.username)) return Tr.t("Владелец", "Owner");
        return ops().contains(name.toLowerCase(java.util.Locale.ROOT)) ? Tr.t("Оператор", "Operator") : Tr.t("Участник", "Member");
    }

    private java.util.Set<String> ops() {
        java.util.Set<String> out = new java.util.HashSet<>();
        Server s = current();
        if (s == null) return out;
        try {
            Path f = s.directory.resolve("ops.json");
            if (!Files.isRegularFile(f)) return out;
            for (Object o : Json.parseArray(Files.readString(f))) {
                if (o instanceof Map<?, ?> m && m.get("name") != null) out.add(String.valueOf(m.get("name")).toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private JPopupMenu playerMenu(String n) {
        boolean op = ops().contains(n.toLowerCase(java.util.Locale.ROOT));
        return SettingsKit.menu(s(260),
                new MenuItem(Tr.t("Режим: выживание", "Mode: survival"), Glyph.GAMEPAD, () -> command("gamemode survival " + n, null)),
                new MenuItem(Tr.t("Режим: творчество", "Mode: creative"), Glyph.BRUSH, () -> command("gamemode creative " + n, null)),
                new MenuItem(Tr.t("Режим: наблюдатель", "Mode: spectator"), Glyph.EYE, () -> command("gamemode spectator " + n, null)),
                new MenuItem(Tr.t("Выдать предмет", "Give item"), Glyph.PLUS, () -> {
                    String item = Dialogs.input(mw, Tr.t("Выдать предмет игроку " + n, "Give an item to " + n),
                            Tr.t("ID предмета и количество, например: diamond 64", "Item id and amount, e.g.: diamond 64"),
                            "diamond 1", Tr.t("Выдать", "Give"));
                    if (item != null && !item.isBlank()) command("give " + n + " " + item.trim(), Tr.t("Предмет выдан", "Item given"));
                }),
                new MenuItem(op ? Tr.t("Снять оператора", "Remove operator") : Tr.t("Сделать оператором", "Make operator"),
                        Glyph.SHIELD, () -> command((op ? "deop " : "op ") + n, null)),
                new MenuItem(Tr.t("Кикнуть", "Kick"), Glyph.EXIT, () -> command("kick " + n, Tr.t("Игрок кикнут", "Player kicked")), true),
                new MenuItem(Tr.t("Забанить", "Ban"), Glyph.CLOSE, () -> {
                    if (Dialogs.ask(mw, Tr.t("Забанить " + n + "?", "Ban " + n + "?"), Tr.t("Забанить", "Ban"), Tr.t("Отмена", "Cancel"))) {
                        command("ban " + n, Tr.t("Игрок забанен", "Player banned"));
                    }
                }, true));
    }

    private final class PlayersBox extends JPanel {
        private final JPanel list = new JPanel(null);

        PlayersBox() {
            super(null);
            setOpaque(false);
            list.setOpaque(false);
            add(list);
        }

        void fill() {
            list.removeAll();
            int y = 0;
            for (String n : players.names()) {
                PlayerRow r = new PlayerRow(n);
                list.add(r);
                r.setBounds(0, y, 10, s(52));
                y += s(56);
            }
            doLayout();
            repaint();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int p = s(16);
            list.setBounds(p, p + s(46), w - p * 2, getHeight() - p * 2 - s(46));
            for (Component c : list.getComponents()) c.setSize(w - p * 2, c.getHeight());
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            card(g2, w, getHeight());
            int p = s(16);
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Игроки онлайн", "Players online"), p + s(2), p + s(24));
            String count = players.online() + " / " + players.max();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(count, w - p - fm.stringWidth(count), p + s(24));
            if (players.names().isEmpty()) {
                g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
                g2.setColor(new Color(0x9AA9BA));
                g2.drawString(alive() ? Tr.t("Никого нет", "Nobody online") : Tr.t("Сервер остановлен", "Server stopped"), p + s(2), p + s(72));
            }
            g2.dispose();
        }
    }

    private final class PlayerRow extends JComponent {
        private final String name;
        private boolean hover;

        PlayerRow(String name) {
            this.name = name;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(Tr.t("Действия с игроком", "Player actions"));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    JPopupMenu m = playerMenu(name);
                    m.show(PlayerRow.this, getWidth() - s(260), getHeight());
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
            g2.setColor(hover ? new Color(0x182535) : new Color(0x131E2B));
            g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
            int box = h - s(14);
            Account ac = app.account();
            BufferedImage face = ac != null && name.equalsIgnoreCase(ac.username) ? ru.cw.launcher.skin.PlayerSkin.face() : null;
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(s(8), s(7), box, box, s(6), s(6)));
            if (face != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(face, s(8), s(7), box, box, null);
            } else {
                SettingsKit.icon(Glyph.USER, box - s(12), new Color(0x8FA0B3)).paintIcon(this, g2, s(14), s(13));
            }
            int x = s(8) + box + s(12);
            g2.setFont(SettingsKit.font(Font.BOLD, 15.5f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(name, fm, w / 2 - x), x, (h + fm.getAscent() - fm.getDescent()) / 2);
            String r = role(name);
            g2.setFont(SettingsKit.font(Font.BOLD, 12f));
            fm = g2.getFontMetrics();
            int bw = fm.stringWidth(r) + s(18);
            int bh = s(24);
            int bx = w - s(44) - bw;
            g2.setColor(new Color(0x1E2C3D));
            g2.fill(new RoundRectangle2D.Float(bx, (h - bh) / 2f, bw, bh, s(6), s(6)));
            g2.setColor(r.equals(Tr.t("Владелец", "Owner")) ? SettingsKit.AMBER : new Color(0xC9D3DE));
            g2.drawString(r, bx + s(9), (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.setColor(new Color(0x9AA9BA));
            for (int i = -1; i <= 1; i++) g2.fill(new Ellipse2D.Float(w - s(22), h / 2f + i * s(6) - s(2), s(4), s(4)));
            g2.dispose();
        }
    }

    private final class QuickActions extends JPanel {
        QuickActions() {
            super(null);
            setOpaque(false);
            Object[][] defs = {
                    {Tr.t("Сохранить мир", "Save world"), Glyph.SAVE, (Runnable) () -> command("save-all", Tr.t("Мир сохранён", "World saved"))},
                    {Tr.t("Белый список", "Whitelist"), Glyph.SHIELD, (Runnable) this::toggleWhitelist},
                    {Tr.t("Открыть папку", "Open folder"), Glyph.FOLDER, (Runnable) () -> {
                        Server s = current();
                        if (s != null) mw.openFolder(s.directory);
                    }},
                    {Tr.t("Создать бэкап", "Create backup"), Glyph.ARCHIVE, (Runnable) () -> {
                        Server s = current();
                        if (s == null) return;
                        Dialogs.toast(mw, Tr.t("Создаю резервную копию…", "Creating a backup…"), SettingsKit.ACCENT);
                        poller.submit(() -> {
                            try {
                                Path zip = manager.backup(s.id);
                                SwingUtilities.invokeLater(() -> Dialogs.toast(mw, Tr.t("Копия готова: ", "Backup ready: ") + zip.getFileName(), SettingsKit.GREEN));
                            } catch (Exception e) {
                                SwingUtilities.invokeLater(() -> Dialogs.error(mw, Tr.t("Копия не создана.", "Backup failed."), Log.reason(e), null));
                            }
                        });
                    }}};
            for (Object[] d : defs) {
                Button b = new Button((String) d[0], (Glyph) d[1], Kind.GHOST);
                b.setFont(SettingsKit.font(Font.BOLD, 14.5f));
                b.addActionListener(e -> ((Runnable) d[2]).run());
                add(b);
            }
        }

        private void toggleWhitelist() {
            Server s = current();
            if (s == null) return;
            boolean on = "true".equals(manager.properties(s.id).getOrDefault("white-list", "false"));
            if (alive()) {
                command("whitelist " + (on ? "off" : "on"), on ? Tr.t("Белый список выключен", "Whitelist off") : Tr.t("Белый список включён", "Whitelist on"));
            }
            try {
                manager.saveProperties(s.id, Map.of("white-list", String.valueOf(!on)));
                if (!alive()) Dialogs.toast(mw, !on ? Tr.t("Белый список включён", "Whitelist on") : Tr.t("Белый список выключен", "Whitelist off"), SettingsKit.GREEN);
            } catch (Exception e) {
                Log.warn("white-list: " + Log.reason(e));
            }
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int p = s(16);
            int bw = (w - p * 2 - s(10)) / 2;
            int bh = s(50);
            int y0 = p + s(46);
            for (int i = 0; i < getComponentCount(); i++) {
                getComponent(i).setBounds(p + (i % 2) * (bw + s(10)), y0 + (i / 2) * (bh + s(10)), bw, bh);
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            card(g2, getWidth(), getHeight());
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Быстрые действия", "Quick actions"), s(18), s(16) + s(24));
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ пусто и создание

    private final class Empty extends JPanel {
        Empty() {
            super(null);
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            card(g2, w, s(260));
            int is = s(64);
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(s(36), s(40), is + s(24), is + s(24), SettingsKit.rr(), SettingsKit.rr()));
            SettingsKit.icon(Glyph.SERVERS, is, new Color(0xD4DCE6)).paintIcon(this, g2, s(48), s(52));
            int x = s(36) + is + s(48);
            g2.setFont(SettingsKit.font(Font.BOLD, 26f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Серверов пока нет", "No servers yet"), x, s(82));
            g2.setFont(SettingsKit.font(Font.PLAIN, 16f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(Tr.t("Создайте свой мир на Fabric или подключите папку готового сервера.",
                    "Create your own Fabric world or import an existing server folder."), x, s(116));
            g2.drawString(Tr.t("Друзья заходят по адресу, который покажет лаунчер (Radmin VPN, Hamachi или локальная сеть).",
                    "Friends join with the address the launcher shows (Radmin VPN, Hamachi or LAN)."), x, s(144));
            g2.dispose();
        }
    }

    /** Окно создания сервера в стиле лаунчера. */
    private final class CreateDialog {
        private final JDialog d = new JDialog(mw, "CWLauncher", Dialog.ModalityType.APPLICATION_MODAL);
        private final JTextField name = SettingsKit.field(Tr.t("Мой сервер", "My server"));
        private final JTextField folder = SettingsKit.field("");
        private final SettingsKit.Dropdown version = new SettingsKit.Dropdown(new String[]{packLabel()}, 0);
        private java.util.List<String> versions = java.util.List.of(app.cfg.minecraftVersion);
        private final Slider ram;
        private final Toggle eula = new Toggle(false);
        private final JLabel status = SettingsKit.label(" ", 14f, Font.PLAIN, new Color(0xB5C2D0));
        private final Button ok = new Button(Tr.t("Создать сервер", "Create server"), Glyph.PLUS, Kind.PRIMARY);
        private final Button cancel = new Button(Tr.t("Отмена", "Cancel"), null, Kind.GHOST);

        CreateDialog() {
            int capGb = Math.max(2, ru.cw.launcher.settings.SettingsManager.maxGameRamMb() / 1024);
            ram = new Slider(1, capGb, Math.max(1, Math.min(capGb, app.cfg.serversDefaultRamMb / 1024)));
            folder.setText(Paths.root().resolve("servers").resolve(Tr.t("Мой сервер", "My server")).toString());
            SettingsKit.onText(name, () -> {
                Path base = Paths.root().resolve("servers");
                if (folder.getText().startsWith(base.toString())) {
                    String n = name.getText().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    folder.setText(base.resolve(n.isEmpty() ? "server" : n).toString());
                }
            });
            d.setUndecorated(true);
            d.setBackground(new Color(0, 0, 0, 0));
            Button pick = new Button("", Glyph.FOLDER, Kind.GHOST).size(46, 46).iconSize(20);
            pick.addActionListener(e -> {
                Path p = Utils.chooseDirectory(d, Tr.t("Папка сервера", "Server folder"));
                if (p != null) folder.setText(p.toString());
            });
            JPanel folderRow = new JPanel(new BorderLayout(s(8), 0));
            folderRow.setOpaque(false);
            folderRow.add(folder, BorderLayout.CENTER);
            folderRow.add(pick, BorderLayout.EAST);
            JLabel eulaLink = SettingsKit.label("<html><u>" + Tr.t("Открыть Minecraft EULA", "Open Minecraft EULA") + "</u></html>",
                    13.5f, Font.PLAIN, SettingsKit.ACCENT);
            eulaLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            eulaLink.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    Utils.openUrl("https://www.minecraft.net/eula");
                }
            });
            String gb = Tr.t(" ГБ", " GB");
            SettingsKit.Card body = new SettingsKit.Card(Glyph.SERVERS, Tr.t("Новый сервер", "New server"),
                    Tr.t("Чистый Fabric-сервер, мир создастся при первом запуске", "A clean Fabric server, the world is created on first start"))
                    .add(SettingsKit.labeled(Tr.t("Название", "Name"), name))
                    .add(SettingsKit.labeled(Tr.t("Папка сервера", "Server folder"), SettingsKit.fullWidth(folderRow)))
                    .add(SettingsKit.labeled(Tr.t("Версия Minecraft", "Minecraft version"), version))
                    .add(SettingsKit.slider(Tr.t("Память сервера", "Server memory"), ram, v -> v + gb))
                    .add(SettingsKit.row(null, Tr.t("Принимаю Minecraft EULA", "I accept the Minecraft EULA"),
                            Tr.t("Без этого сервер не запустится", "Required to run a server"), eula))
                    .add(eulaLink)
                    .add(status)
                    .add(SettingsKit.line(ok, cancel));
            // Сплошная подложка как у остальных окон: прозрачность панелей из настроек
            // не должна пропускать консоль под окном.
            JPanel root = new JPanel(new BorderLayout()) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    SettingsKit.smooth(g2);
                    int r = SettingsKit.radius() + s(4);
                    g2.setColor(new Color(0x111B27));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
                    g2.dispose();
                }
            };
            root.setOpaque(false);
            root.add(body, BorderLayout.CENTER);
            d.setContentPane(root);
            cancel.addActionListener(e -> d.dispose());
            ok.addActionListener(e -> submit());
            d.getRootPane().registerKeyboardAction(e -> d.dispose(),
                    KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        }

        private String packLabel() {
            return app.cfg.minecraftVersion + Tr.t("  ·  как у сборки", "  ·  same as the pack");
        }

        /** Список версий Fabric грузится в фоне; без сети остаётся версия сборки. */
        private void loadVersions() {
            new SwingWorker<java.util.List<String>, Void>() {
                @Override
                protected java.util.List<String> doInBackground() throws Exception {
                    return LocalServerManager.fabricGameVersions();
                }

                @Override
                protected void done() {
                    try {
                        java.util.List<String> list = new ArrayList<>(get());
                        String pack = app.cfg.minecraftVersion;
                        list.remove(pack);
                        list.add(0, pack);
                        String[] labels = new String[list.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = i == 0 ? packLabel() : list.get(i);
                        String current = versions.get(Math.max(0, Math.min(versions.size() - 1, version.getSelectedIndex())));
                        versions = list;
                        version.setItems(labels, Math.max(0, list.indexOf(current)));
                    } catch (Exception e) {
                        Log.warn("Список версий Fabric не загружен: " + Log.reason(e));
                    }
                }
            }.execute();
        }

        void open() {
            loadVersions();
            d.pack();
            d.setSize(Math.max(d.getWidth(), s(620)), d.getHeight());
            d.setLocation(mw.getX() + (mw.getWidth() - d.getWidth()) / 2, mw.getY() + (mw.getHeight() - d.getHeight()) / 2);
            Motion.present(d);
        }

        private void submit() {
            if (name.getText().isBlank()) {
                status.setText(Tr.t("Укажите название сервера.", "Enter a server name."));
                return;
            }
            if (!eula.isOn()) {
                status.setText(Tr.t("Нужно принять Minecraft EULA.", "You must accept the Minecraft EULA."));
                return;
            }
            Path target;
            try {
                target = Path.of(folder.getText().trim()).toAbsolutePath().normalize();
            } catch (Exception ex) {
                status.setText(Tr.t("Проверьте путь к папке.", "Check the folder path."));
                return;
            }
            String v = versions.get(Math.max(0, Math.min(versions.size() - 1, version.getSelectedIndex())));
            String loader = app.fabric().installedLoader(v);
            LocalServerManager.Draft draft = new LocalServerManager.Draft(name.getText().trim(), "", target, v,
                    loader == null ? "" : loader, true);
            int ramMb = ram.getValue() * 1024;
            ok.setEnabled(false);
            cancel.setEnabled(false);
            status.setText(Tr.t("Подготовка сервера…", "Preparing the server…"));
            new SwingWorker<Server, String>() {
                @Override
                protected Server doInBackground() throws Exception {
                    return manager.create(draft, this::publish);
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty()) status.setText(chunks.get(chunks.size() - 1) + "…");
                }

                @Override
                protected void done() {
                    try {
                        Server s = get();
                        // Автозапуск у нового сервера выключен: включается вручную в его настройках.
                        manager.configure(s.id, ramMb, false);
                        d.dispose();
                        selected = s.id;
                        reloadAll();
                        Dialogs.toast(mw, Tr.t("Сервер «" + s.name + "» создан. Запустите его кнопкой «Запустить».",
                                "Server “" + s.name + "” created. Start it with the “Start” button."), SettingsKit.GREEN);
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        status.setText(Tr.t("Не удалось создать сервер: ", "Could not create the server: ") + Log.reason(cause));
                        ok.setEnabled(true);
                        cancel.setEnabled(true);
                    }
                }
            }.execute();
        }
    }
}
