package ru.cw.launcher.ui;

import ru.cw.launcher.background.BackgroundManager;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.core.Settings;
import ru.cw.launcher.minecraft.GameOptions;
import ru.cw.launcher.minecraft.JavaManager;
import ru.cw.launcher.minecraft.KeyBindings;
import ru.cw.launcher.settings.SettingsManager;
import ru.cw.launcher.ui.SettingsKit.Button;
import ru.cw.launcher.ui.SettingsKit.Card;
import ru.cw.launcher.ui.SettingsKit.Dropdown;
import ru.cw.launcher.ui.SettingsKit.Glyph;
import ru.cw.launcher.ui.SettingsKit.Kind;
import ru.cw.launcher.ui.SettingsKit.NavItem;
import ru.cw.launcher.ui.SettingsKit.Slider;
import ru.cw.launcher.ui.SettingsKit.Toggle;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import static ru.cw.launcher.ui.SettingsKit.s;

/**
 * Страница настроек внутри главного окна. Слева от неё — общее меню оболочки,
 * у самой страницы — колонка разделов. Изменения копятся в форме и записываются
 * кнопкой «Сохранить изменения»: настройки лаунчера — в config.json,
 * настройки игры и клавиши — в options.txt Minecraft.
 */
public final class SettingsWindow {

    private SettingsWindow() {
    }

    public static void show(Window owner, LauncherApp app) {
        MainWindow mw = owner instanceof MainWindow m ? m : null;
        if (mw == null) {
            for (Frame f : Frame.getFrames()) {
                if (f instanceof MainWindow m && f.isDisplayable()) mw = m;
            }
        }
        if (mw != null) mw.openSettings();
    }

    enum Section {
        MAIN(Glyph.GEAR),
        GAME(Glyph.GAMEPAD),
        CONTROLS(Glyph.KEYBOARD),
        MODS(Glyph.PUZZLE),
        LOOK(Glyph.BRUSH),
        SERVERS(Glyph.SERVERS),
        EXTRA(Glyph.SLIDERS);

        final Glyph glyph;

        Section(Glyph glyph) {
            this.glyph = glyph;
        }

        String title() {
            return switch (this) {
                case MAIN -> Tr.t("Основные", "General");
                case GAME -> Tr.t("Игра", "Game");
                case CONTROLS -> Tr.t("Управление", "Controls");
                case MODS -> Tr.t("Моды", "Mods");
                case LOOK -> Tr.t("Оформление", "Appearance");
                case SERVERS -> Tr.t("Серверы", "Servers");
                case EXTRA -> Tr.t("Дополнительно", "Advanced");
            };
        }
    }

    /** Связь поля формы с config.json. */
    private record Bind(Consumer<Settings> load, Consumer<Settings> store) {
    }

    /** Связь поля формы с options.txt. */
    private record OptBind(Consumer<GameOptions> load, Consumer<GameOptions> store) {
    }

    /** Версия данных options.txt для Minecraft 1.20.1. */
    private static final String DATA_VERSION_1_20_1 = "3465";

    /** Готовые акцентные цвета темы. */
    private static final String[][] ACCENTS = {
            {"#18A9E6", "Бирюзовый", "Cyan"}, {"#3B82F6", "Синий", "Blue"}, {"#22C55E", "Изумрудный", "Emerald"},
            {"#A855F7", "Фиолетовый", "Purple"}, {"#F97316", "Оранжевый", "Orange"}, {"#EF4444", "Красный", "Red"},
            {"#EC4899", "Розовый", "Pink"}, {"#EAB308", "Золотой", "Gold"}};

    static final class Page extends JPanel {
        private final MainWindow mw;
        private final LauncherApp app;
        private final Settings cfg;
        private GameOptions opts;
        /** Версия, чей options.txt сейчас правится. Параметры игры у каждой версии свои. */
        private final String targetVersion;
        private final Map<Section, List<Bind>> binds = new EnumMap<>(Section.class);
        private final Map<Section, List<OptBind>> optBinds = new EnumMap<>(Section.class);
        private final Map<Section, JComponent> pages = new EnumMap<>(Section.class);
        private final Map<Section, NavItem> tabs = new EnumMap<>(Section.class);
        private final List<Runnable> deps = new ArrayList<>();
        private final List<KeyButton> keyButtons = new ArrayList<>();
        private Section current = Section.MAIN;
        private boolean loading;

        private final JPanel sectionNav = new SectionNavPanel();
        private final JScrollPane scroll = new JScrollPane();
        private final JLabel title = SettingsKit.label(Tr.t("Настройки", "Settings"), 40f, Font.BOLD, SettingsKit.TEXT);
        private final JLabel subtitle = SettingsKit.label(Tr.t("Настройте игру и лаунчер под себя",
                "Make the game and the launcher yours"), 17f, Font.PLAIN, new Color(0xB5C2D0));
        private final Button bReset = new Button(Tr.t("Сбросить раздел", "Reset section"), Glyph.RESET, Kind.GHOST);
        private final Button bSave = new Button(Tr.t("Сохранить изменения", "Save changes"), Glyph.SAVE, Kind.PRIMARY);
        private final JLabel dirtyNote = SettingsKit.label(Tr.t("Есть несохранённые изменения", "You have unsaved changes"),
                14f, Font.PLAIN, SettingsKit.AMBER);

        Page(MainWindow mw, LauncherApp app, int section) {
            this(mw, app, section, null);
        }

