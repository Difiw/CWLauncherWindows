package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Каталог модов, карт и модпаков. Modrinth работает без ключа, CurseForge — с ключом CF_API_KEY. */
public class ContentSource {

    public enum Provider { MODRINTH, CURSEFORGE }

    public enum Kind { MOD, MAP, MODPACK, RESOURCEPACK }

    public enum Sort { POPULAR, NEW, TOP }

    public record Item(String id, String title, String author, String summary, String iconUrl,
                       long downloads, long likes) {
    }

    public record Page(List<Item> items, int total, int pageSize) {
        public int pages() {
            return Math.max(1, (total + pageSize - 1) / pageSize);
        }
    }

    public static final int PAGE_SIZE = 20;

    /** Ключ из настроек лаунчера; если пуст — берётся переменная окружения CF_API_KEY. */
    public static volatile String keyOverride = "";

    public static String curseKey() {
        if (keyOverride != null && !keyOverride.isBlank()) return keyOverride.trim();
        String k = System.getenv("CF_API_KEY");
        return k == null ? "" : k.trim();
    }

    public Page search(Provider provider, Kind kind, Sort sort, String query, int page)
            throws IOException, InterruptedException {
        if (provider == Provider.CURSEFORGE) return searchCurse(kind, sort, query, page);
        return parseModrinth(Http.getBody(modrinthUrl(kind, sort, query, page)));
    }

    // ---- Modrinth ----

    public static String modrinthUrl(Kind kind, Sort sort, String query, int page) {
        String type = switch (kind) {
            case MOD -> "mod";
            case MODPACK -> "modpack";
            case RESOURCEPACK -> "resourcepack";
            // На Modrinth нет карт: ближайшее — датапаки с приключениями.
            case MAP -> "datapack";
        };
        String index = switch (sort) {
            case POPULAR -> "downloads";
            case NEW -> "newest";
            case TOP -> "follows";
        };
        String facets = "[[\"project_type:" + type + "\"]]";
        StringBuilder u = new StringBuilder("https://api.modrinth.com/v2/search?limit=").append(PAGE_SIZE)
                .append("&offset=").append(Math.max(0, page - 1) * PAGE_SIZE)
                .append("&index=").append(query == null || query.isBlank() ? index : "relevance")
                .append("&facets=").append(enc(facets));
        if (query != null && !query.isBlank()) u.append("&query=").append(enc(query.trim()));
        return u.toString();
    }

    public static Page parseModrinth(String json) {
        Map<String, Object> root = Json.parseObject(json);
        List<Item> items = new ArrayList<>();
        for (Object o : Json.arr(root, "hits")) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> h = (Map<String, Object>) o;
            items.add(new Item(Json.str(h, "project_id", ""), Json.str(h, "title", "?"), Json.str(h, "author", ""),
                    Json.str(h, "description", ""), Json.str(h, "icon_url", ""),
                    Json.lng(h, "downloads", 0), Json.lng(h, "follows", 0)));
        }
        return new Page(items, Json.integer(root, "total_hits", items.size()), PAGE_SIZE);
    }

    // ---- CurseForge ----

    private Page searchCurse(Kind kind, Sort sort, String query, int page) throws IOException, InterruptedException {
        String key = curseKey();
        if (key.isEmpty()) throw new IOException("Для CurseForge задайте ключ API в переменной CF_API_KEY");
        java.net.http.HttpRequest req = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create(curseUrl(kind, sort, query, page)))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("x-api-key", key).header("Accept", "application/json").GET().build();
        java.net.http.HttpResponse<String> resp = java.net.http.HttpClient.newHttpClient()
                .send(req, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IOException("CurseForge: HTTP " + resp.statusCode());
        return parseCurse(resp.body());
    }

    public static String curseUrl(Kind kind, Sort sort, String query, int page) {
        int classId = switch (kind) {
            case MOD -> 6;
            case MAP -> 17;
            case MODPACK -> 4471;
            case RESOURCEPACK -> 12;
        };
        int sortField = switch (sort) {
            case POPULAR -> 6;   // TotalDownloads
            case NEW -> 11;      // ReleasedDate
            case TOP -> 2;       // Popularity
        };
        StringBuilder u = new StringBuilder("https://api.curseforge.com/v1/mods/search?gameId=432&classId=")
                .append(classId).append("&sortField=").append(sortField).append("&sortOrder=desc&pageSize=")
                .append(PAGE_SIZE).append("&index=").append(Math.max(0, page - 1) * PAGE_SIZE);
        if (query != null && !query.isBlank()) u.append("&searchFilter=").append(enc(query.trim()));
        return u.toString();
    }

    public static Page parseCurse(String json) {
        Map<String, Object> root = Json.parseObject(json);
        List<Item> items = new ArrayList<>();
        for (Object o : Json.arr(root, "data")) {
            if (!(o instanceof Map<?, ?>)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> h = (Map<String, Object>) o;
            String author = "";
            List<Object> authors = Json.arr(h, "authors");
            if (authors != null && !authors.isEmpty() && authors.get(0) instanceof Map<?, ?> a
                    && a.get("name") instanceof String n) author = n;
            String icon = "";
            Map<String, Object> logo = Json.obj(h, "logo");
            if (logo != null) icon = Json.str(logo, "thumbnailUrl", "");
            items.add(new Item(String.valueOf(Json.lng(h, "id", 0)), Json.str(h, "name", "?"), author,
                    Json.str(h, "summary", ""), icon, Json.lng(h, "downloadCount", 0),
                    Json.lng(h, "thumbsUpCount", 0)));
        }
        int total = items.size();
        Map<String, Object> pag = Json.obj(root, "pagination");
        if (pag != null) total = Json.integer(pag, "totalCount", total);
        return new Page(items, total, PAGE_SIZE);
    }

    /** 297400000 → «297.4M», 12500 → «12.5K». */
    public static String compact(long n) {
        if (n >= 1_000_000_000L) return trim(n / 1e9) + "B";
        if (n >= 1_000_000L) return trim(n / 1e6) + "M";
        if (n >= 1_000L) return trim(n / 1e3) + "K";
        return Long.toString(n);
    }

    private static String trim(double v) {
        String s = String.format(java.util.Locale.ROOT, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
