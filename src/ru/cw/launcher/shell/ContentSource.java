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

    /**
     * MCINSIDE — minecraft-inside.ru. API у сайта нет, а robots.txt запрещает программам поиск
     * и скачивание. Поэтому лаунчер только читает разрешённые списки разделов, а скачивание и
     * поиск открываются на самом сайте в браузере.
     */
    public enum Provider {
        MODRINTH("Modrinth"), CURSEFORGE("CurseForge"), MCINSIDE("Minecraft Inside");

        public final String title;

        Provider(String title) {
            this.title = title;
        }

        public static Provider byTitle(String t) {
            for (Provider p : values()) if (p.title.equals(t)) return p;
            return MODRINTH;
        }

        /** Можно ли поставить проект в сборку автоматически. */
        public boolean installable() {
            return this != MCINSIDE;
        }
    }

    /** Подробности о проекте для экрана описания: текст и страница на сайте. */
    public record Details(String text, String url) {
    }

    private static final String MCI = "https://minecraft-inside.ru";

    /** Переводить ли описания проектов на русский. Задаёт лаунчер из настроек. */
    public static volatile boolean translateToRussian;

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
        if (k != null && !k.isBlank()) return k.trim();
        // Ключ проекта: файл curseforge.key рядом с лаунчером. Его кладёт разработчик один раз,
        // и игрокам вводить ничего не нужно.
        try {
            java.nio.file.Path file = ru.cw.launcher.util.Paths.installRoot().resolve("curseforge.key");
            if (java.nio.file.Files.isRegularFile(file)) {
                String line = java.nio.file.Files.readString(file, StandardCharsets.UTF_8).lines().findFirst().orElse("");
                return line.replace("﻿", "").trim();
            }
        } catch (IOException | RuntimeException ignored) {
            // нет файла или он не читается: остаётся запрос ключа в окне каталога
        }
        return "";
    }

    public Page search(Provider provider, Kind kind, Sort sort, String query, int page)
            throws IOException, InterruptedException {
        if (provider == Provider.CURSEFORGE) return searchCurse(kind, sort, query, page);
        if (provider == Provider.MCINSIDE) {
            return parseMcInside(Http.get(mcInsideUrl(kind, page), null, 12, 2).body());
        }
        return parseModrinth(Http.get(modrinthUrl(kind, sort, query, page), null, 12, 2).body());
    }

    /** Полное описание проекта. Для Minecraft Inside — краткое из списка: страницу открывает браузер. */
    public Details details(Provider provider, Item item) throws IOException, InterruptedException {
        switch (provider) {
            case MODRINTH -> {
                Map<String, Object> m = Json.parseObject(
                        Http.get("https://api.modrinth.com/v2/project/" + enc(item.id()), null, 12, 2).body());
                String type = Json.str(m, "project_type", "mod");
                String url = "https://modrinth.com/" + type + "/" + Json.str(m, "slug", item.id());
                String body = Json.str(m, "body", "");
                return new Details(plainText(body.isBlank() ? Json.str(m, "description", item.summary()) : body), url);
            }
            case CURSEFORGE -> {
                String key = curseKey();
                if (key.isEmpty()) return new Details(item.summary(), "https://www.curseforge.com/minecraft");
                String html = curseGet("https://api.curseforge.com/v1/mods/" + enc(item.id()) + "/description", key);
                Map<String, Object> mod = Json.obj(Json.parseObject(
                        curseGet("https://api.curseforge.com/v1/mods/" + enc(item.id()), key)), "data");
                Map<String, Object> links = mod == null ? null : Json.obj(mod, "links");
                String url = links == null ? "https://www.curseforge.com/minecraft" : Json.str(links, "websiteUrl", "");
                return new Details(plainText(Json.str(Json.parseObject(html), "data", item.summary())), url);
            }
            default -> {
                return new Details(item.summary(), item.id());
            }
        }
    }

    // ---- Minecraft Inside ----

    public static String mcInsideUrl(Kind kind, int page) {
        String section = switch (kind) {
            case MOD -> "mods";
            case MAP -> "maps";
            case MODPACK -> "modpacks";
            case RESOURCEPACK -> "resource-packs";
        };
        return MCI + "/" + section + "/" + (page > 1 ? "page/" + page + "/" : "");
    }

    /** Ссылка на поиск по сайту: открывается в браузере пользователя, программа её не читает. */
    public static String mcInsideSearchUrl(String query) {
        return MCI + "/search/?q=" + enc(query == null ? "" : query.trim());
    }

    private static final java.util.regex.Pattern MCI_POST = java.util.regex.Pattern.compile(
            "<div class=\"box box_grass post[^\"]*\">(.*?)class=\"post__more\"", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern MCI_TITLE = java.util.regex.Pattern.compile(
            "<h2 class=\"box__title\"><a href=\"([^\"]+)\">([^<]+)</a>");
    private static final java.util.regex.Pattern MCI_AUTHOR = java.util.regex.Pattern.compile(
            "post__creator\">.*?<a [^>]*>([^<]+)</a>", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern MCI_COVER = java.util.regex.Pattern.compile(
            "class=\"post__cover\"><img[^>]*src=\"([^\"]+)\"[^>]*>\\s*</a>\\s*(?:<br\\s*/?>)?(.*?)</div>",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern MCI_VIEWS = java.util.regex.Pattern.compile(
            "title=\"Просмотров: (\\d+)\"");
    private static final java.util.regex.Pattern MCI_SCORE = java.util.regex.Pattern.compile(
            "<span class=\"score\">(-?\\d+)</span>");
    private static final java.util.regex.Pattern MCI_LAST = java.util.regex.Pattern.compile(
            "href=\"/[a-z-]+/page/(\\d+)/\"");

    /** Список раздела: id — полный адрес страницы проекта. */
    public static Page parseMcInside(String html) {
        List<Item> items = new ArrayList<>();
        java.util.regex.Matcher post = MCI_POST.matcher(html);
        while (post.find()) {
            String block = post.group(1);
            java.util.regex.Matcher t = MCI_TITLE.matcher(block);
            if (!t.find()) continue;
            String url = absolute(t.group(1));
            String title = unescape(t.group(2)).trim();
            java.util.regex.Matcher a = MCI_AUTHOR.matcher(block);
            String author = a.find() ? unescape(a.group(1)).trim() : "";
            String icon = "";
            String summary = "";
            java.util.regex.Matcher c = MCI_COVER.matcher(block);
            if (c.find()) {
                icon = absolute(c.group(1));
                summary = plainText(c.group(2));
            }
            java.util.regex.Matcher v = MCI_VIEWS.matcher(block);
            java.util.regex.Matcher s = MCI_SCORE.matcher(block);
            items.add(new Item(url, title, author, summary, icon,
                    v.find() ? Long.parseLong(v.group(1)) : 0, s.find() ? Long.parseLong(s.group(1)) : 0));
        }
        int last = 1;
        java.util.regex.Matcher l = MCI_LAST.matcher(html);
        while (l.find()) last = Math.max(last, Integer.parseInt(l.group(1)));
        return new Page(items, last * Math.max(1, items.size()), Math.max(1, items.size()));
    }

    private static String absolute(String href) {
        if (href.startsWith("http")) return href;
        return MCI + (href.startsWith("/") ? "" : "/") + href;
    }

    /** HTML или Markdown описания в читаемый текст: без тегов, картинок и разметки ссылок. */
    public static String plainText(String s) {
        if (s == null) return "";
        String t = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|h[1-6])>", "\n")
                .replaceAll("(?i)<li[^>]*>", "• ")
                .replaceAll("<[^>]+>", "")
                .replaceAll("!\\[[^]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^]]*)]\\([^)]*\\)", "$1")
                .replaceAll("(?m)^#{1,6}\\s*", "")
                .replaceAll("\\*\\*|__|`", "");
        t = unescape(t);
        return t.replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n\\s*\\n+", "\n\n").trim();
    }

    private static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&laquo;", "«")
                .replace("&raquo;", "»").replace("&mdash;", "—").replace("&ndash;", "–").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&");
    }

    /** Клиент создаётся при первом запросе: сбой сети не должен ломать загрузку самого класса. */
    private static final class Client {
        static final java.net.http.HttpClient INSTANCE = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
    }

    public static String curseGet(String url, String key) throws IOException, InterruptedException {
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("x-api-key", key).header("Accept", "application/json").GET().build();
        java.net.http.HttpResponse<String> resp = Client.INSTANCE.send(req,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 403) throw new IOException("CurseForge отклонил ключ API (HTTP 403)");
        if (resp.statusCode() / 100 != 2) throw new IOException("CurseForge: HTTP " + resp.statusCode());
        return resp.body();
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
        if (key.isEmpty()) throw new MissingKey();
        return parseCurse(curseGet(curseUrl(kind, sort, query, page), key));
    }

    /** CurseForge отдаёт данные только с личным ключом API: экран предлагает его ввести. */
    public static final class MissingKey extends IOException {
        public MissingKey() {
            super("Нужен ключ API CurseForge");
        }
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