        /** target — версия Minecraft, для которой правятся параметры игры; null — выбранная в лаунчере. */
        Page(MainWindow mw, LauncherApp app, int section, String target) {
            super(null);
            this.mw = mw;
            this.app = app;
            this.cfg = app.cfg;
            this.targetVersion = target == null || target.isBlank() ? app.cfg.minecraftVersion : target;
            this.opts = GameOptions.load(optionsFile());
            setOpaque(false);
            setFocusable(true);
            for (Section s : Section.values()) {
                binds.put(s, new ArrayList<>());
                optBinds.put(s, new ArrayList<>());
            }
            add(title);
            add(subtitle);
            add(sectionNav);
            add(scroll);
            add(bReset);
            add(bSave);
            add(dirtyNote);
            dirtyNote.setHorizontalAlignment(SwingConstants.RIGHT);
            dirtyNote.setVisible(false);
            bSave.setEnabled(false);
            bSave.iconSize(24);
            bSave.setFont(SettingsKit.font(Font.BOLD, 17f));

            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUI(new SettingsKit.ScrollBarUI());
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(s(10), s(10)));
            scroll.getVerticalScrollBar().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(s(28));
            scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    relayoutView();
                }
            });

            sectionNav.setLayout(null);
            sectionNav.setOpaque(false);
            for (Section s : Section.values()) {
                NavItem item = new NavItem(s.title(), s.glyph, 17f, 26);
                item.addActionListener(e -> select(s));
                tabs.put(s, item);
                sectionNav.add(item);
            }

            pages.put(Section.MAIN, buildMain());
            pages.put(Section.GAME, buildGame());
            pages.put(Section.CONTROLS, buildControls());
            pages.put(Section.MODS, buildMods());
            pages.put(Section.LOOK, buildLook());
            pages.put(Section.SERVERS, buildServers());
            // Настройки локальных серверов — инструмент разработчика.
            tabs.get(Section.SERVERS).setVisible(cfg.developerMode);
            pages.put(Section.EXTRA, buildExtra());

            loadAll(cfg, opts);

            bReset.addActionListener(e -> resetSection());
            bSave.addActionListener(e -> save());

            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cw-back");
            getActionMap().put("cw-back", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    leave(null);
                }
            });
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_S, KeyEvent.CTRL_DOWN_MASK),
                    "cw-save");
            getActionMap().put("cw-save", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (bSave.isEnabled()) save();
                }
            });
            select(section);
        }

        void dispose() {
            // Таймеров у страницы нет; метод оставлен для оболочки.
        }

        int sectionIndex() {
            return current.ordinal();
        }

        void select(int index) {
            Section[] all = Section.values();
            select(all[Math.max(0, Math.min(all.length - 1, index))]);
        }

        // ------------------------------------------------------------------ раскладка

        @Override
        public void doLayout() {
            int w = getWidth();
            int h = getHeight();
            int x0 = s(20);
            int right = w - s(20);
            title.setBounds(x0 + s(16), s(26), w - s(420), s(54));
            subtitle.setBounds(x0 + s(18), s(80), w - s(420), s(28));
            int top = s(124);
            int footerH = s(56);
            int footerY = h - footerH - s(18);
            int secW = s(214);
            int bodyH = footerY - s(14) - top;
            sectionNav.setBounds(x0, top, secW, bodyH);
            int ty = s(12);
            int itemH = s(54);
            int step = s(62);
            for (Section s : Section.values()) {
                if (!tabs.get(s).isVisible()) continue;
                tabs.get(s).setBounds(s(8), ty, secW - s(16), itemH);
                ty += step;
            }
            int sx = x0 + secW + s(12);
            scroll.setBounds(sx, top, right - sx + s(8), bodyH);
            int resetW = secW + s(60);
            bReset.setBounds(x0, footerY, resetW, footerH);
            int saveW = Math.min(s(400), Math.max(s(280), (right - x0) / 3));
            bSave.setBounds(right - saveW, footerY, saveW, footerH);
            int noteX = x0 + resetW + s(16);
            dirtyNote.setBounds(noteX, footerY, bSave.getX() - s(16) - noteX, footerH);
        }

        private void relayoutView() {
            Component view = scroll.getViewport().getView();
            if (view == null) return;
            view.revalidate();
            SwingUtilities.invokeLater(() -> {
                view.revalidate();
                view.repaint();
            });
        }

        private void select(Section s) {
            current = s;
            for (Map.Entry<Section, NavItem> e : tabs.entrySet()) e.getValue().setActive(e.getKey() == s);
            scroll.setViewportView(pages.get(s));
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setValue(0);
            relayoutView();
            bReset.setToolTipText(Tr.t("Вернуть значения по умолчанию в разделе «" + s.title() + "»",
                    "Restore defaults in “" + s.title() + "”"));
        }

        // ------------------------------------------------------------------ состояние формы

        private void loadAll(Settings s, GameOptions o) {
            loading = true;
            try {
                for (Section sec : Section.values()) {
                    for (Bind b : binds.get(sec)) b.load().accept(s);
                    for (OptBind b : optBinds.get(sec)) b.load().accept(o);
                }
            } finally {
                loading = false;
            }
            changed();
        }

        private void changed() {
            if (loading) return;
            previewLook();
            for (Runnable r : deps) r.run();
            refreshConflicts();
            boolean dirty = dirty();
            bSave.setEnabled(dirty);
            dirtyNote.setVisible(dirty);
        }

        /** Оформление видно сразу: цвет, фон, затемнение и прозрачность применяются к окну до сохранения. */
        private void previewLook() {
            Settings probe = cfg.copy();
            for (Bind b : binds.get(Section.LOOK)) b.store().accept(probe);
            SettingsKit.preview(probe);
            mw.repaint();
        }

        /** Отказ от изменений: вернуть оформление из сохранённых настроек. */
        void restoreLook() {
            SettingsKit.preview(cfg);
            mw.repaint();
        }

        private boolean dirty() {
            Settings probe = cfg.copy();
            Map<String, Object> before = probe.toMap();
            for (List<Bind> list : binds.values()) for (Bind b : list) b.store().accept(probe);
            if (!probe.toMap().equals(before)) return true;
            GameOptions op = opts.copy();
            for (List<OptBind> list : optBinds.values()) for (OptBind b : list) b.store().accept(op);
            return !op.raw().equals(opts.raw());
        }

        private boolean gameRunning() {
            try {
                return app.launch.aliveCount(targetVersion) > 0;
            } catch (Exception e) {
                return false;
            }
        }

        private Path optionsFile() {
            return Paths.versionHome(targetVersion).resolve("options.txt");
        }

        /** Подпись настроек, которые меняют вид всего окна: после них интерфейс строится заново. */
        private String lookKey() {
            return nn(cfg.gameLanguage) + "|" + cfg.uiScale + "|" + cfg.uiStyle + "|" + cfg.accentColor + "|"
                    + cfg.cornerRadius + "|" + cfg.panelOpacity + "|" + cfg.shellBackground + "|" + cfg.developerMode
                    + "|" + cfg.backgroundDim + "|" + cfg.backgroundPath + "|" + cfg.useCustomBackground;
        }

        private boolean save() {
            Settings probe = cfg.copy();
            for (List<Bind> list : binds.values()) for (Bind b : list) b.store().accept(probe);
            if (probe.javaPath != null && !probe.javaPath.isBlank() && !Files.isRegularFile(Path.of(probe.javaPath))) {
                select(Section.MAIN);
                Dialogs.info(this, Tr.t("Файл Java не найден:\n" + probe.javaPath
                                + "\nУкажите java.exe или javaw.exe либо выключите «Использовать свою Java».",
                        "Java was not found:\n" + probe.javaPath
                                + "\nPick java.exe or javaw.exe, or turn off “Use my own Java”."));
                return false;
            }
            String lookBefore = lookKey();
            boolean autoStartBefore = cfg.autoStart;
            boolean fullBefore = cfg.launcherFullscreen;
            for (List<Bind> list : binds.values()) for (Bind b : list) b.store().accept(cfg);
            cfg.validate();
            app.saveSettings();
            UiLook.apply(cfg);
            BackgroundManager.setDimPercent(cfg.backgroundDim * 100 / 80);
            Lang.use(cfg.resolvedLanguage());
            if (Files.isDirectory(Paths.gameDir())) app.launch().applyLanguage(cfg, cfg.instanceId());
            if (autoStartBefore != cfg.autoStart) {
                boolean on = cfg.autoStart;
                app.io().submit(() -> SettingsManager.applyAutoStart(on));
            }

            String gameNote = null;
            GameOptions op = opts.copy();
            for (List<OptBind> list : optBinds.values()) for (OptBind b : list) b.store().accept(op);
            if (!op.raw().equals(opts.raw())) {
                if (gameRunning()) {
                    gameNote = Tr.t("Minecraft сейчас запущен и перезапишет options.txt при выходе. "
                                    + "Закройте игру и сохраните настройки игры ещё раз.",
                            "Minecraft is running and will overwrite options.txt on exit. "
                                    + "Close the game and save the game settings again.");
                } else {
                    try {
                        if (!op.raw().containsKey("version") && "1.20.1".equals(targetVersion)) {
                            Map<String, String> ordered = new LinkedHashMap<>();
                            ordered.put("version", DATA_VERSION_1_20_1);
                            ordered.putAll(op.raw());
                            op.raw().clear();
                            op.raw().putAll(ordered);
                        }
                        op.save();
                        opts = GameOptions.load(optionsFile());
                    } catch (Exception e) {
                        gameNote = Tr.t("Не удалось записать настройки игры: ", "Could not write game settings: ")
                                + Log.reason(e);
                        Log.warn("options.txt: " + Log.reason(e));
                    }
                }
            }
            app.refreshState();
            Log.info("Настройки сохранены из экрана настроек");
            if (fullBefore != cfg.launcherFullscreen) mw.applyFullscreen();
            app.applyServerSettings(false);
            mw.applyServerWatch();
            if (!lookBefore.equals(lookKey()) && gameNote == null) {
                // Язык, масштаб, тема и режим разработчика меняют весь интерфейс — строим его заново.
                int section = current.ordinal();
                SwingUtilities.invokeLater(() -> {
                    mw.rebuild(section);
                    Dialogs.toast(mw, Tr.t("Настройки сохранены", "Settings saved"), SettingsKit.GREEN);
                });
                return true;
            }
            loadAll(cfg, gameNote == null ? opts : op);
            if (gameNote != null) {
                Dialogs.info(this, Tr.t("Настройки лаунчера сохранены.\n", "Launcher settings saved.\n") + gameNote);
                bSave.setEnabled(true);
                dirtyNote.setVisible(true);
            } else {
                Dialogs.toast(this, Tr.t("Настройки сохранены", "Settings saved"), SettingsKit.GREEN);
            }
            return gameNote == null;
        }

        private void resetSection() {
            Section s = current;
            if (!Dialogs.ask(this, Tr.t("Вернуть значения по умолчанию в разделе «" + s.title() + "»?\n"
                                    + "Они применятся после нажатия «Сохранить изменения».",
                            "Restore defaults in “" + s.title() + "”?\nThey apply after you press “Save changes”."),
                    Tr.t("Сбросить", "Reset"), Tr.t("Отмена", "Cancel"))) return;
            Settings defaults = new Settings();
            defaults.validate();
            GameOptions empty = GameOptions.load(null);
            loading = true;
            try {
                for (Bind b : binds.get(s)) b.load().accept(defaults);
                for (OptBind b : optBinds.get(s)) b.load().accept(empty);
            } finally {
                loading = false;
            }
            changed();
        }

        /** Уход со страницы: несохранённые изменения не теряются молча. */
        void leave(Runnable then) {
            if (dirty()) {
                if (Dialogs.ask(this, Tr.t("Есть несохранённые изменения. Сохранить их перед выходом?",
                                "You have unsaved changes. Save them before leaving?"),
                        Tr.t("Сохранить", "Save"), Tr.t("Не сохранять", "Discard"))) {
                    if (!save()) return;
                } else {
                    restoreLook();
                }
            }
            mw.closeSettings();
            if (then != null) SwingUtilities.invokeLater(then);
        }

        // ------------------------------------------------------------------ фабрики полей

        private Toggle toggle(Section s, Predicate<Settings> get, BiConsumer<Settings, Boolean> set) {
            Toggle t = new Toggle(get.test(cfg));
            t.onChange(this::changed);
            binds.get(s).add(new Bind(c -> t.setOn(get.test(c)), c -> set.accept(c, t.isOn())));
            return t;
        }

        private Dropdown drop(Section s, String[] labels, ToIntFunction<Settings> get, ObjIntConsumer<Settings> set) {
            Dropdown d = new Dropdown(labels, get.applyAsInt(cfg));
            d.onChange(this::changed);
            binds.get(s).add(new Bind(c -> d.setSelectedIndex(get.applyAsInt(c)),
                    c -> set.accept(c, d.getSelectedIndex())));
            return d;
        }

        private Slider slider(Section s, int min, int max, ToIntFunction<Settings> get, ObjIntConsumer<Settings> set) {
            Slider sl = new Slider(min, max, get.applyAsInt(cfg));
            sl.onChange(this::changed);
            binds.get(s).add(new Bind(c -> sl.setValue(get.applyAsInt(c)), c -> {
                if (get.applyAsInt(c) != sl.getValue()) set.accept(c, sl.getValue());
            }));
            return sl;
        }

        private JTextField text(Section s, Function<Settings, String> get, BiConsumer<Settings, String> set) {
            JTextField f = SettingsKit.field(get.apply(cfg));
            SettingsKit.onText(f, this::changed);
            binds.get(s).add(new Bind(c -> f.setText(nn(get.apply(c))), c -> set.accept(c, f.getText().trim())));
            return f;
        }

        private Toggle optToggle(Section s, String key, boolean def) {
            Toggle t = new Toggle(opts.bool(key, def));
            t.onChange(this::changed);
            optBinds.get(s).add(new OptBind(o -> t.setOn(o.bool(key, def)), o -> {
                if (!o.raw().containsKey(key) && t.isOn() == def) return;
                if (o.bool(key, def) != t.isOn()) o.setBool(key, t.isOn());
            }));
            return t;
        }

        /** Список для ключа options.txt; values — значения в файле, labels — подписи. */
        private Dropdown optDrop(Section s, String key, String[] labels, String[] values, int def, boolean quoted) {
            ToIntFunction<GameOptions> index = o -> {
                String v = o.get(key, values[def]);
                for (int i = 0; i < values.length; i++) if (values[i].equalsIgnoreCase(v)) return i;
                return def;
            };
            Dropdown d = new Dropdown(labels, index.applyAsInt(opts));
            d.onChange(this::changed);
            optBinds.get(s).add(new OptBind(o -> d.setSelectedIndex(index.applyAsInt(o)), o -> {
                int i = d.getSelectedIndex();
                if (!o.raw().containsKey(key) && i == def) return;
                if (index.applyAsInt(o) == i) return;
                if (quoted && !o.raw().containsKey(key)) o.setQuoted(key, values[i]);
                else o.set(key, values[i]);
            }));
            return d;
        }

        /** Ползунок для числа options.txt: toInt переводит значение файла в шаг ползунка, fromInt — обратно. */
        private Slider optSlider(Section s, String key, double def, int min, int max,
                                 java.util.function.DoubleToIntFunction toInt,
                                 java.util.function.IntToDoubleFunction fromInt, boolean integer) {
            Slider sl = new Slider(min, max, toInt.applyAsInt(opts.decimal(key, def)));
            sl.onChange(this::changed);
            optBinds.get(s).add(new OptBind(o -> sl.setValue(toInt.applyAsInt(o.decimal(key, def))), o -> {
                int now = toInt.applyAsInt(o.decimal(key, def));
                if (now == sl.getValue()) return;
                double v = fromInt.applyAsDouble(sl.getValue());
                if (integer) o.setInt(key, (int) Math.round(v));
                else o.setDecimal(key, v);
            }));
            return sl;
        }

        private Slider percent(Section s, String key) {
            return optSlider(s, key, 1.0, 0, 100, v -> (int) Math.round(v * 100), i -> i / 100.0, false);
        }

        private static String nn(String s) {
            return s == null ? "" : s;
        }

        private static JComponent scrollPage(Card... cards) {
            SettingsKit.ScrollPage p = new SettingsKit.ScrollPage(new SettingsKit.Columns(s(14)));
            p.setBorder(new EmptyBorder(0, 0, s(8), s(12)));
            for (Card c : cards) p.add(c);
            return p;
        }

        private static String[] every() {
            return new String[]{Tr.t("Раз в день", "Once a day"), Tr.t("При каждом запуске", "On every start"),
                    Tr.t("Только вручную", "Manually only")};
        }

        // ------------------------------------------------------------------ Основные

        private JComponent buildMain() {
            Section s = Section.MAIN;
            String[][] languages = Settings.languages();
            String[] langLabels = new String[languages.length];
            for (int i = 0; i < languages.length; i++) {
                langLabels[i] = i == 0 ? Tr.t("Как в Windows", "Same as Windows") : languages[i][1];
            }
            Dropdown language = drop(s, langLabels, c -> {
                for (int i = 0; i < languages.length; i++) if (languages[i][0].equals(nn(c.gameLanguage))) return i;
                return 0;
            }, (c, i) -> c.gameLanguage = languages[i][0]);
            Dropdown launch = drop(s, new String[]{Tr.t("Сворачивать лаунчер", "Minimize the launcher"),
                            Tr.t("Оставлять лаунчер открытым", "Keep the launcher open")},
                    c -> "hide".equals(c.launchBehavior) ? 0 : 1, (c, i) -> c.launchBehavior = i == 0 ? "hide" : "menu");
            Dropdown every = drop(s, every(), c -> everyIndex(c.launcherUpdateEvery), (c, i) -> c.launcherUpdateEvery = everyKey(i));
            Card lang = new Card(Glyph.GLOBE, Tr.t("Язык и запуск", "Language and launch"),
                    Tr.t("Основные параметры лаунчера", "Basic launcher options"))
                    .add(SettingsKit.labeled(Tr.t("Язык интерфейса и игры", "Interface and game language"), language))
                    .add(SettingsKit.labeled(Tr.t("Действие при запуске игры", "When the game starts"), launch))
                    .add(SettingsKit.labeled(Tr.t("Проверка обновлений лаунчера", "Check for launcher updates"), every))
                    .add(SettingsKit.row(Glyph.GLOBE, Tr.t("Переводить описания на русский", "Translate descriptions to Russian"),
                            Tr.t("Описания модов и карт на английском переводятся онлайн-переводчиком. Тексты отправляются во внешнюю службу.",
                                    "English mod and map descriptions are translated by an online translator. The texts are sent to an external service."),
                            toggle(s, c -> c.translateDescriptions, (c, v) -> c.translateDescriptions = v)));

            int capGb = Math.max(2, SettingsManager.maxGameRamMb() / 1024);
            int minGb = capGb >= 4 ? 2 : 1;
            int autoGb = Math.max(minGb, Math.min(capGb, (SettingsManager.defaultRamMb() + 512) / 1024));
            ToIntFunction<Settings> ramGb = c -> c.ramMb > 0
                    ? Math.max(minGb, Math.min(capGb, (c.ramMb + 512) / 1024)) : autoGb;
            Slider ram = slider(s, minGb, capGb, ramGb, (c, gb) -> c.ramMb = gb * 1024);
            String gb = Tr.t(" ГБ", " GB");
            int t2 = Math.max(minGb + 1, Math.round(capGb * 3 / 8f));
            int t3 = Math.max(t2 + 1, Math.round(capGb * 3 / 4f));
            if (t3 >= capGb) ram.ticks(v -> v + gb, minGb, capGb);
            else ram.ticks(v -> v + gb, minGb, t2, t3, capGb);
            Card perf = new Card(Glyph.CHIP, Tr.t("Производительность", "Performance"),
                    Tr.t("Память для игры и приоритет", "Game memory and priority"))
                    .add(memoryRow(ram, capGb))
                    .add(SettingsKit.row(Glyph.BARS, Tr.t("Высокий приоритет", "High priority"),
                            Tr.t("Больше процессора для игры", "More CPU time for the game"),
                            toggle(s, c -> c.highPriority, (c, v) -> c.highPriority = v)))
                    .add(SettingsKit.row(Glyph.MONITOR, "VSync", Tr.t("Убирает разрывы картинки", "Removes screen tearing"),
                            toggle(s, c -> c.vsync, (c, v) -> c.vsync = v)));

            Card java = javaCard(s);

            String[] res = resolutions();
            Dropdown resolution = drop(s, res, c -> {
                String key = c.screenWidth + " × " + c.screenHeight;
                for (int i = 0; i < res.length; i++) if (res[i].equals(key)) return i;
                return 0;
            }, (c, i) -> {
                String[] wh = res[i].split(" × ");
                c.screenWidth = Integer.parseInt(wh[0]);
                c.screenHeight = Integer.parseInt(wh[1]);
            });
            Card screen = new Card(Glyph.MONITOR, Tr.t("Экран Minecraft", "Minecraft display"),
                    Tr.t("Окно игры, а не лаунчера", "The game window, not the launcher"))
                    .add(SettingsKit.labeled(Tr.t("Разрешение окна игры", "Game window size"), resolution))
                    .add(SettingsKit.row(Glyph.FULLSCREEN, Tr.t("Minecraft на весь экран", "Minecraft fullscreen"),
                            Tr.t("Игра запускается в полноэкранном режиме", "The game starts in fullscreen"),
                            toggle(s, c -> c.screenFullscreen, (c, v) -> c.screenFullscreen = v)))
                    .add(SettingsKit.row(Glyph.MONITOR, Tr.t("Оптимизация полного экрана", "Fullscreen optimizations"),
                            Tr.t("Функция Windows для плавности", "Windows feature for smoothness"),
                            toggle(s, c -> !c.disableFullscreenOpt, (c, v) -> c.disableFullscreenOpt = !v)));

            Dropdown time = drop(s, new String[]{Tr.t("Секунды", "Seconds"), Tr.t("Минуты", "Minutes"),
                    Tr.t("Часы", "Hours"), Tr.t("Дни", "Days"), Tr.t("Недели", "Weeks")}, c -> switch (nn(c.playTimeUnit)) {
                case "min" -> 1;
                case "hour" -> 2;
                case "day" -> 3;
                case "week" -> 4;
                default -> 0;
            }, (c, i) -> c.playTimeUnit = new String[]{"sec", "min", "hour", "day", "week"}[i]);
            Card launcher = new Card(Glyph.ROCKET, Tr.t("Лаунчер", "Launcher"),
                    Tr.t("Окно, автозапуск и уведомления", "Window, autostart and notifications"))
                    .add(SettingsKit.row(Glyph.FULLSCREEN, Tr.t("Лаунчер на весь экран", "Launcher fullscreen"),
                            Tr.t("Окно лаунчера на весь экран. Клавиша F11", "Launcher window fills the screen. F11 key"),
                            toggle(s, c -> c.launcherFullscreen, (c, v) -> c.launcherFullscreen = v)))
                    .add(SettingsKit.row(Glyph.CLOCK, Tr.t("Запуск вместе с Windows", "Start with Windows"),
                            Tr.t("Открывается при входе в систему", "Opens when you sign in"),
                            toggle(s, c -> c.autoStart, (c, v) -> c.autoStart = v)))
                    .add(SettingsKit.row(Glyph.RESET, Tr.t("Автообновление лаунчера", "Auto-update the launcher"),
                            Tr.t("Новая версия скачивается сама", "New versions download by themselves"),
                            toggle(s, c -> c.autoUpdateLauncher, (c, v) -> c.autoUpdateLauncher = v)))
                    .add(SettingsKit.row(Glyph.BELL, Tr.t("Уведомления", "Notifications"),
                            Tr.t("О сервере и обновлениях", "About the server and updates"),
                            toggle(s, c -> c.notificationsEnabled, (c, v) -> c.notificationsEnabled = v)))
                    .add(SettingsKit.labeled(Tr.t("Точность времени в игре", "Play time precision"), time));
            return scrollPage(lang, perf, screen, java, launcher);
        }

        private JComponent memoryRow(Slider ram, int capGb) {
            JPanel p = new JPanel(new BorderLayout(0, s(10)));
            p.setOpaque(false);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(SettingsKit.label(Tr.t("Память для игры", "Game memory"), 16f, Font.PLAIN, SettingsKit.TEXT), BorderLayout.WEST);
            JLabel value = new JLabel();
            String hex = SettingsKit.hex(SettingsKit.ACCENT);
            String gb = Tr.t(" ГБ", " GB");
            Runnable show = () -> value.setText("<html><span style='color:" + hex + "'><b>"
                    + ram.getValue() + gb + "</b></span><span style='color:#93A3B6'> " + Tr.t("из", "of") + " " + capGb + gb + "</span></html>");
            value.setFont(SettingsKit.font(Font.PLAIN, 16.5f));
            show.run();
            ram.onView(show);
            top.add(value, BorderLayout.EAST);
            p.add(top, BorderLayout.NORTH);
            p.add(ram, BorderLayout.CENTER);
            return SettingsKit.fullWidth(p);
        }

        private Card javaCard(Section s) {
            JavaManager jm = app.java();
            JavaManager.Runtime auto = jm.selected();
            String autoPath = auto == null ? "" : auto.path();
            boolean ownNow = !nn(cfg.javaPath).isBlank();
            Toggle own = new Toggle(ownNow);
            JTextField path = SettingsKit.field(ownNow ? cfg.javaPath : autoPath);
            own.onChange(() -> {
                if (!own.isOn()) path.setText(autoPath);
                changed();
            });
            SettingsKit.onText(path, this::changed);
            binds.get(s).add(new Bind(c -> {
                boolean on = !nn(c.javaPath).isBlank();
                own.setOn(on);
                path.setText(on ? c.javaPath : autoPath);
            }, c -> c.javaPath = own.isOn() ? path.getText().trim() : ""));
            deps.add(() -> path.setEnabled(own.isOn()));
            path.setEnabled(ownNow);

            int need = JavaManager.requiredMajor(cfg.minecraftVersion);
            String found = auto == null ? "" : Tr.t("Java " + auto.major() + " найдена автоматически",
                    "Java " + auto.major() + " found automatically");
            String missing = Tr.t("Java " + need + " поставится при первом запуске", "Java " + need + " installs on first launch");
            SettingsKit.Status status = new SettingsKit.Status(auto == null ? missing : found,
                    auto == null ? SettingsKit.AMBER : SettingsKit.GREEN);
            deps.add(() -> {
                if (own.isOn()) {
                    String p = path.getText().trim();
                    boolean ok = !p.isEmpty() && Files.isRegularFile(Path.of(p));
                    status.set(ok ? Tr.t("Используется своя Java", "Using your own Java")
                            : Tr.t("Укажите путь к java.exe", "Pick java.exe"), ok ? SettingsKit.GREEN : SettingsKit.AMBER);
                } else if (auto != null) {
                    status.set(found, SettingsKit.GREEN);
                } else {
                    status.set(missing, SettingsKit.AMBER);
                }
            });

            Button browse = new Button("", Glyph.FOLDER, Kind.GHOST).size(50, 46).iconSize(22);
            browse.setToolTipText(Tr.t("Выбрать java.exe", "Pick java.exe"));
            browse.addActionListener(e -> {
                String chosen = chooseJava();
                if (chosen == null) return;
                own.setOn(true);
                path.setText(chosen);
                changed();
            });
            JPanel pathRow = new JPanel(new BorderLayout(s(8), 0));
            pathRow.setOpaque(false);
            pathRow.add(path, BorderLayout.CENTER);
            pathRow.add(browse, BorderLayout.EAST);

            JTextField jvm = text(s, c -> c.extraJvmArgs, (c, v) -> c.extraJvmArgs = v);
            jvm.setToolTipText(Tr.t("Например: -XX:+UseZGC. Память задаётся ползунком.", "For example: -XX:+UseZGC. Memory is set by the slider."));
            return new Card(Glyph.JAVA, "Java", Tr.t("Среда, в которой работает игра", "The runtime the game uses"))
                    .add(status)
                    .add(SettingsKit.labeled(Tr.t("Путь к Java", "Java path"), SettingsKit.fullWidth(pathRow)))
                    .add(SettingsKit.row(null, Tr.t("Использовать свою Java", "Use my own Java"),
                            Tr.t("Указать свой java.exe", "Point to your own java.exe"), own))
                    .add(SettingsKit.labeled(Tr.t("Дополнительные аргументы JVM", "Extra JVM arguments"), jvm));
        }

        private String chooseJava() {
            FileDialog dialog = new FileDialog(mw, Tr.t("Выберите java.exe", "Choose java.exe"), FileDialog.LOAD);
            dialog.setFile("java*.exe");
            dialog.setVisible(true);
            if (dialog.getFile() == null || dialog.getDirectory() == null) return null;
            return new java.io.File(dialog.getDirectory(), dialog.getFile()).getAbsolutePath();
        }

        private String[] resolutions() {
            TreeSet<String> set = new TreeSet<>((a, b) -> {
                String[] x = a.split(" × ");
                String[] y = b.split(" × ");
                int c = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
                return c != 0 ? c : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
            });
            for (String r : new String[]{"854 × 480", "1280 × 720", "1366 × 768", "1600 × 900", "1920 × 1080",
                    "2560 × 1440", "3840 × 2160"}) set.add(r);
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            if (screen.width >= 854 && screen.height >= 480) set.add(screen.width + " × " + screen.height);
            set.add(cfg.screenWidth + " × " + cfg.screenHeight);
            return set.toArray(new String[0]);
        }

        // ------------------------------------------------------------------ Игра

        private JComponent buildGame() {
            Section s = Section.GAME;
            Card file = optionsCard();
            String chunks = Tr.t(" чанков", " chunks");
            String off = Tr.t("Выкл.", "Off");

            Slider render = optSlider(s, "renderDistance", 12, 2, 32, v -> (int) Math.round(v), i -> i, true);
            Slider sim = optSlider(s, "simulationDistance", 12, 5, 32, v -> (int) Math.round(v), i -> i, true);
            Slider mip = optSlider(s, "mipmapLevels", 4, 0, 4, v -> (int) Math.round(v), i -> i, true);
            Slider blend = optSlider(s, "biomeBlendRadius", 2, 0, 7, v -> (int) Math.round(v), i -> i, true);
            Card graphics = new Card(Glyph.IMAGE, Tr.t("Графика", "Graphics"), Tr.t("Качество картинки и дальность", "Quality and distance"))
                    .add(SettingsKit.slider(Tr.t("Дальность прорисовки", "Render distance"), render, v -> v + chunks))
                    .add(SettingsKit.slider(Tr.t("Дальность симуляции", "Simulation distance"), sim, v -> v + chunks))
                    .add(SettingsKit.labeled(Tr.t("Качество графики", "Graphics"), optDrop(s, "graphicsMode",
                            new String[]{Tr.t("Быстрая", "Fast"), Tr.t("Детальная", "Fancy"), Tr.t("Сверхдетальная", "Fabulous!")},
                            new String[]{"0", "1", "2"}, 1, false)))
                    .add(SettingsKit.labeled(Tr.t("Облака", "Clouds"), optDrop(s, "renderClouds",
                            new String[]{Tr.t("Выключены", "Off"), Tr.t("Быстрые", "Fast"), Tr.t("Детальные", "Fancy")},
                            new String[]{"false", "fast", "true"}, 2, true)))
                    .add(SettingsKit.labeled(Tr.t("Частицы", "Particles"), optDrop(s, "particles",
                            new String[]{Tr.t("Все", "All"), Tr.t("Меньше", "Decreased"), Tr.t("Минимум", "Minimal")},
                            new String[]{"0", "1", "2"}, 0, false)))
                    .add(SettingsKit.row(Glyph.LAYERS, Tr.t("Мягкое освещение", "Smooth lighting"),
                            Tr.t("Плавные тени на блоках", "Soft shadows on blocks"), optToggle(s, "ao", true)))
                    .add(SettingsKit.row(Glyph.USER, Tr.t("Тени существ", "Entity shadows"),
                            Tr.t("Тени под мобами и игроками", "Shadows under mobs and players"), optToggle(s, "entityShadows", true)))
                    .add(SettingsKit.slider(Tr.t("Уровни MIP-текстур", "Mipmap levels"), mip, v -> v == 0 ? off : String.valueOf(v)))
                    .add(SettingsKit.slider(Tr.t("Смешивание биомов", "Biome blend"), blend, v -> v == 0 ? off : (v * 2 + 1) + "×" + (v * 2 + 1)));

            Slider fov = optSlider(s, "fov", 0, 30, 110, v -> (int) Math.round(70 + v * 40), i -> (i - 70) / 40.0, false);
            Slider gamma = percent(s, "gamma");
            Slider fps = slider(s, 10, 260, c -> c.framerateLimit <= 0 || c.framerateLimit >= 260 ? 260 : Math.max(10, c.framerateLimit),
                    (c, v) -> c.framerateLimit = v >= 260 ? 0 : v);
            Slider distortion = percent(s, "screenEffectScale");
            Slider fovEffect = percent(s, "fovEffectScale");
            Card view = new Card(Glyph.EYE, Tr.t("Камера и интерфейс игры", "Camera and HUD"), Tr.t("Обзор, яркость, FPS", "View, brightness, FPS"))
                    .add(SettingsKit.slider(Tr.t("Поле зрения", "Field of view"), fov,
                            v -> v == 70 ? Tr.t("Нормальное", "Normal") : v == 110 ? "Quake Pro" : String.valueOf(v)))
                    .add(SettingsKit.slider(Tr.t("Яркость", "Brightness"), gamma,
                            v -> v == 0 ? Tr.t("Мрачная", "Moody") : v == 100 ? Tr.t("Ярко", "Bright") : v + "%"))
                    .add(SettingsKit.slider(Tr.t("Ограничение FPS", "Max framerate"), fps,
                            v -> v >= 260 ? Tr.t("Без ограничения", "Unlimited") : v + " FPS"))
                    .add(SettingsKit.labeled(Tr.t("Масштаб интерфейса игры", "GUI scale"), optDrop(s, "guiScale",
                            new String[]{Tr.t("Авто", "Auto"), "1", "2", "3", "4"}, new String[]{"0", "1", "2", "3", "4"}, 0, false)))
                    .add(SettingsKit.row(Glyph.MOUSE, Tr.t("Покачивание камеры", "View bobbing"),
                            Tr.t("Камера качается при ходьбе", "The camera sways while walking"), optToggle(s, "bobView", true)))
                    .add(SettingsKit.slider(Tr.t("Эффекты искажения", "Distortion effects"), distortion, v -> v + "%"))
                    .add(SettingsKit.slider(Tr.t("Эффекты поля зрения", "FOV effects"), fovEffect, v -> v + "%"));

            Card sound = new Card(Glyph.SOUND, Tr.t("Звук", "Sound"), Tr.t("Громкость по категориям", "Volume by category"));
            String[][] cats = {{"master", "Общая громкость", "Master volume"}, {"music", "Музыка", "Music"},
                    {"record", "Проигрыватели", "Jukebox"}, {"weather", "Погода", "Weather"}, {"block", "Блоки", "Blocks"},
                    {"hostile", "Враждебные существа", "Hostile creatures"}, {"neutral", "Дружелюбные существа", "Friendly creatures"},
                    {"player", "Игроки", "Players"}, {"ambient", "Окружение", "Ambient"}, {"voice", "Голос и речь", "Voice"}};
            for (String[] cat : cats) {
                sound.add(SettingsKit.slider(Tr.t(cat[1], cat[2]), percent(s, "soundCategory_" + cat[0]),
                        v -> v == 0 ? off : v + "%"));
            }
            sound.add(SettingsKit.row(Glyph.CHAT, Tr.t("Субтитры", "Subtitles"), Tr.t("Подписи к звукам", "Captions for sounds"),
                    optToggle(s, "showSubtitles", false)));

            Slider chatScale = percent(s, "chatScale");
            Slider chatOpacity = percent(s, "chatOpacity");
            Card chat = new Card(Glyph.CHAT, Tr.t("Чат", "Chat"), Tr.t("Сообщения в игре", "Messages in game"))
                    .add(SettingsKit.labeled(Tr.t("Чат", "Chat"), optDrop(s, "chatVisibility",
                            new String[]{Tr.t("Показан", "Shown"), Tr.t("Только команды", "Commands only"), Tr.t("Скрыт", "Hidden")},
                            new String[]{"0", "1", "2"}, 0, false)))
                    .add(SettingsKit.row(null, Tr.t("Цвета в чате", "Chat colors"), Tr.t("Цветной текст сообщений", "Colored messages"),
                            optToggle(s, "chatColors", true)))
                    .add(SettingsKit.row(null, Tr.t("Ссылки в чате", "Web links"), Tr.t("Ссылки можно открывать", "Links can be opened"),
                            optToggle(s, "chatLinks", true)))
                    .add(SettingsKit.slider(Tr.t("Размер чата", "Chat scale"), chatScale, v -> v + "%"))
                    .add(SettingsKit.slider(Tr.t("Непрозрачность текста", "Text opacity"), chatOpacity, v -> v + "%"));
            return scrollPage(versionCard(), file, graphics, view, sound, chat);
        }

        /**
         * Для какой версии игры эти настройки. У каждой установленной версии свой options.txt:
         * в одной стоят моды и свои параметры, в другой — чистый ванильный клиент.
         */
        private Card versionCard() {
            List<String> ids = new ArrayList<>(Paths.localGameIds());
            if (!ids.contains(cfg.minecraftVersion)) ids.add(0, cfg.minecraftVersion);
            if (!ids.contains(targetVersion)) ids.add(targetVersion);
            ids.sort((a, b) -> Utils.compareVersions(b, a));
            String[] labels = new String[ids.size()];
            for (int i = 0; i < labels.length; i++) {
                String id = ids.get(i);
                labels[i] = "Minecraft " + id + (id.equals(cfg.minecraftVersion) ? Tr.t("  ·  выбрана в лаунчере", "  ·  selected in the launcher") : "");
            }
            Dropdown d = new Dropdown(labels, Math.max(0, ids.indexOf(targetVersion)));
            d.onChange(() -> {
                String pick = ids.get(d.getSelectedIndex());
                if (pick.equals(targetVersion)) return;
                if (bSave.isEnabled()) {
                    d.setSelectedIndex(Math.max(0, ids.indexOf(targetVersion)));
                    Dialogs.toast(mw, Tr.t("Сначала сохраните или сбросьте изменения", "Save or reset your changes first"), SettingsKit.AMBER);
                    return;
                }
                mw.reopenSettings(current.ordinal(), pick);
            });
            return new Card(Glyph.LAYERS, Tr.t("Настройки для версии", "Settings for version"),
                    Tr.t("У каждой версии Minecraft свои параметры. Выберите, какую настраиваете.",
                            "Every Minecraft version keeps its own options. Pick the one you are setting up.")).wide()
                    .add(SettingsKit.labeled(Tr.t("Версия игры", "Game version"), d));
        }

        private Card optionsCard() {
            Path f = optionsFile();
            SettingsKit.Status st = new SettingsKit.Status("", SettingsKit.GREEN);
            Runnable refresh = () -> {
                if (gameRunning()) {
                    st.set(Tr.t("Minecraft запущен: закройте игру, чтобы сохранить её настройки",
                            "Minecraft is running: close it to save game settings"), SettingsKit.AMBER);
                } else if (Files.isRegularFile(f)) {
                    st.set(Tr.t("Настройки читаются из options.txt выбранной версии",
                            "Read from options.txt of the selected version"), SettingsKit.GREEN);
                } else {
                    st.set(Tr.t("Игра ещё не запускалась: файл создастся при сохранении",
                            "The game hasn't run yet: the file is created on save"), SettingsKit.ACCENT);
                }
            };
            refresh.run();
            deps.add(refresh);
            Button open = new Button(Tr.t("Папка игры", "Game folder"), Glyph.FOLDER, Kind.GHOST);
            open.addActionListener(e -> mw.openFolder(Paths.versionHome(targetVersion)));
            JPanel line = new JPanel(new BorderLayout(s(12), 0));
            line.setOpaque(false);
            line.add(st, BorderLayout.CENTER);
            line.add(open, BorderLayout.EAST);
            return new Card(Glyph.FILE, Tr.t("Настройки Minecraft", "Minecraft settings"),
                    Tr.t("Те же параметры, что в меню игры. Minecraft ", "Same options as the in-game menu. Minecraft ")
                            + targetVersion).wide().add(SettingsKit.fullWidth(line));
        }

        // ------------------------------------------------------------------ Управление

        private JComponent buildControls() {
            Section s = Section.CONTROLS;
            Slider sens = optSlider(s, "mouseSensitivity", 0.5, 0, 200, v -> (int) Math.round(v * 200), i -> i / 200.0, false);
            Slider wheel = optSlider(s, "mouseWheelSensitivity", 1.0, 1, 100, v -> (int) Math.round(v * 10), i -> i / 10.0, false);
            Card mouse = new Card(Glyph.MOUSE, Tr.t("Мышь", "Mouse"), Tr.t("Чувствительность и ввод", "Sensitivity and input"))
                    .add(SettingsKit.slider(Tr.t("Чувствительность", "Sensitivity"), sens,
                            v -> v == 0 ? Tr.t("Мин.", "Min") : v == 200 ? Tr.t("Гиперскорость", "Hyperspeed") : v + "%"))
                    .add(SettingsKit.slider(Tr.t("Прокрутка колесом", "Scroll sensitivity"), wheel,
                            v -> String.format(java.util.Locale.ROOT, "%.1f", v / 10.0)))
                    .add(SettingsKit.row(null, Tr.t("Инверсия мыши", "Invert mouse"),
                            Tr.t("Мышь вверх — взгляд вниз", "Mouse up looks down"), optToggle(s, "invertYMouse", false)))
                    .add(SettingsKit.row(null, Tr.t("Прямой ввод", "Raw input"),
                            Tr.t("Без ускорения курсора Windows", "No Windows mouse acceleration"), optToggle(s, "rawMouseInput", true)))
                    .add(SettingsKit.row(null, Tr.t("Сенсорный экран", "Touchscreen"),
                            Tr.t("Управление касаниями", "Touch controls"), optToggle(s, "touchscreen", false)));
            String[] mode = {Tr.t("Удерживать", "Hold"), Tr.t("Переключать", "Toggle")};
            Card move = new Card(Glyph.GAMEPAD, Tr.t("Движение", "Movement"), Tr.t("Приседание, бег, прыжки", "Sneak, sprint, jump"))
                    .add(SettingsKit.labeled(Tr.t("Красться", "Sneak"), optDrop(s, "toggleCrouch", mode, new String[]{"false", "true"}, 0, false)))
                    .add(SettingsKit.labeled(Tr.t("Бег", "Sprint"), optDrop(s, "toggleSprint", mode, new String[]{"false", "true"}, 0, false)))
                    .add(SettingsKit.row(null, Tr.t("Автопрыжок", "Auto-jump"),
                            Tr.t("Сам запрыгивает на блоки", "Jumps onto blocks by itself"), optToggle(s, "autoJump", false)));
            return scrollPage(versionCard(), mouse, move, keysCard(s));
        }

        private Card keysCard(Section s) {
            Button resetKeys = new Button(Tr.t("Сбросить все клавиши", "Reset all keys"), Glyph.RESET, Kind.GHOST);
            Card card = new Card(Glyph.KEYBOARD, Tr.t("Назначение клавиш", "Key binds"),
                    Tr.t("Нажмите на клавишу, затем новую кнопку. Esc — убрать назначение.",
                            "Click a key, then press a new one. Esc clears it.")).wide();
            card.add(SettingsKit.line(resetKeys));
            boolean ru = Lang.russian();
            List<KeyBindings.Group> groups = new ArrayList<>(KeyBindings.vanilla(ru));
            List<String> modIds = new ArrayList<>();
            Map<String, String> current = new LinkedHashMap<>();
            for (String id : opts.keyActions()) {
                if (KeyBindings.isVanilla(id)) continue;
                modIds.add(id);
                current.put(id, opts.key(id, KeyBindings.UNBOUND));
            }
            if (!modIds.isEmpty()) groups.addAll(KeyBindings.modGroups(Paths.mods(cfg.instanceId()), modIds, current, ru));
            List<KeyButton> mine = new ArrayList<>();
            for (KeyBindings.Group g : groups) {
                JLabel head = SettingsKit.label(g.title(), 17f, Font.BOLD, SettingsKit.ACCENT);
                head.setBorder(new EmptyBorder(s(10), s(2), 0, 0));
                card.add(SettingsKit.fullWidth(head));
                SettingsKit.ScrollPage grid = new SettingsKit.ScrollPage(new SettingsKit.Columns(s(10)));
                for (KeyBindings.Action a : g.actions()) {
                    KeyButton kb = new KeyButton(a);
                    mine.add(kb);
                    keyButtons.add(kb);
                    optBinds.get(s).add(new OptBind(o -> kb.setCode(o.key(a.id(), a.def())), o -> {
                        if (!o.raw().containsKey("key_" + a.id()) && kb.code().equals(a.def())) return;
                        if (!o.key(a.id(), a.def()).equals(kb.code())) o.setKey(a.id(), kb.code());
                    }));
                    grid.add(keyRow(a, kb));
                }
                card.add(grid);
            }
            resetKeys.addActionListener(e -> {
                for (KeyButton kb : mine) kb.setCode(kb.action.def());
                changed();
            });
            return card;
        }

        private JComponent keyRow(KeyBindings.Action a, KeyButton kb) {
            JPanel row = new JPanel(new BorderLayout(s(10), 0));
            row.setOpaque(false);
            JLabel name = SettingsKit.label(a.title(), 15f, Font.PLAIN, SettingsKit.TEXT);
            name.setToolTipText(a.id());
            row.add(name, BorderLayout.CENTER);
            Button undo = new Button("", Glyph.RESET, Kind.FLAT).size(40, 44).iconSize(18);
            undo.setToolTipText(Tr.t("По умолчанию: ", "Default: ") + KeyBindings.label(a.def(), Lang.russian()));
            undo.addActionListener(e -> {
                kb.setCode(a.def());
                changed();
            });
            deps.add(() -> undo.setVisible(!kb.code().equals(a.def())));
            JPanel east = new JPanel(new BorderLayout(s(4), 0));
            east.setOpaque(false);
            east.add(kb, BorderLayout.CENTER);
            east.add(undo, BorderLayout.EAST);
            east.setPreferredSize(new Dimension(s(230), s(44)));
            row.add(east, BorderLayout.EAST);
            row.setPreferredSize(new Dimension(s(300), s(44)));
            return row;
        }

        private void refreshConflicts() {
            Map<String, List<KeyButton>> byCode = new LinkedHashMap<>();
            for (KeyButton kb : keyButtons) {
                if (KeyBindings.UNBOUND.equals(kb.code())) continue;
                byCode.computeIfAbsent(kb.code(), k -> new ArrayList<>()).add(kb);
            }
            for (KeyButton kb : keyButtons) {
                List<KeyButton> same = byCode.get(kb.code());
                if (same == null || same.size() < 2) {
                    kb.setConflict(null);
                } else {
                    StringBuilder sb = new StringBuilder(Tr.t("Также назначено: ", "Also bound to: "));
                    boolean first = true;
                    for (KeyButton o : same) {
                        if (o == kb) continue;
                        if (!first) sb.append(", ");
                        sb.append(o.action.title());
                        first = false;
                    }
                    kb.setConflict(sb.toString());
                }
            }
        }

        /** Кнопка клавиши: щелчок — ждём нажатия, Esc — снять назначение. */
        private final class KeyButton extends JComponent {
            final KeyBindings.Action action;
            private String code;
            private boolean listening;
            private String conflict;
            private boolean hover;

            KeyButton(KeyBindings.Action action) {
                this.action = action;
                this.code = opts.key(action.id(), action.def());
                setPreferredSize(new Dimension(s(186), s(44)));
                setFocusable(true);
                setFocusTraversalKeysEnabled(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (listening) {
                            String m = KeyBindings.fromMouse(e);
                            if (m != null) assign(m);
                            e.consume();
                            return;
                        }
                        listening = true;
                        requestFocusInWindow();
                        repaint();
                    }

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
                });
                addKeyListener(new java.awt.event.KeyAdapter() {
                    @Override
                    public void keyPressed(KeyEvent e) {
                        if (!listening) {
                            if (e.getKeyCode() == KeyEvent.VK_ENTER || e.getKeyCode() == KeyEvent.VK_SPACE) {
                                listening = true;
                                repaint();
                                e.consume();
                            }
                            return;
                        }
                        e.consume();
                        if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                            assign(KeyBindings.UNBOUND);
                            return;
                        }
                        String k = KeyBindings.fromKey(e);
                        if (k != null) assign(k);
                    }
                });
                addFocusListener(new java.awt.event.FocusAdapter() {
                    @Override
                    public void focusLost(java.awt.event.FocusEvent e) {
                        listening = false;
                        repaint();
                    }
                });
            }

            String code() {
                return code;
            }

            void setCode(String c) {
                code = c == null || c.isBlank() ? KeyBindings.UNBOUND : c;
                listening = false;
                repaint();
            }

            void setConflict(String text) {
                conflict = text;
                setToolTipText(text);
                repaint();
            }

            private void assign(String c) {
                setCode(c);
                changed();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                int w = getWidth();
                int h = getHeight();
                int r = SettingsKit.rr();
                g2.setColor(listening ? new Color(0x10283A) : SettingsKit.FIELD);
                g2.fill(new RoundRectangle2D.Float(0, 0, w - 1, h - 1, r, r));
                Color line = listening ? SettingsKit.ACCENT : conflict != null ? SettingsKit.RED
                        : hover ? SettingsKit.FIELD_HOVER : SettingsKit.FIELD_LINE;
                g2.setColor(line);
                g2.setStroke(new BasicStroke(listening ? 2f : 1f));
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 2, h - 2, r, r));
                String unboundText = Tr.t("Не назначено", "Not bound");
                String text = listening ? Tr.t("> Нажмите клавишу <", "> Press a key <")
                        : KeyBindings.UNBOUND.equals(code) ? unboundText : KeyBindings.label(code, Lang.russian());
                g2.setFont(SettingsKit.font(listening ? Font.BOLD : Font.PLAIN, 15f));
                boolean unbound = KeyBindings.UNBOUND.equals(code);
                g2.setColor(listening ? SettingsKit.ACCENT : conflict != null ? SettingsKit.RED
                        : unbound ? SettingsKit.DIM : SettingsKit.TEXT);
                FontMetrics fm = g2.getFontMetrics();
                String shown = SettingsKit.ellipsize(text, fm, w - s(20));
                g2.drawString(shown, (w - fm.stringWidth(shown)) / 2, (h + fm.getAscent() - fm.getDescent()) / 2);
                g2.dispose();
            }
        }

        // ------------------------------------------------------------------ Моды

        private JComponent buildMods() {
            Section s = Section.MODS;
            Dropdown every = drop(s, every(), c -> everyIndex(c.modsUpdateEvery), (c, i) -> c.modsUpdateEvery = everyKey(i));
            Card update = new Card(Glyph.RESET, Tr.t("Сборка Common World", "Common World pack"),
                    Tr.t("Обновление и проверка модов", "Mod updates and checks"))
                    .add(SettingsKit.row(null, Tr.t("Автообновление сборки", "Auto-update the pack"),
                            Tr.t("Новые моды ставятся сами", "New mods install by themselves"),
                            toggle(s, c -> c.autoUpdateBuild, (c, v) -> c.autoUpdateBuild = v)))
                    .add(SettingsKit.labeled(Tr.t("Проверять обновления модов", "Check for mod updates"), every))
                    .add(SettingsKit.row(Glyph.SHIELD, Tr.t("Проверка целостности", "Integrity check"),
                            Tr.t("Сверять моды перед запуском", "Verify mods before launch"),
                            toggle(s, c -> c.verifyBuildOnLaunch, (c, v) -> c.verifyBuildOnLaunch = v)));
            int[] counts = {3, 5, 10};
            Dropdown copies = drop(s, new String[]{Tr.t("3 копии", "3 copies"), Tr.t("5 копий", "5 copies"),
                    Tr.t("10 копий", "10 copies")}, c -> c.modBackupCount <= 3 ? 0
                    : c.modBackupCount >= 10 ? 2 : 1, (c, i) -> c.modBackupCount = counts[i]);
            Toggle backups = toggle(s, c -> c.modBackups, (c, v) -> c.modBackups = v);
            deps.add(() -> copies.setEnabled(backups.isOn()));
            Card backup = new Card(Glyph.ARCHIVE, Tr.t("Резервные копии", "Backups"),
                    Tr.t("Старые моды сохраняются перед обновлением", "Old mods are kept before updating"))
                    .add(SettingsKit.row(null, Tr.t("Делать резервные копии", "Make backups"),
                            Tr.t("Можно откатиться при поломке", "Roll back if something breaks"), backups))
                    .add(SettingsKit.labeled(Tr.t("Сколько копий хранить", "How many to keep"), copies));

            String id = cfg.instanceId();
            Card folders = new Card(Glyph.FOLDER, Tr.t("Папки игры", "Game folders"), Tr.t("Быстрый доступ к файлам", "Quick access to files"));
            Object[][] dirs = {{Tr.t("Моды", "Mods"), Paths.mods(id)}, {Tr.t("Конфиги", "Configs"), Paths.configs(id)},
                    {Tr.t("Шейдеры", "Shaders"), Paths.shaderpacks(id)}, {Tr.t("Ресурспаки", "Resource packs"), Paths.resourcepacks(id)},
                    {Tr.t("Миры", "Worlds"), Paths.saves(id)}, {Tr.t("Вся папка", "Whole folder"), Paths.gameDir()}};
            JPanel grid = new JPanel(new GridLayout(0, 2, s(10), s(10)));
            grid.setOpaque(false);
            for (Object[] d : dirs) {
                Button b = new Button((String) d[0], Glyph.FOLDER, Kind.GHOST);
                b.setFont(SettingsKit.font(Font.PLAIN, 15f));
                Path dir = (Path) d[1];
                b.addActionListener(e -> mw.openFolder(dir));
                grid.add(b);
            }
            folders.add(SettingsKit.fullWidth(grid));

            Card list = new Card(Glyph.PUZZLE, Tr.t("Установленные моды", "Installed mods"), Tr.t("Файлы .jar в папке mods", ".jar files in the mods folder")).wide();
            List<String[]> jars = modFiles(Paths.mods(id));
            if (jars.isEmpty()) {
                list.add(SettingsKit.label(Tr.t("Моды ещё не установлены. Нажмите «Установить» на главной.",
                        "Mods are not installed yet. Press “Install” on Home."), 15f, Font.PLAIN, SettingsKit.MUTED));
            } else {
                list.add(SettingsKit.label(Tr.t("Всего: ", "Total: ") + jars.size(), 15f, Font.BOLD, SettingsKit.ACCENT));
                SettingsKit.ScrollPage modGrid = new SettingsKit.ScrollPage(new SettingsKit.Columns(s(8)));
                for (String[] jar : jars) {
                    JPanel row = new JPanel(new BorderLayout(s(10), 0));
                    row.setOpaque(false);
                    row.setPreferredSize(new Dimension(s(300), s(30)));
                    JLabel n = SettingsKit.label(jar[0], 14.5f, Font.PLAIN, SettingsKit.TEXT);
                    n.setToolTipText(jar[0]);
                    row.add(n, BorderLayout.CENTER);
                    row.add(SettingsKit.label(jar[1], 13.5f, Font.PLAIN, SettingsKit.MUTED), BorderLayout.EAST);
                    modGrid.add(row);
                }
                list.add(modGrid);
            }
            return scrollPage(update, backup, folders, list);
        }

        private static List<String[]> modFiles(Path dir) {
            List<String[]> out = new ArrayList<>();
            if (!Files.isDirectory(dir)) return out;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                        .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                        .forEach(p -> {
                            long size;
                            try {
                                size = Files.size(p);
                            } catch (Exception e) {
                                size = 0;
                            }
                            out.add(new String[]{p.getFileName().toString(), Utils.humanSize(size)});
                        });
            } catch (Exception e) {
                Log.warn("Список модов: " + Log.reason(e));
            }
            return out;
        }

        // ------------------------------------------------------------------ Оформление

        private JComponent buildLook() {
            Section s = Section.LOOK;
            Card theme = themeCard(s);
            Card bg = backgroundCard(s);
            Slider dim = slider(s, 0, 100, c -> c.backgroundDim * 100 / 80, (c, v) -> c.backgroundDim = v * 80 / 100);
            dim.ticks(v -> v + "%", 0, 50, 100);
            Toggle behind = toggle(s, c -> c.shellBackground, (c, v) -> c.shellBackground = v);
            Toggle blur = toggle(s, c -> c.uiBlur, (c, v) -> c.uiBlur = v);
            Slider opacity = slider(s, 40, 100, c -> c.panelOpacity, (c, v) -> c.panelOpacity = v);
            opacity.ticks(v -> v + "%", 40, 70, 100);
            Slider heroShade = slider(s, 0, 100, c -> c.heroShade, (c, v) -> c.heroShade = v);
            heroShade.ticks(v -> v + "%", 0, 50, 100);
            deps.add(() -> {
                opacity.setEnabled(behind.isOn());
                blur.setEnabled(behind.isOn());
            });
            Card glass = new Card(Glyph.LAYERS, Tr.t("Фон и затемнение", "Background and dimming"),
                    Tr.t("Видно сразу, сохранить — кнопкой внизу", "Previewed live, save with the button below"))
                    .add(SettingsKit.row(null, Tr.t("Фон под всем интерфейсом", "Background behind the whole UI"),
                            Tr.t("Картинка за меню и карточками", "The picture behind menus and cards"), behind))
                    .add(SettingsKit.row(null, Tr.t("Размыть фон", "Blur the background"),
                            Tr.t("Мягкий фон, текст читается легче", "Soft backdrop, easier to read"), blur))
                    .add(SettingsKit.slider(Tr.t("Затемнение фона", "Background dim"), dim, v -> v + "%"))
                    .add(SettingsKit.slider(Tr.t("Непрозрачность карточек", "Card opacity"), opacity, v -> v + "%"))
                    .add(SettingsKit.slider(Tr.t("Затемнение баннера главной", "Home banner shade"), heroShade,
                            v -> v == 0 ? Tr.t("Нет", "None") : v + "%"));
            Toggle effects = toggle(s, c -> c.uiEffects, (c, v) -> c.uiEffects = v);
            Toggle anim = toggle(s, c -> c.uiAnim, (c, v) -> c.uiAnim = v);
            Toggle fade = toggle(s, c -> c.uiTransitions, (c, v) -> c.uiTransitions = v);
            deps.add(() -> {
                anim.setEnabled(effects.isOn());
                fade.setEnabled(effects.isOn());
            });
            Card fx = new Card(Glyph.EYE, Tr.t("Эффекты", "Effects"), Tr.t("Анимации и счётчик кадров", "Animations and FPS counter"))
                    .add(SettingsKit.row(null, Tr.t("Эффекты интерфейса", "Interface effects"),
                            Tr.t("Общий выключатель", "Master switch"), effects))
                    .add(SettingsKit.row(null, Tr.t("Анимации", "Animations"), Tr.t("Плавные подсветки", "Smooth highlights"), anim))
                    .add(SettingsKit.row(null, Tr.t("Плавное появление окон", "Fade-in windows"),
                            Tr.t("Окна открываются с переходом", "Windows open with a fade"), fade))
                    .add(SettingsKit.row(null, Tr.t("Анимация перестановки скинов", "Skin reorder animation"),
                            Tr.t("Скины плавно сдвигаются в профиле", "Skins slide smoothly in the profile"),
                            toggle(s, c -> c.skinReorderAnim, (c, v) -> c.skinReorderAnim = v)))
                    .add(SettingsKit.row(null, Tr.t("Показывать FPS лаунчера", "Show launcher FPS"),
                            Tr.t("Счётчик кадров слева сверху", "Frame counter at the top left"),
                            toggle(s, c -> c.showFps, (c, v) -> c.showFps = v)));
            return scrollPage(theme, glass, bg, fx);
        }

        // ------------------------------------------------------------------ Серверы

        /** Как лаунчер обращается с локальными серверами: запуск, закрытие, сбои, бэкапы. */
        private JComponent buildServers() {
            Section s = Section.SERVERS;
            Toggle keep = toggle(s, c -> c.serversKeepAlive, (c, v) -> c.serversKeepAlive = v);
            Card life = new Card(Glyph.SERVERS, Tr.t("Работа серверов", "Server lifecycle"),
                    Tr.t("Когда включать и выключать", "When to start and stop"))
                    .add(SettingsKit.row(null, Tr.t("Работать после закрытия лаунчера", "Keep running after closing"),
                            Tr.t("Друзья играют, даже если лаунчер закрыт", "Friends keep playing when the launcher is closed"), keep))
                    .add(SettingsKit.row(null, Tr.t("Перезапуск после сбоя", "Restart after a crash"),
                            Tr.t("Пока лаунчер открыт", "While the launcher is open"),
                            toggle(s, c -> c.serversAutoRestart, (c, v) -> c.serversAutoRestart = v)))
                    .add(SettingsKit.row(null, Tr.t("Уведомления о входе игроков", "Player join notifications"),
                            Tr.t("Всплывающее сообщение в лаунчере", "A pop-up in the launcher"),
                            toggle(s, c -> c.serversJoinNotify, (c, v) -> c.serversJoinNotify = v)));
            int[] minutes = {0, 15, 30, 60, 120, 360, 720};
            String[] minuteLabels = {Tr.t("Выключен", "Off"), Tr.t("Каждые 15 минут", "Every 15 minutes"),
                    Tr.t("Каждые 30 минут", "Every 30 minutes"), Tr.t("Каждый час", "Every hour"),
                    Tr.t("Каждые 2 часа", "Every 2 hours"), Tr.t("Каждые 6 часов", "Every 6 hours"),
                    Tr.t("Каждые 12 часов", "Every 12 hours")};
            Dropdown every = drop(s, minuteLabels, c -> {
                for (int i = 0; i < minutes.length; i++) if (minutes[i] == c.serversBackupMinutes) return i;
                return 0;
            }, (c, i) -> c.serversBackupMinutes = minutes[i]);
            Slider keepCount = slider(s, 1, 30, c -> c.serversBackupKeep, (c, v) -> c.serversBackupKeep = v);
            deps.add(() -> keepCount.setEnabled(every.getSelectedIndex() > 0));
            Card backups = new Card(Glyph.ARCHIVE, Tr.t("Автобэкапы", "Auto-backups"),
                    Tr.t("Копии миров запущенных серверов", "World copies of running servers"))
                    .add(SettingsKit.labeled(Tr.t("Как часто", "How often"), every))
                    .add(SettingsKit.slider(Tr.t("Хранить последних копий", "Keep latest copies"), keepCount, String::valueOf));
            int capGb = Math.max(2, SettingsManager.maxGameRamMb() / 1024);
            Slider ram = slider(s, 1, capGb, c -> Math.max(1, Math.min(capGb, c.serversDefaultRamMb / 1024)),
                    (c, v) -> c.serversDefaultRamMb = v * 1024);
            String gb = Tr.t(" ГБ", " GB");
            Card defaults = new Card(Glyph.CHIP, Tr.t("Новые серверы", "New servers"),
                    Tr.t("Память и адрес по умолчанию", "Default memory and address"))
                    .add(SettingsKit.slider(Tr.t("Память для нового сервера", "Memory for a new server"), ram, v -> v + gb))
                    .add(SettingsKit.label(Tr.t("Адрес для друзей: ", "Address for friends: ")
                            + ru.cw.launcher.server.LocalServerManager.primaryAddress(), 14.5f, Font.PLAIN, new Color(0xB5C2D0)));
            Button open = new Button(Tr.t("Открыть «Серверы»", "Open “Servers”"), Glyph.SERVERS, Kind.GHOST);
            open.addActionListener(e -> mw.navigate(mw::openServers));
            defaults.add(SettingsKit.line(open));
            return scrollPage(life, backups, defaults);
        }

        private Card themeCard(Section s) {
            Swatches swatches = new Swatches();
            binds.get(s).add(new Bind(c -> swatches.set(c.accentColor), c -> c.accentColor = swatches.value));
            Slider scale = slider(s, 80, 130, c -> c.uiScale, (c, v) -> c.uiScale = v);
            scale.ticks(v -> v + "%", 80, 100, 130);
            Slider radius = slider(s, 0, 28, c -> c.cornerRadius, (c, v) -> c.cornerRadius = v);
            return new Card(Glyph.BRUSH, Tr.t("Тема", "Theme"), Tr.t("Цвет, размер и форма интерфейса", "Color, size and shape"))
                    .add(SettingsKit.labeled(Tr.t("Акцентный цвет", "Accent color"), swatches))
                    .add(SettingsKit.slider(Tr.t("Масштаб интерфейса", "Interface scale"), scale, v -> v + "%"))
                    .add(SettingsKit.row(null, Tr.t("Компактный режим", "Compact mode"),
                            Tr.t("Меньше размеры и отступы", "Smaller sizes and spacing"),
                            toggle(s, c -> "compact".equals(c.uiStyle), (c, v) -> c.uiStyle = v ? "compact" : "standard")))
                    .add(SettingsKit.slider(Tr.t("Скругление углов", "Corner roundness"), radius,
                            v -> v == 0 ? Tr.t("Прямые", "Square") : v + " px"));
        }

        /** Круглые образцы акцентного цвета и кнопка «свой цвет». */
        private final class Swatches extends JComponent {
            String value = cfg.accentColor;

            Swatches() {
                setPreferredSize(new Dimension(s(300), s(46)));
                setMaximumSize(new Dimension(Integer.MAX_VALUE, s(46)));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(Tr.t("Цвет кнопок, переключателей и выделения", "Color of buttons, switches and highlights"));
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        int i = e.getX() / step();
                        if (i < ACCENTS.length) {
                            pick(ACCENTS[i][0]);
                        } else if (i == ACCENTS.length) {
                            Color c = JColorChooser.showDialog(mw, Tr.t("Свой цвет", "Custom color"), Color.decode(value));
                            if (c != null) pick(SettingsKit.hex(c));
                        }
                    }
                });
            }

            private int step() {
                return s(46);
            }

            void set(String v) {
                value = v == null ? Settings.ACCENT_DEFAULT : v.toUpperCase(java.util.Locale.ROOT);
                repaint();
            }

            private void pick(String v) {
                set(v);
                changed();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                int d = s(34);
                int y = (getHeight() - d) / 2;
                boolean preset = false;
                for (int i = 0; i < ACCENTS.length; i++) {
                    int x = i * step() + s(4);
                    g2.setColor(Color.decode(ACCENTS[i][0]));
                    g2.fill(new Ellipse2D.Float(x, y, d, d));
                    if (ACCENTS[i][0].equalsIgnoreCase(value)) {
                        preset = true;
                        g2.setColor(Color.WHITE);
                        g2.setStroke(new BasicStroke(s(3)));
                        g2.draw(new Ellipse2D.Float(x - s(3), y - s(3), d + s(6), d + s(6)));
                    }
                }
                int x = ACCENTS.length * step() + s(4);
                Color custom;
                try {
                    custom = Color.decode(value);
                } catch (Exception e) {
                    custom = SettingsKit.ACCENT;
                }
                g2.setColor(preset ? SettingsKit.FIELD : custom);
                g2.fill(new Ellipse2D.Float(x, y, d, d));
                g2.setColor(preset ? SettingsKit.FIELD_LINE : Color.WHITE);
                g2.setStroke(new BasicStroke(preset ? 1.5f : s(3)));
                g2.draw(new Ellipse2D.Float(x, y, d, d));
                SettingsKit.icon(Glyph.PLUS, s(18), Color.WHITE).paintIcon(this, g2, x + (d - s(18)) / 2, y + (d - s(18)) / 2);
                g2.dispose();
            }
        }

        private Card backgroundCard(Section s) {
            Card card = new Card(Glyph.IMAGE, Tr.t("Картинка фона", "Background picture"),
                    Tr.t("Баннер главной и фон окон", "Home banner and window backdrop")).wide();
            JPanel grid = new JPanel(new GridLayout(0, 4, s(12), s(12)));
            grid.setOpaque(false);
            String[] chosen = {currentBackground(cfg)};
            List<Thumb> thumbs = new ArrayList<>();
            Runnable[] fill = new Runnable[1];
            fill[0] = () -> {
                grid.removeAll();
                thumbs.clear();
                List<Path> pics = new ArrayList<>(Paths.backgroundImages());
                if (cfg.useCustomBackground && !nn(cfg.backgroundPath).isBlank()) {
                    Path extra = Path.of(cfg.backgroundPath);
                    boolean listed = pics.stream().anyMatch(p -> same(p, extra.toString()));
                    if (!listed && Files.isRegularFile(extra)) pics.add(0, extra);
                }
                grid.setLayout(new GridLayout(0, pics.isEmpty() ? 1 : 4, s(12), s(12)));
                for (Path p : pics) {
                    Thumb t = new Thumb(p);
                    t.addMouseListener(new MouseAdapter() {
                        @Override
                        public void mousePressed(MouseEvent e) {
                            chosen[0] = isDefault(p) ? "" : p.toAbsolutePath().normalize().toString();
                            for (Thumb o : thumbs) o.setSelected(o == t);
                            changed();
                        }
                    });
                    thumbs.add(t);
                    grid.add(t);
                }
                if (pics.isEmpty()) grid.add(SettingsKit.label(Tr.t("В папке фонов пока нет картинок",
                        "The background folder is empty"), 15f, Font.PLAIN, SettingsKit.MUTED));
                for (Thumb t : thumbs) t.setSelected(chosen[0].isEmpty() ? isDefault(t.file) : same(t.file, chosen[0]));
                grid.revalidate();
                grid.repaint();
                relayoutView();
            };
            fill[0].run();
            binds.get(s).add(new Bind(c -> {
                chosen[0] = currentBackground(c);
                for (Thumb t : thumbs) t.setSelected(chosen[0].isEmpty() ? isDefault(t.file) : same(t.file, chosen[0]));
            }, c -> {
                if (chosen[0].isEmpty()) {
                    c.useCustomBackground = false;
                    c.backgroundPath = "";
                } else {
                    c.useCustomBackground = true;
                    c.backgroundPath = chosen[0];
                }
            }));
            Button add = new Button(Tr.t("Добавить картинку", "Add picture"), Glyph.PLUS, Kind.GHOST);
            add.addActionListener(e -> {
                String path = BackgroundManager.chooseImage(mw);
                if (path == null) return;
                try {
                    Path copied = copyBackground(Path.of(path));
                    chosen[0] = copied.toAbsolutePath().normalize().toString();
                    fill[0].run();
                    changed();
                } catch (Exception ex) {
                    Dialogs.error(this, Tr.t("Не удалось добавить фон.", "Could not add the background."), Log.reason(ex), null);
                }
            });
            Button folder = new Button(Tr.t("Папка фонов", "Backgrounds folder"), Glyph.FOLDER, Kind.GHOST);
            folder.addActionListener(e -> mw.openFolder(Paths.backgroundDir()));
            Button gallery = new Button(Tr.t("Галерея", "Gallery"), Glyph.IMAGE, Kind.GHOST);
            gallery.addActionListener(e -> {
                GalleryWindow.show(mw, app);
                fill[0].run();
            });
            card.add(SettingsKit.fullWidth(grid));
            card.add(SettingsKit.line(add, folder, gallery));
            return card;
        }

        private static boolean isDefault(Path p) {
            return p.getFileName().toString().equalsIgnoreCase("main.png")
                    && p.toAbsolutePath().normalize().startsWith(Paths.backgroundDir().toAbsolutePath().normalize());
        }

        /** Встроенный фон подписан «Основная», а не именем файла main.png. */
        static String displayName(Path p) {
            return isDefault(p) ? Tr.t("Основная", "Default") : p.getFileName().toString();
        }

        private static String currentBackground(Settings c) {
            return c.useCustomBackground && c.backgroundPath != null ? c.backgroundPath : "";
        }

        private static boolean same(Path p, String other) {
            if (other == null || other.isBlank()) return false;
            try {
                return p.toAbsolutePath().normalize().toString()
                        .equalsIgnoreCase(Path.of(other).toAbsolutePath().normalize().toString());
            } catch (Exception e) {
                return false;
            }
        }

        private static Path copyBackground(Path src) throws java.io.IOException {
            Path dir = Paths.backgroundDir();
            Files.createDirectories(dir);
            String name = src.getFileName().toString();
            Path dest = dir.resolve(name);
            if (dest.toAbsolutePath().normalize().equals(src.toAbsolutePath().normalize())) return dest;
            if (Files.exists(dest)) {
                int dot = name.lastIndexOf('.');
                String base = dot > 0 ? name.substring(0, dot) : name;
                String ext = dot > 0 ? name.substring(dot) : "";
                int n = 2;
                do {
                    dest = dir.resolve(base + "-" + n + ext);
                    n++;
                } while (Files.exists(dest));
            }
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
            return dest;
        }

        /** Миниатюра фона 16:9, картинка читается в фоне. */
        private static final class Thumb extends JComponent {
            final Path file;
            private volatile BufferedImage image;
            private boolean selected;
            private boolean hover;

            Thumb(Path file) {
                this.file = file;
                setPreferredSize(new Dimension(s(200), s(116)));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText(displayName(file));
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
                });
                Thread t = new Thread(() -> {
                    try {
                        BufferedImage src = ImageIO.read(file.toFile());
                        if (src == null) return;
                        int w = 360;
                        int h = 202;
                        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = small.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        double scale = Math.max((double) w / src.getWidth(), (double) h / src.getHeight());
                        int dw = (int) Math.ceil(src.getWidth() * scale);
                        int dh = (int) Math.ceil(src.getHeight() * scale);
                        g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                        g.dispose();
                        image = small;
                        SwingUtilities.invokeLater(this::repaint);
                    } catch (Exception e) {
                        Log.warn("Миниатюра фона " + file.getFileName() + ": " + Log.reason(e));
                    }
                }, "cw-thumb");
                t.setDaemon(true);
                t.start();
            }

            void setSelected(boolean s) {
                selected = s;
                repaint();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                int w = getWidth();
                int h = getHeight();
                int r = SettingsKit.rr() + s(2);
                Shape clip = new RoundRectangle2D.Float(0, 0, w, h, r, r);
                g2.setColor(SettingsKit.FIELD);
                g2.fill(clip);
                if (image != null) {
                    g2.setClip(clip);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    double scale = Math.max((double) w / image.getWidth(), (double) h / image.getHeight());
                    int dw = (int) Math.ceil(image.getWidth() * scale);
                    int dh = (int) Math.ceil(image.getHeight() * scale);
                    g2.drawImage(image, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
                    g2.setClip(null);
                }
                g2.setPaint(new GradientPaint(0, h - s(34), new Color(0, 0, 0, 0), 0, h, new Color(0, 0, 0, 170)));
                g2.fill(clip);
                g2.setFont(SettingsKit.font(Font.PLAIN, 13f));
                g2.setColor(Color.WHITE);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(SettingsKit.ellipsize(displayName(file), fm, w - s(40)), s(10), h - s(10));
                g2.setStroke(new BasicStroke(selected ? s(3) : 1f));
                g2.setColor(selected ? SettingsKit.ACCENT : hover ? SettingsKit.FIELD_HOVER : SettingsKit.CARD_LINE);
                g2.draw(new RoundRectangle2D.Float(1, 1, w - 3, h - 3, r, r));
                if (selected) {
                    g2.setColor(SettingsKit.ACCENT);
                    g2.fill(new Ellipse2D.Float(w - s(30), s(8), s(22), s(22)));
                    SettingsKit.icon(Glyph.CHECK, s(14), Color.WHITE).paintIcon(this, g2, w - s(26), s(12));
                }
                g2.dispose();
            }
        }

        // ------------------------------------------------------------------ Дополнительно

        private JComponent buildExtra() {
            Section s = Section.EXTRA;
            int[] speeds = {5, 10, 25, 50, 100};
            String mbit = Tr.t(" Мбит/с", " Mbit/s");
            Toggle limit = toggle(s, c -> c.limitBandwidth, (c, v) -> c.limitBandwidth = v);
            Dropdown speed = drop(s, new String[]{5 + mbit, 10 + mbit, 25 + mbit, 50 + mbit, 100 + mbit}, c -> {
                int best = 2;
                int dist = Integer.MAX_VALUE;
                for (int i = 0; i < speeds.length; i++) {
                    int d = Math.abs(speeds[i] - (c.bandwidthMbit <= 0 ? 25 : c.bandwidthMbit));
                    if (d < dist) {
                        dist = d;
                        best = i;
                    }
                }
                return best;
            }, (c, i) -> {
                if (c.limitBandwidth || c.bandwidthMbit > 0) c.bandwidthMbit = speeds[i];
            });
            deps.add(() -> speed.setEnabled(limit.isOn()));
            Card net = new Card(Glyph.NET, Tr.t("Сеть", "Network"), Tr.t("Загрузки и подключение", "Downloads and connection"))
                    .add(SettingsKit.row(null, Tr.t("Приоритет игры в сети", "Game network priority"),
                            Tr.t("Игра получает канал первой", "The game gets bandwidth first"),
                            toggle(s, c -> c.internetLeader, (c, v) -> c.internetLeader = v)))
                    .add(SettingsKit.row(null, Tr.t("Ограничить загрузку", "Limit downloads"),
                            Tr.t("Чтобы не мешать другим", "So others aren't slowed down"), limit))
                    .add(SettingsKit.labeled(Tr.t("Скорость загрузки", "Download speed"), speed))
                    .add(SettingsKit.row(null, Tr.t("Системный прокси", "System proxy"),
                            Tr.t("Прокси из настроек Windows", "Proxy from Windows settings"),
                            toggle(s, c -> c.useSystemProxy, (c, v) -> c.useSystemProxy = v)))
                    .add(SettingsKit.row(null, Tr.t("Офлайн-запуск", "Offline launch"),
                            Tr.t("Без интернета, если всё скачано", "Without internet when all is downloaded"),
                            toggle(s, c -> c.offlineAllowed, (c, v) -> c.offlineAllowed = v)));

            Card system = new Card(Glyph.CHIP, Tr.t("Система", "System"), Tr.t("Параметры Windows для игры", "Windows options for the game"))
                    .add(SettingsKit.row(null, Tr.t("Мощная видеокарта", "High-performance GPU"),
                            Tr.t("Игра на дискретной видеокарте", "Run the game on the discrete GPU"),
                            toggle(s, c -> c.disableRealtimeOpt, (c, v) -> c.disableRealtimeOpt = v)))
                    .add(SettingsKit.row(null, Tr.t("Проверять место на диске", "Check free disk space"),
                            Tr.t("Предупреждать перед загрузкой", "Warn before downloading"),
                            toggle(s, c -> c.checkDiskSpace, (c, v) -> c.checkDiskSpace = v)));

            String cacheText = Tr.t("Кэш: ", "Cache: ");
            JLabel cacheSize = SettingsKit.label(cacheText + Utils.humanSize(cacheBytes()), 15f, Font.PLAIN, SettingsKit.MUTED);
            Button clear = new Button(Tr.t("Очистить кэш", "Clear cache"), Glyph.TRASH, Kind.GHOST);
            clear.addActionListener(e -> {
                if (!Dialogs.ask(this, Tr.t("Удалить временные данные CWLauncher (", "Delete CWLauncher temporary data (")
                                + Utils.humanSize(cacheBytes()) + ")?", Tr.t("Очистить", "Clear"), Tr.t("Отмена", "Cancel"))) return;
                Utils.clearDir(Paths.cache());
                Utils.clearDir(Paths.tmp());
                cacheSize.setText(cacheText + Utils.humanSize(cacheBytes()));
            });
            Button logs = new Button(Tr.t("Логи", "Logs"), Glyph.FILE, Kind.GHOST);
            logs.addActionListener(e -> mw.openFolder(Paths.logs()));
            Button downloads = new Button(Tr.t("Загрузки", "Downloads"), Glyph.FOLDER, Kind.GHOST);
            downloads.addActionListener(e -> mw.openFolder(Paths.cache()));
            Button diag = new Button(Tr.t("Диагностика", "Diagnostics"), Glyph.BUG, Kind.GHOST);
            diag.setToolTipText(Tr.t("Собрать архив для техподдержки", "Collect an archive for support"));
            diag.addActionListener(e -> makeDiagnostics(diag));
            JPanel tools = new JPanel(new GridLayout(0, 2, s(10), s(10)));
            tools.setOpaque(false);
            tools.add(clear);
            tools.add(logs);
            tools.add(downloads);
            tools.add(diag);
            Card service = new Card(Glyph.SLIDERS, Tr.t("Обслуживание", "Maintenance"), Tr.t("Кэш, журналы и диагностика", "Cache, logs and diagnostics"))
                    .add(cacheSize)
                    .add(SettingsKit.fullWidth(tools));

            JLabel about = SettingsKit.label("CWLauncher " + Log.VERSION, 13.5f, Font.PLAIN, SettingsKit.DIM);
            about.setToolTipText(String.valueOf(Paths.root()));
            Card dev = new Card(Glyph.BUG, Tr.t("Для разработчиков", "For developers"), Tr.t("Журналы и тестовые запуски", "Logs and test launches"))
                    .add(SettingsKit.row(null, Tr.t("Режим разработчика", "Developer mode"),
                            Tr.t("Серверы, несколько окон игры, выбор ника", "Servers, several game windows, nickname"),
                            toggle(s, c -> c.developerMode, (c, v) -> c.developerMode = v)))
                    .add(SettingsKit.row(null, Tr.t("Подробный журнал", "Verbose log"),
                            Tr.t("Больше записей в логах", "More entries in logs"),
                            toggle(s, c -> c.verboseLogging, (c, v) -> c.verboseLogging = v)))
                    .add(about);

            Button wipeGames = new Button(Tr.t("Удалить файлы игры", "Delete game files"), Glyph.TRASH, Kind.DANGER);
            wipeGames.addActionListener(e -> {
                if (app.isBusy()) {
                    Dialogs.info(this, Tr.t("Дождитесь окончания текущей операции.", "Wait for the current operation to finish."));
                    return;
                }
                if (!Dialogs.ask(this, Tr.t("Удалить все версии игры, моды и миры? Лаунчер останется, "
                                + "но игру нужно будет скачать заново.", "Delete all game versions, mods and worlds? "
                                + "The launcher stays, but the game must be downloaded again."),
                        Tr.t("Удалить", "Delete"), Tr.t("Отмена", "Cancel"))) return;
                app.resetGameData();
                Dialogs.info(this, Tr.t("Игровые файлы удалены.", "Game files deleted."));
            });
            Button wipeAll = new Button(Tr.t("Удалить лаунчер", "Uninstall launcher"), Glyph.TRASH, Kind.DANGER);
            wipeAll.addActionListener(e -> {
                if (app.isBusy()) {
                    Dialogs.info(this, Tr.t("Дождитесь окончания текущей операции.", "Wait for the current operation to finish."));
                    return;
                }
                if (!Dialogs.ask(this, Tr.t("Полностью удалить лаунчер и все его файлы? Это сотрёт настройки, "
                                + "аккаунты и все версии игры.", "Remove the launcher and all its files? This erases settings, "
                                + "accounts and all game versions."),
                        Tr.t("Удалить всё", "Delete everything"), Tr.t("Отмена", "Cancel"))) return;
                if (!app.launchUninstaller()) {
                    Dialogs.error(this, Tr.t("Рядом с лаунчером нет Uninstall.exe. Удаление не начато.",
                            "Uninstall.exe is missing next to the launcher."), null, null);
                    return;
                }
                app.shutdown();
                System.exit(0);
            });
            JPanel danger = new JPanel(new GridLayout(0, 1, 0, s(10)));
            danger.setOpaque(false);
            danger.add(wipeGames);
            danger.add(wipeAll);
            Card dangerCard = new Card(Glyph.SHIELD, Tr.t("Опасная зона", "Danger zone"),
                    Tr.t("Действия, которые нельзя отменить", "Actions that can't be undone"))
                    .add(SettingsKit.fullWidth(danger));
            return scrollPage(net, system, service, dev, dangerCard);
        }

        private void makeDiagnostics(JButton button) {
            button.setEnabled(false);
            app.io().submit(() -> {
                try {
                    Path zip = app.diagnostics().writeReportZip(app.cfg, app.mc(), app.fabric(), app.java(), app.mods());
                    SwingUtilities.invokeLater(() -> {
                        button.setEnabled(true);
                        Dialogs.info(this, Tr.t("Файл диагностики создан:\n", "Diagnostics file created:\n") + zip);
                        Utils.openFolder(zip.getParent());
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        button.setEnabled(true);
                        Dialogs.error(this, Tr.t("Не удалось создать диагностику.", "Could not create diagnostics."), Log.reason(ex), null);
                    });
                }
            });
        }

        private static long cacheBytes() {
            return Utils.dirSize(Paths.cache()) + Utils.dirSize(Paths.tmp());
        }

        private static int everyIndex(String key) {
            if ("start".equals(key)) return 1;
            if ("manual".equals(key)) return 2;
            return 0;
        }

        private static String everyKey(int index) {
            if (index == 1) return "start";
            if (index == 2) return "manual";
            return "day";
        }

        /** Колонка разделов настроек — вторая колонка слева. */
        private static final class SectionNavPanel extends JPanel {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                SettingsKit.smooth(g2);
                int r = SettingsKit.radius() + s(2);
                g2.setColor(SettingsKit.card());
                g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, r, r));
                g2.setColor(SettingsKit.CARD_LINE);
                g2.draw(new RoundRectangle2D.Float(0.5f, 0.5f, getWidth() - 2, getHeight() - 2, r, r));
                g2.dispose();
            }
        }
    }
}
