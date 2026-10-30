package ru.cw.launcher.ui;

import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.profiles.LauncherProfile;
import ru.cw.launcher.settings.SettingsManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Полноценный менеджер версий вместо тесного выпадающего списка. */
public final class VersionsWindow {
    private VersionsWindow() {}

    public static void show(Window owner, LauncherApp app, Runnable onChange) {
        JDialog d = new JDialog(owner, "Версии игры", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        d.setMinimumSize(new Dimension(980, 650));
        d.setSize(1120, 720);
        d.setLocationRelativeTo(owner);
        d.setContentPane(new Screen(d, app, onChange));
        WindowBackground.dim(d);
        Motion.present(d);
    }

    private static final class Screen extends JPanel {
        private final JDialog dialog;
        private final LauncherApp app;
        private final Runnable onChange;
        private final JPanel list = new JPanel();
        private final JLabel status = new JLabel("Загружаем доступные версии…");

        Screen(JDialog dialog, LauncherApp app, Runnable onChange) {
            super(new BorderLayout());
            this.dialog = dialog;
            this.app = app;
            this.onChange = onChange;
            setBackground(Theme.BG);
            add(sidebar(), BorderLayout.WEST);
            add(content(), BorderLayout.CENTER);
            load();
        }

        private JComponent sidebar() {
            JPanel p = new JPanel();
            p.setPreferredSize(new Dimension(220, 10));
            p.setBackground(new Color(0x0A111D));
            p.setBorder(new EmptyBorder(28, 20, 22, 20));
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            JLabel brand = new JLabel("COMMON WORLD");
            brand.setFont(Theme.H2);
            brand.setForeground(Color.WHITE);
            p.add(brand);
            JLabel sub = new JLabel("LAUNCHER");
            sub.setFont(Theme.SMALL);
            sub.setForeground(Theme.INFO);
            p.add(sub);
            p.add(Box.createVerticalStrut(34));
            p.add(nav("Главная", Icons.Kind.PICKAXE, false));
            p.add(Box.createVerticalStrut(8));
            p.add(nav("Играть", Icons.Kind.PLAY, false));
            p.add(Box.createVerticalStrut(8));
            p.add(nav("Версии игры", Icons.Kind.MODS, true));
            p.add(Box.createVerticalStrut(8));
            p.add(nav("Моды", Icons.Kind.SETTINGS, false));
            p.add(Box.createVerticalGlue());
            JButton close = SettingsChrome.pill("Назад к лаунчеру", false);
            close.setAlignmentX(Component.LEFT_ALIGNMENT);
            close.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
            close.addActionListener(e -> dialog.dispose());
            p.add(close);
            return p;
        }

        private JButton nav(String text, Icons.Kind icon, boolean active) {
            JButton b = SettingsChrome.pill(text, active);
            b.setIcon(Icons.of(icon, 16).getIconObject());
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.setIconTextGap(12);
            b.setAlignmentX(Component.LEFT_ALIGNMENT);
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            return b;
        }

        private JComponent content() {
            JPanel root = new JPanel(new BorderLayout(0, 18));
            root.setOpaque(false);
            root.setBorder(new EmptyBorder(28, 30, 28, 30));
            JPanel head = new JPanel(new BorderLayout(20, 0));
            head.setOpaque(false);
            JPanel titles = new JPanel();
            titles.setOpaque(false);
            titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("Версии игры");
            title.setFont(Theme.H1.deriveFont(36f));
            title.setForeground(Color.WHITE);
            titles.add(title);
            status.setFont(Theme.BODY);
            status.setForeground(Theme.MUTED);
            titles.add(Box.createVerticalStrut(5));
            titles.add(status);
            head.add(titles, BorderLayout.CENTER);
            JTextField search = new JTextField();
            search.putClientProperty("JTextField.placeholderText", "Найти версию");
            search.setPreferredSize(new Dimension(260, 42));
            Theme.style(search);
            head.add(search, BorderLayout.EAST);
            root.add(head, BorderLayout.NORTH);
            list.setOpaque(false);
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            JScrollPane scroll = new JScrollPane(list);
            Theme.style(scroll);
            scroll.setBorder(null);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            root.add(scroll, BorderLayout.CENTER);
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
            });
            return root;
        }

