package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.shell.ContentSource.Item;
import ru.cw.launcher.shell.ContentSource.Provider;
import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Скачивание готовой сборки с Modrinth (.mrpack) или CurseForge (manifest.json) и превращение её в сборку лаунчера:
 * версия игры и ядро берутся из самой сборки, моды и файлы настроек раскладываются по папкам.
 */
public final class ModpackInstaller {

    public interface Listener {
        /** Сборка создана (папка и build.json есть), дальше идёт скачивание файлов. */
        void created(BuildSpec spec);

        void progress(BuildSpec spec, String text);
    }

    private ModpackInstaller() {
    }

    /** Возвращает список файлов, которые не скачались, в виде «имя: причина». Пустой список — всё хорошо. */
    public static List<String> install(BuildStore store, Provider provider, Item item, Listener listener)
            throws IOException, InterruptedException {
        Path tmpDir = Files.createTempDirectory("cwpack");
        try {
            Path pack = tmpDir.resolve("pack.zip");
            switch (provider) {
                case MODRINTH -> downloadModrinthPack(item, pack);
                case CURSEFORGE -> downloadCursePack(item, pack);
                default -> throw new IOException("Эту сборку нужно скачать на сайте проекта");
            }
            return installFile(store, pack, item.title(), listener);
        } finally {
            try (var walk = Files.walk(tmpDir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // временная папка уберётся системой
            }
        }
    }

    /** Установка из уже скачанного архива: .mrpack или CurseForge zip. */
    public static List<String> installFile(BuildStore store, Path pack, String fallbackName, Listener listener)
            throws IOException, InterruptedException {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            if (zip.getEntry("modrinth.index.json") != null) return fromModrinth(store, zip, fallbackName, listener);
            if (zip.getEntry("manifest.json") != null) return fromCurse(store, zip, fallbackName, listener);
        }
        throw new IOException("Это не сборка Modrinth или CurseForge");
    }

    // ---- скачивание самого архива ----

