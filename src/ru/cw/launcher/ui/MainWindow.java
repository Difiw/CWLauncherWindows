package ru.cw.launcher.ui;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.core.LauncherApp;
import ru.cw.launcher.core.Operation;
import ru.cw.launcher.model.GameState;
import ru.cw.launcher.news.NewsManager;
import ru.cw.launcher.settings.SettingsManager;
import ru.cw.launcher.updater.UpdateChecker;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.AWTEventListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Главное окно: оболочка с меню слева и страницами «Главная» и «Настройки».
 * Здесь логика запуска игры и навигации; вид — в {@link Shell}, {@link HomePage}, {@link SettingsWindow}.
 */
public class MainWindow extends JFrame {

    private static final int EDGE = 7;

    private final LauncherApp app;
    private Shell shell;
    private HomePage home;
    private SettingsWindow.Page settingsPage;
    private NewsPage newsPage;
    private ServersPage serversPage;
    private SkinEditorPage skinEditorPage;
    private OperationWindow operationWindow;
    private final AWTEventListener frameMouse;
    private Rectangle normalBounds;

    private long diskAt;
    private GameState diskState;
    private boolean diskMcReady;
    private String diskFabric = "";
    private String diskMods;
    private boolean diskReading;

    // Перетаскивание и изменение размера окна без рамки Windows.
    private Point press;
    private Rectangle origin;
    private int dragMode = -1;

