package ru.cw.launcher.shell.ui;

import ru.cw.launcher.shell.BuildSpec;
import ru.cw.launcher.shell.BuildStore;
import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.CoreCatalog;
import ru.cw.launcher.shell.ModInstaller;
import ru.cw.launcher.shell.ShellPrefs;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Окно лаунчера v3: боковое меню и страницы Главная, Сборки, Моды, Карты, Ресурспаки, Аккаунты, Настройки. */
public final class ShellWindow extends JPanel {

    public static final String[][] NAV = {
            {"home", "Главная", "home"}, {"builds", "Сборки", "layers"}, {"mods", "Моды", "puzzle"},
            {"maps", "Карты", "map"}, {"packs", "Ресурспаки", "palette"}, {"accounts", "Аккаунты", "user"},
            {"settings", "Настройки", "gear"}};

    private final BuildStore store;
    private final ShellPrefs prefs;
    private final CoreCatalog catalog;
    private final ContentSource content;
    private final ExecutorService installer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cw-install");
        t.setDaemon(true);
        return t;
    });

    private final CardLayout cards = new CardLayout();
    private final JPanel body = Kit.clear(cards);
    private final List<NavItem> navItems = new ArrayList<>();
    private BuildsPage builds;
    private String current = "";

    public ShellWindow(BuildStore store, ShellPrefs prefs) {
        this(store, prefs, new CoreCatalog(), new ContentSource());
    }

    public ShellWindow(BuildStore store, ShellPrefs prefs, CoreCatalog catalog, ContentSource content) {
        super(new BorderLayout());
        this.store = store;
        this.prefs = prefs;
        this.catalog = catalog;
        this.content = content;
        ContentSource.keyOverride = prefs.curseKey;
        setBackground(Kit.BG);
        add(sidebar(), BorderLayout.WEST);
        body.setBorder(new EmptyBorder(16, 20, 14, 20));
        add(body, BorderLayout.CENTER);

        builds = new BuildsPage(store, () -> show("create"));
        body.add(homePage(), "home");
        body.add(builds, "builds");
        body.add(browsePage("Моды", "Моды для Minecraft: Modrinth и CurseForge.", ContentSource.Kind.MOD), "mods");
        body.add(browsePage("Карты", "Карты и приключения.", ContentSource.Kind.MAP), "maps");
        body.add(browsePage("Ресурспаки", "Текстуры и оформление.", ContentSource.Kind.RESOURCEPACK), "packs");
        body.add(accountsPage(), "accounts");
        body.add(settingsPage(), "settings");
        show("home");
    }

    @Override
    protected void paintComponent(Graphics g) {
        g.setColor(Kit.BG);
        g.fillRect(0, 0, getWidth(), getHeight());
    }

    /** Переключает страницу; «create» каждый раз собирается заново, чтобы форма была чистой. */
    public void show(String page) {
        if ("create".equals(page)) {
            for (Component c : body.getComponents()) if (c instanceof CreateBuildPage) body.remove(c);
            CreateBuildPage p = new CreateBuildPage(catalog, new CreateBuildPage.Host() {
                @Override
                public void created(BuildSpec spec, List<ModInstaller.Queued> queue) {
                    builds.refresh();
                    show("builds");
                    install(spec, queue);
                }

                @Override
                public void cancelled() {
                    show("builds");
                }

                @Override
                public String tryCreate(BuildSpec spec) throws IOException {
                    store.create(spec);
                    return null;
                }
            }, content);
            body.add(p, "create");
        }
        if ("builds".equals(page)) builds.refresh();
        current = page;
        cards.show(body, page);
        String highlight = "create".equals(page) ? "builds" : page;
        for (NavItem n : navItems) n.repaint();
        for (NavItem n : navItems) n.setActive(n.key.equals(highlight));
    }

    public String currentPage() {
        return current;
    }

    private void install(BuildSpec spec, List<ModInstaller.Queued> queue) {
        if (queue.isEmpty()) return;
        builds.setStatus(spec.id, "Установка: 0/" + queue.size());
        installer.submit(() -> {
            try {
                List<String> failed = ModInstaller.install(spec, store.dir(spec.id), queue,
                        (done, title) -> builds.setStatus(spec.id, "Установка: " + done + "/" + queue.size()
                                + (title.isEmpty() ? "" : " — " + title)));
                builds.setStatus(spec.id, failed.isEmpty() ? "" : "Не установлено: " + failed.size()
                        + " (" + failed.get(0) + ")");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---- боковое меню ----

    private JComponent sidebar() {
        Kit.Round bar = new Kit.Round(new BorderLayout(), Kit.SIDEBAR, null, 0);
        bar.setPreferredSize(new Dimension(210, 10));
        bar.setBorder(new EmptyBorder(22, 14, 16, 14));

        JPanel logo = Kit.clear(new BorderLayout(10, 0));
        JComponent mark = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(40, 40);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Kit.quality(g2);
                Icons.draw(g2, "cube", 0, 0, 38, Kit.ACCENT_HI);
                g2.dispose();
            }
        };
        logo.add(mark, BorderLayout.WEST);
        JPanel name = Kit.clear(new GridLayout(1, 1));
        name.add(Kit.label("<html><b>CWLauncher</b>&nbsp;<font color='#8D909C' size='2'>v3.0</font></html>", Font.PLAIN, 20, Kit.TEXT));
        logo.add(name, BorderLayout.CENTER);
        logo.setBorder(new EmptyBorder(0, 6, 22, 0));

        JPanel nav = Kit.clear(null);
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        for (String[] n : NAV) {
            NavItem item = new NavItem(n[0], n[1], n[2]);
            navItems.add(item);
            nav.add(item);
            nav.add(Kit.vgap(6));
        }
        JPanel top = Kit.clear(new BorderLayout());
        top.add(logo, BorderLayout.NORTH);
        top.add(nav, BorderLayout.CENTER);
        bar.add(top, BorderLayout.NORTH);

        Kit.Round foot = new Kit.Round(new GridLayout(2, 1, 0, 2), Kit.FIELD, Kit.LINE, 12);
        foot.setBorder(new EmptyBorder(8, 14, 8, 14));
        foot.add(Kit.label("Тема: Красная", Font.BOLD, 13, Kit.TEXT));
        foot.add(Kit.label("● Готов к запуску", Font.PLAIN, 12, Kit.GREEN));
        foot.setPreferredSize(new Dimension(10, 62));
        bar.add(foot, BorderLayout.SOUTH);
        return bar;
    }

    private final class NavItem extends JComponent {
        final String key;
        private final String text;
        private final String icon;
        private boolean active;
        private boolean hover;

        NavItem(String key, String text, String icon) {
            this.key = key;
            this.text = text;
            this.icon = icon;
            setPreferredSize(new Dimension(10, 48));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setAlignmentX(Component.LEFT_ALIGNMENT);
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

                @Override
                public void mouseReleased(MouseEvent e) {
                    ShellWindow.this.show(NavItem.this.key);
                }
            });
        }

        void setActive(boolean a) {
            active = a;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            int w = getWidth();
            int h = getHeight();
            if (active) {
                g.setColor(new Color(0x3A1318));
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
                g.setColor(Kit.ACCENT);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));
            } else if (hover) {
                g.setColor(new Color(0x1B1C22));
                g.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            }
            Icons.draw(g, icon, 16, (h - 24) / 2, 24, active ? Kit.ACCENT_HI : Kit.TEXT);
            g.setFont(Kit.font(active ? Font.BOLD : Font.PLAIN, 16));
            g.setColor(active ? Color.WHITE : Kit.TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 54, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    // ---- страницы ----

    private JComponent homePage() {
        JPanel p = Kit.clear(new BorderLayout(0, 18));
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Добро пожаловать, " + prefs.nickname, Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Соберите свою сборку Minecraft или найдите моды и карты.", Font.PLAIN, 14, Kit.MUTED));
        p.add(t, BorderLayout.NORTH);

        Kit.Round hero = new Kit.Round(new BorderLayout(0, 14), Kit.CARD, Kit.LINE, 20) {
            @Override
            protected void paintComponent(Graphics g0) {
                super.paintComponent(g0);
                Graphics2D g = (Graphics2D) g0.create();
                Kit.quality(g);
                g.setPaint(new GradientPaint(0, 0, new Color(0x00000000, true), getWidth(), 0, new Color(0x40D5323E, true)));
                g.fill(new RoundRectangle2D.Float(1, 1, getWidth() - 2, getHeight() - 2, 20, 20));
                g.dispose();
            }
        };
        hero.setBorder(new EmptyBorder(34, 36, 34, 36));
        JPanel text = Kit.clear(new GridLayout(3, 1, 0, 8));
        text.add(Kit.label("Новая сборка", Font.BOLD, 34, Kit.TEXT));
        text.add(Kit.label("Выберите версию Minecraft и ядро — Forge, Fabric, Quilt или NeoForge.", Font.PLAIN, 15, Kit.MUTED));
        text.add(Kit.label("Моды и карты можно добавить сразу из каталога справа.", Font.PLAIN, 15, Kit.MUTED));
        hero.add(text, BorderLayout.CENTER);
        JPanel btn = Kit.clear(new FlowLayout(FlowLayout.LEFT, 0, 0));
        btn.add(new Kit.Btn("Создать сборку", true, "plus", () -> show("create")));
        hero.add(btn, BorderLayout.SOUTH);
        p.add(hero, BorderLayout.CENTER);
        return p;
    }

    private JComponent browsePage(String title, String sub, ContentSource.Kind kind) {
        JPanel p = Kit.clear(new BorderLayout(0, 16));
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label(title, Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label(sub, Font.PLAIN, 14, Kit.MUTED));
        p.add(t, BorderLayout.NORTH);
        BrowsePanel panel = new BrowsePanel(content, (item, k, prov) -> {
            String url = prov == ContentSource.Provider.MODRINTH
                    ? "https://modrinth.com/project/" + item.id()
                    : "https://www.curseforge.com/projects/" + item.id();
            try {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (IOException | UnsupportedOperationException e) {
                JOptionPane.showMessageDialog(this, url);
            }
        }, (item, k, prov) -> false, kind);
        p.add(panel, BorderLayout.CENTER);
        return p;
    }

    private JComponent accountsPage() {
        JPanel p = Kit.clear(new BorderLayout(0, 16));
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Аккаунты", Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Локальный ник для запуска сборок.", Font.PLAIN, 14, Kit.MUTED));
        p.add(t, BorderLayout.NORTH);
        JPanel form = Kit.clear(null);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JTextField nick = new JTextField(prefs.nickname);
        JLabel msg = Kit.label(" ", Font.PLAIN, 13, Kit.MUTED);
        form.add(left(Kit.label("Ник Minecraft", Font.BOLD, 14, Kit.TEXT)));
        form.add(Kit.vgap(6));
        JComponent f = Kit.field(nick, "user");
        f.setMaximumSize(new Dimension(420, 44));
        f.setPreferredSize(new Dimension(420, 44));
        form.add(left(f));
        form.add(Kit.vgap(12));
        form.add(left(new Kit.Btn("Сохранить", true, "check", () -> {
            String v = nick.getText().trim();
            if (!ShellPrefs.validNick(v)) {
                msg.setForeground(new Color(0xFF6B76));
                msg.setText("Ник: 3–16 символов, латиница, цифры и «_»");
                return;
            }
            prefs.nickname = v;
            msg.setForeground(Kit.GREEN);
            msg.setText(saveOk());
        })));
        form.add(Kit.vgap(10));
        form.add(left(msg));
        p.add(form, BorderLayout.CENTER);
        return p;
    }

    private JComponent settingsPage() {
        JPanel p = Kit.clear(new BorderLayout(0, 16));
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Настройки", Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Каталоги и папка сборок.", Font.PLAIN, 14, Kit.MUTED));
        p.add(t, BorderLayout.NORTH);
        JPanel form = Kit.clear(null);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        JTextField key = new JTextField(prefs.curseKey);
        JLabel msg = Kit.label(" ", Font.PLAIN, 13, Kit.MUTED);
        form.add(left(Kit.label("Ключ CurseForge API (необязательно)", Font.BOLD, 14, Kit.TEXT)));
        form.add(Kit.vgap(6));
        JComponent f = Kit.field(key, "hammer");
        f.setMaximumSize(new Dimension(520, 44));
        f.setPreferredSize(new Dimension(520, 44));
        form.add(left(f));
        form.add(Kit.vgap(12));
        JPanel row = Kit.clear(new FlowLayout(FlowLayout.LEFT, 12, 0));
        row.add(new Kit.Btn("Сохранить", true, "check", () -> {
            prefs.curseKey = key.getText().trim();
            ContentSource.keyOverride = prefs.curseKey;
            msg.setForeground(Kit.GREEN);
            msg.setText(saveOk());
        }));
        row.add(new Kit.Btn("Папка сборок", false, "folder", () -> {
            try {
                java.nio.file.Files.createDirectories(store.root());
                Desktop.getDesktop().open(store.root().toFile());
            } catch (IOException | UnsupportedOperationException e) {
                msg.setForeground(new Color(0xFF6B76));
                msg.setText("Не удалось открыть папку: " + e.getMessage());
            }
        }));
        form.add(left(row));
        form.add(Kit.vgap(10));
        form.add(left(msg));
        p.add(form, BorderLayout.CENTER);
        return p;
    }

    private String saveOk() {
        try {
            prefs.save();
            return "Сохранено";
        } catch (IOException e) {
            return "Не удалось сохранить: " + e.getMessage();
        }
    }

    private static JComponent left(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }
}
