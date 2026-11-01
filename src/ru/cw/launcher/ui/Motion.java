package ru.cw.launcher.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Короткие анимации. Покадровой перерисовки всего окна нет.
 * Выключаются переключателем «Анимации». По умолчанию выключены.
 */
public final class Motion {

    private static final String HOT = "cw.hot";

    private Motion() {
    }

    public static boolean on() {
        return UiLook.anim();
    }

    public static Color mix(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t),
                (int) (a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t));
    }

    /**
     * Короткое проявление окна. Окно не двигается и не перерисовывает содержимое каждый кадр:
     * прозрачность меняет композитор Windows, таймер гаснет сам.
     */
    public static void present(Window w) {
        if (w == null) return;
        if (!on() || w.isVisible()) {
            w.setVisible(true);
            return;
        }
        boolean fade = true;
        try {
            w.setOpacity(0.2f);
        } catch (Exception e) {
            fade = false;
        }
        if (!fade) {
            w.setVisible(true);
            return;
        }
        Timer timer = new Timer(16, null);
        long t0 = System.nanoTime();
        timer.addActionListener(e -> {
            if (!w.isDisplayable()) {
                timer.stop();
                return;
            }
            float p = Math.min(1f, (System.nanoTime() - t0) / 220_000_000f);
            float eased = 0.2f + 0.8f * (1f - (1f - p) * (1f - p));
            try {
                w.setOpacity(Math.min(1f, eased));
            } catch (Exception ex) {
                timer.stop();
                try {
                    w.setOpacity(1f);
                } catch (Exception ignored) {
                }
                return;
            }
            if (p >= 1f) {
                timer.stop();
                try {
                    w.setOpacity(1f);
                } catch (Exception ignored) {
                }
            }
        });
        timer.start();
        w.setVisible(true);
    }

    /** Появление содержимого не крутит покадровую перерисовку всего окна. */
    public static void rise(Graphics2D g2, JComponent c) {
    }

    /** 0 — покой, 1 — наведение или нажатие. Доезжает само, без скачка. */
    public static float hot(JComponent c) {
        float target = targetOf(c);
        if (!on()) return target;
        Object stored = c.getClientProperty(HOT);
        float[] box;
        if (stored instanceof float[] found) {
            box = found;
        } else {
            box = new float[]{target};
            c.putClientProperty(HOT, box);
            float[] ref = box;
            Timer timer = new Timer(16, null);
            timer.addActionListener(e -> {
                float next = targetOf(c);
                ref[0] += (next - ref[0]) * 0.32f;
                if (Math.abs(next - ref[0]) < 0.02f) {
                    ref[0] = next;
                    ((Timer) e.getSource()).stop();
                }
                c.repaint();
            });
            MouseAdapter arm = new MouseAdapter() {
                private void kick() {
                    if (!timer.isRunning()) timer.start();
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    kick();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    kick();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    kick();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    kick();
                }
            };
            c.addMouseListener(arm);
        }
        return box[0];
    }

    /** Кнопка чуть поднимается. Таймер уже есть у {@link #hot}, отдельной перерисовки окна нет. */
    public static void lift(Graphics2D g2, float hot) {
        // Геометрия компонента не должна прыгать при наведении.
    }

    /** Блик проходит по кнопке один раз, пока курсор наезжает, и останавливается. */
    public static void sweep(Graphics2D g2, Shape shape, float hot) {
        // Старый белый блик конфликтовал с тёмной системной темой Windows.
    }

    /** Постоянная перерисовка всего окна выключена: из-за неё интерфейс держал нулевой кадр. */
    public static void pulse(JComponent c) {
    }

    public static void sheen(Graphics2D g2, int w, int h) {
    }

    private static float targetOf(JComponent c) {
        if (!(c instanceof AbstractButton b) || !b.isEnabled()) return 0f;
        ButtonModel model = b.getModel();
        if (model.isPressed() || model.isRollover() || model.isArmed()) return 1f;
        return 0f;
    }

}
