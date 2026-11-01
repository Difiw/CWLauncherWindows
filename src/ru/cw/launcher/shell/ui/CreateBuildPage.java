package ru.cw.launcher.shell.ui;

import ru.cw.launcher.shell.BuildSpec;
import ru.cw.launcher.shell.BuildSpec.Kind;
import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.CoreCatalog;
import ru.cw.launcher.shell.ModInstaller;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Экран «Создание сборки»: форма слева, каталог модов справа. */
public final class CreateBuildPage extends JPanel {

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cw-core-catalog");
        t.setDaemon(true);
        return t;
    });

    /** Что делает страница, когда пользователь нажал «Создать» или «Отмена». */
    public interface Host {
        void created(BuildSpec spec, List<ModInstaller.Queued> queue);

        void cancelled();

        String tryCreate(BuildSpec spec) throws java.io.IOException;
    }

    private final CoreCatalog catalog;
    private final Host host;
    private final BuildSpec spec = new BuildSpec();
    private final List<ModInstaller.Queued> queue = new ArrayList<>();
    private final AtomicInteger coreGen = new AtomicInteger();

    private final JTextField name = new JTextField(spec.name);
    private final JTextArea desc = new JTextArea();
    private final JLabel counter = Kit.label("0 / 500", Font.PLAIN, 12, Kit.MUTED);
    private final Kit.Dropdown mc = new Kit.Dropdown("cube");
    private final Kit.Dropdown loader = new Kit.Dropdown("hammer");
    private final Kit.Dropdown core = new Kit.Dropdown(null);
    private final JLabel error = Kit.label(" ", Font.PLAIN, 13, new Color(0xFF6B76));
    private final JLabel queueInfo = Kit.label(" ", Font.PLAIN, 12, Kit.MUTED);
    private final List<KindCard> kinds = new ArrayList<>();
    private final JSlider ram;
    private final JLabel ramText = Kit.label("4 ГБ", Font.BOLD, 14, Kit.TEXT);
    private BufferedImage icon;

    public CreateBuildPage(CoreCatalog catalog, Host host, ContentSource content) {
        super(new BorderLayout(18, 0));
        this.catalog = catalog;
        this.host = host;
        setOpaque(false);
        setBorder(new EmptyBorder(0, 0, 0, 0));
        int totalGb = totalRamGb();
        ram = Kit.slider(1, Math.max(4, totalGb), 4);

        JPanel left = Kit.clear(new BorderLayout(0, 10));
        left.add(buildHeader(), BorderLayout.NORTH);
        left.add(buildForm(totalGb), BorderLayout.CENTER);
        left.add(buildFooter(), BorderLayout.SOUTH);
        left.setMinimumSize(new Dimension(560, 10));

        BrowsePanel panel = new BrowsePanel(content,
                (item, k, p) -> toggleQueue(new ModInstaller.Queued(p, k, item.id(), item.title())),
                (item, k, p) -> queue.stream().anyMatch(q -> q.id().equals(item.id()) && q.provider() == p));
        panel.setPreferredSize(new Dimension(400, 10));
        add(left, BorderLayout.CENTER);
        add(panel, BorderLayout.EAST);

        mc.setItems(CoreCatalog.FALLBACK_MC, spec.mcVersion);
        loader.setItems(List.of(CoreCatalog.LOADERS), spec.loader);
        core.setItems(List.of(), null);
        mc.onChange(v -> {
            spec.mcVersion = v;
            refreshCore();
        });
        loader.onChange(v -> {
            spec.loader = v;
            refreshCore();
        });
        core.onChange(v -> spec.loaderVersion = v);
        loadMinecraftVersions();
        refreshCore();
    }

    private void toggleQueue(ModInstaller.Queued q) {
        boolean removed = queue.removeIf(x -> x.id().equals(q.id()) && x.provider() == q.provider());
        if (!removed) queue.add(q);
        queueInfo.setText(queue.isEmpty() ? " " : "Будет установлено после создания: " + queue.size());
        repaint();
    }

    // ---- загрузка версий ----

    private void loadMinecraftVersions() {
        IO.submit(() -> {
            List<String> v = catalog.minecraftVersions();
            SwingUtilities.invokeLater(() -> {
                mc.setItems(v, spec.mcVersion);
                spec.mcVersion = mc.selected();
                refreshCore();
            });
        });
    }

    private void refreshCore() {
        int gen = coreGen.incrementAndGet();
        String l = spec.loader;
        String v = spec.mcVersion;
        core.setItems(List.of(), null);
        core.select("загрузка…");
        IO.submit(() -> {
            List<String> versions = catalog.coreVersions(l, v);
            SwingUtilities.invokeLater(() -> {
                if (gen != coreGen.get()) return;
                core.setItems(versions, null);
                if (versions.isEmpty()) core.select("нет версий");
                spec.loaderVersion = versions.isEmpty() ? "" : core.selected();
            });
        });
    }

    // ---- разметка ----

    private JComponent buildHeader() {
        JPanel h = Kit.clear(new BorderLayout(14, 0));
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
                        host.cancelled();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                Kit.quality(g);
                g.setColor(hover ? new Color(0x262830) : new Color(0x1B1C22));
                g.fill(new RoundRectangle2D.Float(0, 0, 44, 44, 12, 12));
                g.setColor(Kit.LINE);
                g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, 43, 43, 12, 12));
                Icons.draw(g, "arrow-left", 10, 10, 24, Kit.TEXT);
                g.dispose();
            }
        };
        h.add(back, BorderLayout.WEST);
        JPanel t = Kit.clear(new GridLayout(2, 1, 0, 2));
        t.add(Kit.label("Создание сборки", Font.BOLD, 28, Kit.TEXT));
        t.add(Kit.label("Создайте собственную сборку Minecraft с модами, картами и настройками.", Font.PLAIN, 14, Kit.MUTED));
        h.add(t, BorderLayout.CENTER);
        h.setBorder(new EmptyBorder(0, 0, 0, 0));
        return h;
    }

    private JComponent buildForm(int totalGb) {
        JPanel form = new WidthTracking();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        // 1) иконка + название + описание
        JPanel top = Kit.clear(new BorderLayout(20, 0));
        top.add(new IconBox(), BorderLayout.WEST);
        JPanel texts = Kit.clear(new BorderLayout(0, 6));
        JPanel nameBlock = Kit.clear(new BorderLayout(0, 6));
        nameBlock.add(Kit.label("<html>Название сборки <font color='#EC4854'>*</font></html>", Font.BOLD, 14, Kit.TEXT),
                BorderLayout.NORTH);
        JComponent nf = Kit.field(name, null);
        nf.setPreferredSize(new Dimension(10, 44));
        nameBlock.add(nf, BorderLayout.CENTER);
        texts.add(nameBlock, BorderLayout.NORTH);

        JPanel descBlock = Kit.clear(new BorderLayout(0, 6));
        descBlock.add(Kit.label("Описание", Font.BOLD, 14, Kit.TEXT), BorderLayout.NORTH);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.putClientProperty("JTextField.placeholderText", "Моя идеальная сборка с модами");
        JComponent df = Kit.field(desc, null);
        df.setBorder(new EmptyBorder(10, 0, 10, 0));
        df.setPreferredSize(new Dimension(10, 68));
        descBlock.add(df, BorderLayout.CENTER);
        counter.setHorizontalAlignment(SwingConstants.RIGHT);
        descBlock.add(counter, BorderLayout.SOUTH);
        texts.add(descBlock, BorderLayout.CENTER);
        top.add(texts, BorderLayout.CENTER);
        form.add(top);
        desc.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                if (desc.getText().length() > 500) {
                    SwingUtilities.invokeLater(() -> desc.setText(desc.getText().substring(0, 500)));
                    return;
                }
                counter.setText(desc.getText().length() + " / 500");
            }

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                upd();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                upd();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                upd();
            }
        });
        form.add(Kit.vgap(8));

        // 2) версии
        JPanel versions = Kit.clear(new GridLayout(1, 3, 14, 0));
        versions.add(labeled("Версия Minecraft", mc, "cube"));
        versions.add(labeled("Загрузчик / ядро", loader, "gear"));
        versions.add(labeled("Версия ядра", core, null));
        versions.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        form.add(versions);
        form.add(Kit.vgap(10));

        // 3) тип сборки
        form.add(sectionTitle("Тип сборки", "layers"));
        form.add(Kit.vgap(8));
        JPanel kindRow = Kit.clear(new GridLayout(1, 4, 12, 0));
        for (Kind k : Kind.values()) {
            KindCard c = new KindCard(k);
            kinds.add(c);
            kindRow.add(c);
        }
        kindRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        kindRow.setPreferredSize(new Dimension(10, 56));
        form.add(kindRow);
        form.add(Kit.vgap(10));

        // 4) дополнительно
        form.add(sectionTitle("Дополнительно", "gear"));
        form.add(Kit.vgap(8));
        JPanel extra = Kit.clear(new GridLayout(1, 2, 24, 0));
        JPanel checks = Kit.clear(null);
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        Kit.Check opt = new Kit.Check("Оптимизированные настройки", spec.optimized);
        opt.onToggle(v -> spec.optimized = v);
        Kit.Check auto = new Kit.Check("Автообновление модов", spec.autoUpdateMods);
        auto.onToggle(v -> spec.autoUpdateMods = v);
        Kit.Check more = new Kit.Check("Выделить больше RAM", spec.moreRam);
        more.onToggle(v -> spec.moreRam = v);
        checks.add(opt);
        checks.add(Kit.vgap(2));
        checks.add(auto);
        checks.add(Kit.vgap(2));
        checks.add(more);
        extra.add(checks);

        JPanel mem = Kit.clear(new BorderLayout(0, 2));
        JPanel memTop = Kit.clear(new BorderLayout());
        memTop.add(Kit.label("Память (RAM)", Font.PLAIN, 14, Kit.TEXT), BorderLayout.WEST);
        mem.add(memTop, BorderLayout.NORTH);
        JPanel sliderRow = Kit.clear(new BorderLayout(14, 0));
        sliderRow.add(ram, BorderLayout.CENTER);
        Kit.Round val = new Kit.Round(new GridBagLayout(), Kit.FIELD, Kit.LINE, 12);
        val.setPreferredSize(new Dimension(84, 42));
        val.add(ramText);
        JPanel valWrap = Kit.clear(new GridBagLayout());
        valWrap.add(val);
        sliderRow.add(valWrap, BorderLayout.EAST);
        mem.add(sliderRow, BorderLayout.CENTER);
        mem.add(Kit.label("Рекомендуется: 4–6 ГБ (из " + totalGb + " ГБ)", Font.PLAIN, 12, Kit.MUTED), BorderLayout.SOUTH);
        extra.add(mem);
        extra.setMaximumSize(new Dimension(Integer.MAX_VALUE, 100));
        form.add(extra);
        ram.addChangeListener(e -> {
            spec.ramMb = ram.getValue() * 1024;
            ramText.setText(ram.getValue() + " ГБ");
        });
        form.add(Kit.vgap(8));
        form.add(queueInfo);
        form.add(Box.createVerticalGlue());
        for (Component c : form.getComponents()) {
            if (!(c instanceof JComponent jc) || c instanceof Box.Filler) continue;
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension max = jc.getMaximumSize();
            jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.min(max.height, jc.getPreferredSize().height)));
        }

        JScrollPane sp = new JScrollPane(form, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        Kit.style(sp);
        return sp;
    }

    private JComponent buildFooter() {
        JPanel f = Kit.clear(new BorderLayout(0, 6));
        f.add(error, BorderLayout.NORTH);
        JPanel row = Kit.clear(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 1;
        c.gridx = 0;
        c.weightx = 0.36;
        Kit.Btn cancel = new Kit.Btn("Отмена", false, null, host::cancelled);
        row.add(cancel, c);
        c.gridx = 1;
        c.weightx = 0;
        row.add(Box.createHorizontalStrut(12), c);
        c.gridx = 2;
        c.weightx = 0.64;
        Kit.Btn create = new Kit.Btn("Создать сборку", true, "check", this::submit);
        row.add(create, c);
        row.setPreferredSize(new Dimension(10, 50));
        f.add(row, BorderLayout.CENTER);
        return f;
    }

    private void submit() {
        spec.name = name.getText();
        spec.description = desc.getText();
        spec.ramMb = ram.getValue() * 1024;
        if ("загрузка…".equals(core.selected()) || "нет версий".equals(core.selected())) spec.loaderVersion = "";
        else spec.loaderVersion = core.selected();
        try {
            String err = host.tryCreate(spec);
            if (err != null) {
                error.setText(err);
                return;
            }
            error.setText(" ");
            host.created(spec, new ArrayList<>(queue));
        } catch (java.io.IOException e) {
            error.setText(e.getMessage());
        }
    }

    // ---- сборочные части ----

    private JComponent labeled(String title, JComponent box, String icon) {
        JPanel p = Kit.clear(new BorderLayout(0, 6));
        JPanel t = Kit.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
        if (icon != null) {
            JComponent ic = new JComponent() {
                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(20, 20);
                }

                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    Icons.draw(g2, icon, 0, 0, 20, Kit.TEXT);
                    g2.dispose();
                }
            };
            t.add(ic);
        }
        t.add(Kit.label(title, Font.BOLD, 14, Kit.TEXT));
        t.setBorder(new EmptyBorder(0, -4, 0, 0));
        p.add(t, BorderLayout.NORTH);
        box.setPreferredSize(new Dimension(10, 44));
        p.add(box, BorderLayout.CENTER);
        return p;
    }

    private JComponent sectionTitle(String text, String icon) {
        JPanel t = Kit.clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JComponent ic = new JComponent() {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(22, 22);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Icons.draw(g2, icon, 0, 0, 22, Kit.TEXT);
                g2.dispose();
            }
        };
        t.add(ic);
        t.add(Kit.label(text, Font.BOLD, 16, Kit.TEXT));
        t.setAlignmentX(Component.LEFT_ALIGNMENT);
        t.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        t.setBorder(new EmptyBorder(0, -4, 0, 0));
        return t;
    }

    /** Карточка выбора типа сборки. */
    private final class KindCard extends JComponent {
        private final Kind k;
        private boolean hover;

        KindCard(Kind k) {
            this.k = k;
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
                    spec.kind = KindCard.this.k;
                    for (KindCard c : kinds) c.repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            boolean on = spec.kind == k;
            int w = getWidth();
            int h = getHeight();
            g.setColor(on ? new Color(0x2A1116) : hover ? new Color(0x1E2027) : Kit.FIELD);
            g.fill(new RoundRectangle2D.Float(0, 0, w, h, 14, 14));
            g.setColor(on ? Kit.ACCENT : Kit.LINE);
            g.setStroke(new BasicStroke(on ? 1.6f : 1f));
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 14, 14));
            String icon = switch (k) {
                case MODPACK -> "radio";
                case MAP -> "map";
                case VANILLA -> "cube";
                case EMPTY -> "cube";
            };
            Icons.draw(g, icon, 12, (h - 22) / 2, 22, on ? Kit.ACCENT_HI : Kit.TEXT);
            g.setFont(Kit.font(Font.BOLD, 14));
            g.setColor(Kit.TEXT);
            g.drawString(k.title, 44, h / 2 - 3);
            g.setFont(Kit.font(Font.PLAIN, 12));
            g.setColor(Kit.MUTED);
            FontMetrics fm = g.getFontMetrics();
            String hint = k.hint;
            while (hint.length() > 3 && fm.stringWidth(hint) > w - 52) hint = hint.substring(0, hint.length() - 2);
            g.drawString(hint.equals(k.hint) ? hint : hint + "…", 44, h / 2 + 14);
            g.dispose();
        }
    }

    /** Квадрат с картинкой сборки и кнопкой-карандашом. */
    private final class IconBox extends JComponent {
        private final Rectangle pencil = new Rectangle();

        IconBox() {
            setPreferredSize(new Dimension(154, 146));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    choose();
                }
            });
        }

        private void choose() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Картинка сборки");
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Изображения", "png", "jpg", "jpeg"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            try {
                BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) throw new java.io.IOException("не изображение");
                icon = img;
                spec.iconPath = f.getAbsolutePath();
                error.setText(" ");
                repaint();
            } catch (java.io.IOException ex) {
                error.setText("Не удалось открыть картинку: " + ex.getMessage());
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            Kit.quality(g);
            int w = getWidth();
            int h = getHeight();
            Shape clip = new RoundRectangle2D.Float(0, 0, w, h, 16, 16);
            g.setClip(clip);
            if (icon != null) {
                g.drawImage(icon, 0, 0, w, h, null);
            } else {
                g.setPaint(new GradientPaint(0, 0, new Color(0xFF8A4C), 0, h, new Color(0x3A2A6E)));
                g.fillRect(0, 0, w, h);
                g.setColor(new Color(0xFFD27A));
                g.fillOval(w / 2 - 28, h / 2 - 8, 56, 56);
                g.setColor(new Color(0x1E5E34));
                g.fillRect(0, h - 40, w, 40);
            }
            g.setClip(null);
            g.setColor(Kit.LINE);
            g.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, 16, 16));
            pencil.setBounds(w - 46, h - 46, 34, 34);
            g.setColor(new Color(0x1B1C22));
            g.fill(new RoundRectangle2D.Float(pencil.x, pencil.y, 34, 34, 10, 10));
            g.setColor(Kit.LINE);
            g.draw(new RoundRectangle2D.Float(pencil.x + 0.5f, pencil.y + 0.5f, 33, 33, 10, 10));
            Icons.draw(g, "pencil", pencil.x + 7, pencil.y + 7, 20, Color.WHITE);
            g.dispose();
        }
    }

    /** Панель формы повторяет ширину окна прокрутки, горизонтальной прокрутки нет. */
    private static final class WidthTracking extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return 120;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static int totalRamGb() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean();
            return Math.max(4, (int) Math.round(os.getTotalMemorySize() / (1024.0 * 1024 * 1024)));
        } catch (Throwable t) {
            return 8;
        }
    }

    /** Для проверки в тестах. */
    public BuildSpec spec() {
        return spec;
    }

    /** Для проверки в тестах: поставить название. */
    public void setBuildName(String s) {
        name.setText(s);
    }
}
