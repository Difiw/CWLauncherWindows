package ru.cw.launcher.ui;

import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.profiles.LauncherProfile;
import ru.cw.launcher.settings.SettingsManager;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.Toggle;
import ru.cw.launcher.util.Log;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Страница «Версии игры»: сверху сборка сервера Common World, ниже все версии Minecraft
 * с поиском, вкладками «Все / Установленные / Релизы / Снапшоты» и переключателем Fabric.
 */
final class VersionsPage extends JPanel {

    private enum Filter {ALL, INSTALLED, RELEASES, SNAPSHOTS}

    private record Version(String id, String type, boolean installed) {
    }

    private final MainWindow mw;
    private final LauncherApp app;
    private final Runnable onChange;
    private final JLabel title = SettingsKit.label(Tr.t("Версии игры", "Game versions"), 40f, Font.BOLD, SettingsKit.TEXT);
    private final JLabel subtitle = SettingsKit.label(Tr.t("Сборка сервера или любая версия Minecraft",
            "The server pack or any Minecraft version"), 17f, Font.PLAIN, new Color(0xB5C2D0));
    private final JTextField search = SettingsKit.field("");
    private final FilterButton[] filters = {
            new FilterButton(Filter.RELEASES, Tr.t("Релизы", "Releases")),
            new FilterButton(Filter.INSTALLED, Tr.t("Установленные", "Installed")),
            new FilterButton(Filter.SNAPSHOTS, Tr.t("Снапшоты", "Snapshots")),
            new FilterButton(Filter.ALL, Tr.t("Все", "All"))};
    private final Featured featured = new Featured();
    private final JPanel list = new JPanel(null);
    private final JScrollPane scroll = new JScrollPane(list);
    private final JLabel status = SettingsKit.label(Tr.t("Загружаем список версий…", "Loading versions…"), 15.5f, Font.PLAIN,
            new Color(0xB5C2D0));
    private Filter filter = Filter.RELEASES;
    private List<Version> versions = new ArrayList<>();
    private boolean loaded;

