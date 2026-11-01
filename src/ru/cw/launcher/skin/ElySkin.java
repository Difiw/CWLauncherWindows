package ru.cw.launcher.skin;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.util.Json;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Скин, который стоит у аккаунта на самом Ely.by. Лаунчер скачивает его в фоне,
 * хранит в кэше %APPDATA%\CWLauncher\cache\ely-skins и показывает в профиле,
 * пока у аккаунта не выбран свой локальный скин.
 */
public final class ElySkin {

    public enum State { IDLE, LOADING, READY, NONE, ERROR }

    /** Скачанный скин: картинка, модель Alex и время проверки. */
    public record Entry(BufferedImage image, boolean slim, String url, long checkedAt) {
    }

    static final String SESSION = "https://authserver.ely.by/api/authlib-injector/sessionserver/session/minecraft/profile/";
    static final String TEXTURES = "https://skinsystem.ely.by/textures/";
    /** Автоматически Ely спрашивается не чаще раза в 10 минут; кнопка «Обновить скин» — всегда. */
    static final long FRESH_MS = 10 * 60 * 1000L;
    private static final int MAX_PNG = 1_000_000;

    /** Создаётся при первом запросе: сбой сети не должен ломать аватары из кэша. */
    private static HttpClient http;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cw-ely-skin");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, String> ERRORS = new ConcurrentHashMap<>();
    private static final Map<String, Entry> MEMORY = new ConcurrentHashMap<>();
    private static final Map<String, List<Runnable>> WAITING = new ConcurrentHashMap<>();
    private static final Map<String, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final long RETRY_MS = 60 * 1000L;

    private ElySkin() {
    }

    public static boolean applies(Account a) {
        return a != null && a.type == Account.Type.ELY && !key(a).isBlank();
    }

    public static State state(Account a) {
        return applies(a) ? STATES.getOrDefault(key(a), State.IDLE) : State.IDLE;
    }

    /** Понятная причина последней ошибки (техническая — в журнале). */
    public static String error(Account a) {
        return applies(a) ? ERRORS.getOrDefault(key(a), "") : "";
    }

    /** Скин из кэша без сети. null — кэша нет или Ely сказал, что скина нет. */
    public static Entry cached(Account a) {
        if (!applies(a)) return null;
        String k = key(a);
        Path png = pngFile(k);
        if (!Files.isRegularFile(png)) {
            MEMORY.remove(k);
            return null;
        }
        // Картинка читается с диска один раз; load() сбрасывает её после нового скачивания.
        Entry mem = MEMORY.get(k);
        if (mem != null) return mem;
        Map<String, Object> meta = readMeta(k);
        BufferedImage img = decode(png);
        if (img == null) {
            Log.warn("Кэш скина Ely повреждён и удалён: " + png.getFileName());
            try {
                Files.deleteIfExists(png);
            } catch (IOException ignored) {
            }
            MEMORY.remove(k);
            return null;
        }
        Entry e = new Entry(img, "slim".equals(Json.str(meta, "model", "")), Json.str(meta, "url", ""),
                Json.lng(meta, "checkedAt", 0));
        MEMORY.put(k, e);
        return e;
    }

    /** Файл кэша для игры и редактора. */
    public static Path file(Account a) {
        if (!applies(a)) return null;
        Path p = pngFile(key(a));
        return Files.isRegularFile(p) ? p : null;
    }

    /**
     * Проверяет скин на Ely в фоне. Без force запрос пропускается, если кэш свежий.
     * Одновременные запросы одного аккаунта объединяются; done зовётся в потоке Swing.
     */
    public static void request(Account a, boolean force, Runnable done) {
        if (!applies(a)) return;
        String k = key(a);
        if (!force && fresh(k)) return;
        // После сетевой ошибки сам по себе лаунчер повторит не раньше чем через минуту.
        Long failed = FAILED_AT.get(k);
        if (!force && failed != null && System.currentTimeMillis() - failed < RETRY_MS) return;
        String name = a.username == null ? "" : a.username;
        List<Runnable> joined = new CopyOnWriteArrayList<>();
        if (done != null) joined.add(done);
        List<Runnable> running = WAITING.putIfAbsent(k, joined);
        if (running != null) {
            if (done != null) running.add(done);
            return;
        }
        STATES.put(k, State.LOADING);
        ERRORS.remove(k);
        IO.submit(() -> {
            try {
                load(k, name);
            } catch (Throwable t) {
                STATES.put(k, State.ERROR);
                ERRORS.put(k, "Внутренняя ошибка");
                Log.warn("Скин Ely " + name + ": " + Log.reason(t));
            } finally {
                List<Runnable> callbacks = WAITING.remove(k);
                if (callbacks != null) {
                    SwingUtilities.invokeLater(() -> {
                        for (Runnable r : callbacks) {
                            try {
                                r.run();
                            } catch (Exception e) {
                                Log.warn("Обновление профиля после скина: " + Log.reason(e));
                            }
                        }
                    });
                }
            }
        });
    }

