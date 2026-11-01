package ru.cw.launcher.ui;



import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Единая кнопка современной beta-темы.
 * Реализует hover / pressed / disabled состояния и плавную анимацию яркости.
 */
public class CutButton extends JButton {

    public enum Accent {
        PRIMARY,      // синяя акцентная (запустить)
        NEUTRAL,      // графитовая с синей линией
        SUCCESS,
        WARNING,
        DANGER,
        GHOST
    }

    /** Размер среза угла в пикселях (масштабируется через setScale). */
    private int cut = 10;
    private final Accent accent;
    private final boolean glow;
    private float anim = 0f;
    private Timer timer;
    private double scale = 1.0;

    public CutButton(String text, Accent accent) {
        this(text, accent, true);
    }

    public CutButton(String text, Accent accent, boolean glow) {
        super(text);
        this.accent = accent;
        this.glow = glow;
        setFocusPainted(false);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setOpaque(false);
        setUI(new javax.swing.plaf.basic.BasicButtonUI() {
            @Override
            public void paint(Graphics g, JComponent c) {
            }
        });
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setFont(accent == Accent.PRIMARY ? Theme.H2.deriveFont(Font.BOLD) : Theme.BODY.deriveFont(Font.BOLD));
        setForeground(enabledTextColor());
        addChangeListener(e -> updateAnim());
        addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                startAnim(true);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                startAnim(false);
            }
        });
    }

    /** Размерный масштаб для адаптивной вёрстки (меняет шрифт и срез). */
    public void setScale(double s) {
        this.scale = s;
        this.cut = (int) Math.round(10 * s);
        Font base = accent == Accent.PRIMARY ? Theme.H2 : Theme.BODY;
        setFont(base.deriveFont(Font.BOLD, (float) Math.max(9, base.getSize2D() * s)));
        revalidate();
        repaint();
    }

    private void updateAnim() {
        repaint();
    }

    private void startAnim(boolean hover) {
        if (!UiLook.anim()) {
            if (timer != null && timer.isRunning()) timer.stop();
            anim = hover ? 1f : 0f;
            repaint();
            return;
        }
        if (timer != null && timer.isRunning()) timer.stop();
        timer = new Timer(16, null);
        timer.addActionListener(e -> {
            float target = hover ? 1f : 0f;
            anim += (target - anim) * 0.25f;
            if (Math.abs(target - anim) < 0.01f) {
                anim = target;
                ((Timer) e.getSource()).stop();
            }
            repaint();
        });
        timer.start();
    }

    private Color baseFill() {
        if (!isEnabled()) {
            return new Color(22, 27, 34, 200);
        }
        return switch (accent) {
            case PRIMARY -> blend(new Color(0x168CC2), new Color(0x24A9E2), anim * .65f);
            case SUCCESS -> blend(new Color(0x174423), new Color(0x226b36), anim);
            case WARNING -> blend(new Color(0x4a3a12), new Color(0x7a5c17), anim);
            case DANGER -> blend(new Color(0x4d1d1d), new Color(0x7d2b2b), anim);
            case GHOST -> blend(new Color(12, 20, 34, 220), new Color(22, 37, 56, 230), anim);
            default -> blend(new Color(20, 31, 48, 235), new Color(30, 48, 70, 240), anim);
        };
    }

    private Color lineColor() {
        if (!isEnabled()) return Theme.DISABLED;
        return switch (accent) {
            case PRIMARY -> Theme.INFO;
            case SUCCESS -> Theme.GREEN;
            case WARNING -> Theme.AMBER;
            case DANGER -> Theme.RED;
            case GHOST -> Theme.LINE;
            default -> Theme.LINE;
        };
    }

    private Color enabledTextColor() {
        return isEnabled() ? Theme.TEXT : Theme.DISABLED;
    }

    @Override
    public void setEnabled(boolean b) {
        super.setEnabled(b);
        setForeground(enabledTextColor());
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    /** Спокойная скруглённая форма без экспериментальных срезов. */
    public Shape shape(int w, int h) {
        int radius = Math.max(12, Math.min(18, h / 2));
        return new RoundRectangle2D.Float(.5f, .5f, Math.max(1, w - 1), Math.max(1, h - 1), radius, radius);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        Motion.lift(g2, anim);
        if (UiLook.anim() && getModel().isPressed()) {
            g2.translate(w / 2.0, h / 2.0);
            g2.scale(0.96, 0.96);
            g2.translate(-w / 2.0, -h / 2.0);
        }
        Shape s = shape(w - 1, h - 1);

        // Свечение удалено: оно давало грязный белый ореол в тёмной теме Windows.
        g2.setColor(baseFill());
        g2.fill(s);
        Motion.sweep(g2, s, anim);
        g2.setColor(lineColor());
        g2.setStroke(new BasicStroke(isEnabled() && accent == Accent.PRIMARY ? 1.6f : 1f));
        g2.draw(s);
        g2.setColor(getForeground());
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String text = getText() == null ? "" : getText();
        Icon ic = getIcon();
        int textW = fm.stringWidth(text);
        int iconW = ic == null ? 0 : ic.getIconWidth() + (text.isEmpty() ? 0 : 8);
        int contentW = iconW + textW;
        int tx = Math.max(8, (w - contentW) / 2);
        if (ic != null) {
            ic.paintIcon(this, g2, tx, (h - ic.getIconHeight()) / 2);
            tx += ic.getIconWidth() + 8;
        }
        g2.drawString(text, tx, (h - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }

    /** Минимальный размер с учётом текста. Высота фиксирована, чтобы кнопка не наезжала на текст. */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width + 26, 90), Math.max(d.height + 12, 36));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    public float getAlignmentX() {
        return 0f;
    }

    /** Иконка слева от текста (отрисовывается в том же стиле). */
    public void setIcon(Icon icon) {
        super.setIcon(icon);
        setHorizontalTextPosition(SwingConstants.RIGHT);
        setHorizontalAlignment(SwingConstants.CENTER);
    }
}
