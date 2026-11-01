package ru.cw.launcher.shell.ui;

import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.ContentSource.Item;
import ru.cw.launcher.shell.ContentSource.Provider;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** «Скачать сборку»: каталог готовых сборок. Выбранная ставится как обычная сборка лаунчера. */
public final class DownloadPackPage extends JPanel {

    public interface Host {
        void download(Item item, Provider provider);

        void back();
    }

    public DownloadPackPage(ContentSource content, Host host) {
        super(new BorderLayout(0, 14));
        setOpaque(false);

        JPanel head = Kit.clear(new BorderLayout(14, 0));
        JComponent back = new JComponent() {
            boolean hover;

            {
                setPreferredSize(new Dimension(44, 44));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                        host.back();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Kit.quality(g);
                g.setColor(hover ? Kit.HOVER_HI : Kit.HOVER);
                g.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 12, 12));
                g.setColor(Kit.LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, 43, 43, 12, 12));
                Icons.draw(g, "arrow-left", 10, 10, 24, Kit.TEXT);
                g.dispose();
            }
        };
        head.add(back, BorderLayout.WEST);
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Скачать сборку", Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Готовые сборки с Modrinth и CurseForge. Нажмите на сборку, чтобы прочитать описание.",
                Font.PLAIN, 14, Kit.MUTED));
        head.add(t, BorderLayout.CENTER);
        add(head, BorderLayout.NORTH);

        BrowsePanel panel = new BrowsePanel(content, (item, k, p) -> host.download(item, p),
                (item, k, p) -> false, ContentSource.Kind.MODPACK);
        add(panel, BorderLayout.CENTER);
        setBorder(new EmptyBorder(0, 0, 0, 0));
    }
}
