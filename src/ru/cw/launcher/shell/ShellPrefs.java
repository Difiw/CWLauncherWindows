package ru.cw.launcher.shell;

import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Небольшие настройки нового интерфейса: ник и ключ CurseForge. */
public final class ShellPrefs {

    public String nickname = "Player";
    public String curseKey = "";

    private final Path file;

    public ShellPrefs(Path file) {
        this.file = file;
    }

    public void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            Map<String, Object> m = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            nickname = Json.str(m, "nickname", nickname);
            curseKey = Json.str(m, "curseKey", "");
        } catch (IOException | RuntimeException ignored) {
            // повреждённый файл: остаются значения по умолчанию
        }
    }

    public void save() throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nickname", nickname);
        m.put("curseKey", curseKey);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(m), StandardCharsets.UTF_8);
    }

    /** Ник Minecraft: 3–16 символов, латиница, цифры и «_». */
    public static boolean validNick(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{3,16}");
    }
}
