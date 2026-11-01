package ru.cw.launcher.shell.ui;

import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.ContentSource.Item;
import ru.cw.launcher.shell.ContentSource.Kind;
import ru.cw.launcher.shell.ContentSource.Provider;
import ru.cw.launcher.shell.ContentSource.Sort;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Правая колонка: поиск модов, карт и модпаков с пагинацией (Modrinth / CurseForge). */
public final class BrowsePanel extends Kit.Round {

    /** Нажатие на кнопку у проекта. */
    public interface Action {
        void accept(Item item, Kind kind, Provider provider);
    }

    /** Проект уже добавлен (кнопка рисуется галочкой). */
    public interface Marked {
        boolean test(Item item, Kind kind, Provider provider);
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "cw-browse");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, BufferedImage> ICONS = new ConcurrentHashMap<>();

    /** Сохраняет ключ CurseForge в настройках; задаётся оболочкой. */
    public static volatile java.util.function.Consumer<String> keySaver = k -> {
    };

    private final ContentSource source;
    private final Action onAction;
    private final Marked marked;
    private final AtomicInteger generation = new AtomicInteger();

    private Kind kind;
    private Sort sort = Sort.POPULAR;
    private Provider provider = Provider.MODRINTH;
    private int page = 1;
    private int pages = 1;
    private String query = "";

    private final JPanel tabs = Kit.clear(new GridLayout(1, 3, 6, 0));
    private final Kit.Dropdown providerBox = new Kit.Dropdown("globe");
    private final JTextField search = new JTextField();
    private final JPanel chips = Kit.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
    private final JPanel list = new JPanel();
    private final JPanel pager = Kit.clear(new FlowLayout(FlowLayout.CENTER, 8, 0));
    private final javax.swing.Timer debounce;
    private List<Kit.Chip> chipList = new ArrayList<>();
    private final List<TabButton> tabButtons = new ArrayList<>();
    private String status = "";

