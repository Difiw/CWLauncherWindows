package ru.cw.launcher.ui;

import ru.cw.launcher.util.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * «О лаунчере»: что это за программа, политика конфиденциальности, разработчик и ссылки.
 * Текст рисуется самой страницей: так высота карточек считается точно и не зависит от раскладки Swing.
 */
final class AboutPage extends JPanel {

    private static final String GITHUB_LAUNCHER = "https://github.com/Difiw/CWLauncherWindows";
    private static final String GITHUB_ANDROID = "https://github.com/Difiw/CWLauncherAndroid";
    private static final String SITE = "https://commonworld.ru/index.php";

    private record Link(String label, String url) {
    }

    private record Section(String title, List<String> paragraphs, List<Link> links) {
    }

    private final java.util.function.Consumer<String> opener;
    private final List<Section> sections = new ArrayList<>();
    private final Doc doc = new Doc();

    AboutPage(java.util.function.Consumer<String> opener, ru.cw.launcher.core.Settings cfg) {
        super(new BorderLayout());
        this.opener = opener;
        setOpaque(false);
        sections.addAll(content(cfg));

        JPanel head = new JPanel();
        head.setOpaque(false);
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setBorder(BorderFactory.createEmptyBorder(s(26), s(34), s(10), s(20)));
        JLabel title = SettingsKit.label(Tr.t("О лаунчере", "About"), 40f, Font.BOLD, SettingsKit.TEXT);
        JLabel sub = SettingsKit.label("CWLauncher " + Log.VERSION + " · " + Tr.t("для Common World и ваших сборок",
                "for Common World and your own builds"), 17f, Font.PLAIN, new Color(0xB5C2D0));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(title);
        head.add(sub);
        add(head, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(doc, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(null);
        SettingsKit.styleScroll(scroll);
        scroll.getVerticalScrollBar().setUnitIncrement(s(24));
        add(scroll, BorderLayout.CENTER);
    }

    private static List<Section> content(ru.cw.launcher.core.Settings cfg) {
        List<Section> out = new ArrayList<>();
        out.add(new Section(Tr.t("Что это за лаунчер", "What this launcher is"), List.of(
                Tr.t("CWLauncher — бесплатный лаунчер для Minecraft: Java Edition. Он создан для игры на сервере Common World, "
                                + "но подходит и для своих версий игры, сборок с модами и локальных серверов с друзьями.",
                        "CWLauncher is a free launcher for Minecraft: Java Edition. It is made for the Common World server, "
                                + "but also works for your own game versions, modpacks and local servers for friends."),
                Tr.t("Лаунчер сам скачивает нужную версию Minecraft, Java и моды, проверяет файлы и запускает игру. "
                                + "Ничего устанавливать вручную не нужно.",
                        "The launcher downloads the right Minecraft version, Java and mods, checks the files and starts the game. "
                                + "Nothing has to be installed by hand."),
                Tr.t("Версия лаунчера: ", "Launcher version: ") + Log.VERSION), List.of()));

        out.add(new Section(Tr.t("Что умеет", "Features"), List.of(
                Tr.t("• Запуск Minecraft с Fabric и модами сервера Common World в один клик.",
                        "• Starts Minecraft with Fabric and the Common World mods in one click."),
                Tr.t("• Вход: локальный аккаунт или Ely.by через браузер. Пароль в лаунчер не вводится.",
                        "• Sign-in: a local account or Ely.by through the browser. No password is typed into the launcher."),
                Tr.t("• Скины: библиотека скинов, объёмный просмотр и редактор.",
                        "• Skins: a skin library, 3D preview and an editor."),
                Tr.t("• Сборки: свои сборки Minecraft, каталог модов, карт и ресурспаков (Modrinth, CurseForge, Minecraft Inside).",
                        "• Builds: your own Minecraft builds and a catalog of mods, maps and resource packs (Modrinth, CurseForge, Minecraft Inside)."),
                Tr.t("• Локальные серверы: запуск сервера для игры с друзьями (режим разработчика).",
                        "• Local servers: run a server to play with friends (developer mode)."),
                Tr.t("• Новости сервера и лаунчера, статус сервера, автоматическое обновление.",
                        "• Server and launcher news, server status, automatic updates."),
                Tr.t("• Настройки: оформление, память, Java, параметры игры, диагностика.",
                        "• Settings: appearance, memory, Java, game options, diagnostics.")), List.of()));

        out.add(new Section(Tr.t("Политика конфиденциальности", "Privacy policy"), List.of(
                Tr.t("Редакция от 1 ноября 2026 года.", "Edition of 1 November 2026."),
                Tr.t("Что хранится на вашем компьютере. Настройки, список аккаунтов, скины, журнал работы, файлы игры и сборок. "
                                + "Всё лежит в папке %APPDATA%\\CWLauncher и в папке лаунчера. Лаунчер ничего оттуда не отправляет.",
                        "What is stored on your computer. Settings, the account list, skins, the log, game and build files. "
                                + "Everything lives in %APPDATA%\\CWLauncher and in the launcher folder. The launcher does not upload any of it."),
                Tr.t("Пароли. Лаунчер не запрашивает и не хранит пароли. Вход через Ely.by выполняется на сайте Ely.by в вашем браузере. "
                                + "Токены входа хранятся в зашифрованном виде средствами Windows (DPAPI) и привязаны к вашей учётной записи Windows.",
                        "Passwords. The launcher neither asks for nor stores passwords. Ely.by sign-in happens on the Ely.by site in your browser. "
                                + "Sign-in tokens are encrypted with Windows (DPAPI) and tied to your Windows account."),
                Tr.t("Сбор данных. В лаунчере нет аналитики, рекламы и сбора статистики использования. "
                                + "Время игры и счётчики активности на главной считаются и хранятся только у вас.",
                        "Data collection. The launcher has no analytics, ads or usage statistics. "
                                + "Play time and activity counters on the home page are calculated and kept only on your computer."),
                Tr.t("Сетевые запросы. Лаунчер обращается к серверам Mojang (версии и файлы игры), Fabric (загрузчик), "
                                + "серверу Common World (статус, новости, обновления сборки и лаунчера), Ely.by (вход), "
                                + "Modrinth, CurseForge и Minecraft Inside (каталог сборок, только когда вы его открываете) и к GitHub (ссылки). "
                                + "Как любой сайт, они видят ваш IP-адрес и название программы в запросе.",
                        "Network requests. The launcher contacts Mojang (game versions and files), Fabric (loader), "
                                + "the Common World server (status, news, pack and launcher updates), Ely.by (sign-in), "
                                + "Modrinth, CurseForge and Minecraft Inside (the build catalog, only while you have it open) and GitHub (links). "
                                + "Like any website they can see your IP address and the program name in the request."),
                Tr.t("Перевод описаний. Если вы включите перевод описаний модов в настройках, тексты описаний отправляются "
                                + "службе перевода. Отключено по умолчанию.",
                        "Description translation. If you turn on description translation in settings, the description texts are sent "
                                + "to a translation service. It is off by default."),
                Tr.t("Удаление данных. Закройте лаунчер и удалите папку %APPDATA%\\CWLauncher и папку лаунчера. "
                                + "Аккаунт Ely.by удаляется только на сайте Ely.by.",
                        "Deleting data. Close the launcher and delete %APPDATA%\\CWLauncher and the launcher folder. "
                                + "An Ely.by account can only be deleted on the Ely.by site."),
                Tr.t("Вопросы по конфиденциальности — через поддержку в Discord (ссылка ниже).",
                        "Privacy questions — through support on Discord (link below).")), List.of()));

        List<Link> links = new ArrayList<>();
        links.add(new Link(Tr.t("GitHub лаунчера (Windows)", "Launcher on GitHub (Windows)"), GITHUB_LAUNCHER));
        links.add(new Link(Tr.t("GitHub приложения для Android", "Android app on GitHub"), GITHUB_ANDROID));
        links.add(new Link(Tr.t("Сайт сервера", "Server website"), SITE));
        if (cfg.telegramUrl != null && !cfg.telegramUrl.isBlank()) links.add(new Link("Telegram", cfg.telegramUrl));
        if (cfg.discordUrl != null && !cfg.discordUrl.isBlank()) links.add(new Link("Discord", cfg.discordUrl));
        if (cfg.supportUrl != null && !cfg.supportUrl.isBlank()) {
            links.add(new Link(Tr.t("Техподдержка", "Support"), cfg.supportUrl));
        }
        out.add(new Section(Tr.t("Разработчик и проекты", "Developer and projects"), List.of(
                Tr.t("Разработка и поддержка — команда Common World (GitHub: Difiw). "
                                + "Исходный код лаунчера для Windows и приложения для Android открыт, ошибки и пожелания можно "
                                + "оставлять в разделе Issues на GitHub.",
                        "Developed and maintained by the Common World team (GitHub: Difiw). "
                                + "The source code of the Windows launcher and the Android app is open; bugs and ideas "
                                + "can be filed under Issues on GitHub."),
                Tr.t("Есть приложение CWLauncher для Android — тот же лаунчер для телефона.",
                        "There is also a CWLauncher app for Android — the same launcher for your phone.")), links));

        out.add(new Section(Tr.t("Сторонние сервисы", "Third-party services"), List.of(
                Tr.t("Minecraft — товарный знак Mojang AB и Microsoft. Лаунчер не является официальным продуктом Mojang или Microsoft. "
                                + "Fabric, Modrinth, CurseForge, Minecraft Inside и Ely.by — отдельные проекты, "
                                + "их содержимое принадлежит их авторам и распространяется на условиях этих сайтов. "
                                + "Мод или карту нужно скачивать, только если вас устраивает его лицензия.",
                        "Minecraft is a trademark of Mojang AB and Microsoft. The launcher is not an official Mojang or Microsoft product. "
                                + "Fabric, Modrinth, CurseForge, Minecraft Inside and Ely.by are separate projects; "
                                + "their content belongs to its authors and is shared under the terms of those sites. "
                                + "Download a mod or map only if you are fine with its license.")), List.of()));
        return out;
    }

    // ------------------------------------------------------------------ рисование

    /** Колонка карточек. Ширина ограничена, чтобы строки текста читались. */
    private final class Doc extends JComponent implements Scrollable {
        private final List<Object[]> hits = new ArrayList<>();
        private int hover = -1;

        Doc() {
            setOpaque(false);
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int h = hit(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(h >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                    if (h != hover) {
                        hover = h;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    int h = hit(e.getPoint());
                    if (h >= 0) opener.accept(((Link) hits.get(h)[1]).url());
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            });
        }

        private int hit(Point p) {
            for (int i = 0; i < hits.size(); i++) if (((Rectangle) hits.get(i)[0]).contains(p)) return i;
            return -1;
        }

        private int contentWidth(int width) {
            return Math.min(s(980), width - s(34) - s(24));
        }

        @Override
        public Dimension getPreferredSize() {
            int w = getParent() != null && getParent().getWidth() > 0 ? getParent().getWidth() : s(900);
            BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = scratch.createGraphics();
            int h = lay(g, w, false);
            g.dispose();
            return new Dimension(w, h);
        }

        /** Раскладывает карточки; apply — рисовать, иначе только измерить высоту. */
        private int lay(Graphics2D g, int width, boolean paint) {
            SettingsKit.smooth(g);
            int x = s(34);
            int cw = contentWidth(width);
            int gap = s(14);
            int pad = s(22);
            int y = s(6);
            Font titleFont = SettingsKit.font(Font.BOLD, 21f);
            Font textFont = SettingsKit.font(Font.PLAIN, 15.5f);
            Font btnFont = SettingsKit.font(Font.BOLD, 14.5f);
            if (paint) hits.clear();
            for (Section sec : sections) {
                FontMetrics tf = g.getFontMetrics(titleFont);
                FontMetrics xf = g.getFontMetrics(textFont);
                FontMetrics bf = g.getFontMetrics(btnFont);
                int inner = cw - pad * 2;
                List<String> lines = new ArrayList<>();
                List<Integer> gaps = new ArrayList<>();
                for (String para : sec.paragraphs()) {
                    List<String> wrapped = wrap(para, xf, para.startsWith("•") ? inner - s(0) : inner);
                    for (int i = 0; i < wrapped.size(); i++) {
                        lines.add(wrapped.get(i));
                        gaps.add(i == wrapped.size() - 1 ? s(9) : 0);
                    }
                }
                int lineH = xf.getHeight() + s(3);
                int textH = 0;
                for (int i = 0; i < lines.size(); i++) textH += lineH + gaps.get(i);
                // Кнопки-ссылки идут рядами с переносом.
                int bh = s(40);
                int rows = 0;
                if (!sec.links().isEmpty()) {
                    int rx = 0;
                    rows = 1;
                    for (Link l : sec.links()) {
                        int bw = bf.stringWidth(l.label()) + s(36);
                        if (rx > 0 && rx + bw > inner) {
                            rows++;
                            rx = 0;
                        }
                        rx += bw + s(10);
                    }
                }
                int linksH = rows == 0 ? 0 : s(6) + rows * (bh + s(10));
                int cardH = pad + tf.getHeight() + s(12) + textH + linksH + pad - s(4);
                if (paint) {
                    int r = SettingsKit.radius() + s(2);
                    g.setColor(SettingsKit.card());
                    g.fill(new RoundRectangle2D.Float(x, y, cw - 1, cardH - 1, r, r));
                    g.setColor(SettingsKit.CARD_LINE);
                    g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, cw - 2, cardH - 2, r, r));
                    g.setFont(titleFont);
                    g.setColor(SettingsKit.TEXT);
                    int ty = y + pad + tf.getAscent();
                    g.drawString(sec.title(), x + pad, ty);
                    g.setFont(textFont);
                    g.setColor(new Color(0xD5DEE8));
                    int ly = y + pad + tf.getHeight() + s(12) + xf.getAscent();
                    for (int i = 0; i < lines.size(); i++) {
                        g.drawString(lines.get(i), x + pad, ly);
                        ly += lineH + gaps.get(i);
                    }
                    int bx = 0;
                    int by = y + pad + tf.getHeight() + s(12) + textH + s(6);
                    for (Link l : sec.links()) {
                        int bw = bf.stringWidth(l.label()) + s(36);
                        if (bx > 0 && bx + bw > inner) {
                            bx = 0;
                            by += bh + s(10);
                        }
                        Rectangle rect = new Rectangle(x + pad + bx, by, bw, bh);
                        hits.add(new Object[]{rect, l});
                        boolean over = hover == hits.size() - 1;
                        g.setColor(over ? SettingsKit.ACTIVE : new Color(0x16222F));
                        g.fill(new RoundRectangle2D.Float(rect.x, rect.y, rect.width, rect.height, SettingsKit.rr(), SettingsKit.rr()));
                        g.setColor(SettingsKit.CARD_LINE);
                        g.draw(new RoundRectangle2D.Float(rect.x + 0.5f, rect.y + 0.5f, rect.width - 1, rect.height - 1,
                                SettingsKit.rr(), SettingsKit.rr()));
                        g.setFont(btnFont);
                        g.setColor(Color.WHITE);
                        g.drawString(l.label(), rect.x + s(18), rect.y + (bh + bf.getAscent() - bf.getDescent()) / 2);
                        bx += bw + s(10);
                    }
                }
                y += cardH + gap;
            }
            return y + s(20);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            lay(g2, getWidth(), true);
            g2.dispose();
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return s(24);
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return Math.max(s(24), r.height - s(60));
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** Перенос по словам под ширину. Длинные слова без пробелов режутся по ширине. */
    private static List<String> wrap(String text, FontMetrics fm, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (fm.stringWidth(next) <= width) {
                line = new StringBuilder(next);
                continue;
            }
            if (line.length() > 0) out.add(line.toString());
            line = new StringBuilder(word);
            while (fm.stringWidth(line.toString()) > width && line.length() > 1) {
                int cut = line.length() - 1;
                while (cut > 1 && fm.stringWidth(line.substring(0, cut)) > width) cut--;
                out.add(line.substring(0, cut));
                line = new StringBuilder(line.substring(cut));
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
