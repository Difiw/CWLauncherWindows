package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/** Скачивает выбранные моды/карты в папку сборки: подбирает файл под версию игры и загрузчик. */
public final class ModInstaller {

    /** Проект, отложенный до создания сборки. */
    public record Queued(ContentSource.Provider provider, ContentSource.Kind kind, String id, String title) {
    }

    /** Файл, который надо скачать. */
    public record Target(String url, String fileName, String sha1, long size) {
    }

    private ModInstaller() {
    }

    /** Ставит всё из очереди. Возвращает список неудач в виде «название: причина». */
    public static List<String> install(BuildSpec spec, Path buildDir, List<Queued> queue,
                                       BiConsumer<Integer, String> progress) throws InterruptedException {
        List<String> failed = new java.util.ArrayList<>();
        int done = 0;
        for (Queued q : queue) {
            progress.accept(done, q.title());
            try {
                Target t = q.provider() == ContentSource.Provider.MODRINTH
                        ? modrinthTarget(q, spec)
                        : curseTarget(q, spec);
                if (t == null) throw new IOException("нет файла для " + spec.mcVersion + " / " + spec.loader);
                Path dir = buildDir.resolve(switch (q.kind()) {
                    case MAP -> "saves";
                    case RESOURCEPACK -> "resourcepacks";
                    default -> "mods";
                });
                Path dest = dir.resolve(safeFile(t.fileName()));
                Http.download(t.url(), dest, t.sha1(), t.size(), null, null);
            } catch (IOException | RuntimeException e) {
                failed.add(q.title() + ": " + e.getMessage());
            }
            done++;
        }
        progress.accept(done, "");
        return failed;
    }

    static Target modrinthTarget(Queued q, BuildSpec spec) throws IOException, InterruptedException {
        String loaders = q.kind() == ContentSource.Kind.MOD && spec.usesLoader()
                ? "&loaders=" + enc("[\"" + spec.loader.toLowerCase(Locale.ROOT) + "\"]") : "";
        String url = "https://api.modrinth.com/v2/project/" + enc(q.id()) + "/version?game_versions="
                + enc("[\"" + spec.mcVersion + "\"]") + loaders;
        return parseModrinthVersions(Http.getBody(url));
    }

    /** Первая (самая свежая) версия проекта → её основной файл. */
    public static Target parseModrinthVersions(String json) {
        for (Object v : Json.parseArray(json)) {
            if (!(v instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> ver = (Map<String, Object>) v;
            Map<String, Object> best = null;
            for (Object f : Json.arr(ver, "files")) {
                if (!(f instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> file = (Map<String, Object>) f;
                if (best == null || Json.bool(file, "primary", false)) best = file;
            }
            if (best == null) continue;
            Map<String, Object> hashes = Json.obj(best, "hashes");
            return new Target(Json.str(best, "url", ""), Json.str(best, "filename", "file.jar"),
                    hashes == null ? null : Json.str(hashes, "sha1", null), Json.lng(best, "size", 0));
        }
        return null;
    }

    private static Target curseTarget(Queued q, BuildSpec spec) throws IOException, InterruptedException {
        String key = ContentSource.curseKey();
        if (key.isEmpty()) throw new IOException("нужен ключ CF_API_KEY");
        int type = switch (spec.loader) {
            case "Forge" -> 1;
            case "Fabric" -> 4;
            case "Quilt" -> 5;
            case "NeoForge" -> 6;
            default -> 0;
        };
        String url = "https://api.curseforge.com/v1/mods/" + enc(q.id()) + "/files?pageSize=1&gameVersion="
                + enc(spec.mcVersion) + (type > 0 && q.kind() == ContentSource.Kind.MOD ? "&modLoaderType=" + type : "");
        try {
            java.net.http.HttpResponse<String> r = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("x-api-key", key)
                            .timeout(java.time.Duration.ofSeconds(20)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (r.statusCode() / 100 != 2) throw new IOException("HTTP " + r.statusCode());
            for (Object o : Json.arr(Json.parseObject(r.body()), "data")) {
                if (!(o instanceof Map<?, ?>)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> f = (Map<String, Object>) o;
                String dl = Json.str(f, "downloadUrl", "");
                if (dl.isBlank()) throw new IOException("автор запретил скачивание вне CurseForge");
                return new Target(dl, Json.str(f, "fileName", "file.jar"), null, Json.lng(f, "fileLength", 0));
            }
            return null;
        } catch (IOException e) {
            throw e;
        }
    }

    /** Имя файла без путей: защита от «../» в ответе сервера. */
    public static String safeFile(String name) {
        String n = name == null ? "" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\u0000-\\u001F]", "_").trim();
        return n.isEmpty() || n.equals("..") ? "file.jar" : n;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
