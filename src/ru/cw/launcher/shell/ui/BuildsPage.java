package ru.cw.launcher.shell.ui;

import ru.cw.launcher.shell.BuildSpec;
import ru.cw.launcher.shell.BuildStore;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Список созданных сборок. */
public final class BuildsPage extends JPanel {

    private final BuildStore store;
    private final Runnable onCreate;
    private final JPanel cards = new JPanel();
    private final Map<String, String> status = new ConcurrentHashMap<>();

    public BuildsPage(BuildStore store, Runnable onCreate) {
        super(new BorderLayout(0, 16));
        this.store = store;
        this.onCreate = onCreate;
        setOpaque(false);

        JPanel head = Kit.clear(new BorderLayout());
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Сборки", Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Ваши сборки Minecraft: версия, ядро, моды и настройки.", Font.PLAIN, 14, Kit.MUTED));
        head.add(t, BorderLayout.CENTER);
        head.add(new Kit.Btn("Создать сборку", true, "plus", onCreate), BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        cards.setOpaque(false);
        cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
        JPanel wrap = Kit.clear(new BorderLayout());
        wrap.add(cards, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        Kit.style(sp);
        add(sp, BorderLayout.CENTER);
        refresh();
    }

    /** Текст под названием сборки: «Установка модов 2/5» и т. п. */
    public void setStatus(String id, String text) {
        if (text == null || text.isEmpty()) status.remove(id);
        else status.put(id, text);
        SwingUtilities.invokeLater(this::repaintCards);
    }

    private void repaintCards() {
        cards.repaint();
    }

    public void refresh() {
        cards.removeAll();
        List<BuildSpec> all = store.list();
        if (all.isEmpty()) {
            JPanel empty = new Kit.Round(new BorderLayout(), Kit.CARD, Kit.LINE, 18);
            empty.setBorder(new EmptyBorder(40, 30, 40, 30));
            JPanel in = Kit.clear(new GridLayout(2, 1, 0, 6));
            JLabel a = Kit.label("Пока нет ни одной сборки", Font.BOLD, 18, Kit.TEXT);
            a.setHorizontalAlignment(SwingConstants.CENTER);
            JLabel b = Kit.label("Нажмите «Создать сборку», выберите версию игры и ядро.", Font.PLAIN, 14, Kit.MUTED);
            b.setHorizontalAlignment(SwingConstants.CENTER);
            in.add(a);
            in.add(b);
            empty.add(in, BorderLayout.CENTER);
            cards.add(empty);
        }
        for (BuildSpec b : all) {
            cards.add(new Card(b));
            cards.add(Kit.vgap(12));
        }
        cards.revalidate();
        cards.repaint();
    }

    private final class Card extends Kit.Round {
        private final BuildSpec b;
        private final Rectangle folder = new Rectangle();
        private final Rectangle trash = new Rectangle();

        Card(BuildSpec b) {
            super(null, Kit.CARD, Kit.LINE, 18);
            this.b = b;
            setPreferredSize(new Dimension(10, 96));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean on = folder.contains(e.getPoint()) || trash.contains(e.getPoint());
                    setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (folder.contains(e.getPoint())) openFolder();
                    else if (trash.contains(e.getPoint())) confirmDelete();
                }
            });
        }

        private void openFolder() {
            try {
                Desktop.getDesktop().open(store.dir(b.id).toFile());
            } catch (IOException | UnsupportedOperationException ex) {
                JOptionPane.showMessageDialog(this, "Не удалось открыть папку: " + ex.getMessage());
            }
        }

        private void confirmDelete() {
            int r = JOptionPane.showConfirmDialog(this,
                    "Удалить сборку «" + b.name + "» вместе с модами и мирами?", "Удаление сборки",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;
            try {
                store.delete(b.id);
                refresh();
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Не удалось удалить: " + ex.getMessage());
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            int w = getWidth();
            g.setPaint(new GradientPaint(0, 0, new Color(0x6A2F9E), 70, 70, new Color(0xD5323E)));
            g.fill(new RoundRectangle2D.Float(18, 16, 64, 64, 16, 16));
            Icons.draw(g, "cube", 33, 31, 34, Color.WHITE);
            g.setFont(Kit.font(Font.BOLD, 17));
            g.setColor(Kit.TEXT);
            g.drawString(b.name, 100, 38);
            g.setFont(Kit.font(Font.PLAIN, 13));
            g.setColor(Kit.MUTED);
            String core = b.usesLoader() ? b.loader + (b.loaderVersion.isEmpty() ? "" : " " + b.loaderVersion) : "Vanilla";
            g.drawString("Minecraft " + b.mcVersion + "  •  " + core + "  •  " + b.kind.title + "  •  "
                    + String.format("%.1f", b.effectiveRamMb() / 1024.0) + " ГБ", 100, 60);
            String st = status.get(b.id);
            if (st != null) {
                g.setColor(Kit.ACCENT_HI);
                g.drawString(st, 100, 80);
            } else if (!b.description.isBlank()) {
                String d = b.description.replace('\n', ' ');
                FontMetrics fm = g.getFontMetrics();
                while (d.length() > 3 && fm.stringWidth(d) > w - 320) d = d.substring(0, d.length() - 2);
                g.drawString(d, 100, 80);
            }
            folder.setBounds(w - 108, 28, 40, 40);
            trash.setBounds(w - 58, 28, 40, 40);
            for (Rectangle r : new Rectangle[]{folder, trash}) {
                g.setColor(new Color(0x1B1C22));
                g.fill(new RoundRectangle2D.Float(r.x, r.y, 40, 40, 12, 12));
                g.setColor(Kit.LINE);
                g.draw(new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, 39, 39, 12, 12));
            }
            Icons.draw(g, "folder", folder.x + 9, folder.y + 9, 22, Kit.TEXT);
            Icons.draw(g, "trash", trash.x + 9, trash.y + 9, 22, new Color(0xFF6B76));
            g.dispose();
        }
    }

    /** Нужен для тестов отрисовки. */
    public BuildStore store() {
        return store;
    }

    /** Не используется напрямую, оставлено для симметрии с другими страницами. */
    public Runnable createAction() {
        return onCreate;
    }
}
