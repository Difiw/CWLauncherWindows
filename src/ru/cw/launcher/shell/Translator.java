package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Перевод описаний модов с английского на русский через бесплатный API MyMemory.
 * Это онлайн-служба, а не встроенная нейросеть: языковая модель весит гигабайты и в лаунчер не помещается.
 * У бесплатного доступа есть дневной лимит символов — при его исчерпании остаётся оригинал.
 */
public final class Translator {

    private static final String API = "https://api.mymemory.translated.net/get?langpair=en%7Cru&q=";
    /** Длина одного запроса: API принимает до 500 байт, русский текст в UTF-8 занимает вдвое больше места. */
    private static final int CHUNK = 420;
    /** Не больше этого числа символов оригинала за один раз: берегём дневной лимит. */
    private static final int BUDGET = 3500;
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Translator() {
    }

    /** Текст в основном на кириллице — переводить нечего. */
    public static boolean mostlyRussian(String s) {
        if (s == null) return true;
        int cyr = 0;
        int lat = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'а' && c <= 'я' || c >= 'А' && c <= 'Я' || c == 'ё' || c == 'Ё') cyr++;
            else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') lat++;
        }
        return cyr >= lat;
    }

    /** Переводит абзацами, сохраняя разбивку. Бросает IOException, если служба недоступна или лимит исчерпан. */
    public static String toRussian(String text) throws IOException, InterruptedException {
        if (text == null || text.isBlank() || mostlyRussian(text)) return text;
        StringBuilder out = new StringBuilder();
        int used = 0;
        boolean cut = false;
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isBlank()) {
                out.append('\n');
                continue;
            }
            StringBuilder translated = new StringBuilder();
            for (String chunk : chunks(paragraph)) {
                if (used + chunk.length() > BUDGET) {
                    cut = true;
                    break;
                }
                used += chunk.length();
                if (translated.length() > 0) translated.append(' ');
                translated.append(chunk(chunk));
            }
            out.append(translated).append('\n');
            if (cut) break;
        }
        String result = out.toString().stripTrailing();
        return cut ? result + "…" : result;
    }

    private static String chunk(String piece) throws IOException, InterruptedException {
        String hit = CACHE.get(piece);
        if (hit != null) return hit;
        String url = API + URLEncoder.encode(piece, StandardCharsets.UTF_8);
        Map<String, Object> root = Json.parseObject(Http.get(url, null, 12, 2).body());
        Object status = root.get("responseStatus");
        Map<String, Object> data = Json.obj(root, "responseData");
        String value = data == null ? "" : Json.str(data, "translatedText", "");
        boolean ok = status != null && "200".equals(String.valueOf(status).replace(".0", ""));
        if (!ok || value.isBlank() || value.toUpperCase().contains("MYMEMORY WARNING")) {
            throw new IOException("бесплатный лимит переводчика на сегодня исчерпан");
        }
        String clean = value.replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">");
        CACHE.put(piece, clean);
        return clean;
    }

    /** Режет абзац по предложениям на куски до CHUNK символов. */
    static List<String> chunks(String paragraph) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String sentence : paragraph.trim().split("(?<=[.!?])\\s+")) {
            while (sentence.length() > CHUNK) {
                int cut = sentence.lastIndexOf(' ', CHUNK);
                if (cut < CHUNK / 2) cut = CHUNK;
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
                out.add(sentence.substring(0, cut).trim());
                sentence = sentence.substring(cut).trim();
            }
            if (cur.length() + sentence.length() + 1 > CHUNK && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(sentence);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }
}
