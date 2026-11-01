package ru.cw.launcher.shell;

import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Хранилище сборок: папка на сборку с build.json и каталогами mods, saves, config и т. д. */
public final class BuildStore {

    private static final String[] SUBDIRS = {"mods", "saves", "config", "resourcepacks", "shaderpacks"};

    private final Path root;

    public BuildStore(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path dir(String id) {
        return root.resolve(id);
    }

    public List<BuildSpec> list() {
        List<BuildSpec> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (var s = Files.list(root)) {
            for (Path d : s.filter(Files::isDirectory).toList()) {
                Path f = d.resolve("build.json");
                if (!Files.isRegularFile(f)) continue;
                try {
                    out.add(BuildSpec.fromJson(Json.parseObject(Files.readString(f, StandardCharsets.UTF_8))));
                } catch (RuntimeException | IOException ignored) {
                    // Повреждённый build.json не должен ломать список остальных сборок.
                }
            }
        } catch (IOException ignored) {
        }
        out.sort(Comparator.comparingLong((BuildSpec b) -> b.createdAt).reversed());
        return out;
    }

    /** Создаёт папки и build.json. Имя папки уникально: «Сборка», «Сборка-2», … */
    public BuildSpec create(BuildSpec spec) throws IOException {
        String err = spec.validate();
        if (err != null) throw new IOException(err);
        spec.name = spec.name.trim();
        String base = slug(spec.name);
        String id = base;
        for (int n = 2; Files.exists(root.resolve(id)); n++) id = base + "-" + n;
        spec.id = id;
        spec.createdAt = System.currentTimeMillis();
        Path dir = root.resolve(id);
        for (String sub : SUBDIRS) {
            if ("mods".equals(sub) && !spec.usesLoader()) continue;
            Files.createDirectories(dir.resolve(sub));
        }
        save(spec);
        return spec;
    }

    public void save(BuildSpec spec) throws IOException {
        Path dir = root.resolve(spec.id);
        Files.createDirectories(dir);
        Path tmp = dir.resolve("build.json.tmp");
        Files.writeString(tmp, Json.write(spec.toJson()), StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve("build.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    public void delete(String id) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path dir = base.resolve(id).normalize();
        if (!dir.startsWith(base) || dir.equals(base) || !Files.isDirectory(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    /** Безопасное имя папки из названия. */
    public static String slug(String name) {
        String s = name == null ? "" : name.trim().replaceAll("[<>:\"/\\\\|?*\\u0000-\\u001F]", "")
                .replaceAll("\\s+", "-").replaceAll("^[.\\-]+|[.\\s]+$", "");
        if (s.length() > 40) s = s.substring(0, 40);
        if (s.isBlank()) s = "build";
        if (s.toLowerCase(Locale.ROOT).matches("con|prn|aux|nul|com\\d|lpt\\d")) s = s + "_";
        return s;
    }
}
