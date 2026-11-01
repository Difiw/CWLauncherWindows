import ru.cw.launcher.shell.BuildSpec;
import ru.cw.launcher.shell.BuildStore;
import ru.cw.launcher.shell.ContentSource;
import ru.cw.launcher.shell.CoreCatalog;
import ru.cw.launcher.shell.ModInstaller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Проверки нового слоя сборок без сети: java -cp build ShellCheck. */
public final class ShellCheck {

    private static int failed;

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("FAIL: " + what);
        } else {
            System.out.println("ok:   " + what);
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("cw-shell-check");
        BuildStore store = new BuildStore(tmp);

        BuildSpec s = new BuildSpec();
        s.name = "  Моя сборка  ";
        s.mcVersion = "1.20.1";
        s.loader = "Fabric";
        s.loaderVersion = "0.16.0";
        s.kind = BuildSpec.Kind.MODPACK;
        s.description = "тест";
        store.create(s);
        check("Моя-сборка".equals(s.id), "id из названия: " + s.id);
        check(Files.isDirectory(tmp.resolve(s.id).resolve("mods")), "создана папка mods");
        check(Files.isRegularFile(tmp.resolve(s.id).resolve("build.json")), "записан build.json");

        BuildSpec dup = new BuildSpec();
        dup.name = "Моя сборка";
        store.create(dup);
        check("Моя-сборка-2".equals(dup.id), "дубликат получает суффикс: " + dup.id);

        List<BuildSpec> list = store.list();
        check(list.size() == 2, "список содержит обе сборки");
        BuildSpec back = list.stream().filter(b -> b.id.equals(s.id)).findFirst().orElseThrow();
        check(back.loader.equals("Fabric") && back.loaderVersion.equals("0.16.0") && back.description.equals("тест"),
                "поля читаются обратно");

        BuildSpec bad = new BuildSpec();
        bad.name = "   ";
        check(bad.validate() != null, "пустое название отклоняется");
        bad.name = "x";
        bad.ramMb = 256;
        check(bad.validate() != null, "мало памяти отклоняется");
        try {
            store.create(bad);
            check(false, "create бросает на неверных данных");
        } catch (java.io.IOException e) {
            check(true, "create бросает на неверных данных");
        }

        BuildSpec van = new BuildSpec();
        van.name = "Ваниль";
        van.kind = BuildSpec.Kind.VANILLA;
        store.create(van);
        check(!Files.exists(tmp.resolve(van.id).resolve("mods")), "у ванильной сборки нет mods");

        check("build".equals(BuildStore.slug("../..")) || !BuildStore.slug("../..").contains("."),
                "slug убирает точки пути: " + BuildStore.slug("../.."));
        check(!BuildStore.slug("a/b\\c:d").matches(".*[/\\\\:].*"), "slug убирает разделители");
        check("CON_".equals(BuildStore.slug("CON")), "slug обходит имя CON");

        store.delete(dup.id);
        check(store.list().size() == 2 && !Files.exists(tmp.resolve(dup.id)), "удаление сборки");
        store.delete("../");
        check(Files.isDirectory(tmp), "delete не выходит из корня");

        // каталог версий
        check(CoreCatalog.parseMinecraft("{\"versions\":[{\"id\":\"1.21\",\"type\":\"release\"},"
                + "{\"id\":\"24w01a\",\"type\":\"snapshot\"},{\"id\":\"1.20.1\",\"type\":\"release\"}]}")
                .equals(List.of("1.21", "1.20.1")), "версии Minecraft: только релизы");
        check(CoreCatalog.parseLoaderMeta("[{\"loader\":{\"version\":\"0.16.9\"}},{\"loader\":{\"version\":\"0.16.8\"}}]")
                .equals(List.of("0.16.9", "0.16.8")), "версии Fabric/Quilt");
        List<String> forge = CoreCatalog.forMinecraft(CoreCatalog.parseMavenVersions(
                "<versions><version>1.20.1-47.2.0</version><version>1.20.1-47.3.0</version>"
                        + "<version>1.19.2-43.0.0</version></versions>"), "1.20.1");
        check(forge.equals(List.of("47.3.0", "47.2.0")), "Forge: свежая версия первой " + forge);
        List<String> neo = CoreCatalog.neoForMinecraft(List.of("20.4.1", "21.1.5", "21.1.77", "21.0.9"), "1.21.1");
        check(neo.equals(List.of("21.1.77", "21.1.5")), "NeoForge для 1.21.1: " + neo);
        check(CoreCatalog.neoForMinecraft(List.of("21.0.9"), "1.21").equals(List.of("21.0.9")), "NeoForge для 1.21");
        check(CoreCatalog.staticVersions("Rift", "1.13.2").size() == 1 && CoreCatalog.staticVersions("Rift", "1.20.1").isEmpty(),
                "Rift только 1.13.2");
        check(CoreCatalog.staticVersions("LiteLoader", "1.12.2").size() == 1
                && CoreCatalog.staticVersions("LiteLoader", "1.20.1").isEmpty(), "LiteLoader только старые версии");

        // каталог модов
        ContentSource.Page mp = ContentSource.parseModrinth("{\"total_hits\":45,\"hits\":[{\"project_id\":\"P1\","
                + "\"title\":\"JEI\",\"author\":\"mezz\",\"description\":\"Items\",\"icon_url\":\"http://x/i.png\","
                + "\"downloads\":297400000,\"follows\":4500000}]}");
        check(mp.items().size() == 1 && mp.items().get(0).title().equals("JEI") && mp.pages() == 3,
                "разбор поиска Modrinth, страниц: " + mp.pages());
        check("297.4M".equals(ContentSource.compact(297_400_000)) && "4.5M".equals(ContentSource.compact(4_500_000))
                && "12".equals(ContentSource.compact(12)) && "2K".equals(ContentSource.compact(2000)), "сокращение чисел");
        String url = ContentSource.modrinthUrl(ContentSource.Kind.MOD, ContentSource.Sort.NEW, "создать & мод", 3);
        check(url.contains("offset=40") && url.contains("query=") && !url.contains(" ") && url.contains("relevance"),
                "URL Modrinth: страница 3 → offset 40, запрос закодирован");
        ContentSource.Page cp = ContentSource.parseCurse("{\"data\":[{\"id\":238222,\"name\":\"JEI\",\"summary\":\"s\","
                + "\"downloadCount\":5,\"thumbsUpCount\":1,\"authors\":[{\"name\":\"mezz\"}],"
                + "\"logo\":{\"thumbnailUrl\":\"http://c/i.png\"}}],\"pagination\":{\"totalCount\":100}}");
        check(cp.items().get(0).author().equals("mezz") && cp.items().get(0).id().equals("238222") && cp.pages() == 5,
                "разбор поиска CurseForge");

        // подбор файла для установки
        ModInstaller.Target t = ModInstaller.parseModrinthVersions("[{\"files\":[{\"url\":\"http://a/x-sources.jar\","
                + "\"filename\":\"x-sources.jar\",\"primary\":false,\"size\":1,\"hashes\":{\"sha1\":\"aa\"}},"
                + "{\"url\":\"http://a/x.jar\",\"filename\":\"x.jar\",\"primary\":true,\"size\":2,"
                + "\"hashes\":{\"sha1\":\"bb\"}}]}]");
        check(t != null && t.fileName().equals("x.jar") && "bb".equals(t.sha1()), "выбран основной файл версии");
        check(ModInstaller.parseModrinthVersions("[]") == null, "нет версий → null");
        check("evil.jar".equals(ModInstaller.safeFile("../../evil.jar")) && "a_b.jar".equals(ModInstaller.safeFile("a:b.jar")),
                "safeFile отрезает путь");

        System.out.println(failed == 0 ? "ВСЕ ПРОВЕРКИ ПРОШЛИ" : "ПРОВАЛЕНО: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
