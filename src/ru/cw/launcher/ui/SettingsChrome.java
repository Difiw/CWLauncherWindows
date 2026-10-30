package ru.cw.launcher.ui;

import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/** Карточки, вкладки и переключатели экрана настроек. */
public final class SettingsChrome {

    public static final Color CARD_FILL = new Color(16, 24, 39, 232);
    public static final Color CARD_LINE = new Color(0x263750);
    public static final Color TAB_ON = new Color(0x168CC2);
    public static final Color TAB_OFF = new Color(12, 20, 34, 230);
    public static final Color FIELD = new Color(0x0C1422);

    private SettingsChrome() {
    }

    public static JPanel split(JComponent left, JComponent right) {
        JPanel p = new JPanel(new GridLayout(1, 2, 14, 0));
        p.setOpaque(false);
        p.add(left);
        p.add(right);
        return p;
    }

    public static JPanel column(JComponent... cards) {
        JPanel p = new JPanel(new GridLayout(cards.length, 1, 0, UiLook.gap()));
        p.setOpaque(false);
        for (JComponent c : cards) p.add(c);
        return p;
    }

    public static JPanel grid(int rows, int cols, JComponent... cards) {
        JPanel p = new JPanel(new GridLayout(rows, cols, 14, 12));
        p.setOpaque(false);
        for (JComponent c : cards) p.add(c);
        return p;
    }

