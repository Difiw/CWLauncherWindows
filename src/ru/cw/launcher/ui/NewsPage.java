package ru.cw.launcher.ui;

import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.news.NewsManager;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.Toggle;
import ru.cw.launcher.updates.UpdateParser;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Страница «Новости»: баннер с главными записями, список, последнее обновление и история версий.
 * Источники те же простые файлы с Google Диска: News.txt (обычный текст или JSON) и
 * UpdateV*.txt из UpdateInfo.zip. Картинок в них нет — карточки берут фон лаунчера.
 */
final class NewsPage extends JPanel {

    enum Tab {ALL, SERVER, UPDATES, LAUNCHER}

    private enum Category {SERVER, UPDATE, LAUNCHER}

    /** Запись ленты: из новости, заметки об обновлении или сведений о лаунчере. */
    private record Entry(Category category, String title, String summary, String body, String date, String key,
                         String version) {
    }

    private final MainWindow mw;
    private final LauncherApp app;
    private final JLabel title = SettingsKit.label(Tr.t("Новости", "News"), 40f, Font.BOLD, SettingsKit.TEXT);
    private final JTextField search = SettingsKit.field("");
    private final Toggle unreadOnly = new Toggle(false);
    private final JPanel unreadBox = new JPanel(null) {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            g2.setColor(SettingsKit.card());
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, SettingsKit.rr(), SettingsKit.rr()));
            g2.dispose();
        }
    };
    private final JLabel unreadLabel = SettingsKit.label(Tr.t("Только непрочитанные", "Unread only"), 15f, Font.PLAIN, SettingsKit.TEXT);
    private final TabButton[] tabs = {
            new TabButton(Tab.ALL, Tr.t("Все", "All")),
            new TabButton(Tab.SERVER, Tr.t("Новости сервера", "Server news")),
            new TabButton(Tab.UPDATES, Tr.t("Обновления", "Updates")),
            new TabButton(Tab.LAUNCHER, Tr.t("Лаунчер", "Launcher"))};
    private final Hero hero = new Hero();
    private final JPanel list = new JPanel(null);
    private final JScrollPane listScroll = new JScrollPane(list);
    private final Latest latest = new Latest();
    private final History history = new History();
    private final Reader reader = new Reader();
    private final JLabel empty = SettingsKit.label("", 16f, Font.PLAIN, new Color(0xB5C2D0));
    private final Set<String> read = new LinkedHashSet<>();
    private final Timer carousel;
    private Tab tab;
    private List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();

    NewsPage(MainWindow mw, LauncherApp app, Tab start) {
        super(null);
        this.mw = mw;
        this.app = app;
        this.tab = start == null ? Tab.ALL : start;
        setOpaque(false);
        loadRead();
        search.putClientProperty("cw.hint", Tr.t("Поиск новостей…", "Search news…"));
        SettingsKit.onText(search, this::refilter);
        unreadOnly.onChange(this::refilter);
        list.setOpaque(false);
        listScroll.setOpaque(false);
        listScroll.getViewport().setOpaque(false);
        listScroll.setBorder(BorderFactory.createEmptyBorder());
        listScroll.setViewportBorder(null);
        listScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        SettingsKit.styleScroll(listScroll);
        listScroll.getVerticalScrollBar().setUnitIncrement(s(30));
        listScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                layoutList();
            }
        });
        add(title);
        add(search);
        // Переключатель с подписью на своей подложке: на картинке фона текст иначе не читается.
        unreadBox.add(unreadOnly);
        unreadBox.add(unreadLabel);
        unreadBox.setOpaque(false);
        add(unreadBox);
        for (TabButton t : tabs) add(t);
        add(hero);
        add(listScroll);
        add(latest);
        add(history);
        add(empty);
        add(reader);
        reader.setVisible(false);
        empty.setHorizontalAlignment(SwingConstants.CENTER);
        carousel = new Timer(8000, e -> hero.next());
        carousel.start();
        reload();
    }

    void dispose() {
        carousel.stop();
    }

    // ------------------------------------------------------------------ данные

    void reload() {
        all = collect();
        latest.fill();
        refilter();
    }

    private List<Entry> collect() {
        List<Entry> out = new ArrayList<>();
        try {
            for (NewsManager.Item it : app.news().items()) {
                String body = it.text() == null ? "" : it.text().trim();
                String t = it.title() == null || it.title().isBlank() ? Tr.t("Новость", "News") : it.title().trim();
                out.add(new Entry(Category.SERVER, t, summary(body), body, it.date() == null ? "" : it.date().trim(),
                        "news:" + t + ":" + body.hashCode(), null));
            }
        } catch (Exception e) {
            Log.warn("Новости для страницы: " + Log.reason(e));
        }
        try {
            for (UpdateParser.Note n : app.updateInfo().cached()) {
                String body = n.text() == null ? "" : n.text().trim();
                out.add(new Entry(Category.UPDATE, Tr.t("Обновление сборки ", "Pack update ") + n.version(), summary(body),
                        body, "", "update:" + n.version(), n.version()));
            }
        } catch (Exception e) {
            Log.warn("Обновления для страницы: " + Log.reason(e));
        }
        String current = Log.VERSION;
        String remote = app.cfg.remoteLauncherVersion;
        boolean newer = app.launcherUpdateButton();
        // Запись о лаунчере — только когда вышла новая версия. «У вас последняя версия» — не новость.
        if (newer && remote != null) {
            String launcherBody = Tr.t("Доступна новая версия CWLauncher " + remote + ". Сейчас установлена " + current
                            + ".\nНажмите «Обновить лаунчер», и он обновится сам.",
                    "CWLauncher " + remote + " is available. You have " + current + ".\nPress “Update launcher” to install it.");
            out.add(new Entry(Category.LAUNCHER, Tr.t("Вышел CWLauncher ", "CWLauncher ") + remote, summary(launcherBody),
                    launcherBody, "", "launcher:" + remote, remote));
        }
        return out;
    }

    /** Две первые содержательные строки: без шапки «COMMON WORLD — ОБНОВЛЕНИЕ» и строки «Версия:». */
    private static String summary(String body) {
        StringBuilder sb = new StringBuilder();
        int lines = 0;
        for (String raw : clean(body)) {
            if (lines >= 2) break;
            if (sb.length() > 0) sb.append(' ');
            sb.append(raw);
            lines++;
        }
        return sb.toString();
    }

    private static List<String> clean(String body) {
        List<String> out = new ArrayList<>();
        List<String> headers = new ArrayList<>();
        if (body == null) return out;
        for (String raw : body.replace("\r", "").split("\n")) {
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(Locale.ROOT);
            if (low.startsWith("common world") && (low.contains("обновлен") || low.contains("update"))) continue;
            if (low.startsWith("версия:") || low.startsWith("version:")) continue;
            t = t.replaceFirst("^[•\\-*–]+\\s*", "");
            if (t.isEmpty()) continue;
            // Заголовки разделов вроде «ADMIN CONTROL — СПИСОК ИЗМЕНЕНИЙ» в кратком тексте не нужны.
            if (t.length() < 90 && t.equals(t.toUpperCase(Locale.ROOT)) && !t.equals(t.toLowerCase(Locale.ROOT))) {
                headers.add(t);
                continue;
            }
            out.add(t);
        }
        return out.isEmpty() ? headers : out;
    }

    private boolean matches(Entry e) {
        boolean byTab = switch (tab) {
            case ALL -> true;
            case SERVER -> e.category() == Category.SERVER;
            case UPDATES -> e.category() == Category.UPDATE;
            case LAUNCHER -> e.category() == Category.LAUNCHER;
        };
        if (!byTab) return false;
        if (unreadOnly.isOn() && read.contains(e.key())) return false;
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        return q.isEmpty() || (e.title() + "\n" + e.body()).toLowerCase(Locale.ROOT).contains(q);
    }

    private void refilter() {
        shown = new ArrayList<>();
        for (Entry e : all) if (matches(e)) shown.add(e);
        for (TabButton t : tabs) t.repaint();
        hero.fill();
        list.removeAll();
        int skip = Math.min(shown.size(), hero.count());
        for (int i = skip; i < shown.size(); i++) list.add(new Card(shown.get(i), i));
        if (skip > 0 && shown.size() == skip) {
            // Все записи уже в баннере: список повторяет их, чтобы не было пустоты.
            for (int i = 0; i < shown.size(); i++) list.add(new Card(shown.get(i), i));
        }
        boolean filtered = !search.getText().isBlank() || unreadOnly.isOn();
        empty.setText(shown.isEmpty() ? (!filtered && tab == Tab.LAUNCHER
                ? Tr.t("У вас последняя версия CWLauncher " + Log.VERSION + ". Новостей о лаунчере нет.",
                "You have the latest CWLauncher " + Log.VERSION + ". No launcher news.")
                : !filtered
                ? Tr.t("Здесь пока пусто. Новости появятся, когда сервер их опубликует.",
                "Nothing here yet. News appear when the server publishes them.")
                : Tr.t("Ничего не найдено", "Nothing found")) : "");
        empty.setVisible(shown.isEmpty());
        doLayout();
        layoutList();
        repaint();
    }

    private void loadRead() {
        try {
            Path f = Paths.root().resolve("news-read.txt");
            if (Files.isRegularFile(f)) read.addAll(Files.readAllLines(f, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private void markRead(Entry e) {
        if (!read.add(e.key())) return;
        try {
            Path f = Paths.root().resolve("news-read.txt");
            Files.createDirectories(f.getParent());
            Files.write(f, read, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Log.warn("Отметка прочтения: " + Log.reason(ex));
        }
    }

    private void open(Entry e) {
        markRead(e);
        reader.show(e);
        setReading(true);
    }

    private void setReading(boolean on) {
        reader.setVisible(on);
        for (Component c : getComponents()) {
            if (c == reader || c == title) continue;
            c.setVisible(!on && (c != empty || shown.isEmpty()));
        }
        if (!on) refilter();
        doLayout();
        repaint();
    }

    void select(Tab t) {
        tab = t;
        if (reader.isVisible()) setReading(false);
        refilter();
    }

    // ------------------------------------------------------------------ раскладка

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int x0 = s(20);
        int right = w - s(20);
        title.setBounds(x0 + s(16), s(26), s(400), s(54));
        int tabY = s(108);
        int tx = x0;
        for (TabButton t : tabs) {
            int tw = t.preferredWidth();
            t.setBounds(tx, tabY, tw, s(44));
            tx += tw + s(10);
        }
        int boxW = s(64) + s(14) + unreadLabel.getPreferredSize().width + s(24);
        unreadOnly.setBounds(s(8), (s(44) - s(36)) / 2, s(64), s(36));
        unreadLabel.setBounds(s(80), 0, boxW - s(88), s(44));
        // Поиск и переключатель — справа на строке вкладок, с отступом от плашки сервера сверху.
        int searchW = Math.min(s(300), Math.max(s(150), right - boxW - s(12) - tx - s(16)));
        unreadBox.setBounds(right - boxW, tabY, boxW, s(44));
        search.setBounds(right - boxW - s(12) - searchW, tabY, searchW, s(44));
        int top = tabY + s(60);
        int bottom = h - s(18);
        reader.setBounds(x0, tabY, right - x0, bottom - tabY);
        int heroH = Math.max(s(220), Math.min(s(320), (bottom - top) * 42 / 100));
        hero.setBounds(x0, top, right - x0, heroH);
        int y = top + heroH + s(14);
        int rightW = Math.max(s(320), (right - x0) * 36 / 100);
        int leftW = right - x0 - rightW - s(14);
        listScroll.setBounds(x0, y, leftW, bottom - y);
        empty.setBounds(x0, y + s(30), leftW, s(30));
        int histH = s(104);
        latest.setBounds(x0 + leftW + s(14), y, rightW, bottom - y - histH - s(14));
        history.setBounds(x0 + leftW + s(14), bottom - histH, rightW, histH);
        layoutList();
    }

    private void layoutList() {
        int w = listScroll.getViewport().getWidth() > 0 ? listScroll.getViewport().getWidth() : listScroll.getWidth();
        int y = 0;
        int ch = s(150);
        for (Component c : list.getComponents()) {
            c.setBounds(0, y, Math.max(10, w - s(12)), ch);
            y += ch + s(12);
        }
        list.setPreferredSize(new Dimension(w, Math.max(0, y - s(12))));
        list.revalidate();
        list.repaint();
    }

    private static void card(Graphics2D g2, int w, int h) {
        int r = SettingsKit.radius() + s(2);
        g2.setColor(SettingsKit.card());
        g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
        g2.setColor(SettingsKit.CARD_LINE);
        g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
    }

    private static String label(Category c) {
        return switch (c) {
            case SERVER -> Tr.t("НОВОСТИ СЕРВЕРА", "SERVER NEWS");
            case UPDATE -> Tr.t("ОБНОВЛЕНИЕ", "UPDATE");
            case LAUNCHER -> Tr.t("ЛАУНЧЕР", "LAUNCHER");
        };
    }

    private static Color tone(Category c) {
        return switch (c) {
            case SERVER -> SettingsKit.ACCENT;
            case UPDATE -> SettingsKit.GREEN;
            case LAUNCHER -> new Color(0xA78BFA);
        };
    }

    /** Плашка категории: «НОВОСТИ СЕРВЕРА», «ОБНОВЛЕНИЕ». Возвращает её ширину. */
    private static int badge(Graphics2D g2, Category c, int x, int y) {
        g2.setFont(SettingsKit.font(Font.BOLD, 12.5f));
        FontMetrics fm = g2.getFontMetrics();
        String t = label(c);
        int bw = fm.stringWidth(t) + s(20);
        int bh = s(26);
        Color tone = tone(c);
        g2.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), 40));
        g2.fill(new RoundRectangle2D.Float(x, y, bw, bh, s(6), s(6)));
        g2.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), 150));
        g2.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, bw - 1, bh - 1, s(6), s(6)));
        g2.setColor(tone);
        g2.drawString(t, x + s(10), y + (bh + fm.getAscent() - fm.getDescent()) / 2);
        return bw;
    }

    private static void cover(Graphics2D g2, BufferedImage img, int x, int y, int w, int h, float focus) {
        if (img == null) return;
        double scale = Math.max((double) w / img.getWidth(), (double) h / img.getHeight());
        int dw = (int) Math.ceil(img.getWidth() * scale);
        int dh = (int) Math.ceil(img.getHeight() * scale);
        int dx = x + (int) ((w - dw) * focus);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(img, dx, y + (h - dh) / 2, dw, dh, null);
    }

    /** Кусок фона для миниатюры: у каждой карточки своё место картинки. */
    private BufferedImage thumb(int index) {
        BufferedImage pic = app.background().picture();
        if (pic == null) return null;
        // Нижние две трети картинки: там постройки и вода, а не тёмное небо.
        int cw = pic.getWidth() / 3;
        int chh = pic.getHeight() / 2;
        int cx = (pic.getWidth() - cw) / 2 + Math.floorMod(index, 3) * (pic.getWidth() - cw) / 4;
        int cy = pic.getHeight() - chh - (index % 2) * pic.getHeight() / 10;
        cy = Math.max(0, Math.min(pic.getHeight() - chh, cy));
        return pic.getSubimage(cx, cy, cw, chh);
    }

    private static int wrap(Graphics2D g2, String text, int x, int y, int width, int maxLines, int lineH) {
        FontMetrics fm = g2.getFontMetrics();
        int lines = 0;
        StringBuilder line = new StringBuilder();
        String[] words = (text == null ? "" : text).split("\\s+");
        for (int i = 0; i < words.length; i++) {
            String next = line.length() == 0 ? words[i] : line + " " + words[i];
            if (fm.stringWidth(next) > width && line.length() > 0) {
                lines++;
                boolean last = lines >= maxLines;
                g2.drawString(last ? SettingsKit.ellipsize(line + " " + words[i] + "…", fm, width) : line.toString(), x, y);
                y += lineH;
                if (last) return y;
                line = new StringBuilder(words[i]);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) {
            g2.drawString(SettingsKit.ellipsize(line.toString(), fm, width), x, y);
            y += lineH;
        }
        return y;
    }

    // ------------------------------------------------------------------ вкладки

    private final class TabButton extends JComponent {
        private final Tab t;
        private final String text;
        private boolean hover;

        TabButton(Tab t, String text) {
            this.t = t;
            this.text = text;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    select(t);
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

        int preferredWidth() {
            return getFontMetrics(SettingsKit.font(Font.PLAIN, 16f)).stringWidth(text) + s(44);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            boolean on = tab == t;
            Shape shape = new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr());
            if (on) {
                g2.setPaint(new GradientPaint(0, 0, SettingsKit.ACCENT, 0, h, SettingsKit.ACCENT_2));
                g2.fill(shape);
            } else {
                g2.setColor(hover ? new Color(0x182535) : SettingsKit.card());
                g2.fill(shape);
                g2.setColor(SettingsKit.CARD_LINE);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, SettingsKit.rr(), SettingsKit.rr()));
            }
            g2.setFont(SettingsKit.font(on ? Font.BOLD : Font.PLAIN, 16f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, (w - fm.stringWidth(text)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ баннер

    private final class Hero extends JPanel {
        private final Button more = new Button(Tr.t("Читать подробнее", "Read more"), Glyph.RIGHT, Kind.PRIMARY);
        private final List<Entry> items = new ArrayList<>();
        private int index;

        Hero() {
            super(null);
            setOpaque(false);
            more.setFont(SettingsKit.font(Font.BOLD, 17f));
            add(more);
            more.addActionListener(e -> {
                if (!items.isEmpty()) open(items.get(index));
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    int dot = dotAt(e.getPoint());
                    if (dot >= 0) {
                        index = dot;
                        carousel.restart();
                        repaint();
                    }
                }
            });
        }

        /** В баннере — до трёх записей: сначала новости сервера, потом свежие обновления. */
        void fill() {
            items.clear();
            for (Entry e : shown) if (e.category() == Category.SERVER && items.size() < 3) items.add(e);
            for (Entry e : shown) if (e.category() != Category.SERVER && items.size() < 3) items.add(e);
            index = Math.min(index, Math.max(0, items.size() - 1));
            more.setVisible(!items.isEmpty());
            setVisible(!items.isEmpty() && !reader.isVisible());
            repaint();
        }

        int count() {
            return items.size();
        }

        void next() {
            if (items.size() < 2 || !isShowing()) return;
            index = (index + 1) % items.size();
            repaint();
        }

        private int dotAt(Point p) {
            for (int i = 0; i < items.size(); i++) if (dotRect(i).contains(p)) return i;
            return -1;
        }

        private Rectangle dotRect(int i) {
            int n = items.size();
            int dw = s(22);
            int gap = s(8);
            int total = n * dw + (n - 1) * gap;
            return new Rectangle(getWidth() - s(28) - total + i * (dw + gap), getHeight() - s(30), dw, s(14));
        }

        @Override
        public void doLayout() {
            more.setBounds(s(28), getHeight() - s(28) - s(56), s(280), s(56));
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (items.isEmpty()) return;
            Entry e = items.get(index);
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int r = SettingsKit.radius() + s(4);
            Shape clip = new RoundRectangle2D.Float(0, 0, w, h, r, r);
            g2.setColor(new Color(0x0D1621));
            g2.fill(clip);
            g2.clip(clip);
            cover(g2, app.background().picture(), 0, 0, w, h, 0.7f);
            int shade = Math.max(140, SettingsKit.heroShade() * 24 / 10);
            g2.setPaint(new LinearGradientPaint(0, 0, w * 0.75f, 0, new float[]{0f, 0.5f, 1f},
                    new Color[]{new Color(13, 22, 33, Math.min(245, shade)), new Color(13, 22, 33, Math.min(245, shade) / 2),
                            new Color(13, 22, 33, 0)}));
            g2.fillRect(0, 0, w, h);
            g2.setClip(null);
            g2.setColor(SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
            int x = s(28);
            int textW = Math.max(s(360), w * 55 / 100);
            badge(g2, e.category(), x, s(30));
            g2.setFont(SettingsKit.font(Font.BOLD, 34f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            int y;
            if (fm.stringWidth(e.title()) <= w - x * 2) {
                g2.drawString(e.title(), x, s(98));
                y = s(134);
            } else {
                // Длинный заголовок (часто вся новость в одну строку) — две строки помельче.
                g2.setFont(SettingsKit.font(Font.BOLD, 26f));
                y = wrap(g2, e.title(), x, s(92), w - x * 2 - s(40), 2, s(34)) + s(4);
            }
            g2.setFont(SettingsKit.font(Font.PLAIN, 17f));
            g2.setColor(new Color(0xD5DEE8));
            if (!e.summary().equals(e.title())) y = wrap(g2, e.summary(), x, y, textW, 2, s(25));
            if (!e.date().isEmpty()) {
                SettingsKit.icon(Glyph.CLOCK, s(18), new Color(0xB5C2D0)).paintIcon(this, g2, x, y - s(6));
                g2.setFont(SettingsKit.font(Font.PLAIN, 15.5f));
                g2.setColor(new Color(0xB5C2D0));
                g2.drawString(e.date(), x + s(28), y + s(8));
            }
            if (items.size() > 1) {
                for (int i = 0; i < items.size(); i++) {
                    Rectangle d = dotRect(i);
                    g2.setColor(i == index ? SettingsKit.ACCENT : new Color(255, 255, 255, 80));
                    int dh = s(6);
                    g2.fill(new RoundRectangle2D.Float(d.x, d.y + (d.height - dh) / 2f, d.width, dh, dh, dh));
                }
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ карточка списка

    private final class Card extends JComponent {
        private final Entry e;
        private final int index;
        private boolean hover;

        Card(Entry e, int index) {
            this.e = e;
            this.index = index;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent ev) {
                    open(e);
                }

                @Override
                public void mouseEntered(MouseEvent ev) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent ev) {
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
            int p = s(16);
            int tw = Math.min(s(230), w * 32 / 100);
            int th = h - p * 2;
            Shape box = new RoundRectangle2D.Float(p, p, tw, th, SettingsKit.rr(), SettingsKit.rr());
            g2.setColor(new Color(0x1B2836));
            g2.fill(box);
            Shape old = g2.getClip();
            g2.clip(box);
            cover(g2, thumb(index), p, p, tw, th, 0.5f);
            g2.setClip(old);
            int x = p + tw + s(24);
            int right = w - s(56);
            badge(g2, e.category(), x, p + s(2));
            g2.setFont(SettingsKit.font(Font.BOLD, 20f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(e.title(), fm, right - x), x, p + s(58));
            g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
            g2.setColor(new Color(0xC3CEDA));
            wrap(g2, e.summary(), x, p + s(84), right - x, 2, s(22));
            if (!e.date().isEmpty()) {
                SettingsKit.icon(Glyph.CLOCK, s(16), new Color(0x9AA9BA)).paintIcon(this, g2, x, h - p - s(16));
                g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
                g2.setColor(new Color(0x9AA9BA));
                g2.drawString(e.date(), x + s(24), h - p - s(3));
            }
            if (!read.contains(e.key())) {
                g2.setColor(SettingsKit.ACCENT);
                g2.fill(new Ellipse2D.Float(w - s(30), p + s(6), s(12), s(12)));
            }
            SettingsKit.icon(Glyph.RIGHT, s(22), new Color(0x9AA9BA)).paintIcon(this, g2, w - s(40), (h - s(22)) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ последнее обновление

    private final class Latest extends JPanel {
        private final Button action = new Button("", Glyph.RIGHT, Kind.PRIMARY);
        private Entry note;
        private boolean launcherUpdate;

        Latest() {
            super(null);
            setOpaque(false);
            add(action);
            action.addActionListener(e -> {
                if (launcherUpdate) app.beginLauncherUpdate();
                else if (note != null) open(note);
            });
        }

        void fill() {
            note = null;
            for (Entry e : all) if (e.category() == Category.UPDATE) {
                note = e;
                break;
            }
            launcherUpdate = app.launcherUpdateButton();
            action.setText(launcherUpdate ? Tr.t("Обновить лаунчер до ", "Update launcher to ") + app.cfg.remoteLauncherVersion
                    : Tr.t("Читать полностью", "Read in full"));
            action.setVisible(launcherUpdate || note != null);
            repaint();
        }

        @Override
        public void doLayout() {
            action.setBounds(s(20), getHeight() - s(20) - s(48), getWidth() - s(40), s(48));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            card(g2, w, h);
            int p = s(20);
            g2.setFont(SettingsKit.font(Font.BOLD, 21f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Последнее обновление", "Latest update"), p, p + s(24));
            if (note == null) {
                g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
                g2.setColor(new Color(0xB5C2D0));
                g2.drawString(Tr.t("Список обновлений появится, когда источник ответит.",
                        "The update list appears when the source responds."), p, p + s(64));
                g2.dispose();
                return;
            }
            String v = "v " + note.version();
            g2.setFont(SettingsKit.font(Font.BOLD, 16f));
            FontMetrics fm = g2.getFontMetrics();
            int bw = fm.stringWidth(v) + s(24);
            int bh = s(32);
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(w - p - bw, p + s(2), bw, bh, SettingsKit.rr(), SettingsKit.rr()));
            g2.setColor(SettingsKit.ACCENT);
            g2.drawString(v, w - p - bw + s(12), p + s(2) + (bh + fm.getAscent() - fm.getDescent()) / 2);
            int box = s(64);
            int by = p + s(50);
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(p, by, box, box, SettingsKit.rr(), SettingsKit.rr()));
            SettingsKit.icon(Glyph.FILE, s(32), new Color(0xD4DCE6)).paintIcon(this, g2, p + s(16), by + s(16));
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("Версия ", "Version ") + note.version(), p + box + s(16), by + s(28));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14.5f));
            g2.setColor(new Color(0xB5C2D0));
            g2.drawString(Tr.t("Сборка Common World", "Common World pack"), p + box + s(16), by + s(52));
            int y = by + box + s(30);
            int limit = h - s(20) - s(48) - s(16);
            g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
            fm = g2.getFontMetrics();
            for (String line : clean(note.body())) {
                if (y + s(4) > limit) break;
                g2.setColor(SettingsKit.GREEN);
                g2.fill(new Ellipse2D.Float(p, y - s(14), s(18), s(18)));
                SettingsKit.icon(Glyph.CHECK, s(12), new Color(0x0B131D)).paintIcon(this, g2, p + s(3), y - s(11));
                g2.setColor(new Color(0xDCE4EC));
                g2.drawString(SettingsKit.ellipsize(line, fm, w - p * 2 - s(30)), p + s(30), y);
                y += s(28);
            }
            g2.dispose();
        }
    }

    private final class History extends JComponent {
        private boolean hover;

        History() {
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(Tab.UPDATES);
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
            int p = s(20);
            int box = h - p * 2;
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(p, p, box, box, SettingsKit.rr(), SettingsKit.rr()));
            int is = box * 50 / 100;
            SettingsKit.icon(Glyph.FILE, is, new Color(0xD4DCE6)).paintIcon(this, g2, p + (box - is) / 2, p + (box - is) / 2);
            int x = p + box + s(18);
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString(Tr.t("История версий", "Version history"), x, p + s(22));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0xB5C2D0));
            int count = 0;
            for (Entry e : all) if (e.category() == Category.UPDATE) count++;
            g2.drawString(Tr.t("Все обновления сборки: ", "All pack updates: ") + count, x, p + s(48));
            SettingsKit.icon(Glyph.RIGHT, s(22), new Color(0x9AA9BA)).paintIcon(this, g2, w - s(40), (h - s(22)) / 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ чтение записи

    private final class Reader extends JPanel {
        private final Button back = new Button(Tr.t("Ко всем новостям", "Back to news"), Glyph.LEFT, Kind.GHOST);
        private final JTextArea text = new JTextArea();
        private final JScrollPane scroll = new JScrollPane(text);
        private Entry entry;

        Reader() {
            super(null);
            setOpaque(false);
            // Своя отрисовка поля: тема Nimbus заливает его сплошным фоном поверх карточки.
            text.setUI(new javax.swing.plaf.basic.BasicTextAreaUI());
            text.setEditable(false);
            text.setLineWrap(true);
            text.setWrapStyleWord(true);
            text.setOpaque(false);
            text.setForeground(new Color(0xDCE4EC));
            text.setFont(SettingsKit.font(Font.PLAIN, 16.5f));
            text.setBorder(BorderFactory.createEmptyBorder());
            text.setSelectionColor(SettingsKit.ACTIVE);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setViewportBorder(null);
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            SettingsKit.styleScroll(scroll);
            scroll.getVerticalScrollBar().setUnitIncrement(s(30));
            add(back);
            add(scroll);
            back.addActionListener(e -> setReading(false));
        }

        void show(Entry e) {
            entry = e;
            text.setText(e.body().isBlank() ? e.summary() : e.body());
            text.setCaretPosition(0);
            doLayout();
            repaint();
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            back.setBounds(0, 0, s(240), s(44));
            int tw = w - s(56);
            text.setSize(tw, Short.MAX_VALUE);
            int needed = text.getPreferredSize().height + s(8);
            scroll.setBounds(s(28), s(200), tw, Math.max(s(60), Math.min(h - s(200) - s(24), needed)));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (entry == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            int top = s(60);
            // Карточка по высоте текста: короткая запись не оставляет пустой экран.
            int cardH = Math.min(h - top, scroll.getY() + scroll.getHeight() + s(28) - top);
            Graphics2D c = (Graphics2D) g2.create();
            c.translate(0, top);
            card(c, w, cardH);
            c.dispose();
            int x = s(28);
            badge(g2, entry.category(), x, top + s(24));
            g2.setFont(SettingsKit.font(Font.BOLD, 30f));
            g2.setColor(Color.WHITE);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(SettingsKit.ellipsize(entry.title(), fm, w - x * 2), x, top + s(92));
            if (!entry.date().isEmpty()) {
                SettingsKit.icon(Glyph.CLOCK, s(17), new Color(0xB5C2D0)).paintIcon(this, g2, x, top + s(108));
                g2.setFont(SettingsKit.font(Font.PLAIN, 15f));
                g2.setColor(new Color(0xB5C2D0));
                g2.drawString(entry.date(), x + s(26), top + s(122));
            }
            g2.setColor(SettingsKit.CARD_LINE);
            g2.drawLine(x, s(190), w - x, s(190));
            g2.dispose();
        }
    }
}
