package ru.cw.launcher.ui;

import ru.cw.launcher.news.NewsManager;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/** Новости целиком: сразу текст на весь экран, без списка заголовков сбоку. */
public final class NewsWindow {

    private NewsWindow() {
    }

    public static void show(Window owner, List<NewsManager.Item> items) {
        JDialog d = new JDialog(owner, "Новости", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        if (owner != null && owner.isShowing()) d.setBounds(owner.getBounds());
        else {
            d.setSize(980, 640);
            d.setLocationRelativeTo(owner);
        }
        d.getContentPane().setBackground(new Color(0x0B1220));
        d.setLayout(new BorderLayout(12, 8));
        ((JComponent) d.getContentPane()).setBorder(BorderFactory.createEmptyBorder(16, 16, 12, 16));

        JLabel title = new JLabel("Новости");
        title.setFont(Theme.H2);
        title.setForeground(Color.WHITE);
        d.add(title, BorderLayout.NORTH);

        JTextArea area = new JTextArea(text(items));
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Theme.BODY);
        area.setBackground(Theme.FIELD);
        area.setForeground(Theme.TEXT);
        area.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        area.setCaretPosition(0);

        JScrollPane textScroll = new JScrollPane(area);
        Theme.style(textScroll);
        textScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2458A0)));
        textScroll.getViewport().setBackground(Theme.FIELD);
        d.add(textScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        CutButton close = new CutButton("Закрыть", CutButton.Accent.GHOST, false);
        close.addActionListener(e -> d.dispose());
        buttons.add(close);
        d.add(buttons, BorderLayout.SOUTH);
        WindowBackground.dim(d);
        Motion.present(d);
    }

    /** Все новости подряд: заголовок, дата, текст. */
    private static String text(List<NewsManager.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (NewsManager.Item item : items) {
            if (item == null) continue;
            if (sb.length() > 0) sb.append("\n\n────────────\n\n");
            sb.append(item.title()).append("\n\n");
            if (item.date() != null && !item.date().isBlank()) sb.append(item.date()).append("\n\n");
            if (item.text() != null) sb.append(item.text());
        }
        return sb.toString();
    }
}
