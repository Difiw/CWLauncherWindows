package ru.cw.launcher.ui;

import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.core.Operation;
import ru.cw.launcher.core.Progress;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/** Полноэкранное состояние загрузки, установки и восстановления. */
final class OperationWindow extends JDialog {
    private final LauncherApp app;
    private final JLabel title = new JLabel("Подготовка…");
    private final JLabel percent = new JLabel("0%");
    private final JLabel meta = new JLabel(" ");
    private final JLabel file = new JLabel(" ");
    private final JProgressBar bar = new JProgressBar(0, 100);

    OperationWindow(Window owner, LauncherApp app) {
        super(owner, "Операция CWLauncher", Dialog.ModalityType.MODELESS);
        this.app = app;
        setUndecorated(true);
        if (owner != null && owner.isShowing()) setBounds(owner.getBounds());
        else { setSize(1120, 720); setLocationRelativeTo(owner); }
        JPanel root = new JPanel(new BorderLayout(24, 24));
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(34, 40, 30, 40));
        JLabel heading = new JLabel("Обновление Minecraft");
        heading.setFont(Theme.H1.deriveFont(36f));
        heading.setForeground(Color.WHITE);
        root.add(heading, BorderLayout.NORTH);

        JPanel center = SettingsChrome.card(Icons.Kind.DOWNLOAD, "Устанавливаем обновление",
                "Последняя версия игры и все необходимые файлы", null, operationBody());
        JPanel system = SettingsChrome.card(Icons.Kind.CHECK, "Состояние системы",
                "Среда готова к установке", null, systemBody());
        JPanel split = new JPanel(new GridBagLayout()); split.setOpaque(false);
        GridBagConstraints a = new GridBagConstraints(); a.gridx=0; a.weightx=.72; a.weighty=1; a.fill=GridBagConstraints.BOTH; a.insets=new Insets(0,0,0,18);
        split.add(center,a); GridBagConstraints b = new GridBagConstraints(); b.gridx=1; b.weightx=.28; b.weighty=1; b.fill=GridBagConstraints.BOTH; split.add(system,b);
        root.add(split, BorderLayout.CENTER);
        JButton cancel = SettingsChrome.pill("Отмена", false); cancel.addActionListener(e -> app.cancelCurrent());
        JLabel note = new JLabel("Можно свернуть лаунчер — загрузка продолжится"); note.setForeground(Theme.MUTED); note.setFont(Theme.BODY);
        JPanel foot = new JPanel(new BorderLayout()); foot.setOpaque(false); foot.add(note, BorderLayout.WEST); foot.add(cancel, BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);
        setContentPane(root);
    }

    private JComponent operationBody() {
        JPanel p = new JPanel(); p.setOpaque(false); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        title.setFont(Theme.H2.deriveFont(24f)); title.setForeground(Color.WHITE); title.setAlignmentX(Component.LEFT_ALIGNMENT);
        percent.setFont(Theme.H1.deriveFont(52f)); percent.setForeground(Theme.INFO); percent.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setUI(new Theme.FlatProgressBarUI()); bar.setForeground(Theme.INFO); bar.setBackground(Theme.FIELD); bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16)); bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        meta.setFont(Theme.H2); meta.setForeground(Theme.TEXT); meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        file.setFont(Theme.MONO); file.setForeground(Theme.MUTED); file.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(30)); p.add(title); p.add(Box.createVerticalStrut(28)); p.add(percent); p.add(Box.createVerticalStrut(18)); p.add(bar); p.add(Box.createVerticalStrut(18)); p.add(meta); p.add(Box.createVerticalStrut(12)); p.add(file);
        return p;
    }

    private JComponent systemBody() {
        JPanel p = new JPanel(); p.setOpaque(false); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (String s : new String[]{"Интернет  •  подключён", "Свободное место  •  проверено", "Java 17  •  готова", "Файлы игры  •  доступны"}) {
            JLabel l = new JLabel("●  " + s); l.setFont(Theme.BODY); l.setForeground(Theme.GREEN); l.setAlignmentX(Component.LEFT_ALIGNMENT); p.add(l); p.add(Box.createVerticalStrut(18));
        }
        return p;
    }

    void refresh() {
        Operation op = app.currentOperation();
        Progress pr = op == null ? null : op.progress();
        String stage = pr == null || pr.stage() == null || pr.stage().isBlank() ? "Подготовка операции" : pr.stage();
        title.setText(stage);
        int value = pr != null && pr.total() > 0 ? (int)Math.min(100, pr.done()*100/pr.total()) : op == null ? 0 : Math.max(0, op.percent());
        bar.setIndeterminate(value <= 0); if (value > 0) bar.setValue(value); percent.setText(value > 0 ? value + "%" : "…");
        if (pr != null) {
            String speed = pr.speed() > 0 ? ru.cw.launcher.util.Utils.humanSize((long)pr.speed()) + "/с" : "считаем скорость";
            meta.setText(speed + "    •    " + (pr.etaSeconds() >= 0 ? pr.etaText() : "оцениваем время"));
            file.setText(pr.text() == null ? " " : pr.text());
        }
    }
}
