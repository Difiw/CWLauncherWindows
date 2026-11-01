package ru.cw.launcher.shell.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSliderUI;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.List;
import java.util.function.Consumer;

/** Общие детали рисования нового интерфейса: красная тема, скруглённые панели, кнопки, списки. */
public final class Kit {

    private Kit() {
    }

    public static Color BG = new Color(0x0B0B0F);
    public static Color SIDEBAR = new Color(0x101014);
    public static Color CARD = new Color(0x15161B);
    public static Color FIELD = new Color(0x1A1B21);
    public static Color LINE = new Color(0x2B2D35);
    public static Color TEXT = new Color(0xF3F3F6);
    public static Color MUTED = new Color(0x8D909C);
    public static Color ACCENT = new Color(0xD5323E);
    public static Color ACCENT_HI = new Color(0xEC4854);
    public static Color ACCENT_DIM = new Color(0x3A1318);
    public static Color GREEN = new Color(0x4CD97B);

    public static final String FAMILY = family();

    private static String family() {
        for (String n : new String[]{"Segoe UI", "Arial", "Tahoma", "Dialog"}) {
            if (new Font(n, Font.PLAIN, 14).canDisplayUpTo("ОбновлениеЁёЖж") == -1) return n;
        }
        return "Dialog";
    }

    public static Font font(int style, float size) {
        return new Font(FAMILY, style, 1).deriveFont(style, size);
    }

