package ru.cw.launcher.core;

import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Статистика для главного экрана: время в Minecraft и в Common World, время в лаунчере,
 * число запусков, последний запуск и минуты игры по дням. Считается раз в 10 секунд,
 * пока лаунчер открыт, и хранится в activity.properties.
 */
public final class ActivityStats {

    private static final Object LOCK = new Object();
    private static final int KEEP_DAYS = 60;
    private static final long TICK_MS = 10_000;

    private static long minecraft;
    private static long commonWorld;
    private static long launcher;
    private static long launches;
    private static long lastLaunch;
    private static final Map<LocalDate, Long> days = new HashMap<>();

    private static final long openedAt = System.currentTimeMillis();
    private static long lastTick = openedAt;
    /** PID игры → это Common World. Новый PID — новый запуск. */
    private static final Map<Long, Boolean> seen = new HashMap<>();
    private static volatile Supplier<List<Long>> games = List::of;
    private static volatile BooleanSupplier isCommonWorld = () -> true;
    private static boolean started;

    private ActivityStats() {
    }

    public record Snapshot(long minecraft, long commonWorld, long launcher, long launches, long lastLaunch,
                           long session, boolean playing, long[] week, long total) {
    }

    public static void watch(Supplier<List<Long>> running, BooleanSupplier commonWorldNow) {
        if (running != null) games = running;
        if (commonWorldNow != null) isCommonWorld = commonWorldNow;
    }

    public static void begin() {
        synchronized (LOCK) {
            if (started) return;
            started = true;
            load();
        }
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    return;
                }
                tick();
            }
        }, "cw-activity");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(ActivityStats::tick, "cw-activity-end"));
    }

    private static void tick() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            // Сон компьютера не считаем игрой: шаг больше минуты обрезается.
            long sec = Math.max(0, Math.min(60_000, now - lastTick)) / 1000;
            lastTick = now;
            launcher += sec;
            List<Long> live;
            try {
                live = games.get();
            } catch (RuntimeException e) {
                live = List.of();
            }
            Set<Long> alive = new HashSet<>(live == null ? List.of() : live);
            boolean cw = false;
            for (Long pid : alive) {
                Boolean flag = seen.get(pid);
                if (flag == null) {
                    flag = isCommonWorld.getAsBoolean();
                    seen.put(pid, flag);
                    launches++;
                    lastLaunch = now;
                }
                cw |= flag;
            }
            seen.keySet().retainAll(alive);
            if (!alive.isEmpty()) {
                minecraft += sec;
                if (cw) commonWorld += sec;
                days.merge(LocalDate.now(), sec, Long::sum);
            }
            save();
        }
    }

    /** Новый запуск игры сразу, не дожидаясь шага таймера. */
    public static void launched() {
        tick();
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            long now = System.currentTimeMillis();
            long pending = Math.max(0, Math.min(60_000, now - lastTick)) / 1000;
            boolean playing = !seen.isEmpty();
            boolean cw = seen.containsValue(Boolean.TRUE);
            long[] week = new long[7];
            LocalDate monday = LocalDate.now().with(java.time.DayOfWeek.MONDAY);
            long total = 0;
            for (int i = 0; i < 7; i++) {
                week[i] = days.getOrDefault(monday.plusDays(i), 0L);
                total += week[i];
            }
            int today = LocalDate.now().getDayOfWeek().getValue() - 1;
            if (playing) {
                week[today] += pending;
                total += pending;
            }
            return new Snapshot(minecraft + (playing ? pending : 0), commonWorld + (cw ? pending : 0),
                    launcher + pending, launches, lastLaunch, (now - openedAt) / 1000, playing, week, total);
        }
    }

    /** Минуты игры за последние n дней, от старых к сегодняшнему. */
    public static long[] lastDays(int n) {
        synchronized (LOCK) {
            long[] out = new long[n];
            LocalDate today = LocalDate.now();
            for (int i = 0; i < n; i++) out[i] = days.getOrDefault(today.minusDays(n - 1 - i), 0L);
            return out;
        }
    }

    public static void reset() {
        synchronized (LOCK) {
            minecraft = commonWorld = launcher = launches = lastLaunch = 0;
            days.clear();
            save();
        }
    }

    private static Path file() {
        return Paths.root().resolve("activity.properties");
    }

    private static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            Log.warn("Статистика не прочитана: " + Log.reason(e));
            return;
        }
        minecraft = num(p, "minecraft");
        commonWorld = num(p, "commonWorld");
        launcher = num(p, "launcher");
        launches = num(p, "launches");
        lastLaunch = num(p, "lastLaunch");
        for (String key : p.stringPropertyNames()) {
            if (!key.startsWith("day.")) continue;
            try {
                days.put(LocalDate.parse(key.substring(4)), num(p, key));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void save() {
        LocalDate cut = LocalDate.now().minusDays(KEEP_DAYS);
        days.keySet().removeIf(d -> d.isBefore(cut));
        Properties p = new Properties();
        p.setProperty("minecraft", Long.toString(minecraft));
        p.setProperty("commonWorld", Long.toString(commonWorld));
        p.setProperty("launcher", Long.toString(launcher));
        p.setProperty("launches", Long.toString(launches));
        p.setProperty("lastLaunch", Long.toString(lastLaunch));
        days.forEach((d, v) -> p.setProperty("day." + d, Long.toString(v)));
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling("activity.properties.tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(w, "CWLauncher activity");
            }
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Статистика не записана: " + Log.reason(e));
        }
    }

    private static long num(Properties p, String key) {
        try {
            return Math.max(0, Long.parseLong(p.getProperty(key, "0").trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