        private void load() {
            app.io().submit(() -> {
                List<Map<String, Object>> versions;
                String error = null;
                try { versions = new ArrayList<>(app.mc().selectableVersions()); }
                catch (Exception ex) { versions = new ArrayList<>(); error = ex.getMessage(); }
                List<Map<String, Object>> ready = versions;
                String problem = error;
                SwingUtilities.invokeLater(() -> fill(ready, problem));
            });
        }

        private void fill(List<Map<String, Object>> versions, String error) {
            list.removeAll();
            list.add(new VersionCard("common-world", "Common World", "Minecraft 1.20.1 • Fabric",
                    app.mc().versionReady("1.20.1"), app.cfg.commonWorld, true));
            for (Map<String, Object> value : versions) {
                String id = String.valueOf(value.get("id"));
                if (id.equals("1.20.1")) continue;
                boolean installed = Boolean.TRUE.equals(value.get("installed"));
                list.add(Box.createVerticalStrut(12));
                list.add(new VersionCard(id, "Minecraft " + id,
                        app.cfg.fabricFor(id) ? "Fabric" : "Vanilla", installed,
                        !app.cfg.commonWorld && id.equals(app.cfg.minecraftVersion), false));
            }
            status.setText(error == null ? "Выберите сборку Minecraft, Fabric и модов" : "Показаны локальные версии");
            list.revalidate();
            list.repaint();
        }

        private void filter(String query) {
            String q = query == null ? "" : query.trim().toLowerCase();
            for (Component c : list.getComponents()) {
                if (c instanceof VersionCard card) card.setVisible(q.isEmpty() || card.search.contains(q));
            }
            list.revalidate();
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
            dialog.dispose();
        }

        private final class VersionCard extends JPanel {
            final String search;
            VersionCard(String id, String title, String detail, boolean installed, boolean selected, boolean server) {
                super(new BorderLayout(18, 0));
                search = (title + " " + detail).toLowerCase();
                setOpaque(false);
                setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
                setPreferredSize(new Dimension(700, 132));
                setBorder(BorderFactory.createCompoundBorder(new Dialogs.CutBorder(selected ? Theme.INFO : Theme.LINE, 14), new EmptyBorder(18, 20, 18, 20)));
                add(new JLabel(Icons.of(server ? Icons.Kind.SERVER : Icons.Kind.MODS, 34).getIconObject()), BorderLayout.WEST);
                JPanel text = new JPanel();
                text.setOpaque(false);
                text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
                JLabel name = new JLabel(title);
                name.setFont(Theme.H2.deriveFont(23f));
                name.setForeground(Color.WHITE);
                text.add(name);
                text.add(Box.createVerticalStrut(6));
                JLabel meta = new JLabel(detail + "    •    " + (installed ? "Установлена" : "Не установлена"));
                meta.setFont(Theme.BODY);
                meta.setForeground(installed ? Theme.GREEN : Theme.MUTED);
                text.add(meta);
                text.add(Box.createVerticalStrut(8));
                JLabel state = new JLabel(selected ? "●  Используется сейчас" : server ? "Рекомендуемая сборка сервера" : "Доступна для установки");
                state.setFont(Theme.SMALL);
                state.setForeground(selected ? Theme.INFO : Theme.MUTED);
                text.add(state);
                add(text, BorderLayout.CENTER);
                JButton action = SettingsChrome.pill(selected ? "Выбрано" : installed ? "Выбрать" : "Установить", !selected);
                action.setEnabled(!selected);
                action.addActionListener(e -> choose(id, server));
                JPanel east = new JPanel(new GridBagLayout());
                east.setOpaque(false);
                east.add(action);
                add(east, BorderLayout.EAST);
            }
        }
    }
}
