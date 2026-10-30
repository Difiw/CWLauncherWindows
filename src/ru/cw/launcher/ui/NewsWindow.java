package ru.cw.launcher.ui;

import ru.cw.launcher.news.NewsManager;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;

/** Современная текстовая лента, рассчитанная на источник без картинок. */
public final class NewsWindow {
    private NewsWindow() {}

    public static void show(Window owner, List<NewsManager.Item> items) {
        JDialog d = new JDialog(owner, "Новости", Dialog.ModalityType.APPLICATION_MODAL);
        d.setUndecorated(true);
        if (owner != null && owner.isShowing()) d.setBounds(owner.getBounds());
        else { d.setSize(1100, 720); d.setLocationRelativeTo(owner); }
        JPanel root = new JPanel(new BorderLayout(0, 20));
        root.setBackground(Theme.BG);
        root.setBorder(new EmptyBorder(28, 34, 26, 34));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel titles = new JPanel();
        titles.setOpaque(false);
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Новости Common World");
        title.setFont(Theme.H1.deriveFont(36f));
        title.setForeground(Color.WHITE);
        JLabel hint = new JLabel("События сервера, изменения сборки и важные объявления");
        hint.setFont(Theme.BODY);
        hint.setForeground(Theme.MUTED);
        titles.add(title); titles.add(Box.createVerticalStrut(5)); titles.add(hint);
        head.add(titles, BorderLayout.CENTER);
        JButton close = SettingsChrome.pill("Закрыть", false);
        close.addActionListener(e -> d.dispose());
        head.add(close, BorderLayout.EAST);
        root.add(head, BorderLayout.NORTH);
        JPanel feed = new JPanel();
        feed.setOpaque(false);
        feed.setLayout(new BoxLayout(feed, BoxLayout.Y_AXIS));
        if (items == null || items.isEmpty()) feed.add(empty());
        else {
            boolean first = true;
            for (NewsManager.Item item : items) {
                if (item == null) continue;
                if (!first) feed.add(Box.createVerticalStrut(14));
                feed.add(card(item, first));
                first = false;
            }
        }
        JScrollPane scroll = new JScrollPane(feed);
        Theme.style(scroll);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(20);
        root.add(scroll, BorderLayout.CENTER);
        d.setContentPane(root);
        WindowBackground.dim(d);
        Motion.present(d);
    }

    private static JComponent card(NewsManager.Item item, boolean featured) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, featured ? 210 : 150));
        p.setPreferredSize(new Dimension(800, featured ? 210 : 150));
        p.setBorder(BorderFactory.createCompoundBorder(new Dialogs.CutBorder(featured ? Theme.INFO : Theme.LINE, 16), new EmptyBorder(featured ? 26 : 20, 24, featured ? 24 : 20, 24)));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel category = new JLabel(featured ? "ПОСЛЕДНЯЯ НОВОСТЬ" : "НОВОСТЬ");
        category.setFont(Theme.SMALL.deriveFont(Font.BOLD));
        category.setForeground(Theme.INFO);
        JLabel title = new JLabel(item.title() == null || item.title().isBlank() ? "Common World" : item.title());
        title.setFont(featured ? Theme.H1.deriveFont(28f) : Theme.H2.deriveFont(20f));
        title.setForeground(Color.WHITE);
        JLabel date = new JLabel(item.date() == null ? "" : item.date());
        date.setFont(Theme.SMALL); date.setForeground(Theme.MUTED);
        JTextArea body = new JTextArea(item.text() == null ? "" : item.text());
        body.setEditable(false); body.setLineWrap(true); body.setWrapStyleWord(true);
        body.setRows(featured ? 4 : 3); body.setFont(Theme.BODY); body.setForeground(Theme.TEXT);
        body.setOpaque(false); body.setBorder(null);
        text.add(category); text.add(Box.createVerticalStrut(6)); text.add(title);
        if (!date.getText().isBlank()) { text.add(Box.createVerticalStrut(4)); text.add(date); }
        text.add(Box.createVerticalStrut(10)); text.add(body);
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    private static JComponent empty() {
        JLabel l = new JLabel("Новости пока недоступны", SwingConstants.CENTER);
        l.setFont(Theme.H2); l.setForeground(Theme.MUTED);
        l.setBorder(new EmptyBorder(80, 20, 80, 20));
        return l;
    }
}