    private static void load(String k, String name) {
        Texture tex;
        try {
            tex = lookup(k, name);
        } catch (Exception e) {
            fail(k, name, "Ely.by недоступен. Проверьте интернет и нажмите «Обновить скин».", e);
            return;
        }
        if (tex == null) {
            Log.info("Скин Ely " + name + ": на Ely.by скин не установлен");
            try {
                Files.deleteIfExists(pngFile(k));
                writeMeta(k, "", false);
            } catch (IOException ignored) {
            }
            MEMORY.remove(k);
            FAILED_AT.remove(k);
            STATES.put(k, State.NONE);
            return;
        }
        byte[] png;
        try {
            png = download(tex.url);
        } catch (Exception e) {
            fail(k, name, "Файл скина не скачался с Ely.by.", e);
            return;
        }
        BufferedImage img = decode(png);
        if (img == null || !validLayout(img)) {
            fail(k, name, "Ely.by вернул повреждённый файл скина.",
                    new IOException("не PNG 64×64/64×32: " + (img == null ? "не читается" : img.getWidth() + "×" + img.getHeight())));
            return;
        }
        try {
            Path target = pngFile(k);
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".part");
            Files.write(tmp, png);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            writeMeta(k, tex.url, tex.slim);
        } catch (IOException e) {
            fail(k, name, "Скин не сохранён в кэш.", e);
            return;
        }
        MEMORY.remove(k);
        FAILED_AT.remove(k);
        STATES.put(k, State.READY);
        Log.info("Скин Ely " + name + " загружен: " + tex.url + (tex.slim ? " (Alex)" : " (Steve)"));
    }

    private static void fail(String k, String name, String message, Exception e) {
        STATES.put(k, State.ERROR);
        ERRORS.put(k, message);
        FAILED_AT.put(k, System.currentTimeMillis());
        Log.warn("Скин Ely " + name + " не загружен: " + Log.reason(e));
    }

    public record Texture(String url, boolean slim) {
    }

    /** Сначала профиль по UUID (не зависит от смены ника), затем skinsystem по нику. */
    private static Texture lookup(String uuid, String name) throws IOException, InterruptedException {
        IOException last = null;
        try {
            HttpResponse<String> r = get(SESSION + uuid + "?unsigned=true");
            if (r.statusCode() == 200 && !r.body().isBlank()) return fromProfile(r.body());
            if (r.statusCode() != 204 && r.statusCode() != 404) last = new IOException("sessionserver HTTP " + r.statusCode());
        } catch (IOException e) {
            last = e;
        }
        if (!name.isBlank()) {
            HttpResponse<String> r = get(TEXTURES + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8));
            if (r.statusCode() == 200 && !r.body().isBlank()) return fromTextures(Json.parseObject(r.body()));
            if (r.statusCode() == 204 || r.statusCode() == 404) return null;
            throw new IOException("skinsystem HTTP " + r.statusCode());
        }
        if (last != null) throw last;
        return null;
    }

    public static Texture fromProfile(String body) throws IOException {
        Map<String, Object> root = Json.parseObject(body);
        List<Object> props = Json.arr(root, "properties");
        if (props == null) return null;
        for (Object o : props) {
            if (!(o instanceof Map<?, ?> m) || !"textures".equals(m.get("name"))) continue;
            Object v = m.get("value");
            if (!(v instanceof String s) || s.isBlank()) return null;
            String json = new String(Base64.getDecoder().decode(s.trim()), StandardCharsets.UTF_8);
            return fromTextures(Json.obj(Json.parseObject(json), "textures"));
        }
        return null;
    }

    public static Texture fromTextures(Map<String, Object> textures) {
        Map<String, Object> skin = textures == null ? null : Json.obj(textures, "SKIN");
        if (skin == null) return null;
        String url = Json.str(skin, "url", "");
        if (url.isBlank()) return null;
        Map<String, Object> meta = Json.obj(skin, "metadata");
        boolean slim = meta != null && "slim".equalsIgnoreCase(Json.str(meta, "model", ""));
        return new Texture(url, slim);
    }

    private static synchronized HttpClient client() throws IOException {
        if (http == null) {
            try {
                http = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(8))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();
            } catch (RuntimeException e) {
                throw new IOException("сетевой клиент не создан", e);
            }
        }
        return http;
    }

    private static HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json")
                .header("User-Agent", "CWLauncher/" + Log.VERSION)
                .GET().build();
        return client().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static byte[] download(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!host.equals("ely.by") && !host.endsWith(".ely.by")) throw new IOException("чужой адрес текстуры: " + host);
        HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "CWLauncher/" + Log.VERSION)
                .GET().build();
        HttpResponse<byte[]> r = client().send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " " + url);
        byte[] body = r.body();
        if (body == null || body.length == 0) throw new IOException("пустой ответ " + url);
        if (body.length > MAX_PNG) throw new IOException("слишком большой файл " + body.length);
        return body;
    }

    public static BufferedImage decode(byte[] data) {
        try {
            return ImageIO.read(new ByteArrayInputStream(data));
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage decode(Path file) {
        try {
            return decode(Files.readAllBytes(file));
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean validLayout(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        return w >= 64 && w % 64 == 0 && (h == w || h * 2 == w);
    }

    private static boolean fresh(String k) {
        long checked = Json.lng(readMeta(k), "checkedAt", 0);
        return checked > 0 && System.currentTimeMillis() - checked < FRESH_MS;
    }

    private static String key(Account a) {
        String u = a.uuid == null ? "" : a.uuid.replace("-", "").toLowerCase(Locale.ROOT);
        return u.matches("[0-9a-f]{32}") ? u : "";
    }

    private static Path dir() {
        return Paths.cache().resolve("ely-skins");
    }

    private static Path pngFile(String k) {
        return dir().resolve(k + ".png");
    }

    private static Map<String, Object> readMeta(String k) {
        try {
            Path f = dir().resolve(k + ".json");
            if (Files.isRegularFile(f)) return Json.parseObject(Files.readString(f));
        } catch (Exception ignored) {
        }
        return new LinkedHashMap<>();
    }

    private static void writeMeta(String k, String url, boolean slim) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("model", slim ? "slim" : "default");
        m.put("checkedAt", System.currentTimeMillis());
        Files.createDirectories(dir());
        Files.writeString(dir().resolve(k + ".json"), Json.writeCompact(m));
    }
}
