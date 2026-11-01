import ru.cw.launcher.shell.BuildSpec;
import ru.cw.launcher.shell.BuildStore;
import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.CoreCatalog;
import ru.cw.launcher.shell.ShellPrefs;
import ru.cw.launcher.shell.ui.CreateBuildPage;
import ru.cw.launcher.shell.ui.Kit;
import ru.cw.launcher.shell.ui.ShellWindow;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Сквозная проверка экрана: ввод в форму, смена ядра, нажатие «Создать сборку», список сборок. */
public final class ShellFlow {
    static int failed;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok:   " : "FAIL: ") + what);
        if (!ok) failed++;
    }

    static <T extends Component> void collect(Container c, Class<T> type, List<T> out) {
        for (Component k : c.getComponents()) {
            if (type.isInstance(k)) out.add(type.cast(k));
            if (k instanceof Container cc) collect(cc, type, out);
        }
    }

    static void click(Component c) {
        long t = System.currentTimeMillis();
        for (int id : new int[]{MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED}) {
            c.dispatchEvent(new MouseEvent(c, id, t, 0, 5, 5, 1, false, MouseEvent.BUTTON1));
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("cw-flow");
        BuildStore store = new BuildStore(tmp.resolve("builds"));
        ShellPrefs prefs = new ShellPrefs(tmp.resolve("shell.json"));
        List<String> asked = new ArrayList<>();
        CoreCatalog cat = new CoreCatalog() {
            @Override
            public List<String> minecraftVersions() {
                return List.of("1.21.4", "1.20.1");
            }

            @Override
            public List<String> coreVersions(String loader, String mc) {
                asked.add(loader + "@" + mc);
                return "Rift".equals(loader) ? List.of() : List.of(loader + "-9.9", loader + "-9.8");
            }
        };
        ContentSource src = new ContentSource() {
            @Override
            public Page search(Provider p, Kind k, Sort s, String q, int page) {
                return new Page(List.of(new Item("id1", "Mod One", "me", "sum", "", 10, 1)), 1, 20);
            }
        };
        ShellWindow[] w = new ShellWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame();
            w[0] = new ShellWindow(store, prefs, cat, src);
            f.setContentPane(w[0]);
            f.setSize(1280, 760);
            f.setLocation(-3000, 0);
            w[0].show("create");
            f.setVisible(true);
        });
        Thread.sleep(800);
        SwingUtilities.invokeAndWait(() -> {
            List<CreateBuildPage> pages = new ArrayList<>();
            collect(w[0], CreateBuildPage.class, pages);
            check(pages.size() == 1, "страница создания собрана");
            CreateBuildPage page = pages.get(0);
            List<Kit.Dropdown> dd = new ArrayList<>();
            collect(page, Kit.Dropdown.class, dd);
            // порядок в форме: Minecraft, загрузчик, версия ядра, затем список источников справа
            Kit.Dropdown mc = dd.get(0), loader = dd.get(1), core = dd.get(2);
            check(mc.selected().equals("1.20.1"), "версия Minecraft подставлена из каталога");
            check(core.selected().equals("Forge-9.9"), "версия ядра подгрузилась: " + core.selected());
            loader.choose("Fabric");
            check(page.spec().loader.equals("Fabric"), "выбор загрузчика попал в сборку");
            mc.choose("1.21.4");
            check(page.spec().mcVersion.equals("1.21.4"), "выбор версии игры попал в сборку");
            loader.choose("Rift");
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> {
            List<CreateBuildPage> pages = new ArrayList<>();
            collect(w[0], CreateBuildPage.class, pages);
            CreateBuildPage page = pages.get(0);
            List<Kit.Dropdown> dd = new ArrayList<>();
            collect(page, Kit.Dropdown.class, dd);
            check(dd.get(2).selected().equals("нет версий"), "для Rift на 1.21.4 версий ядра нет");
            check(asked.contains("Fabric@1.21.4") && asked.contains("Rift@1.21.4"), "ядра запрашивались при смене: " + asked);
            dd.get(1).choose("Fabric");
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(() -> {
            List<CreateBuildPage> pages = new ArrayList<>();
            collect(w[0], CreateBuildPage.class, pages);
            CreateBuildPage page = pages.get(0);
            // пустое название → ошибка, сборка не создаётся
            page.setBuildName("   ");
            List<Kit.Btn> btns = new ArrayList<>();
            collect(page, Kit.Btn.class, btns);
            Kit.Btn create = btns.get(btns.size() - 1);
            click(create);
            check(store.list().isEmpty(), "пустое название: сборка не создана");
            check(w[0].currentPage().equals("create"), "пустое название: остаёмся на форме");
            page.setBuildName("Тестовая сборка");
            click(create);
        });
        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> {
            List<BuildSpec> all = store.list();
            check(all.size() == 1, "сборка создана и записана на диск");
            if (!all.isEmpty()) {
                BuildSpec b = all.get(0);
                check(b.name.equals("Тестовая сборка") && b.loader.equals("Fabric") && b.mcVersion.equals("1.21.4")
                        && b.loaderVersion.equals("Fabric-9.9"), "в build.json верные ядро и версии: " + b.loader + " " + b.loaderVersion);
                check(b.ramMb == 4096, "память 4 ГБ");
                check(Files.isDirectory(store.dir(b.id).resolve("mods")), "папка mods создана");
            }
            check(w[0].currentPage().equals("builds"), "после создания открыт список сборок");
        });
        System.out.println(failed == 0 ? "ВСЕ ПРОВЕРКИ ПРОШЛИ" : "ПРОВАЛЕНО: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
