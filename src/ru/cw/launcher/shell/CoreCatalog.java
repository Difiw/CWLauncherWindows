package ru.cw.launcher.shell;

import ru.cw.launcher.net.Http;
import ru.cw.launcher.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Версии Minecraft и ядер (Forge, Fabric, Quilt, NeoForge, LiteLoader, Rift) для создания сборки. */
public class CoreCatalog {

    public static final String[] LOADERS = {"Forge", "Fabric", "Quilt", "NeoForge", "LiteLoader", "Rift"};

    /** Версии, которые показываются, пока сеть недоступна. */
    public static final List<String> FALLBACK_MC = List.of("1.21.4", "1.21.1", "1.20.6", "1.20.4", "1.20.2", "1.20.1",
            "1.19.4", "1.19.2", "1.18.2", "1.16.5", "1.12.2", "1.8.9");

    private static final String MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String FORGE_META = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
    private static final String NEO_META = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";

    private final Map<String, List<String>> cache = new LinkedHashMap<>();

    /** Релизы Minecraft, свежие первыми. */
    public List<String> minecraftVersions() {
        List<String> hit = cache.get("mc");
        if (hit != null) return hit;
        try {
            List<String> v = parseMinecraft(Http.get(MOJANG, "mc-manifest-v2").body());
            if (!v.isEmpty()) return remember("mc", v);
        } catch (IOException | RuntimeException e) {
            // ниже вернём запасной список
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return FALLBACK_MC;
    }

    /** Версии ядра для пары «загрузчик + версия игры», лучшая первой. Пустой список — ядра нет. */
    public List<String> coreVersions(String loader, String mc) {
        String key = loader + "|" + mc;
        List<String> hit = cache.get(key);
        if (hit != null) return hit;
        try {
            List<String> v = switch (loader) {
                case "Fabric" -> parseLoaderMeta(Http.get("https://meta.fabricmc.net/v2/versions/loader/" + mc,
                        "fabric-loaders-" + mc).body());
                case "Quilt" -> parseLoaderMeta(Http.get("https://meta.quiltmc.org/v3/versions/loader/" + mc,
                        "quilt-loaders-" + mc).body());
                case "Forge" -> forMinecraft(parseMavenVersions(Http.get(FORGE_META, "forge-meta").body()), mc);
                case "NeoForge" -> neoForMinecraft(parseMavenVersions(Http.get(NEO_META, "neoforge-meta").body()), mc);
                default -> staticVersions(loader, mc);
            };
            return remember(key, v);
        } catch (IOException | RuntimeException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }

    private List<String> remember(String key, List<String> v) {
        synchronized (cache) {
            cache.put(key, v);
        }
        return v;
    }

    // ---- разбор ответов (отдельно от сети, чтобы проверять без интернета) ----

    public static List<String> parseMinecraft(String json) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(Json.parseObject(json), "versions")) {
            if (!(o instanceof Map<?, ?> m)) continue;
            if ("release".equals(m.get("type")) && m.get("id") instanceof String id) out.add(id);
        }
        return out;
    }

    /** Ответ Fabric/Quilt meta: массив объектов с полем loader.version. */
    public static List<String> parseLoaderMeta(String json) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.parseArray(json)) {
            if (!(o instanceof Map<?, ?> m) || !(m.get("loader") instanceof Map<?, ?> l)) continue;
            if (l.get("version") instanceof String v && !v.isBlank()) out.add(v);
        }
        return out;
    }

    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    /** maven-metadata.xml: все версии, старые первыми (как в файле). */
    public static List<String> parseMavenVersions(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = VERSION_TAG.matcher(xml);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    /** Forge публикует «1.20.1-47.3.0»: оставляем версии для нужной игры, новые первыми. */
    public static List<String> forMinecraft(List<String> all, String mc) {
        List<String> out = new ArrayList<>();
        String prefix = mc + "-";
        for (String v : all) if (v.startsWith(prefix)) out.add(v.substring(prefix.length()));
        java.util.Collections.reverse(out);
        return out;
    }

    /** NeoForge: игра 1.21.1 → «21.1.x», 1.20.4 → «20.4.x», 1.21 → «21.0.x». */
    public static List<String> neoForMinecraft(List<String> all, String mc) {
        String[] p = mc.split("\\.");
        if (p.length < 2 || !"1".equals(p[0])) return List.of();
        String patch = p.length > 2 ? p[2] : "0";
        String prefix = p[1] + "." + patch + ".";
        List<String> out = new ArrayList<>();
        for (String v : all) if (v.startsWith(prefix)) out.add(v);
        java.util.Collections.reverse(out);
        return out;
    }

    /** LiteLoader жив только до 1.12.2, Rift — только 1.13.2. */
    public static List<String> staticVersions(String loader, String mc) {
        if ("Rift".equals(loader)) return "1.13.2".equals(mc) ? List.of("1.13.2-1.0.4") : List.of();
        if ("LiteLoader".equals(loader)) {
            return switch (mc) {
                case "1.12.2" -> List.of("1.12.2-SNAPSHOT");
                case "1.12.1" -> List.of("1.12.1-SNAPSHOT");
                case "1.12" -> List.of("1.12-SNAPSHOT");
                case "1.11.2" -> List.of("1.11.2-SNAPSHOT");
                case "1.10.2" -> List.of("1.10.2-SNAPSHOT");
                case "1.9.4" -> List.of("1.9.4-SNAPSHOT");
                case "1.8.9" -> List.of("1.8.9-SNAPSHOT");
                case "1.7.10" -> List.of("1.7.10-SNAPSHOT");
                default -> List.of();
            };
        }
        return List.of();
    }
}
