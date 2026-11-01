package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.shell.ui.Kit;
import ru.cw.launcher.shell.ui.ShellWindow;
import ru.cw.launcher.util.Paths;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

/**
 * Запуск нового интерфейса (v3) отдельно от старого окна:
 * {@code java -cp build ru.cw.launcher.shell.ShellMain [--page create]}.
 */
public final class ShellMain {

    private ShellMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        Path root = Paths.root();
        Http.init(Paths.cache(), 12_000, 40_000);
        String page = "home";
        for (int i = 0; i < args.length - 1; i++) if ("--page".equals(args[i])) page = args[i + 1];
        String startPage = page;
        BuildStore store = new BuildStore(root.resolve("builds"));
        ShellPrefs prefs = new ShellPrefs(root.resolve("shell.json"));
        prefs.load();
        SwingUtilities.invokeAndWait(() -> {
            ToolTipManager.sharedInstance().setInitialDelay(300);
            JFrame f = new JFrame("CWLauncher");
            f.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            ShellWindow w = new ShellWindow(store, prefs);
            f.setContentPane(w);
            f.getContentPane().setBackground(Kit.BG);
            f.setMinimumSize(new Dimension(1100, 640));
            f.setSize(1280, 760);
            f.setLocationRelativeTo(null);
            w.show(startPage);
            f.setVisible(true);
        });
    }
}
