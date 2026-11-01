package ru.cw.launcher.shell;

import ru.cw.launcher.util.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/** Описание одной сборки: версия игры, ядро и параметры, как на экране «Создание сборки». */
public final class BuildSpec {

    public enum Kind {
        MODPACK("Модпак", "С модами"),
        MAP("Карта", "Своё приключение"),
        VANILLA("Ванильная", "Чистая игра"),
        EMPTY("Пустая", "Только ядро");

        public final String title;
        public final String hint;

        Kind(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    public String id = "";
    public String name = "Новая сборка";
    public String description = "";
    public String mcVersion = "1.20.1";
    public String loader = "Forge";
    public String loaderVersion = "";
    public Kind kind = Kind.MODPACK;
    public boolean optimized = true;
    public boolean autoUpdateMods = false;
    public boolean moreRam = false;
    public int ramMb = 4096;
    public String iconPath = "";
    public long createdAt = System.currentTimeMillis();

    /** Ванильная сборка идёт без загрузчика модов. */
    public boolean usesLoader() {
        return kind != Kind.VANILLA && !"Vanilla".equalsIgnoreCase(loader);
    }

    /** Возвращает текст ошибки или null, если сборку можно создавать. */
    public String validate() {
        if (name == null || name.trim().isEmpty()) return "Введите название сборки";
        if (name.trim().length() > 60) return "Название длиннее 60 символов";
        if (description != null && description.length() > 500) return "Описание длиннее 500 символов";
        if (mcVersion == null || mcVersion.isBlank()) return "Выберите версию Minecraft";
        if (usesLoader() && (loader == null || loader.isBlank())) return "Выберите загрузчик";
        if (ramMb < 1024 || ramMb > 32768) return "Память должна быть от 1 до 32 ГБ";
        return null;
    }

    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("description", description);
        m.put("mcVersion", mcVersion);
        m.put("loader", loader);
        m.put("loaderVersion", loaderVersion);
        m.put("kind", kind.name());
        m.put("optimized", optimized);
        m.put("autoUpdateMods", autoUpdateMods);
        m.put("moreRam", moreRam);
        m.put("ramMb", ramMb);
        m.put("iconPath", iconPath);
        m.put("createdAt", createdAt);
        return m;
    }

    public static BuildSpec fromJson(Map<String, Object> m) {
        BuildSpec b = new BuildSpec();
        b.id = Json.str(m, "id", "");
        b.name = Json.str(m, "name", b.name);
        b.description = Json.str(m, "description", "");
        b.mcVersion = Json.str(m, "mcVersion", b.mcVersion);
        b.loader = Json.str(m, "loader", b.loader);
        b.loaderVersion = Json.str(m, "loaderVersion", "");
        try {
            b.kind = Kind.valueOf(Json.str(m, "kind", "MODPACK"));
        } catch (IllegalArgumentException e) {
            b.kind = Kind.MODPACK;
        }
        b.optimized = Json.bool(m, "optimized", true);
        b.autoUpdateMods = Json.bool(m, "autoUpdateMods", false);
        b.moreRam = Json.bool(m, "moreRam", false);
        b.ramMb = Json.integer(m, "ramMb", 4096);
        b.iconPath = Json.str(m, "iconPath", "");
        b.createdAt = Json.lng(m, "createdAt", System.currentTimeMillis());
        return b;
    }

    /** Итоговый -Xmx с учётом галочки «Выделить больше RAM». */
    public int effectiveRamMb() {
        return moreRam ? Math.min(32768, ramMb + 2048) : ramMb;
    }
}