    private static void downloadModrinthPack(Item item, Path dest) throws IOException, InterruptedException {
        String body = Http.get("https://api.modrinth.com/v2/project/" + enc(item.id()) + "/version", null, 12, 2).body();
        for (Object v : Json.parseArray(body)) {
            if (!(v instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> version = (Map<String, Object>) v;
            Map<String, Object> best = null;
            for (Object f : Json.arr(version, "files")) {
                if (!(f instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> file = (Map<String, Object>) f;
                if (!Json.str(file, "filename", "").toLowerCase().endsWith(".mrpack")) continue;
                if (best == null || Json.bool(file, "primary", false)) best = file;
            }
            if (best == null) continue;
            Map<String, Object> hashes = Json.obj(best, "hashes");
            Http.download(Json.str(best, "url", ""), dest, hashes == null ? null : Json.str(hashes, "sha1", null),
                    Json.lng(best, "size", 0), null, null);
            return;
        }
        throw new IOException("У сборки нет файла .mrpack");
    }

    private static void downloadCursePack(Item item, Path dest) throws IOException, InterruptedException {
        String key = ContentSource.curseKey();
        if (key.isEmpty()) throw new ContentSource.MissingKey();
        String body = ContentSource.curseGet("https://api.curseforge.com/v1/mods/" + enc(item.id()) + "/files?pageSize=1", key);
        for (Object o : Json.arr(Json.parseObject(body), "data")) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) o;
            String url = Json.str(f, "downloadUrl", "");
            if (url.isBlank()) throw new IOException("автор запретил скачивание вне CurseForge — скачайте сборку на сайте");
            Http.download(url, dest, null, Json.lng(f, "fileLength", 0), null, null);
            return;
        }
        throw new IOException("У сборки нет файлов");
    }

    // ---- Modrinth ----

    private static List<String> fromModrinth(BuildStore store, ZipFile zip, String fallbackName, Listener l)
            throws IOException, InterruptedException {
        Map<String, Object> idx = Json.parseObject(read(zip, "modrinth.index.json"));
        BuildSpec spec = new BuildSpec();
        spec.name = limit(Json.str(idx, "name", fallbackName), 60);
        spec.description = limit(Json.str(idx, "summary", ""), 500);
        Map<String, Object> deps = Json.obj(idx, "dependencies");
        spec.mcVersion = deps == null ? "1.20.1" : Json.str(deps, "minecraft", "1.20.1");
        spec.loader = "Vanilla";
        if (deps != null) {
            String[][] known = {{"fabric-loader", "Fabric"}, {"quilt-loader", "Quilt"}, {"neoforge", "NeoForge"}, {"forge", "Forge"}};
            for (String[] k : known) {
                if (deps.containsKey(k[0])) {
                    spec.loader = k[1];
                    spec.loaderVersion = String.valueOf(deps.get(k[0]));
                    break;
                }
            }
        }
        store.create(spec);
        l.created(spec);
        Path dir = store.dir(spec.id);
        extractOverrides(zip, dir, "overrides/", "client-overrides/");

        List<Object> files = Json.arr(idx, "files");
        List<String> failed = new ArrayList<>();
        int n = files == null ? 0 : files.size();
        int i = 0;
        if (files != null) for (Object o : files) {
            i++;
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) o;
            String rel = Json.str(f, "path", "");
            Map<String, Object> env = Json.obj(f, "env");
            if (env != null && "unsupported".equals(Json.str(env, "client", "required"))) continue;
            l.progress(spec, "Скачивание файлов: " + i + "/" + n);
            try {
                Path dest = safeResolve(dir, rel);
                List<Object> urls = Json.arr(f, "downloads");
                if (urls == null || urls.isEmpty()) throw new IOException("нет ссылки");
                Map<String, Object> hashes = Json.obj(f, "hashes");
                Http.download(String.valueOf(urls.get(0)), dest, hashes == null ? null : Json.str(hashes, "sha1", null),
                        Json.lng(f, "fileSize", 0), null, null);
            } catch (IOException | RuntimeException e) {
                failed.add(rel + ": " + e.getMessage());
            }
        }
        return failed;
    }

    // ---- CurseForge ----

    private static List<String> fromCurse(BuildStore store, ZipFile zip, String fallbackName, Listener l)
            throws IOException, InterruptedException {
        Map<String, Object> mf = Json.parseObject(read(zip, "manifest.json"));
        BuildSpec spec = new BuildSpec();
        spec.name = limit(Json.str(mf, "name", fallbackName), 60);
        Map<String, Object> mc = Json.obj(mf, "minecraft");
        spec.mcVersion = mc == null ? "1.20.1" : Json.str(mc, "version", "1.20.1");
        spec.loader = "Vanilla";
        List<Object> loaders = mc == null ? null : Json.arr(mc, "modLoaders");
        if (loaders != null && !loaders.isEmpty() && loaders.get(0) instanceof Map<?, ?> first) {
            String id = String.valueOf(first.get("id"));
            int dash = id.indexOf('-');
            String name = dash > 0 ? id.substring(0, dash) : id;
            spec.loaderVersion = dash > 0 ? id.substring(dash + 1) : "";
            spec.loader = switch (name.toLowerCase()) {
                case "fabric" -> "Fabric";
                case "quilt" -> "Quilt";
                case "neoforge" -> "NeoForge";
                case "forge" -> "Forge";
                default -> "Vanilla";
            };
        }
        store.create(spec);
        l.created(spec);
        Path dir = store.dir(spec.id);
        extractOverrides(zip, dir, Json.str(mf, "overrides", "overrides") + "/");

        String key = ContentSource.curseKey();
        List<Object> files = Json.arr(mf, "files");
        List<String> failed = new ArrayList<>();
        int n = files == null ? 0 : files.size();
        int i = 0;
        if (files != null) for (Object o : files) {
            i++;
            if (!(o instanceof Map<?, ?> f)) continue;
            String label = "мод " + f.get("projectID");
            l.progress(spec, "Скачивание модов: " + i + "/" + n);
            try {
                if (key.isEmpty()) throw new ContentSource.MissingKey();
                String data = ContentSource.curseGet("https://api.curseforge.com/v1/mods/" + f.get("projectID")
                        + "/files/" + f.get("fileID"), key);
                Map<String, Object> info = Json.obj(Json.parseObject(data), "data");
                if (info == null) throw new IOException("нет данных файла");
                String name = Json.str(info, "fileName", "file.jar");
                label = name;
                String url = Json.str(info, "downloadUrl", "");
                if (url.isBlank()) throw new IOException("автор запретил скачивание вне CurseForge");
                String sub = name.toLowerCase().endsWith(".jar") ? "mods" : "resourcepacks";
                Http.download(url, dir.resolve(sub).resolve(ModInstaller.safeFile(name)), null,
                        Json.lng(info, "fileLength", 0), null, null);
            } catch (IOException | RuntimeException e) {
                failed.add(label + ": " + e.getMessage());
            }
        }
        return failed;
    }

    // ---- общие части ----

    /** Копирует папки overrides сборки в папку игры. Пути проверяются: архив не может писать за пределы папки. */
    private static void extractOverrides(ZipFile zip, Path dir, String... prefixes) throws IOException {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            if (e.isDirectory()) continue;
            for (String prefix : prefixes) {
                if (!e.getName().startsWith(prefix)) continue;
                Path dest;
                try {
                    dest = safeResolve(dir, e.getName().substring(prefix.length()));
                } catch (IOException unsafe) {
                    break; // файл с путём за пределами папки сборки пропускается, остальные ставятся
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(e)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                break;
            }
        }
    }

    static Path safeResolve(Path dir, String rel) throws IOException {
        Path base = dir.toAbsolutePath().normalize();
        Path p = base.resolve(rel.replace('\\', '/')).normalize();
        if (!p.startsWith(base) || p.equals(base)) throw new IOException("недопустимый путь " + rel);
        return p;
    }

    private static String read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String limit(String s, int max) {
        String t = s == null ? "" : s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
