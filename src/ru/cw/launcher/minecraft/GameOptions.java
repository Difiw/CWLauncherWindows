package ru.cw.launcher.minecraft;

import ru.cw.launcher.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * options.txt Minecraft: чтение и запись без потери чужих строк.
 * Неизвестные ключи (моды, новые версии игры) сохраняются как были.
 */
public final class GameOptions {

    private final Path file;
    private final Map<String, String> values = new LinkedHashMap<>();

    private GameOptions(Path file) {
        this.file = file;
    }

    public static GameOptions load(Path file) {
        GameOptions o = new GameOptions(file);
        try {
            if (file != null && Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    int i = line.indexOf(':');
                    if (i > 0) o.values.put(line.substring(0, i), line.substring(i + 1));
                }
            }
        } catch (IOException e) {
            Log.warn("options.txt не прочитан: " + Log.reason(e));
        }
        return o;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return file != null && Files.isRegularFile(file);
    }

    public Map<String, String> raw() {
        return values;
    }

    public GameOptions copy() {
        GameOptions o = new GameOptions(file);
        o.values.putAll(values);
        return o;
    }

    public String get(String key, String def) {
        String v = values.get(key);
        return v == null ? def : unquote(v);
    }

    public void set(String key, String value) {
        String old = values.get(key);
        // Строковые значения 1.19+ записаны в кавычках, их формат сохраняется.
        if (old != null && old.startsWith("\"")) values.put(key, "\"" + value + "\"");
        else values.put(key, value);
    }

    public void setQuoted(String key, String value) {
        values.put(key, "\"" + value + "\"");
    }

    public boolean bool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v.trim());
    }

    public void setBool(String key, boolean on) {
        set(key, String.valueOf(on));
    }

    public int integer(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    public double decimal(String key, double def) {
        String v = get(key, null);
        if (v == null) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setDecimal(String key, double value) {
        set(key, String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", ".0"));
    }

    // ------------------------------------------------------------------ клавиши

    /** Клавиша действия в формате Minecraft, например key.keyboard.w. */
    public String key(String action, String def) {
        String v = values.get("key_" + action);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public void setKey(String action, String code) {
        values.put("key_" + action, code);
    }

    /** Все действия, которые уже записаны в файле, включая клавиши модов. */
    public List<String> keyActions() {
        List<String> out = new ArrayList<>();
        for (String k : values.keySet()) if (k.startsWith("key_")) out.add(k.substring(4));
        return out;
    }

    public void save() throws IOException {
        if (file == null) throw new IOException("Папка игры не определена");
        Files.createDirectories(file.getParent());
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) out.add(e.getKey() + ":" + e.getValue());
        Path tmp = file.resolveSibling("options.txt.tmp");
        Files.writeString(tmp, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Log.info("options.txt сохранён: " + file);
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
        return s;
    }
}