    public MainWindow(LauncherApp app) {
        super("CWLauncher");
        this.app = app;
        setUndecorated(true);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        try {
            setIconImage(Icons.appIcon());
        } catch (Exception ignored) {
        }
        build(-1);
        app.setLauncherUpdateFocus(() -> {
            setExtendedState(getExtendedState() & ~ICONIFIED);
            setVisible(true);
            toFront();
            requestFocus();
            showHome();
        });
        app.onStateChanged(() -> SwingUtilities.invokeLater(this::syncFromState));
        applyServerWatch();
        // Названия клавиш модов читаются из jar заранее: раздел «Управление» откроется без задержки.
        app.io().submit(() -> ru.cw.launcher.minecraft.KeyBindings.warm(Paths.mods(app.cfg.instanceId()), Lang.russian()));
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                navigate(MainWindow.this::quit);
            }

            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                app.checkServer();
            }
        });
        getRootPane().registerKeyboardAction(e -> toggleFullscreen(),
                KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        frameMouse = this::frameMouse;
        Toolkit.getDefaultToolkit().addAWTEventListener(frameMouse,
                AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        setMinimumSize(new Dimension(1120, 700));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        int w = Math.min(1440, Math.max(1120, (int) (screen.width * 0.76)));
        int h = Math.min(900, Math.max(700, (int) (screen.height * 0.80)));
        setSize(w, h);
        setLocationRelativeTo(null);
        normalBounds = getBounds();
        Timer timer = new Timer(200, e -> syncFromState());
        timer.start();
        Timer fps = new Timer(33, e -> {
            if (UiLook.showFps() && shell != null) shell.repaint();
        });
        fps.start();
    }

    /** Строит оболочку заново: после смены языка, масштаба, темы или режима разработчика. */
    private void build(int settingsSection) {
        SettingsKit.applyTheme(app.cfg);
        Lang.use(app.cfg.resolvedLanguage());
        if (settingsPage != null) settingsPage.dispose();
        settingsPage = null;
        shell = new Shell(this, app);
        home = new HomePage(this, app);
        setContentPane(shell);
        if (settingsSection >= 0) openSettings(settingsSection);
        else shell.setPage(home, Shell.Target.HOME);
        syncFromState();
        shell.revalidate();
        shell.repaint();
    }

    void rebuild(int settingsSection) {
        build(settingsSection);
        applyFullscreen();
    }

    // ------------------------------------------------------------------ навигация

    /** Уход со страницы настроек спрашивает про несохранённые изменения, потом выполняет действие. */
    void navigate(Runnable then) {
        if (skinEditorPage != null && skinEditorPage.dirty()) {
            skinEditorPage.leave(then);
            return;
        }
        if (settingsPage != null) {
            settingsPage.leave(then);
            return;
        }
        if (then != null) then.run();
    }

    void showHome() {
        dropNews();
        skinEditorPage = null;
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        shell.setPage(home, Shell.Target.HOME);
        syncFromState();
    }

    public void openSettings() {
        openSettings(0);
    }

    public void openSettings(int section) {
        if (settingsPage != null) {
            settingsPage.select(section);
            return;
        }
        dropNews();
        settingsPage = new SettingsWindow.Page(this, app, section);
        shell.setPage(settingsPage, Shell.Target.SETTINGS);
        settingsPage.requestFocusInWindow();
    }

    /** Закрывает настройки и возвращает главную. */
    void closeSettings() {
        showHome();
    }

    void openNews() {
        openNews(NewsPage.Tab.ALL);
    }

    /** Новости и список версий — страница оболочки. Пустые источники показывают пустое состояние. */
    void openNews(NewsPage.Tab tab) {
        if (newsPage != null) {
            newsPage.select(tab);
            return;
        }
        skinEditorPage = null;
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        newsPage = new NewsPage(this, app, tab);
        shell.setPage(newsPage, Shell.Target.NEWS);
    }

    private void dropNews() {
        if (newsPage != null) newsPage.dispose();
        newsPage = null;
        if (serversPage != null) serversPage.dispose();
        serversPage = null;
    }

    /** Серверы — страница оболочки (видна в режиме разработчика). */
    void openServers() {
        skinEditorPage = null;
        dropNews();
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        serversPage = new ServersPage(this, app);
        shell.setPage(serversPage, Shell.Target.SERVERS);
    }

    /** Уведомления о входе и выходе игроков на локальных серверах — по настройкам. */
    void applyServerWatch() {
        if (app.cfg.developerMode && app.cfg.serversJoinNotify) {
            app.localServers.watchJoins(msg -> SwingUtilities.invokeLater(() -> Dialogs.toast(this, msg, SettingsKit.ACCENT)));
        } else {
            app.localServers.watchJoins(null);
        }
    }

    /** Профиль — страница оболочки, как главная и настройки. */
    void openProfile() {
        dropNews();
        skinEditorPage = null;
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        shell.setPage(new ProfilePage(this, app), Shell.Target.PROFILE);
    }

    /** Открывает встроенный редактор нового или существующего скина. */
    void openSkinEditor(Account account, Path existing, BufferedImage image, String name) {
        if (account == null) return;
        dropNews();
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        skinEditorPage = new SkinEditorPage(this, app, account, existing, image, name);
        shell.setPage(skinEditorPage, Shell.Target.PROFILE);
        skinEditorPage.requestFocusInWindow();
    }

    void closeSkinEditor() {
        skinEditorPage = null;
        openProfile();
    }

    /** Выбор версии — страница оболочки, а не отдельное окно поверх главной. */
    void openVersions() {
        skinEditorPage = null;
        dropNews();
        if (settingsPage != null) {
            settingsPage.dispose();
            settingsPage = null;
        }
        shell.setPage(new VersionsPage(this, app, this::syncFromState), Shell.Target.VERSIONS);
    }

    void openGameFolder() {
        openFolder(Paths.gameDir());
    }

    // ------------------------------------------------------------------ окно

    /** Полноэкранный режим лаунчера — отдельная настройка от полного экрана Minecraft. */
    void toggleFullscreen() {
        app.cfg.launcherFullscreen = !app.cfg.launcherFullscreen;
        SettingsManager.save();
        applyFullscreen();
    }

    void applyFullscreen() {
        boolean full = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        if (app.cfg.launcherFullscreen && !full) {
            normalBounds = getBounds();
            setExtendedState(getExtendedState() | MAXIMIZED_BOTH);
        } else if (!app.cfg.launcherFullscreen && full) {
            setExtendedState(NORMAL);
            if (normalBounds != null) setBounds(normalBounds);
        }
    }

    private void frameMouse(AWTEvent event) {
        if (!(event instanceof MouseEvent e) || e.getComponent() == null) return;
        if (SwingUtilities.getWindowAncestor(e.getComponent()) != this && e.getComponent() != this) return;
        if (!isShowing()) return;
        Point p = SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), getRootPane());
        boolean full = (getExtendedState() & MAXIMIZED_BOTH) == MAXIMIZED_BOTH;
        switch (e.getID()) {
            case MouseEvent.MOUSE_MOVED -> {
                int mode = full ? 0 : edge(p);
                if (mode != 0) getRootPane().setCursor(cursorFor(mode));
                else if (getRootPane().getCursor().getType() != Cursor.DEFAULT_CURSOR) getRootPane().setCursor(null);
            }
            case MouseEvent.MOUSE_PRESSED -> {
                if (e.getButton() != MouseEvent.BUTTON1) return;
                int mode = full ? 0 : edge(p);
                Component c = e.getComponent();
                boolean header = p.y < SettingsKit.s(110) && draggable(c);
                if (mode == 0 && !header) {
                    dragMode = -1;
                    return;
                }
                dragMode = mode;
                press = e.getLocationOnScreen();
                origin = getBounds();
                if (header && e.getClickCount() == 2 && mode == 0) {
                    dragMode = -1;
                    toggleFullscreen();
                }
            }
            case MouseEvent.MOUSE_DRAGGED -> {
                if (dragMode < 0 || press == null || full) return;
                Point now = e.getLocationOnScreen();
                int dx = now.x - press.x;
                int dy = now.y - press.y;
                if (dragMode == 0) {
                    setLocation(origin.x + dx, origin.y + dy);
                    return;
                }
                Rectangle r = new Rectangle(origin);
                Dimension min = getMinimumSize();
                if ((dragMode & 1) != 0 && r.height - dy >= min.height) {
                    r.y += dy;
                    r.height -= dy;
                }
                if ((dragMode & 2) != 0) r.height = Math.max(min.height, r.height + dy);
                if ((dragMode & 4) != 0 && r.width - dx >= min.width) {
                    r.x += dx;
                    r.width -= dx;
                }
                if ((dragMode & 8) != 0) r.width = Math.max(min.width, r.width + dx);
                setBounds(r);
            }
            case MouseEvent.MOUSE_RELEASED -> {
                if (dragMode >= 0) normalBounds = getBounds();
                dragMode = -1;
                press = null;
            }
            default -> {
            }
        }
    }

    /** Окно тянут за пустое место шапки, а не за кнопки и поля. */
    private static boolean draggable(Component c) {
        return !(c instanceof AbstractButton) && !(c instanceof javax.swing.text.JTextComponent)
                && !(c instanceof SettingsKit.Toggle) && !(c instanceof SettingsKit.Dropdown)
                && !(c instanceof SettingsKit.Slider) && !(c instanceof JScrollBar)
                && (c.getCursor() == null || c.getCursor().getType() != Cursor.HAND_CURSOR);
    }

    private int edge(Point p) {
        int w = getWidth();
        int h = getHeight();
        int mode = 0;
        if (p.y <= EDGE) mode |= 1;
        if (p.y >= h - EDGE) mode |= 2;
        if (p.x <= EDGE) mode |= 4;
        if (p.x >= w - EDGE) mode |= 8;
        return mode;
    }

    private static Cursor cursorFor(int mode) {
        return switch (mode) {
            case 1, 2 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
            case 4, 8 -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
            case 1 | 4, 2 | 8 -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
            case 1 | 8, 2 | 4 -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
            default -> Cursor.getDefaultCursor();
        };
    }

    // ------------------------------------------------------------------ состояние

    /**
     * Диск читается не чаще раза в секунду и в фоне: проверка файлов в потоке интерфейса
     * подвешивала окно. Только самое первое чтение синхронное, чтобы кнопка сразу была верной.
     */
    private void readDisk(GameState st) {
        long now = System.currentTimeMillis();
        if (diskReading || (diskAt != 0 && st == diskState && now - diskAt < 1200)) return;
        boolean first = diskAt == 0;
        diskAt = now;
        diskState = st;
        String version = app.cfg.minecraftVersion;
        String instance = app.cfg.instanceId();
        if (first) {
            applyDisk(app.mc().versionReady(version), app.fabric().installedLoader(version), installedMods(instance));
            return;
        }
        diskReading = true;
        app.io().submit(() -> {
            try {
                boolean ready = app.mc().versionReady(version);
                String fabric = app.fabric().installedLoader(version);
                String mods = installedMods(instance);
                SwingUtilities.invokeLater(() -> {
                    applyDisk(ready, fabric, mods);
                    diskReading = false;
                });
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> diskReading = false);
            }
        });
    }

    private String installedMods(String instance) {
        try {
            return app.mods().installedBuildVersion(instance);
        } catch (Exception e) {
            return null;
        }
    }

    private void applyDisk(boolean ready, String fabric, String mods) {
        diskMcReady = ready;
        diskFabric = fabric == null ? "" : fabric;
        diskMods = mods;
    }

    public void syncFromState() {
        if (shell == null || home == null) return;
        GameState st = app.state().get();
        if (st.isBusy()) {
            if (operationWindow == null || !operationWindow.isDisplayable()) operationWindow = new OperationWindow(this, app);
            operationWindow.refresh();
            if (!operationWindow.isVisible() && isShowing()) operationWindow.setVisible(true);
        } else if (operationWindow != null && operationWindow.isVisible()) {
            operationWindow.dispose();
            operationWindow = null;
        }
        readDisk(st);
        boolean busy = st.isBusy();
        boolean devWindows = app.cfg.developerMode && app.launch.aliveCount(app.cfg.minecraftVersion) > 0;
        String mainText = labelFor(st);
        if (st == GameState.UPDATE_AVAILABLE && app.pendingGameUpdate()) mainText = Lang.t("update_mc");
        else if (st == GameState.UPDATE_AVAILABLE && modsUpdateButton()) mainText = Lang.t("update_mods");
        else if (devWindows && st == GameState.RUNNING) mainText = Tr.t("Запустить ещё", "Launch another");
        boolean locked = st == GameState.STARTING || (st == GameState.RUNNING && !app.cfg.developerMode);
        boolean running = st == GameState.RUNNING || devWindows;
        boolean fabricOn = app.cfg.commonWorld || app.cfg.fabricFor(app.cfg.minecraftVersion);
        home.state(mainText, !busy && !locked, busy, running, fabricOn);
        boolean explain = st == GameState.ERROR || st == GameState.NOT_INSTALLED || st == GameState.UPDATE_AVAILABLE
                || st == GameState.OFFLINE;
        home.reason(explain ? app.state().detail() : "", st == GameState.ERROR);
        home.refresh();
        shell.refresh();
    }

    private boolean modsNeedUpdate() {
        return app.cfg.commonWorldMods() && app.cfg.remoteBuildVersion != null
                && UpdateChecker.buildUpdateNeeded(
                app.mods().installedBuildVersion(app.cfg.instanceId()),
                app.cfg.remoteBuildVersion);
    }

    private boolean modsUpdateButton() {
        // Версия сборки берётся из фонового чтения диска, а не из файла на каждом тике.
        boolean need = app.cfg.commonWorldMods() && app.cfg.remoteBuildVersion != null
                && UpdateChecker.buildUpdateNeeded(diskMods, app.cfg.remoteBuildVersion);
        if (!need) return false;
        boolean game = !diskMcReady
                || ("fabric".equalsIgnoreCase(app.cfg.loader) && diskFabric.isBlank());
        return !game;
    }

    /** Язык меняется пересборкой интерфейса: все подписи берутся заново. */
    public void applyLanguage() {
        int section = settingsPage != null ? settingsPage.sectionIndex() : -1;
        rebuild(section);
    }

    private static String labelFor(GameState st) {
        return switch (st) {
            case READY, OFFLINE -> Lang.t("launch");
            case NOT_INSTALLED -> Lang.t("install");
            case UPDATE_AVAILABLE -> Lang.t("update_btn");
            case ERROR -> Lang.t("check");
            case CHECKING -> Lang.t("checking");
            case STARTING -> Lang.t("starting");
            case RUNNING -> Lang.t("running");
            default -> Lang.t("go");
        };
    }

    void onMain() {
        GameState st = app.state().get();
        if (st.isBusy()) {
            if (Dialogs.ask(this, Tr.t("Отменить текущую операцию? Незавершённый файл будет удалён.",
                            "Cancel the current operation? The unfinished file will be removed."),
                    Tr.t("Отменить", "Cancel it"), Tr.t("Продолжить", "Continue"))) {
                app.cancelCurrent();
            }
            return;
        }
        switch (st) {
            case READY, OFFLINE, RUNNING -> {
                if (st == GameState.RUNNING && !app.cfg.developerMode) return;
                if (!requireAccount()) return;
                if (app.cfg.developerMode) {
                    boolean askNick = app.launch.aliveCount(app.cfg.minecraftVersion) > 0 || st == GameState.RUNNING;
                    DevLaunchDialog.Choice choice = DevLaunchDialog.ask(this, app.cfg, askNick);
                    if (choice == null) return;
                    app.launchGame(choice.dir, choice.nick);
                } else {
                    app.launchGame();
                    applyLaunchWindowBehavior();
                }
            }
            case NOT_INSTALLED -> {
                if (!requireAccount()) return;
                while (true) {
                    java.nio.file.Path dir = Dialogs.chooseInstallRoot(this, app.cfg.minecraftVersion);
                    if (dir == null) return;
                    if (Utils.writable(dir)) {
                        app.rememberVersionDir(app.cfg.minecraftVersion, dir);
                        app.saveSettings();
                        app.install();
                        return;
                    }
                    Dialogs.info(this, Utils.writeDeniedMessage(dir));
                }
            }
            case UPDATE_AVAILABLE -> {
                if (!requireAccount()) return;
                if (app.pendingGameUpdate()) app.updateMinecraftAndLaunch();
                else if (modsNeedUpdate()) app.updateModsAndLaunch();
                else {
                    app.launchGame();
                    applyLaunchWindowBehavior();
                }
            }
            case ERROR -> {
                Operation op = app.verifyOperation();
                op.onError(t -> SwingUtilities.invokeLater(() -> {
                    if (Dialogs.ask(this, Operation.friendly(t) + "\n" + Tr.t("Восстановить сборку?", "Repair the game?"),
                            Tr.t("Восстановить", "Repair"), Tr.t("Закрыть", "Close"))) {
                        Motion.present(new RepairWindow(this, app));
                    }
                }));
                if (!app.submit("Проверка сборки", op)) {
                    Dialogs.info(this, Tr.t("Сейчас уже выполняется другая операция.", "Another operation is running."));
                }
            }
            default -> {
            }
        }
    }

    /** «Скрывать лаунчер» сворачивает окно после запуска игры. */
    private void applyLaunchWindowBehavior() {
        if ("hide".equals(app.cfg.launchBehavior)) setExtendedState(ICONIFIED);
    }

    private boolean requireAccount() {
        if (app.account() != null) return true;
        Dialogs.info(this, Lang.t("prof_need"));
        openProfile();
        return false;
    }

    void copyServerAddress() {
        String ip = Utils.isBlank(app.cfg.serverIp) ? "" : app.cfg.serverIp.trim();
        if (ip.isBlank()) return;
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(ip), null);
    }

    void openUrl(String url) {
        if (!Utils.openUrl(url)) Dialogs.error(this, Tr.t("Не удалось открыть ссылку в браузере.",
                "Could not open the link in a browser."), url, null);
    }

    void openFolder(java.nio.file.Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            Log.warn("Папка: " + Log.reason(e));
        }
        if (!Utils.openFolder(dir)) Dialogs.error(this, Tr.t("Не удалось открыть папку.", "Could not open the folder."),
                String.valueOf(dir), null);
    }

    public void quit() {
        if (app.isBusy() && !Dialogs.ask(this, Tr.t("Идёт операция. Выйти и прервать её?",
                "An operation is running. Quit and stop it?"), Tr.t("Выйти", "Quit"), Tr.t("Отмена", "Cancel"))) return;
        Toolkit.getDefaultToolkit().removeAWTEventListener(frameMouse);
        app.shutdown();
        dispose();
        System.exit(0);
    }

    /**
     * Перезапуск: новый процесс ждёт, пока этот завершится и отпустит блокировку единственного экземпляра.
     */
    void restartLauncher() {
        if (app.isBusy() && !Dialogs.ask(this, Tr.t("Идёт операция. Перезапустить и прервать её?",
                "An operation is running. Restart and stop it?"), Tr.t("Перезапустить", "Restart"), Tr.t("Отмена", "Cancel"))) return;
        try {
            List<String> cmd = new ArrayList<>();
            String exe = SettingsManager.executablePath();
            if (exe != null) {
                cmd.add(exe);
            } else {
                ProcessHandle.Info info = ProcessHandle.current().info();
                cmd.add(info.command().orElseThrow());
                info.arguments().ifPresent(a -> cmd.addAll(List.of(a)));
            }
            cmd.removeIf(a -> a.startsWith("--wait-pid"));
            cmd.add("--wait-pid=" + ProcessHandle.current().pid());
            new ProcessBuilder(cmd).start();
            Log.info("Перезапуск лаунчера: " + cmd.get(0));
        } catch (Exception e) {
            Dialogs.error(this, Tr.t("Не удалось перезапустить лаунчер.", "Could not restart the launcher."),
                    Log.reason(e), null);
            return;
        }
        Toolkit.getDefaultToolkit().removeAWTEventListener(frameMouse);
        app.shutdown();
        dispose();
        System.exit(0);
    }

    public void showStartup() {
        setVisible(true);
        applyFullscreen();
        toFront();
        requestFocus();
        Utils.nameOnTaskbar(this);
        syncFromState();
        repaint();
        Log.info("Главное окно: " + getWidth() + "x" + getHeight());
    }

    public LauncherApp app() {
        return app;
    }
}