    /**
     * @param onAction кнопка у проекта (скачать / добавить в сборку)
     * @param marked   true, если проект уже добавлен: кнопка рисуется галочкой
     */
    public BrowsePanel(ContentSource source, Action onAction, Marked marked, Kind... kinds) {
        super(new BorderLayout(0, 12), Kit.CARD, Kit.LINE, 18);
        this.source = source;
        this.onAction = onAction;
        this.marked = marked;
        setBorder(new EmptyBorder(14, 14, 12, 14));

        JPanel top = Kit.clear(new BorderLayout(0, 12));
        this.kind = kinds.length == 0 ? Kind.MOD : kinds[0];
        if (kinds.length != 1) top.add(buildTabs(kinds.length == 0
                ? new Kind[]{Kind.MOD, Kind.MAP, Kind.MODPACK} : kinds), BorderLayout.NORTH);
        List<String> sites = new ArrayList<>();
        for (Provider p : Provider.values()) sites.add(p.title);
        providerBox.setItems(sites, Provider.MODRINTH.title);
        providerBox.itemIcons(t -> switch (Provider.byTitle(t)) {
            case MODRINTH -> "modrinth";
            case CURSEFORGE -> "curseforge";
            case MCINSIDE -> "mcinside";
        });
        providerBox.onChange(v -> {
            provider = Provider.byTitle(v);
            search.putClientProperty("JTextField.placeholderText", provider == Provider.MCINSIDE ? "Поиск откроется на сайте (Enter)" : "Поиск");
            search.repaint();
            reload(1);
        });
        JPanel mid = Kit.clear(new BorderLayout(0, 10));
        providerBox.setPreferredSize(new Dimension(10, 46));
        mid.add(providerBox, BorderLayout.NORTH);
        JPanel sr = Kit.clear(new BorderLayout());
        search.putClientProperty("JTextField.placeholderText", "Поиск");
        search.addActionListener(e -> {
            if (provider == Provider.MCINSIDE) ru.cw.launcher.util.Utils.openUrl(ContentSource.mcInsideSearchUrl(search.getText()));
        });
        JComponent sf = Kit.field(search, "search");
        sf.setPreferredSize(new Dimension(10, 42));
        sr.add(sf, BorderLayout.CENTER);
        mid.add(sr, BorderLayout.CENTER);
        top.add(mid, BorderLayout.CENTER);
        chips.setBorder(new EmptyBorder(0, 0, 0, 0));
        rebuildChips();
        JPanel north = Kit.clear(new BorderLayout(0, 10));
        north.add(top, BorderLayout.NORTH);
        north.add(chips, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);

        list.setOpaque(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        JPanel wrap = Kit.clear(new BorderLayout());
        wrap.add(list, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        Kit.style(sp);
        add(sp, BorderLayout.CENTER);
        add(pager, BorderLayout.SOUTH);

        debounce = new javax.swing.Timer(380, e -> {
            if (!search.getText().trim().equals(query)) {
                query = search.getText().trim();
                reload(1);
            }
        });
        debounce.setRepeats(false);
        SwingUtilities.invokeLater(() -> reload(1));
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                debounce.restart();
            }
        });
    }

    private JComponent buildTabs(Kind[] kinds) {
        Kit.Round bar = new Kit.Round(new GridLayout(1, kinds.length, 4, 0), new Color(0x101116), Kit.LINE, 14);
        bar.setBorder(new EmptyBorder(4, 4, 4, 4));
        for (Kind k : kinds) {
            String name = switch (k) {
                case MOD -> "Моды";
                case MAP -> "Карты";
                case MODPACK -> "Модпаки";
                case RESOURCEPACK -> "Ресурспаки";
            };
            String icon = switch (k) {
                case MOD -> "puzzle";
                case MAP -> "map";
                case MODPACK -> "layers";
                case RESOURCEPACK -> "palette";
            };
            TabButton t = new TabButton(name, icon, k);
            tabButtons.add(t);
            bar.add(t);
        }
        tabButtons.get(0).on = true;
        bar.setPreferredSize(new Dimension(10, 52));
        return bar;
    }

    private final class TabButton extends JComponent {
        private final String text;
        private final String icon;
        private final Kind k;
        private boolean on;

        TabButton(String text, String icon, Kind k) {
            this.text = text;
            this.icon = icon;
            this.k = k;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(Kit.font(Font.PLAIN, 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (kind == TabButton.this.k) return;
                    kind = TabButton.this.k;
                    for (TabButton t : tabButtons) {
                        t.on = t == TabButton.this;
                        t.repaint();
                    }
                    reload(1);
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            int w = getWidth();
            int h = getHeight();
            if (on) {
                g.setColor(Kit.ACCENT_DIM);
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, 11, 11));
                g.setColor(Kit.ACCENT);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 11, 11));
            }
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text) + 30;
            int x = (w - tw) / 2;
            Icons.draw(g, icon, x, (h - 20) / 2, 20, on ? Color.WHITE : Kit.MUTED);
            g.setColor(on ? Color.WHITE : Kit.MUTED);
            g.drawString(text, x + 30, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    private void rebuildChips() {
        chips.removeAll();
        chipList = new ArrayList<>();
        String[] names = {"Популярные", "Новые", "Топ"};
        Sort[] sorts = {Sort.POPULAR, Sort.NEW, Sort.TOP};
        for (int i = 0; i < 3; i++) {
            Sort s = sorts[i];
            Kit.Chip c = new Kit.Chip(names[i], s == sort, () -> {
                sort = s;
                rebuildChips();
                reload(1);
            });
            chipList.add(c);
            chips.add(c);
        }
        chips.revalidate();
        chips.repaint();
    }

    /** Загружает страницу в фоне; устаревшие ответы отбрасываются счётчиком поколения. */
    public void reload(int newPage) {
        page = Math.max(1, newPage);
        int gen = generation.incrementAndGet();
        Kind k = kind;
        Sort s = sort;
        Provider p = provider;
        String q = query;
        int pg = page;
        status = "Загрузка…";
        showStatus(status);
        IO.submit(() -> {
            ContentSource.Page result = null;
            String error = null;
            boolean noKey = false;
            try {
                result = source.search(p, k, s, q, pg);
            } catch (ContentSource.MissingKey e) {
                noKey = true;
                error = e.getMessage();
            } catch (java.io.IOException | RuntimeException e) {
                error = friendly(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            ContentSource.Page res = result;
            String err = error;
            boolean needKey = noKey;
            SwingUtilities.invokeLater(() -> {
                if (gen != generation.get()) return;
                if (needKey) {
                    showKeyPrompt();
                    pages = 1;
                    rebuildPager();
                    return;
                }
                if (err != null) {
                    showStatus("Не удалось загрузить: " + err);
                    pages = 1;
                    rebuildPager();
                    return;
                }
                fill(res, k, p);
            });
        });
    }

    /** Окно описания проекта: полный текст, ссылка на сайт и кнопка добавления. Текст грузится в фоне. */
    private void details(Item item, Kind k, Provider p) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog d = new JDialog(owner, item.title(), Dialog.ModalityType.MODELESS);
        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBackground(Kit.CARD);
        root.setBorder(new EmptyBorder(18, 20, 16, 20));
        JPanel head = Kit.clear(new GridLayout(2, 1, 0, 2));
        head.add(Kit.label(item.title(), Font.BOLD, 20, Kit.TEXT));
        head.add(Kit.label("<html>" + escape(item.author().isEmpty() ? p.title : item.author() + " · " + p.title) + "</html>", Font.PLAIN, 13, Kit.MUTED));
        root.add(head, BorderLayout.NORTH);
        JTextArea text = new JTextArea(item.summary() + "\n\nЗагрузка полного описания…");
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setOpaque(false);
        text.setForeground(Kit.TEXT);
        text.setFont(Kit.font(Font.PLAIN, 14));
        text.setBorder(new EmptyBorder(4, 2, 4, 8));
        JScrollPane sp = new JScrollPane(text);
        Kit.style(sp);
        sp.setPreferredSize(new Dimension(560, 340));
        root.add(sp, BorderLayout.CENTER);
        String[] url = {p == Provider.MCINSIDE ? item.id() : ""};
        JPanel row = Kit.clear(new GridLayout(1, 0, 10, 0));
        row.add(new Kit.Btn("Открыть на сайте", false, "globe", () -> {
            if (!url[0].isEmpty()) ru.cw.launcher.util.Utils.openUrl(url[0]);
        }));
        if (p.installable()) {
            row.add(new Kit.Btn(marked.test(item, k, p) ? "Убрать из сборки" : "Добавить в сборку", true, "download", () -> {
                onAction.accept(item, k, p);
                d.dispose();
                repaint();
            }));
        }
        row.add(new Kit.Btn("Закрыть", false, null, d::dispose));
        row.setPreferredSize(new Dimension(10, 44));
        root.add(row, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        IO.submit(() -> {
            String body;
            try {
                ContentSource.Details det = source.details(p, item);
                if (det.url() != null && !det.url().isEmpty()) url[0] = det.url();
                body = det.text().isBlank() ? item.summary() : det.text();
            } catch (java.io.IOException | RuntimeException e) {
                body = item.summary() + "\n\n(Полное описание не загрузилось: " + friendly(e) + ")";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String shown = body;
            SwingUtilities.invokeLater(() -> {
                text.setText(shown);
                text.setCaretPosition(0);
            });
            if (!ContentSource.translateToRussian || ru.cw.launcher.shell.Translator.mostlyRussian(shown)) return;
            String translated;
            try {
                translated = ru.cw.launcher.shell.Translator.toRussian(shown)
                        + "\n\n— Автоматический перевод. Оригинал на английском есть на сайте проекта.";
            } catch (java.io.IOException | RuntimeException e) {
                translated = shown + "\n\n(Перевод не получился: " + friendly(e) + ")";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            String ru = translated;
            SwingUtilities.invokeLater(() -> {
                text.setText(ru);
                text.setCaretPosition(0);
            });
        });
    }

    /** Понятный текст вместо сырого исключения: сеть, таймаут, отказ сервера. */
    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof java.net.http.HttpTimeoutException || m.contains("timed out")) return "Сайт не отвечает. Проверьте интернет и повторите.";
        if (e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException || m.contains("loopback")) return "Нет соединения с сайтом. Проверьте интернет.";
        return m;
    }

    /** CurseForge работает только с личным ключом: поле для ввода вместо тупиковой ошибки. */
    private void showKeyPrompt() {
        list.removeAll();
        JPanel box = Kit.clear(new BorderLayout(0, 10));
        box.setBorder(new EmptyBorder(24, 8, 0, 8));
        box.add(Kit.label("<html><div style='width:290px'>CurseForge отдаёт каталог только по личному ключу API. Ключ бесплатный: console.curseforge.com → API keys. Вставьте его сюда один раз.</div></html>", Font.PLAIN, 13, Kit.MUTED), BorderLayout.NORTH);
        JTextField key = new JTextField();
        key.putClientProperty("JTextField.placeholderText", "Ключ API CurseForge");
        JComponent field = Kit.field(key, "key");
        field.setPreferredSize(new Dimension(10, 42));
        box.add(field, BorderLayout.CENTER);
        JPanel row = Kit.clear(new GridLayout(1, 2, 8, 0));
        row.add(new Kit.Btn("Получить ключ", false, "globe", () -> ru.cw.launcher.util.Utils.openUrl("https://console.curseforge.com/")));
        Runnable save = () -> {
            String v = key.getText().trim();
            if (v.isEmpty()) return;
            ContentSource.keyOverride = v;
            keySaver.accept(v);
            reload(1);
        };
        row.add(new Kit.Btn("Сохранить", true, "check", save));
        row.setPreferredSize(new Dimension(10, 44));
        box.add(row, BorderLayout.SOUTH);
        key.addActionListener(e -> save.run());
        list.add(box);
        list.revalidate();
        list.repaint();
    }

    private void showStatus(String text) {
        list.removeAll();
        JLabel l = Kit.label("<html><div style='width:300px'>" + escape(text) + "</div></html>", Font.PLAIN, 13, Kit.MUTED);
        l.setBorder(new EmptyBorder(30, 8, 0, 8));
        list.add(l);
        list.revalidate();
        list.repaint();
    }

    private void fill(ContentSource.Page res, Kind k, Provider p) {
        list.removeAll();
        pages = Math.min(res.pages(), 500);
        if (res.items().isEmpty()) {
            showStatus("Ничего не найдено");
        } else {
            for (Item it : res.items()) list.add(new Row(it, k, p));
        }
        rebuildPager();
        list.revalidate();
        list.repaint();
    }

    private void rebuildPager() {
        pager.removeAll();
        pager.add(pageButton("chevron-left", page > 1, page - 1));
        List<Integer> shown = new ArrayList<>();
        shown.add(1);
        for (int p = Math.max(2, page - 1); p <= Math.min(pages - 1, page + 1); p++) shown.add(p);
        if (pages > 1) shown.add(pages);
        int prev = 0;
        for (int p : shown) {
            if (p - prev > 1) pager.add(dots());
            pager.add(numButton(p));
            prev = p;
        }
        pager.add(pageButton("chevron-right", page < pages, page + 1));
        pager.revalidate();
        pager.repaint();
    }

    private JComponent dots() {
        JLabel l = Kit.label("…", Font.PLAIN, 14, Kit.MUTED);
        return l;
    }

    private JComponent numButton(int p) {
        boolean on = p == page;
        JComponent c = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(36, 36);
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Kit.quality(g);
                g.setColor(on ? Kit.ACCENT_DIM : Kit.HOVER);
                g.fill(new RoundRectangle2D.Float(0, 0, 36, 36, 11, 11));
                g.setColor(on ? Kit.ACCENT : Kit.LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, 35, 35, 11, 11));
                g.setFont(Kit.font(Font.PLAIN, 14));
                g.setColor(Kit.TEXT);
                FontMetrics fm = g.getFontMetrics();
                String s = Integer.toString(p);
                g.drawString(s, (36 - fm.stringWidth(s)) / 2, (36 - fm.getHeight()) / 2 + fm.getAscent());
                g.dispose();
            }
        };
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) {
                if (p != page) reload(p);
            }
        });
        return c;
    }

    private JComponent pageButton(String icon, boolean enabled, int target) {
        JComponent c = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(36, 36);
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Kit.quality(g);
                g.setColor(Kit.HOVER);
                g.fill(new RoundRectangle2D.Float(0, 0, 36, 36, 11, 11));
                g.setColor(Kit.LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, 35, 35, 11, 11));
                Icons.draw(g, icon, 8, 8, 20, enabled ? Kit.TEXT : new Color(0x4A4D58));
                g.dispose();
            }
        };
        if (enabled) {
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            c.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    reload(target);
                }
            });
        }
        return c;
    }

    /** Одна строка каталога. */
    private final class Row extends JComponent {
        private final Item item;
        private final Kind k;
        private final Provider prov;
        private final Rectangle btn = new Rectangle();
        private boolean hoverBtn;

        Row(Item item, Kind k, Provider prov) {
            this.item = item;
            this.k = k;
            this.prov = prov;
            setPreferredSize(new Dimension(10, 84));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 84));
            if (!item.iconUrl().isEmpty() && !ICONS.containsKey(item.iconUrl())) {
                IO.submit(() -> {
                    BufferedImage img = loadIcon(item.iconUrl());
                    if (img != null) {
                        ICONS.put(item.iconUrl(), img);
                        SwingUtilities.invokeLater(this::repaint);
                    }
                });
            }
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean h = btn.contains(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    if (h != hoverBtn) {
                        hoverBtn = h;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (btn.contains(e.getPoint())) {
                        // Minecraft Inside без API: страница проекта и скачивание — на самом сайте.
                        if (!prov.installable()) ru.cw.launcher.util.Utils.openUrl(item.id());
                        else onAction.accept(item, Row.this.k, prov);
                        repaint();
                    } else {
                        details(item, Row.this.k, prov);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hoverBtn = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            int w = getWidth();
            int h = getHeight();
            g.setColor(Kit.LINE);
            g.drawLine(0, h - 1, w, h - 1);
            // иконка проекта
            BufferedImage img = ICONS.get(item.iconUrl());
            Shape clip = new RoundRectangle2D.Float(2, 14, 54, 54, 14, 14);
            if (img != null) {
                Shape old = g.getClip();
                g.setClip(clip);
                g.drawImage(img, 2, 14, 54, 54, null);
                g.setClip(old);
            } else {
                g.setColor(new Color(0x22242B));
                g.fill(clip);
                g.setFont(Kit.font(Font.BOLD, 20));
                g.setColor(Kit.MUTED);
                String ch = item.title().isEmpty() ? "?" : item.title().substring(0, 1).toUpperCase();
                FontMetrics f = g.getFontMetrics();
                g.drawString(ch, 2 + (54 - f.stringWidth(ch)) / 2, 14 + (54 - f.getHeight()) / 2 + f.getAscent());
            }
            int tx = 70;
            int right = w - 56;
            g.setFont(Kit.font(Font.BOLD, 14));
            g.setColor(Kit.TEXT);
            g.drawString(clip(g, item.title(), right - tx), tx, 28);
            g.setFont(Kit.font(Font.PLAIN, 12));
            g.setColor(Kit.MUTED);
            g.drawString(clip(g, item.author(), right - tx), tx, 45);
            g.drawString(clip(g, item.summary(), right - tx), tx, 62);
            // счётчики: загрузки и подписчики
            g.setFont(Kit.font(Font.PLAIN, 12));
            int sx = tx;
            Icons.draw(g, "download", sx, 69, 13, Kit.MUTED);
            String d = ContentSource.compact(item.downloads());
            g.drawString(d, sx + 17, 80);
            sx += 17 + g.getFontMetrics().stringWidth(d) + 18;
            Icons.draw(g, "heart", sx, 69, 13, Kit.MUTED);
            g.drawString(ContentSource.compact(item.likes()), sx + 17, 80);
            // кнопка
            boolean done = prov.installable() && marked.test(item, k, prov);
            btn.setBounds(w - 46, 22, 40, 40);
            g.setColor(done ? new Color(0x1F5B35) : hoverBtn ? Kit.ACCENT_HI : Kit.ACCENT);
            g.fill(new RoundRectangle2D.Float(btn.x, btn.y, btn.width, btn.height, 12, 12));
            Icons.draw(g, done ? "check" : prov.installable() ? "download" : "globe", btn.x + 9, btn.y + 9, 22, Color.WHITE);
            g.dispose();
        }
    }

    private static String clip(Graphics2D g, String s, int width) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= width) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static BufferedImage loadIcon(String url) {
        try {
            java.net.http.HttpResponse<byte[]> r = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(15))
                            .header("User-Agent", "CWLauncher/3.0").GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() / 100 != 2) return null;
            return ImageIO.read(new java.io.ByteArrayInputStream(r.body()));
        } catch (Exception e) {
            return null;
        }
    }
}