    public static void quality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    public static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font(style, size));
        l.setForeground(color);
        return l;
    }

    public static JPanel clear(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    /** Панель со скруглённой заливкой и тонкой рамкой. */
    public static class Round extends JPanel {
        private Color fill;
        private Color line;
        private final int radius;

        public Round(LayoutManager lm, Color fill, Color line, int radius) {
            super(lm);
            this.fill = fill;
            this.line = line;
            this.radius = radius;
            setOpaque(false);
        }

        public void colors(Color fill, Color line) {
            this.fill = fill;
            this.line = line;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            RoundRectangle2D r = new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 1, getHeight() - 1, radius, radius);
            if (fill != null) {
                g.setColor(fill);
                g.fill(r);
            }
            if (line != null) {
                g.setColor(line);
                g.draw(r);
            }
            g.dispose();
            super.paintComponent(g0);
        }
    }

    /** Кнопка: основная (красная) или вторая (тёмная); поддерживает значок слева. */
    public static class Btn extends JComponent {
        private final String text;
        private final boolean primary;
        private final String icon;
        private boolean hover;
        private boolean press;
        private boolean enabled = true;
        private Runnable action;

        public Btn(String text, boolean primary, String icon, Runnable action) {
            this.text = text;
            this.primary = primary;
            this.icon = icon;
            this.action = action;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(font(Font.BOLD, 15));
            setFocusable(true);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    press = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    press = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    boolean fire = press && hover && enabled;
                    press = false;
                    repaint();
                    if (fire && Btn.this.action != null) Btn.this.action.run();
                }
            });
        }

        public void setAction(Runnable a) {
            this.action = a;
        }

        @Override
        public void setEnabled(boolean b) {
            enabled = b;
            setCursor(Cursor.getPredefinedCursor(b ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            repaint();
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(fm.stringWidth(text) + (icon == null ? 40 : 76), 46);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            Color bg = primary ? (press ? ACCENT.darker() : hover ? ACCENT_HI : ACCENT)
                    : (hover ? new Color(0x23252D) : new Color(0x1B1C22));
            if (!enabled) bg = new Color(0x2A2B31);
            g.setColor(bg);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            if (!primary) {
                g.setColor(LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));
            }
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int tw = fm.stringWidth(text);
            int iw = icon == null ? 0 : 30;
            int x = (w - tw - iw) / 2;
            g.setColor(enabled ? Color.WHITE : MUTED);
            if (icon != null) {
                Icons.draw(g, icon, x, (h - 22) / 2, 22, g.getColor());
                x += iw;
            }
            g.drawString(text, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Выпадающий список в стиле лаунчера: рамка, шеврон, меню с галочкой у выбранного. */
    public static class Dropdown extends JComponent {
        private List<String> items = List.of();
        private String selected = "";
        private final String icon;
        private boolean hover;
        private boolean open;
        private Consumer<String> onChange = v -> {
        };

        public Dropdown(String icon) {
            this.icon = icon;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(font(Font.PLAIN, 14));
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
                public void mousePressed(MouseEvent e) {
                    if (!open) popup();
                }
            });
        }

        public void onChange(Consumer<String> c) {
            onChange = c;
        }

        public String selected() {
            return selected;
        }

        /** Подставляет список; выбранное значение сохраняется, если оно осталось в списке. */
        public void setItems(List<String> list, String prefer) {
            items = List.copyOf(list);
            if (prefer != null && items.contains(prefer)) selected = prefer;
            else if (!items.contains(selected)) selected = items.isEmpty() ? "" : items.get(0);
            repaint();
        }

        /** Выбор программно, с тем же откликом, что и щелчок по пункту меню. */
        public void choose(String value) {
            if (!items.contains(value) || value.equals(selected)) return;
            selected = value;
            repaint();
            onChange.accept(value);
        }

        public void select(String value) {
            selected = value;
            repaint();
        }

        private void popup() {
            if (items.isEmpty()) return;
            JPopupMenu menu = new JPopupMenu();
            menu.setBorder(BorderFactory.createLineBorder(LINE));
            menu.setBackground(new Color(0x181A20));
            JPanel list = new JPanel();
            list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
            list.setBackground(new Color(0x181A20));
            for (String it : items) list.add(menuRow(it, menu));
            JScrollPane sp = new JScrollPane(list, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(null);
            sp.getViewport().setBackground(new Color(0x181A20));
            sp.getVerticalScrollBar().setUnitIncrement(18);
            style(sp.getVerticalScrollBar());
            int rows = Math.min(items.size(), 8);
            sp.setPreferredSize(new Dimension(Math.max(getWidth(), 190), rows * 34 + 4));
            menu.add(sp);
            menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                    open = true;
                    repaint();
                }

                @Override
                public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                    open = false;
                    repaint();
                }

                @Override
                public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                    open = false;
                    repaint();
                }
            });
            menu.show(this, 0, getHeight() + 4);
        }

        private JComponent menuRow(String value, JPopupMenu menu) {
            boolean sel = value.equals(selected);
            JComponent row = new JComponent() {
                boolean over;

                {
                    setPreferredSize(new Dimension(10, 34));
                    setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
                    setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    addMouseListener(new MouseAdapter() {
                        @Override
                        public void mouseEntered(MouseEvent e) {
                            over = true;
                            repaint();
                        }

                        @Override
                        public void mouseExited(MouseEvent e) {
                            over = false;
                            repaint();
                        }

                        @Override
                        public void mouseReleased(MouseEvent e) {
                            menu.setVisible(false);
                            if (!value.equals(selected)) {
                                selected = value;
                                Dropdown.this.repaint();
                                onChange.accept(value);
                            }
                        }
                    });
                }

                @Override
                protected void paintComponent(Graphics g0) {
                    Graphics2D g = (Graphics2D) g0.create();
                    quality(g);
                    if (over) {
                        g.setColor(new Color(0x262830));
                        g.fillRect(0, 0, getWidth(), getHeight());
                    }
                    g.setFont(Dropdown.this.getFont());
                    g.setColor(TEXT);
                    FontMetrics fm = g.getFontMetrics();
                    g.drawString(value, 14, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                    if (sel) Icons.draw(g, "check", getWidth() - 30, 8, 18, ACCENT_HI);
                    g.dispose();
                }
            };
            return row;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(180, 42);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            g.setColor(FIELD);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, 12, 12));
            g.setColor(open ? ACCENT : hover ? new Color(0x4A4D58) : LINE);
            g.setStroke(new BasicStroke(open ? 1.6f : 1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 12, 12));
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int x = 14;
            if (icon != null) {
                Icons.draw(g, icon, x, (h - 20) / 2, 20, TEXT);
                x += 30;
            }
            g.setColor(TEXT);
            String s = selected.isEmpty() ? "—" : selected;
            g.drawString(s, x, (h - fm.getHeight()) / 2 + fm.getAscent());
            Icons.draw(g, open ? "chevron-up" : "chevron-down", w - 32, (h - 18) / 2, 18, TEXT);
            g.dispose();
        }
    }

    /** Флажок с красной заливкой. */
    public static class Check extends JComponent {
        private boolean on;
        private final String text;
        private Consumer<Boolean> onToggle = v -> {
        };

        public Check(String text, boolean on) {
            this.text = text;
            this.on = on;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(font(Font.PLAIN, 14));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    Check.this.on = !Check.this.on;
                    repaint();
                    onToggle.accept(Check.this.on);
                }
            });
        }

        public boolean isOn() {
            return on;
        }

        public void onToggle(Consumer<Boolean> c) {
            onToggle = c;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(text) + 36, 30);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, 30);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int y = (getHeight() - 20) / 2;
            if (on) {
                g.setColor(ACCENT);
                g.fill(new RoundRectangle2D.Float(0, y, 20, 20, 6, 6));
                Icons.draw(g, "check", 1, y + 1, 18, Color.WHITE);
            } else {
                g.setColor(FIELD);
                g.fill(new RoundRectangle2D.Float(0, y, 20, 20, 6, 6));
                g.setColor(new Color(0x4A4D58));
                g.draw(new RoundRectangle2D.Float(0.5f, y + 0.5f, 19, 19, 6, 6));
            }
            g.setFont(getFont());
            g.setColor(TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, 32, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Круглая «пилюля» для фильтров. */
    public static class Chip extends JComponent {
        private final String text;
        private boolean on;
        private boolean hover;
        private final Runnable action;

        public Chip(String text, boolean on, Runnable action) {
            this.text = text;
            this.on = on;
            this.action = action;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(font(Font.PLAIN, 13));
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
                    if (Chip.this.action != null) Chip.this.action.run();
                }
            });
        }

        public void setOn(boolean v) {
            on = v;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(getFontMetrics(getFont()).stringWidth(text) + 34, 34);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            quality(g);
            int w = getWidth();
            int h = getHeight();
            g.setColor(on ? ACCENT : hover ? new Color(0x23252D) : new Color(0x1B1C22));
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, h, h));
            if (!on) {
                g.setColor(LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, h, h));
            }
            g.setFont(getFont());
            g.setColor(on ? Color.WHITE : TEXT);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(text, (w - fm.stringWidth(text)) / 2, (h - fm.getHeight()) / 2 + fm.getAscent());
            g.dispose();
        }
    }

    /** Текстовое поле в скруглённой рамке; рамка краснеет при фокусе. */
    public static JComponent field(JTextComponent field0, String leadingIcon) {
        field0.setOpaque(false);
        field0.setForeground(TEXT);
        field0.setCaretColor(TEXT);
        field0.setSelectionColor(ACCENT);
        field0.setFont(font(Font.PLAIN, 14));
        field0.setBorder(new EmptyBorder(0, 0, 0, 0));
        Round box = new Round(new BorderLayout(10, 0), FIELD, LINE, 12);
        box.setBorder(new EmptyBorder(0, 14, 0, 14));
        if (leadingIcon != null) {
            JComponent ic = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    quality(g2);
                    Icons.draw(g2, leadingIcon, 0, (getHeight() - 18) / 2, 18, MUTED);
                    g2.dispose();
                }

                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(18, 18);
                }
            };
            box.add(ic, BorderLayout.WEST);
        }
        box.add(field0, BorderLayout.CENTER);
        field0.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusGained(java.awt.event.FocusEvent e) {
                box.colors(FIELD, ACCENT);
            }

            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                box.colors(FIELD, LINE);
            }
        });
        return box;
    }

    public static void style(JScrollBar bar) {
        bar.setOpaque(false);
        bar.setPreferredSize(new Dimension(8, 8));
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(0x3A3D47);
                trackColor = new Color(0, 0, 0, 0);
            }

            @Override
            protected JButton createDecreaseButton(int o) {
                return zero();
            }

            @Override
            protected JButton createIncreaseButton(int o) {
                return zero();
            }

            private JButton zero() {
                JButton b = new JButton();
                b.setPreferredSize(new Dimension(0, 0));
                return b;
            }

            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            }

            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
                if (r.isEmpty()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                quality(g2);
                g2.setColor(thumbColor);
                g2.fill(new RoundRectangle2D.Float(r.x + 1, r.y + 1, r.width - 2, r.height - 2, 6, 6));
                g2.dispose();
            }
        });
    }

    public static void style(JScrollPane sp) {
        sp.setBorder(null);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        style(sp.getVerticalScrollBar());
        sp.getVerticalScrollBar().setUnitIncrement(24);
    }

    /** Красный ползунок. */
    public static JSlider slider(int min, int max, int value) {
        JSlider s = new JSlider(min, max, value);
        s.setOpaque(false);
        s.setFocusable(false);
        s.setUI(new BasicSliderUI(s) {
            @Override
            public void paintTrack(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                quality(g2);
                Rectangle t = trackRect;
                int y = t.y + t.height / 2 - 3;
                g2.setColor(new Color(0x2A2C34));
                g2.fill(new RoundRectangle2D.Float(t.x, y, t.width, 6, 6, 6));
                int fillW = thumbRect.x + thumbRect.width / 2 - t.x;
                g2.setColor(ACCENT);
                g2.fill(new RoundRectangle2D.Float(t.x, y, Math.max(0, fillW), 6, 6, 6));
                g2.dispose();
            }

            @Override
            public void paintThumb(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                quality(g2);
                int d = 20;
                int cx = thumbRect.x + thumbRect.width / 2 - d / 2;
                int cy = thumbRect.y + thumbRect.height / 2 - d / 2;
                g2.setColor(new Color(0xF4F4F7));
                g2.fillOval(cx, cy, d, d);
                g2.setColor(ACCENT);
                g2.setStroke(new BasicStroke(2f));
                g2.drawOval(cx + 1, cy + 1, d - 2, d - 2);
                g2.dispose();
            }

            @Override
            protected Dimension getThumbSize() {
                return new Dimension(22, 22);
            }

            @Override
            public void paintFocus(Graphics g) {
            }
        });
        return s;
    }

    public static Path2D poly(double... xy) {
        Path2D p = new Path2D.Double();
        p.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) p.lineTo(xy[i], xy[i + 1]);
        p.closePath();
        return p;
    }

    public static Component hgap(int w) {
        return Box.createHorizontalStrut(w);
    }

    public static Component vgap(int h) {
        return Box.createVerticalStrut(h);
    }
}