    VersionsPage(MainWindow mw, LauncherApp app, Runnable onChange) {
        super(null);
        this.mw = mw;
        this.app = app;
        this.onChange = onChange;
        setOpaque(false);
        search.putClientProperty("cw.hint", Tr.t("Найти версию, например 1.21", "Find a version, e.g. 1.21"));
        SettingsKit.onText(search, this::refilter);
        list.setOpaque(false);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setViewportBorder(null);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        SettingsKit.styleScroll(scroll);
        scroll.getVerticalScrollBar().setUnitIncrement(s(30));
        scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                layoutList();
            }
        });
        for (JComponent c : new JComponent[]{title, subtitle, search, featured, scroll, status}) add(c);
        for (FilterButton f : filters) add(f);
        load();
    }

    private void load() {
        app.io().submit(() -> {
            List<Version> out = new ArrayList<>();
            String error = null;
            try {
                for (Map<String, Object> m : app.mc().selectableVersions()) {
                    String id = String.valueOf(m.get("id"));
                    if ("1.20.1".equals(id) && app.cfg.commonWorld) continue;
                    out.add(new Version(id, String.valueOf(m.getOrDefault("type", "release")),
                            Boolean.TRUE.equals(m.get("installed"))));
                }
            } catch (Exception e) {
                error = Log.reason(e);
                Log.warn("Список версий: " + error);
            }
            String problem = error;
            SwingUtilities.invokeLater(() -> {
                versions = out;
                loaded = true;
                status.setText(problem == null ? "" : Tr.t("Список Mojang недоступен, показаны установленные версии",
                        "Mojang list unavailable, showing installed versions"));
                refilter();
            });
        });
    }

    private void refilter() {
        list.removeAll();
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        int n = 0;
        for (Version v : versions) {
            boolean byTab = switch (filter) {
                case ALL -> true;
                case INSTALLED -> v.installed();
                case RELEASES -> "release".equalsIgnoreCase(v.type());
                case SNAPSHOTS -> "snapshot".equalsIgnoreCase(v.type());
            };
            if (!byTab || (!q.isEmpty() && !v.id().toLowerCase(Locale.ROOT).contains(q))) continue;
            list.add(new Row(v));
            n++;
        }
        String none = Tr.t("Ничего не найдено", "Nothing found");
        String offline = Tr.t("Список версий Mojang недоступен — проверьте интернет. Установленных версий пока нет.",
                "The Mojang version list is unavailable — check your connection. No versions installed yet.");
        if (n == 0 && versions.isEmpty() && loaded) status.setText(offline);
        else if (n == 0 && !versions.isEmpty()) status.setText(none);
        else if (n > 0 && (status.getText().equals(none) || status.getText().equals(offline))) status.setText("");
        for (FilterButton f : filters) f.repaint();
        featured.repaint();
        doLayout();
        layoutList();
        repaint();
    }

    private void choose(String id, boolean commonWorld) {
        SettingsManager.update(s -> {
            s.commonWorld = commonWorld;
            s.minecraftVersion = commonWorld ? "1.20.1" : id;
            s.loader = commonWorld || s.fabricFor(id) ? "fabric" : "vanilla";
            if (commonWorld) s.serverAddress = ru.cw.launcher.core.Settings.SERVER;
        });
        LauncherProfile profile = app.profile();
        if (profile != null) {
            profile.minecraftVersion = app.cfg.minecraftVersion;
            app.profiles().update(profile);
        }
        app.bindPaths();
        app.recheckMinecraftFiles();
        if (onChange != null) onChange.run();
        Dialogs.toast(mw, commonWorld ? Tr.t("Выбрана сборка Common World", "Common World pack selected")
                : Tr.t("Выбрана Minecraft ", "Selected Minecraft ") + id, SettingsKit.GREEN);
        mw.showHome();
    }

    private void setFabric(String id, boolean on) {
        SettingsManager.update(s -> {
            s.fabricVersions.put(id, on);
            if (!s.commonWorld && id.equals(s.minecraftVersion)) s.loader = on ? "fabric" : "vanilla";
        });
        if (!app.cfg.commonWorld && id.equals(app.cfg.minecraftVersion)) {
            app.recheckMinecraftFiles();
            if (onChange != null) onChange.run();
        }
    }

    // ------------------------------------------------------------------ раскладка

    @Override
    public void doLayout() {
        int w = getWidth();
        int h = getHeight();
        int x0 = s(20);
        int right = w - s(20);
        title.setBounds(x0 + s(16), s(26), s(500), s(54));
        subtitle.setBounds(x0 + s(18), s(80), s(600), s(28));
        int rowY = s(124);
        int fx = x0;
        for (FilterButton f : filters) {
            int fw = f.preferredWidth();
            f.setBounds(fx, rowY, fw, s(44));
            fx += fw + s(10);
        }
        int sw = Math.min(s(340), Math.max(s(180), right - fx - s(16)));
        search.setBounds(right - sw, rowY, sw, s(44));
        int top = rowY + s(60);
        featured.setBounds(x0, top, right - x0, s(150));
        int y = top + s(150) + s(16);
        scroll.setBounds(x0, y, right - x0 + s(8), h - y - s(18));
        status.setBounds(x0 + s(4), y + s(6), right - x0, s(26));
        layoutList();
    }

    private void layoutList() {
        int w = scroll.getViewport().getWidth() > 0 ? scroll.getViewport().getWidth() : scroll.getWidth();
        int y = status.getText().isBlank() ? 0 : s(40);
        int rh = s(78);
        for (Component c : list.getComponents()) {
            c.setBounds(0, y, Math.max(10, w - s(14)), rh);
            y += rh + s(10);
        }
        list.setPreferredSize(new Dimension(w, Math.max(0, y - s(10))));
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

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "release" -> Tr.t("Релиз", "Release");
            case "snapshot" -> Tr.t("Снапшот", "Snapshot");
            case "old_beta" -> "Beta";
            case "old_alpha" -> "Alpha";
            default -> type == null || type.isBlank() ? Tr.t("Версия", "Version") : type;
        };
    }

    /** Плашка «Установлена» / «Не установлена». */
    private static void chip(Graphics2D g2, boolean installed, int x, int y) {
        String t = installed ? Tr.t("Установлена", "Installed") : Tr.t("Скачается при запуске", "Downloads on launch");
        g2.setFont(SettingsKit.font(Font.BOLD, 12.5f));
        FontMetrics fm = g2.getFontMetrics();
        int bw = fm.stringWidth(t) + s(30);
        int bh = s(24);
        Color tone = installed ? SettingsKit.GREEN : new Color(0x9AA9BA);
        g2.setColor(new Color(tone.getRed(), tone.getGreen(), tone.getBlue(), 36));
        g2.fill(new RoundRectangle2D.Float(x, y, bw, bh, bh, bh));
        g2.setColor(tone);
        g2.fill(new Ellipse2D.Float(x + s(10), y + bh / 2f - s(4), s(8), s(8)));
        g2.drawString(t, x + s(22), y + (bh + fm.getAscent() - fm.getDescent()) / 2);
    }

    // ------------------------------------------------------------------ вкладки

    private final class FilterButton extends JComponent {
        private final Filter f;
        private final String text;
        private boolean hover;

        FilterButton(Filter f, String text) {
            this.f = f;
            this.text = text;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    filter = f;
                    refilter();
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
            boolean on = filter == f;
            if (on) {
                g2.setPaint(new GradientPaint(0, 0, SettingsKit.ACCENT, 0, h, SettingsKit.ACCENT_2));
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
            } else {
                g2.setColor(hover ? new Color(0x182535) : SettingsKit.card());
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, SettingsKit.rr(), SettingsKit.rr()));
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

    // ------------------------------------------------------------------ сборка сервера

    private final class Featured extends JPanel {
        private final Button pick = new Button("", Glyph.CHECK, Kind.PRIMARY);

        Featured() {
            super(null);
            setOpaque(false);
            add(pick);
            pick.addActionListener(e -> choose("1.20.1", true));
        }

        @Override
        public void doLayout() {
            pick.setBounds(getWidth() - s(28) - s(220), (getHeight() - s(56)) / 2, s(220), s(56));
        }

        @Override
        protected void paintComponent(Graphics g) {
            boolean selected = app.cfg.commonWorld;
            pick.setText(Tr.t("Выбрать", "Select"));
            pick.setVisible(!selected);
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
            if (pic != null) {
                double scale = Math.max((double) w / pic.getWidth(), (double) h / pic.getHeight());
                int dw = (int) Math.ceil(pic.getWidth() * scale);
                int dh = (int) Math.ceil(pic.getHeight() * scale);
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(pic, w - dw, Math.round((h - dh) * 0.3f), dw, dh, null);
            }
            g2.setPaint(new LinearGradientPaint(0, 0, w * 0.8f, 0, new float[]{0f, 0.55f, 1f},
                    new Color[]{new Color(13, 22, 33, 245), new Color(13, 22, 33, 170), new Color(13, 22, 33, 40)}));
            g2.fillRect(0, 0, w, h);
            g2.setClip(null);
            g2.setStroke(new BasicStroke(selected ? 2f : 1f));
            g2.setColor(selected ? SettingsKit.ACCENT : SettingsKit.CARD_LINE);
            g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r));
            int p = s(26);
            int box = h - p * 2;
            g2.setColor(SettingsKit.ACCENT);
            g2.fill(new RoundRectangle2D.Float(p, p, box, box, SettingsKit.rr() + s(4), SettingsKit.rr() + s(4)));
            int is = box * 50 / 100;
            SettingsKit.icon(Glyph.SERVERS, is, Color.WHITE).paintIcon(this, g2, p + (box - is) / 2, p + (box - is) / 2);
            int x = p + box + s(24);
            g2.setFont(SettingsKit.font(Font.BOLD, 12.5f));
            g2.setColor(SettingsKit.ACCENT);
            g2.drawString(Tr.t("СБОРКА СЕРВЕРА", "SERVER PACK"), x, p + s(16));
            g2.setFont(SettingsKit.font(Font.BOLD, 28f));
            g2.setColor(Color.WHITE);
            g2.drawString("Common World", x, p + s(52));
            g2.setFont(SettingsKit.font(Font.PLAIN, 15.5f));
            g2.setColor(new Color(0xC3CEDA));
            g2.drawString(Tr.t("Minecraft 1.20.1 · Fabric · моды сервера", "Minecraft 1.20.1 · Fabric · server mods"), x, p + s(78));
            chip(g2, app.mc().versionReady("1.20.1"), x, p + s(88));
            if (selected) {
                // Вместо блёклой выключенной кнопки — плашка «Используется».
                String t = Tr.t("Используется", "In use");
                g2.setFont(SettingsKit.font(Font.BOLD, 16f));
                FontMetrics fm = g2.getFontMetrics();
                int bw = fm.stringWidth(t) + s(56);
                int bh = s(46);
                int bx = w - s(28) - bw;
                int by = (h - bh) / 2;
                g2.setColor(new Color(13, 22, 33, 200));
                g2.fill(new RoundRectangle2D.Float(bx, by, bw, bh, bh, bh));
                g2.setColor(SettingsKit.ACCENT);
                g2.setStroke(new BasicStroke(1.5f));
                g2.draw(new RoundRectangle2D.Float(bx + 0.5f, by + 0.5f, bw - 1, bh - 1, bh, bh));
                SettingsKit.icon(Glyph.CHECK, s(18), SettingsKit.ACCENT).paintIcon(this, g2, bx + s(16), by + (bh - s(18)) / 2);
                g2.setColor(Color.WHITE);
                g2.drawString(t, bx + s(42), by + (bh + fm.getAscent() - fm.getDescent()) / 2);
            }
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ строка версии

    private final class Row extends JPanel {
        private final Version v;
        private final Toggle fabric;
        private final JLabel fabricLabel = SettingsKit.label("Fabric", 14.5f, Font.PLAIN, new Color(0xD5DEE8));
        private final Button pick = new Button("", null, Kind.GHOST);

        Row(Version v) {
            super(null);
            this.v = v;
            setOpaque(false);
            fabric = new Toggle(app.cfg.fabricFor(v.id()));
            fabric.setToolTipText(Tr.t("Ставить Fabric для этой версии", "Install Fabric for this version"));
            fabric.onChange(() -> setFabric(v.id(), fabric.isOn()));
            boolean selected = !app.cfg.commonWorld && v.id().equals(app.cfg.minecraftVersion);
            pick.setText(selected ? Tr.t("Выбрано", "Selected") : Tr.t("Выбрать", "Select"));
            pick.setEnabled(!selected);
            pick.addActionListener(e -> choose(v.id(), false));
            add(fabric);
            add(fabricLabel);
            add(pick);
        }

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            pick.setBounds(w - s(20) - s(150), (h - s(46)) / 2, s(150), s(46));
            int fx = pick.getX() - s(24) - s(64);
            fabric.setBounds(fx, (h - s(36)) / 2, s(64), s(36));
            fabricLabel.setBounds(fx - s(60), 0, s(56), h);
            fabricLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            SettingsKit.smooth(g2);
            int w = getWidth();
            int h = getHeight();
            boolean selected = !app.cfg.commonWorld && v.id().equals(app.cfg.minecraftVersion);
            card(g2, w, h);
            if (selected) {
                int r = SettingsKit.radius() + s(2);
                g2.setStroke(new BasicStroke(2f));
                g2.setColor(SettingsKit.ACCENT);
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r));
            }
            int p = s(14);
            int box = h - p * 2;
            g2.setColor(new Color(0x1B2836));
            g2.fill(new RoundRectangle2D.Float(p, p, box, box, SettingsKit.rr(), SettingsKit.rr()));
            int is = box * 55 / 100;
            SettingsKit.icon(Glyph.CUBE, is, new Color(0xD4DCE6)).paintIcon(this, g2, p + (box - is) / 2, p + (box - is) / 2);
            int x = p + box + s(18);
            g2.setFont(SettingsKit.font(Font.BOLD, 19f));
            g2.setColor(Color.WHITE);
            g2.drawString("Minecraft " + v.id(), x, h / 2 - s(4));
            g2.setFont(SettingsKit.font(Font.PLAIN, 14f));
            g2.setColor(new Color(0x9AA9BA));
            String type = typeLabel(v.type());
            g2.drawString(type, x, h / 2 + s(18));
            FontMetrics fm = g2.getFontMetrics();
            chip(g2, v.installed(), x + fm.stringWidth(type) + s(14), h / 2 + s(2));
            g2.dispose();
        }
    }
}
