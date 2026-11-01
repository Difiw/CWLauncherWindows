import ru.cw.launcher.net.Http;
import ru.cw.launcher.shell.BuildStore;
import ru.cw.launcher.shell.ShellPrefs;
import ru.cw.launcher.shell.ui.ShellWindow;
import ru.cw.launcher.util.Paths;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/** Снимок страницы нового интерфейса: java -cp build ShellShot <страница> <файл.png> [секунд ожидания]. */
public final class ShellShot {
    public static void main(String[] args) throws Exception {
        String page = args.length > 0 ? args[0] : "create";
        File out = new File(args.length > 1 ? args[1] : "shot.png");
        int wait = args.length > 2 ? Integer.parseInt(args[2]) : 6;
        Http.init(Paths.cache(), 12_000, 40_000);
        Path tmp = Files.createTempDirectory("cw-shot");
        BuildStore store = new BuildStore(tmp.resolve("builds"));
        ShellPrefs prefs = new ShellPrefs(tmp.resolve("shell.json"));
        JFrame[] frame = new JFrame[1];
        ShellWindow[] win = new ShellWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            frame[0] = new JFrame("shot");
            win[0] = new ShellWindow(store, prefs, new ru.cw.launcher.shell.CoreCatalog() {
                @Override
                public java.util.List<String> minecraftVersions() {
                    return java.util.List.of("1.21.4", "1.20.1", "1.19.2");
                }

                @Override
                public java.util.List<String> coreVersions(String loader, String mc) {
                    return "Rift".equals(loader) ? java.util.List.of() : java.util.List.of("47.3.0", "47.2.0");
                }
            }, new ru.cw.launcher.shell.ContentSource() {
                @Override
                public Page search(Provider p, Kind k, Sort s, String q, int page) {
                    String[][] d = {{"JEI (Just Enough Items)", "mezz", "Просмотр рецептов, предметов и их использования."},
                            {"JourneyMap", "techbrew", "Карта мира в реальном времени."},
                            {"Create", "simibubi", "Механизмы, автоматизация и инженерия."},
                            {"Biomes O' Plenty", "Forstride", "Больше биомов, растений и разнообразия мира."},
                            {"Sodium", "jellysquid3", "Оптимизация рендера."}};
                    java.util.List<Item> items = new java.util.ArrayList<>();
                    for (String[] r : d) items.add(new Item(r[0], r[0], r[1], r[2], "", 297_400_000L, 4_500_000L));
                    return new Page(items, 1000, 20);
                }
            });
            frame[0].setContentPane(win[0]);
            frame[0].setSize(1280, 760);
            frame[0].setLocation(-3000, 0);
            win[0].show(page);
            frame[0].setVisible(true);
        });
        Thread.sleep(wait * 1000L);
        SwingUtilities.invokeAndWait(() -> {
            Component c = frame[0].getContentPane();
            BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            c.paint(g);
            g.dispose();
            try {
                ImageIO.write(img, "png", out);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
        });
        System.out.println("saved " + out.getAbsolutePath());
        System.exit(0);
    }
}