    public static JPanel card(Icons.Kind icon, String title, String text, JComponent corner, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 8)) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(CARD_FILL);
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 16, 16));
                g2.setColor(CARD_LINE);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 16, 16));
                g2.dispose();
            }
        };
        p.setOpaque(false);
        int pad = UiLook.pad();
        p.setBorder(BorderFactory.createEmptyBorder(pad, pad, pad, pad));
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.add(badge(icon), BorderLayout.WEST);
        JPanel titles = new JPanel();
        titles.setLayout(new BoxLayout(titles, BoxLayout.Y_AXIS));
        titles.setOpaque(false);
        JLabel name = new JLabel(title);
        name.setFont(Theme.H3);
        name.setForeground(Color.WHITE);
        name.setAlignmentX(Component.LEFT_ALIGNMENT);
        titles.add(name);
        if (text != null && !text.isBlank()) {
            JLabel desc = new JLabel("<html><div style='width:250px'>" + text + "</div></html>");
            desc.setFont(Theme.SMALL);
            desc.setForeground(Theme.MUTED);
            desc.setAlignmentX(Component.LEFT_ALIGNMENT);
            titles.add(Box.createVerticalStrut(3));
            titles.add(desc);
        }
        head.add(titles, BorderLayout.CENTER);
        if (corner != null) {
            JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            east.setOpaque(false);
            east.add(corner);
            head.add(east, BorderLayout.EAST);
        }
        p.add(head, BorderLayout.NORTH);
        if (body != null) p.add(body, BorderLayout.CENTER);
        return p;
    }

    public static JPanel stack(JComponent... rows) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) p.add(Box.createVerticalStrut(8));
            rows[i].setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(rows[i]);
        }
        return p;
    }

    public static JPanel row(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        for (JComponent c : parts) p.add(c);
        return p;
    }

    public static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.SMALL);
        l.setForeground(Theme.MUTED);
        return l;
    }

    public static Switch toggle(boolean on) {
        return new Switch(on);
    }

    public static JComboBox<String> combo(String selected, String... items) {
        JComboBox<String> box = new JComboBox<>(items);
        if (selected != null) box.setSelectedItem(selected);
        Theme.style(box);
        box.setPreferredSize(new Dimension(220, 32));
        box.setMaximumSize(new Dimension(280, 32));
        return box;
    }

    public static JSlider slider(int min, int max, int value) {
        int hi = Math.max(min, max);
        int v = Math.max(min, Math.min(hi, value));
        JSlider s = new JSlider(min, hi, v);
        s.setOpaque(false);
        s.setFocusable(false);
        s.setUI(new BlueSlider(s));
        s.setPreferredSize(new Dimension(220, 28));
        return s;
    }

    public static JButton pill(String text, boolean primary) {
        JButton b = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (primary) {
                    g2.setColor(getModel().isRollover() ? Theme.INFO : TAB_ON);
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                } else {
                    g2.setColor(new Color(10, 22, 48, 180));
                    g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
                    g2.setColor(CARD_LINE);
                    g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 12, 12));
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        b.setFont(Theme.BODY);
        b.setForeground(Color.WHITE);
        b.setContentAreaFilled(false);
        b.setBorderPainted(false);
        b.setFocusPainted(false);
        b.setOpaque(false);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        return b;
    }

    public static final class Tab extends JButton {
        private boolean active;

        public Tab(String text, Icons.Kind icon) {
            super(text);
            setIcon(Icons.of(icon, 15).getIconObject());
            setFont(Theme.BODY);
            setForeground(Color.WHITE);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 16));
        }

        public void setActive(boolean active) {
            this.active = active;
            setForeground(Color.WHITE);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            float hot = Motion.hot(this);
            Motion.lift(g2, hot * 0.6f);
            RoundRectangle2D.Float face = new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
            if (active) {
                g2.setColor(Motion.mix(TAB_ON, TAB_ON.brighter(), hot * 0.45f));
                g2.fill(face);
            } else {
                g2.setColor(Motion.mix(TAB_OFF, new Color(28, 70, 140, 230), hot));
                g2.fill(face);
                g2.setColor(new Color(0x23487A));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, 12, 12));
            }
            Motion.sweep(g2, face, hot);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    public static final class Switch extends JComponent {
        private boolean on;
        private float thumb;
        private Timer glide;
        private final java.util.List<Runnable> listeners = new java.util.ArrayList<>();

        public Switch(boolean on) {
            this.on = on;
            this.thumb = on ? 1f : 0f;
            setPreferredSize(new Dimension(46, 26));
            setMinimumSize(new Dimension(46, 26));
            setMaximumSize(new Dimension(46, 26));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    setOn(!Switch.this.on);
                }
            });
        }

        public boolean isOn() {
            return on;
        }

        public void setOn(boolean on) {
            if (this.on == on) return;
            this.on = on;
            animate();
            for (Runnable r : listeners) r.run();
        }

        private void animate() {
            if (glide != null) glide.stop();
            if (!UiLook.anim()) {
                thumb = on ? 1f : 0f;
                repaint();
                return;
            }
            glide = new Timer(16, e -> {
                float target = on ? 1f : 0f;
                thumb += (target - thumb) * 0.34f;
                if (Math.abs(target - thumb) < 0.02f) {
                    thumb = target;
                    ((Timer) e.getSource()).stop();
                }
                repaint();
            });
            glide.start();
        }

        public void onChange(Runnable r) {
            listeners.add(r);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Motion.mix(new Color(0x1A2C4E), TAB_ON, thumb));
            g2.fill(new RoundRectangle2D.Float(0, 2, 44, 22, 22, 22));
            g2.setColor(Color.WHITE);
            int x = 3 + Math.round(19 * thumb);
            g2.fillOval(x, 4, 18, 18);
            g2.dispose();
        }
    }

    private static JComponent badge(Icons.Kind kind) {
        JComponent c = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(0x12305C));
                g2.fillOval(0, 0, 34, 34);
                g2.setColor(new Color(0x8EB4FF));
                Icons.of(kind, 16).paint(new Color(0x8EB4FF), g2, 9, 9, 16, 16);
                g2.dispose();
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(34, 34);
            }

            @Override
            public Dimension getMinimumSize() {
                return getPreferredSize();
            }

            @Override
            public Dimension getMaximumSize() {
                return getPreferredSize();
            }
        };
        c.setOpaque(false);
        return c;
    }

    private static final class BlueSlider extends BasicSliderUI {
        BlueSlider(JSlider slider) {
            super(slider);
        }

        @Override
        public void paintTrack(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int y = trackRect.y + trackRect.height / 2 - 3;
            g2.setColor(new Color(0x16325C));
            g2.fillRoundRect(trackRect.x, y, trackRect.width, 6, 6, 6);
            int fill = thumbRect.x + thumbRect.width / 2 - trackRect.x;
            g2.setColor(TAB_ON);
            g2.fillRoundRect(trackRect.x, y, Math.max(6, fill), 6, 6, 6);
            g2.dispose();
        }

        @Override
        public void paintThumb(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(thumbRect.x, thumbRect.y + 2, 16, 16);
            g2.setColor(TAB_ON);
            g2.fillOval(thumbRect.x + 4, thumbRect.y + 6, 8, 8);
            g2.dispose();
        }

        @Override
        protected Dimension getThumbSize() {
            return new Dimension(16, 20);
        }

        @Override
        public void paintFocus(Graphics g) {
        }
    }
}
