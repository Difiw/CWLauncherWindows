package ru.cw.launcher.ui;

import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.server.LocalServerManager;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.List;

/** Панель создания и управления локальными серверами. */
public final class LocalServerWindow {

    private LocalServerWindow() {
    }

    public static void show(Window owner, LauncherApp app) {
        JDialog dialog = new JDialog(owner, "Локальные серверы", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setMinimumSize(new Dimension(640, 430));
        dialog.setSize(720, 500);
        dialog.setLocationRelativeTo(owner);

        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBackground(Theme.PANEL);
        body.setBorder(BorderFactory.createCompoundBorder(
                new Dialogs.CutBorder(Theme.INFO, 10), BorderFactory.createEmptyBorder(18, 20, 16, 20)));

        JLabel title = new JLabel("Локальные серверы");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.H3);
        JLabel addressHint = new JLabel(addressHint());
        addressHint.setForeground(Theme.MUTED);
        addressHint.setFont(Theme.SMALL);
        JPanel header = new JPanel(new BorderLayout(0, 6));
        header.setOpaque(false);
        header.add(title, BorderLayout.NORTH);
        header.add(addressHint, BorderLayout.CENTER);
        body.add(header, BorderLayout.NORTH);

        DefaultListModel<LocalServerManager.Server> model = new DefaultListModel<>();
        JList<LocalServerManager.Server> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.FIELD);
        list.setForeground(Theme.TEXT);
        list.setFont(Theme.BODY);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> source, Object value, int index,
                                                          boolean selected, boolean focus) {
                LocalServerManager.Server server = (LocalServerManager.Server) value;
                String text = server.name + "  ·  " + server.status() + "  ·  " + server.address();
                JLabel row = (JLabel) super.getListCellRendererComponent(source, text, index, selected, focus);
                row.setOpaque(true);
                row.setBackground(selected ? Theme.PANEL_2 : Theme.FIELD);
                row.setForeground(Theme.TEXT);
                row.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                return row;
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(new Dialogs.CutBorder(Theme.LINE, 6));
        body.add(scroll, BorderLayout.CENTER);

        JLabel detail = new JLabel("Создайте сервер, чтобы начать локальное тестирование.");
        detail.setForeground(Theme.MUTED);
        detail.setFont(Theme.SMALL);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.add(detail, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        CutButton create = new CutButton("Создать сервер", CutButton.Accent.PRIMARY);
        CutButton start = new CutButton("Запустить", CutButton.Accent.NEUTRAL);
        CutButton stop = new CutButton("Остановить", CutButton.Accent.DANGER);
        CutButton folder = new CutButton("Открыть папку", CutButton.Accent.GHOST);
        CutButton copy = new CutButton("Скопировать IP", CutButton.Accent.GHOST);
        CutButton close = new CutButton(Lang.t("close"), CutButton.Accent.GHOST);
        actions.add(create);
        actions.add(start);
        actions.add(stop);
        actions.add(folder);
        actions.add(copy);
        actions.add(close);
        footer.add(actions, BorderLayout.SOUTH);
        body.add(footer, BorderLayout.SOUTH);

        boolean[] refreshing = {false};
        Runnable refresh = () -> {
            if (refreshing[0]) return;
            refreshing[0] = true;
            try {
                String selectedId = list.getSelectedValue() == null ? null : list.getSelectedValue().id;
                List<LocalServerManager.Server> servers = app.localServers.servers();
                model.clear();
                for (LocalServerManager.Server server : servers) model.addElement(server);
                if (selectedId != null) {
                    for (int i = 0; i < model.size(); i++) {
                        if (model.get(i).id.equals(selectedId)) {
                            list.setSelectedIndex(i);
                            break;
                        }
                    }
                } else if (!model.isEmpty()) {
                    list.setSelectedIndex(model.size() - 1);
                }
                LocalServerManager.Server selected = list.getSelectedValue();
                if (selected == null) {
                    detail.setText("Создайте сервер — мир Minecraft будет создан автоматически при первом запуске.");
                    start.setEnabled(false);
                    stop.setEnabled(false);
                    folder.setEnabled(false);
                    copy.setEnabled(false);
                } else {
                    detail.setText("<html>" + escape(selected.description) + " &nbsp; Minecraft "
                            + escape(selected.minecraftVersion) + " · Fabric " + escape(selected.loaderVersion)
                            + "<br>Для себя: localhost:" + selected.port + " · Для сети: "
                            + escape(selected.address()) + "<br>Папка: "
                            + escape(selected.directory.toString()) + "</html>");
                    String state = selected.status();
                    boolean running = "Работает".equals(state);
                    boolean changing = state.startsWith("Запуска") || state.startsWith("Останавливается")
                            || state.startsWith("Проверка") || state.startsWith("Подготовка");
                    start.setEnabled(!running && !changing);
                    stop.setEnabled(running);
                    folder.setEnabled(true);
                    copy.setEnabled(true);
                }
                list.repaint();
            } finally {
                refreshing[0] = false;
            }
        };

        create.addActionListener(e -> create(app, dialog, refresh));
        start.addActionListener(e -> {
            LocalServerManager.Server server = list.getSelectedValue();
            if (server != null) {
                app.localServers.start(server.id);
                detail.setText("Запуск сервера…");
                refresh.run();
            }
        });
        stop.addActionListener(e -> {
            LocalServerManager.Server server = list.getSelectedValue();
            if (server != null) {
                app.localServers.stop(server.id);
                detail.setText("Остановка сервера…");
                refresh.run();
            }
        });
        folder.addActionListener(e -> {
            LocalServerManager.Server server = list.getSelectedValue();
            if (server != null && !Utils.openFolder(server.directory)) {
                Dialogs.error(dialog, "Не удалось открыть папку сервера.", server.directory.toString(), null);
            }
        });
        copy.addActionListener(e -> {
            LocalServerManager.Server server = list.getSelectedValue();
            if (server == null) return;
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(server.address()), null);
            detail.setText("Адрес скопирован: " + server.address());
        });
        close.addActionListener(e -> dialog.dispose());
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refresh.run();
        });

        dialog.setContentPane(body);
        WindowBackground.dim(dialog);
        refresh.run();
        Timer timer = new Timer(1000, e -> refresh.run());
        timer.start();
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
            }
        });
        Motion.present(dialog);
    }

    private static void create(LauncherApp app, JDialog owner, Runnable refresh) {
        JDialog dialog = new JDialog(owner, "Создать локальный сервер", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel body = new JPanel(new BorderLayout(0, 12));
        body.setBackground(Theme.PANEL);
        body.setBorder(BorderFactory.createCompoundBorder(
                new Dialogs.CutBorder(Theme.INFO, 10), BorderFactory.createEmptyBorder(18, 20, 16, 20)));
        JLabel title = new JLabel("Создать локальный сервер");
        title.setForeground(Theme.TEXT);
        title.setFont(Theme.H3);
        body.add(title, BorderLayout.NORTH);

        JTextField name = Theme.field("Мой сервер", 40);
        JTextField description = Theme.field("", 160);
        JTextField directory = Theme.field("", 240);
        directory.setText(Paths.root().resolve("servers").resolve("Мой сервер").toString());

        JButton chooseServer = new JButton("Выбрать…");
        chooseServer.addActionListener(e -> chooseDirectory(dialog, directory));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 0, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 0;
        addRow(form, c, 0, "Название", name, null);
        addRow(form, c, 1, "Короткое описание", description, null);
        addRow(form, c, 2, "Папка сервера", directory, chooseServer);

        JLabel version = new JLabel("Minecraft " + app.cfg.minecraftVersion + " · Fabric");
        version.setForeground(Theme.TEXT);
        version.setFont(Theme.BODY);
        c.gridx = 1;
        c.gridy = 3;
        c.gridwidth = 2;
        form.add(version, c);

        JCheckBox accept = Theme.check("Я прочитал(а) и принимаю Minecraft EULA", false);
        JButton eulaLink = new JButton("Открыть EULA");
        eulaLink.setForeground(Theme.INFO);
        eulaLink.setBorderPainted(false);
        eulaLink.setContentAreaFilled(false);
        eulaLink.addActionListener(e -> {
            if (!Utils.openUrl("https://www.minecraft.net/eula")) {
                Dialogs.error(dialog, "Не удалось открыть Minecraft EULA.", "https://www.minecraft.net/eula", null);
            }
        });
        JPanel consent = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        consent.setOpaque(false);
        consent.add(accept);
        consent.add(eulaLink);
        c.gridx = 1;
        c.gridy = 4;
        c.gridwidth = 2;
        form.add(consent, c);

        JLabel note = new JLabel("<html>Создаётся чистый Fabric-сервер без копирования файлов игры. "
                + "Мир создастся при первом запуске; свои серверные моды можно добавить в папку mods.</html>");
        note.setForeground(Theme.MUTED);
        note.setFont(Theme.SMALL);
        c.gridx = 1;
        c.gridy = 5;
        c.gridwidth = 2;
        form.add(note, c);
        body.add(form, BorderLayout.CENTER);

        JLabel status = new JLabel("Сервер работает отдельно от лаунчера; мир сохранится при штатной остановке.");
        status.setForeground(Theme.MUTED);
        status.setFont(Theme.SMALL);
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        bottom.setOpaque(false);
        bottom.add(status, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        CutButton create = new CutButton("Создать и запустить", CutButton.Accent.PRIMARY);
        CutButton cancel = new CutButton(Lang.t("cancel"), CutButton.Accent.GHOST);
        buttons.add(create);
        buttons.add(cancel);
        bottom.add(buttons, BorderLayout.SOUTH);
        body.add(bottom, BorderLayout.SOUTH);

        create.addActionListener(e -> {
            if (name.getText() == null || name.getText().trim().isEmpty()) {
                status.setText("Укажите название сервера.");
                return;
            }
            if (directory.getText().isBlank()) {
                status.setText("Укажите папку сервера.");
                return;
            }
            if (!accept.isSelected()) {
                status.setText("Для запуска сервера необходимо принять EULA.");
                return;
            }
            String serverName = name.getText() == null ? "" : name.getText().trim();
            Path target;
            try {
                target = Path.of(directory.getText().trim()).toAbsolutePath().normalize();
            } catch (Exception ex) {
                status.setText("Проверьте путь к папке сервера.");
                return;
            }
            create.setEnabled(false);
            cancel.setEnabled(false);
            status.setText("Подготовка сервера…");
            LocalServerManager.Draft draft = new LocalServerManager.Draft(serverName,
                    description.getText(), target, app.cfg.minecraftVersion,
                    app.fabric().installedLoader(app.cfg.minecraftVersion), accept.isSelected());
            new SwingWorker<LocalServerManager.Server, String>() {
                @Override
                protected LocalServerManager.Server doInBackground() throws Exception {
                    return app.localServers.create(draft, this::publish);
                }

                @Override
                protected void process(List<String> chunks) {
                    if (!chunks.isEmpty()) status.setText(chunks.get(chunks.size() - 1) + "…");
                }

                @Override
                protected void done() {
                    try {
                        LocalServerManager.Server server = get();
                        refresh.run();
                        JOptionPane.showMessageDialog(dialog,
                                "Сервер «" + server.name + "» запущен.\nАдрес: " + server.address()
                                        + "\nДоступность извне зависит от сети, VPN и брандмауэра Windows.",
                                "Сервер готов", JOptionPane.INFORMATION_MESSAGE);
                        dialog.dispose();
                    } catch (Exception ex) {
                        Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                        Log.error("Не удалось создать локальный сервер: " + Log.reason(cause), cause);
                        status.setText("Не удалось создать сервер: " + Log.reason(cause));
                        create.setEnabled(true);
                        cancel.setEnabled(true);
                    }
                }
            }.execute();
        });
        cancel.addActionListener(e -> dialog.dispose());
        dialog.setContentPane(body);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(620, 420));
        dialog.setLocationRelativeTo(owner);
        WindowBackground.dim(dialog);
        Motion.present(dialog);
    }

    private static void addRow(JPanel form, GridBagConstraints c, int row, String label,
                               JTextField field, JButton browse) {
        JLabel caption = new JLabel(label);
        caption.setForeground(Theme.TEXT);
        caption.setFont(Theme.BODY);
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 1;
        c.weightx = 0;
        form.add(caption, c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(field, c);
        if (browse != null) {
            c.gridx = 2;
            c.weightx = 0;
            c.insets = new Insets(4, 0, 4, 0);
            form.add(browse, c);
            c.insets = new Insets(4, 0, 4, 8);
        }
    }

    private static void chooseDirectory(Component parent, JTextField field) {
        JFileChooser chooser = new JFileChooser();
        try {
            if (!field.getText().isBlank()) {
                chooser.setCurrentDirectory(Path.of(field.getText()).toFile());
            }
        } catch (RuntimeException e) {
            Log.warn("Не удалось использовать текущую папку в диалоге выбора: " + Log.reason(e));
        }
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Папка локального сервера");
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            field.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        }
    }

    private static String addressHint() {
        List<String> addresses = LocalServerManager.addresses();
        if (addresses.isEmpty()) {
            return "Адрес появится после подключения к сети или Radmin VPN.";
        }
        return "Приоритет — адрес Radmin VPN (если подключён), затем локальные IP: "
                + String.join("  ·  ", addresses)
                + "  ·  порт сервера указан в списке";
    }

    private static String escape(String text) {
        if (text == null || text.isBlank()) return "—";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
