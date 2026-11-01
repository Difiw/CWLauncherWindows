package ru.cw.launcher.server;

import ru.cw.launcher.minecraft.JavaManager;
import ru.cw.launcher.net.Http;
import ru.cw.launcher.util.Json;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;
import ru.cw.launcher.util.Utils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Создание и управление локальными Fabric-серверами CWLauncher. */
public final class LocalServerManager {

    private static final int DEFAULT_PORT = 25565;
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions";

    public static final class Draft {
        public final String name;
        public final String description;
        public final Path directory;
        public final String minecraftVersion;
        public final String loaderVersion;
        public final boolean acceptEula;

        public Draft(String name, String description, Path directory,
                     String minecraftVersion, String loaderVersion, boolean acceptEula) {
            this.name = name;
            this.description = description;
            this.directory = directory;
            this.minecraftVersion = minecraftVersion;
            this.loaderVersion = loaderVersion;
            this.acceptEula = acceptEula;
        }
    }

    public static final class Server {
        public final String id;
        public final String name;
        public final String description;
        public final Path directory;
        public final String minecraftVersion;
        public final String loaderVersion;
        public final int port;
        private final String rconPassword;
        private volatile Process process;
        private volatile String status = "Остановлен";
        /** Память сервера (-Xmx), МБ. */
        private volatile int ramMb = 2048;
        /** Запускать вместе с лаунчером. Включается только вручную в настройках сервера. */
        private volatile boolean autoStart;
        /** Остановку запросил пользователь: падением это не считается. */
        private volatile boolean stopping;

        public int ramMb() {
            return ramMb;
        }

        public boolean autoStart() {
            return autoStart;
        }

        public boolean running() {
            Process p = process;
            return p != null && p.isAlive() || "Работает".equals(status);
        }

        private Server(String id, String name, String description, Path directory,
                       String minecraftVersion, String loaderVersion, int port, String rconPassword) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.directory = directory;
            this.minecraftVersion = minecraftVersion;
            this.loaderVersion = loaderVersion;
            this.port = port;
            this.rconPassword = rconPassword;
        }

        public String status() {
            Process p = process;
            if (p != null && p.isAlive()) return "Работает";
            return status;
        }

        public String address() {
            String ip = primaryAddress();
            return ip + ":" + port;
        }
    }

    private final JavaManager javaManager;
    private final List<Server> servers = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "cw-local-server");
        t.setDaemon(true);
        return t;
    });
    private final Path registry = Paths.root().resolve("local-servers.json");
    /** Фоновые задачи: перезапуск после сбоя, автобэкапы, уведомления о входе. */
    private final java.util.concurrent.ScheduledExecutorService housekeeping =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cw-local-server-tasks");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean autoRestart;
    private volatile java.util.concurrent.ScheduledFuture<?> backupTask;
    private volatile java.util.concurrent.ScheduledFuture<?> joinTask;

    public LocalServerManager(JavaManager javaManager) {
        this.javaManager = javaManager;
        load();
    }

    public synchronized List<Server> servers() {
        return List.copyOf(servers);
    }

    /**
     * При старте лаунчера поднимает только серверы, у которых автозапуск включён вручную
     * в их настройках. Остальные остаются выключенными.
     */
    public void resume() {
        for (Server server : servers()) {
            if (server.autoStart) start(server.id);
            else adopt(server);
        }
    }

    /** Сервер, оставшийся работать после прошлого запуска лаунчера, показывается как запущенный. */
    private void adopt(Server server) {
        worker.submit(() -> {
            ProcessHandle h = handle(server);
            if (h != null && h.isAlive() || rcon(server, "list") != null) server.status = "Работает";
        });
    }

    public void start(String id) {
        Server server = find(id);
        if (server == null) return;
        worker.submit(() -> {
            try {
                startNow(server);
            } catch (Exception e) {
                server.status = "Ошибка: " + Log.reason(e);
                Log.error("Не удалось запустить локальный сервер " + server.name + ": " + Log.reason(e), e);
            }
        });
    }

    public void stop(String id) {
        Server server = find(id);
        if (server == null) return;
        server.stopping = true;
        worker.submit(() -> {
            server.status = "Останавливается";
            try {
                if (!sendRcon(server, "stop")) {
                    throw new IOException("Сервер не ответил на команду остановки. Проверьте server.log.");
                }
                Process p = server.process;
                if (p != null) p.waitFor(20, TimeUnit.SECONDS);
                server.status = "Остановлен";
                Log.info("Локальный сервер остановлен: " + server.name);
            } catch (Exception e) {
                server.status = "Ошибка остановки: " + Log.reason(e);
                Log.error("Не удалось остановить сервер " + server.name + ": " + Log.reason(e), e);
            }
        });
    }

    /** Создаёт чистую папку сервера, устанавливает Fabric Dedicated Server и запускает его. */
    public Server create(Draft draft, java.util.function.Consumer<String> progress) throws Exception {
        if (draft != null && (draft.loaderVersion == null || draft.loaderVersion.isBlank())) {
            draft = new Draft(draft.name, draft.description, draft.directory,
                    draft.minecraftVersion, latestLoader(draft.minecraftVersion), draft.acceptEula);
        }
        validate(draft);
        Path dest = draft.directory.toAbsolutePath().normalize();
        if (Files.exists(dest)) {
            try (var children = Files.list(dest)) {
                if (children.findAny().isPresent()) {
                    throw new IOException("Папка сервера уже существует и не пуста: " + dest);
                }
            }
        }
        if (!draft.acceptEula) throw new IOException("Для создания сервера необходимо принять Minecraft EULA.");

        Files.createDirectories(dest);
        int port = nextPort();
        Server server = new Server(UUID.randomUUID().toString(), draft.name.trim(),
                draft.description == null ? "" : draft.description.trim(), dest,
                draft.minecraftVersion, draft.loaderVersion, port, UUID.randomUUID().toString().replace("-", ""));
        synchronized (this) {
            servers.add(server);
            save();
        }
        try {
            progress.accept("Установка Fabric Dedicated Server");
            installFabricServer(server);
            writeServerFiles(server);
            // Созданный сервер остаётся выключенным: запускается только кнопкой «Запустить».
            server.status = "Остановлен";
            return server;
        } catch (Exception e) {
            server.status = "Не запущен: " + Log.reason(e);
            Log.error("Создание локального сервера " + server.name + " не завершено: " + Log.reason(e), e);
            throw e;
        }
    }

    private void startNow(Server server) throws Exception {
        synchronized (server) {
            server.status = "Проверка";
            if (sendRcon(server, "list")) {
                server.status = "Работает";
                return;
            }
            ProcessHandle running = handle(server);
            if (running != null && running.isAlive()) {
                // Уже работает (например, с прошлого запуска лаунчера) — второй процесс не нужен.
                server.status = "Работает";
                return;
            }
            Path launcher = server.directory.resolve("fabric-server-launch.jar");
            if (!Files.isRegularFile(launcher)) {
                server.status = "Подготовка";
                installFabricServer(server);
                writeServerFiles(server);
            }
            JavaManager.Runtime runtime = javaManager.selected();
            int requiredJava = JavaManager.requiredMajor(server.minecraftVersion);
            if (runtime == null || !JavaManager.accepts(runtime.major(), requiredJava)) {
                runtime = javaManager.select(server.minecraftVersion, "");
            }
            if (runtime == null || !JavaManager.accepts(runtime.major(), requiredJava)) {
                throw new IOException("Для сервера Minecraft " + server.minecraftVersion
                        + " нужна Java " + requiredJava + ".");
            }
            ensureRcon(server);
            Path log = server.directory.resolve("server.log");
            Process process = new ProcessBuilder(runtime.path(), "-Dcw.localServerId=" + server.id,
                    "-Xms512M", "-Xmx" + Math.max(1024, server.ramMb) + "M",
                    "-jar", "fabric-server-launch.jar", "nogui")
                    .directory(server.directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            server.process = process;
            server.stopping = false;
            server.status = "Запускается";
            try {
                Files.writeString(server.directory.resolve("server.pid"), Long.toString(process.pid()));
            } catch (IOException e) {
                Log.warn("Не удалось записать PID сервера " + server.name + ": " + Log.reason(e));
            }
            process.onExit().thenRun(() -> {
                server.status = "Остановлен";
                server.process = null;
                try {
                    Files.deleteIfExists(server.directory.resolve("server.pid"));
                } catch (IOException e) {
                    Log.warn("Не удалось удалить PID сервера " + server.name + ": " + Log.reason(e));
                }
                Log.info("Локальный сервер завершён: " + server.name);
                if (!server.stopping && autoRestart) {
                    Log.warn("Локальный сервер упал, перезапуск через 5 секунд: " + server.name);
                    server.status = "Перезапуск после сбоя";
                    housekeeping.schedule(() -> start(server.id), 5, TimeUnit.SECONDS);
                }
            });
            Log.info("Локальный сервер запускается: " + server.name + " (" + server.address() + ")");
        }
    }

    private void installFabricServer(Server server) throws Exception {
        JavaManager.Runtime runtime = javaManager.selected();
        int requiredJava = JavaManager.requiredMajor(server.minecraftVersion);
        if (runtime == null || !JavaManager.accepts(runtime.major(), requiredJava)) {
            runtime = javaManager.select(server.minecraftVersion, "");
        }
        if (runtime == null || !JavaManager.accepts(runtime.major(), requiredJava)) {
            throw new IOException("Для установки сервера нужна Java " + requiredJava + ".");
        }
        String cacheKey = "fabric-installer-" + server.loaderVersion + ".jar";
        Path installer = Paths.cache().resolve(cacheKey);
        if (!Files.isRegularFile(installer) || Files.size(installer) < 20_000) {
            String version = latestInstallerVersion();
            String url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
                    + version + "/fabric-installer-" + version + ".jar";
            Http.download(url, installer, null, 0, null, null);
        }
        Path installLog = server.directory.resolve("fabric-install.log");
        Process p = new ProcessBuilder(runtime.path(), "-jar", installer.toString(),
                "server", "-dir", server.directory.toString(),
                "-mcversion", server.minecraftVersion, "-loader", server.loaderVersion,
                "-downloadMinecraft")
                .directory(server.directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(installLog.toFile())
                .start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException("Установка Fabric-сервера превысила лимит времени. См. fabric-install.log.");
        }
        if (p.exitValue() != 0 || !Files.isRegularFile(server.directory.resolve("fabric-server-launch.jar"))) {
            throw new IOException("Fabric не установил сервер. Подробности: " + installLog);
        }
    }

    /** Стабильные версии Minecraft, для которых у Fabric есть сервер, от новых к старым. */
    public static List<String> fabricGameVersions() throws IOException, InterruptedException {
        List<String> out = new ArrayList<>();
        for (Object o : Json.parseArray(Http.get(FABRIC_META + "/game", "fabric-game-versions").body())) {
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) raw;
            String v = Json.str(item, "version", "");
            if (Json.bool(item, "stable", false) && v.matches("[0-9]+(?:\\.[0-9]+)+")) out.add(v);
        }
        return out;
    }

    /** Версия Minecraft по файлам готового сервера или null. */
    public static String detectVersion(Path dir) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?:mc\\.|server[.-])(1\\.[0-9]+(?:\\.[0-9]+)?)");
        try (var files = Files.list(dir)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                var m = p.matcher(f.getFileName().toString());
                if (m.find()) return m.group(1);
            }
        } catch (IOException ignored) {
        }
        Path versions = dir.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (var dirs = Files.list(versions)) {
                for (Path f : (Iterable<Path>) dirs::iterator) {
                    String n = f.getFileName().toString();
                    if (n.matches("1\\.[0-9]+(?:\\.[0-9]+)?")) return n;
                }
            } catch (IOException ignored) {
            }
        }
        return null;
    }

    private static String latestInstallerVersion() throws IOException, InterruptedException {
        String body = Http.get(FABRIC_META + "/installer", "fabric-installer-versions").body();
        List<Object> versions = Json.parseArray(body);
        if (versions.isEmpty() || !(versions.get(0) instanceof Map<?, ?> raw)) {
            throw new IOException("Fabric не вернул версию установщика.");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) raw;
        String version = Json.str(item, "version", "");
        if (!version.matches("[0-9]+(?:\\.[0-9A-Za-z-]+)+")) {
            throw new IOException("Некорректная версия Fabric Installer.");
        }
        return version;
    }

    private static String latestLoader(String minecraftVersion) throws IOException, InterruptedException {
        String body = Http.get(FABRIC_META + "/loader/" + minecraftVersion,
                "fabric-loaders-" + minecraftVersion).body();
        List<Object> versions = Json.parseArray(body);
        if (versions.isEmpty() || !(versions.get(0) instanceof Map<?, ?> raw)) {
            throw new IOException("Fabric не вернул совместимый загрузчик для Minecraft " + minecraftVersion + ".");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) raw;
        Map<String, Object> loader = Json.obj(item, "loader");
        String version = loader == null ? "" : Json.str(loader, "version", "");
        if (!version.matches("[0-9]+(?:\\.[0-9A-Za-z-]+)+")) {
            throw new IOException("Fabric вернул некорректную версию загрузчика.");
        }
        return version;
    }

    private static void writeServerFiles(Server server) throws IOException {
        Files.createDirectories(server.directory.resolve("mods"));
        String properties = "motd=" + server.name.replace("\n", " ").replace("\r", "") + "\n"
                + "server-port=" + server.port + "\n"
                + "server-ip=\n"
                + "online-mode=true\n"
                + "enable-rcon=true\n"
                + "rcon.password=" + server.rconPassword + "\n"
                + "rcon.port=" + rconPort(server.port) + "\n"
                + "broadcast-rcon-to-ops=false\n"
                + "enable-command-block=true\n";
        Path propertiesFile = server.directory.resolve("server.properties");
        if (!Files.exists(propertiesFile)) {
            Files.writeString(propertiesFile, properties, StandardCharsets.UTF_8);
        }
        Path eulaFile = server.directory.resolve("eula.txt");
        if (!Files.exists(eulaFile)) Files.writeString(eulaFile, "eula=true\n", StandardCharsets.UTF_8);
        String script = "@echo off\r\ncd /d \"%~dp0\"\r\n"
                + "java -Xms512M -Xmx2G -jar fabric-server-launch.jar nogui\r\npause\r\n";
        Path scriptFile = server.directory.resolve("start-server.cmd");
        if (!Files.exists(scriptFile)) Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
    }

    private static void validate(Draft draft) throws IOException {
        if (draft == null) throw new IOException("Не заданы параметры сервера.");
        String name = draft.name == null ? "" : draft.name.trim();
        if (name.isEmpty() || name.length() > 40 || name.matches(".*[\\r\\n<>].*")) {
            throw new IOException("Название сервера должно содержать от 1 до 40 символов.");
        }
        if (draft.description != null && draft.description.trim().length() > 160) {
            throw new IOException("Описание не должно превышать 160 символов.");
        }
        if (draft.directory == null || draft.minecraftVersion == null
                || !draft.minecraftVersion.matches("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?")
                || draft.loaderVersion == null || !draft.loaderVersion.matches("[0-9]+(?:\\.[0-9A-Za-z-]+)+")) {
            throw new IOException("Версия Minecraft или Fabric некорректна.");
        }
    }

    private int nextPort() throws IOException {
        List<Integer> used = servers().stream().map(server -> server.port).toList();
        for (int port = DEFAULT_PORT; port <= 65500; port++) {
            if (used.contains(port)) continue;
            try (java.net.ServerSocket socket = new java.net.ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(port));
                return port;
            } catch (IOException ignored) {
            }
        }
        throw new IOException("Нет доступных портов для локального сервера.");
    }

    private synchronized Server find(String id) {
        for (Server server : servers) if (server.id.equals(id)) return server;
        return null;
    }

    private void load() {
        if (!Files.isRegularFile(registry)) return;
        try {
            for (Object value : Json.parseArray(Files.readString(registry))) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> item = (Map<String, Object>) raw;
                String directoryText = Json.str(item, "directory", "");
                if (directoryText.isBlank()) continue;
                Path directory = Path.of(directoryText).toAbsolutePath().normalize();
                String id = Json.str(item, "id", "");
                String password = Json.str(item, "rconPassword", "");
                if (id.isBlank() || password.isBlank() || !Files.isDirectory(directory)) continue;
                // Две записи на одну папку (старое окно создания) — оставляем первую: им не поделить мир и порт.
                if (servers.stream().anyMatch(s -> s.directory.equals(directory))) {
                    Log.warn("Повторная запись локального сервера на ту же папку пропущена: " + directory);
                    continue;
                }
                Server server = new Server(id, Json.str(item, "name", "Сервер"),
                        Json.str(item, "description", ""), directory,
                        Json.str(item, "minecraftVersion", ""), Json.str(item, "loaderVersion", ""),
                        Json.integer(item, "port", DEFAULT_PORT), password);
                server.ramMb = Json.integer(item, "ramMb", 2048);
                server.autoStart = Json.bool(item, "startWithLauncher", false);
                servers.add(server);
            }
            Log.info("Локальные серверы загружены: " + servers.size());
        } catch (Exception e) {
            Log.error("Не удалось прочитать список локальных серверов: " + Log.reason(e), e);
        }
    }

    private synchronized void save() throws IOException {
        List<Object> entries = new ArrayList<>();
        for (Server server : servers) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", server.id);
            item.put("name", server.name);
            item.put("description", server.description);
            item.put("directory", server.directory.toString());
            item.put("minecraftVersion", server.minecraftVersion);
            item.put("loaderVersion", server.loaderVersion);
            item.put("port", server.port);
            item.put("rconPassword", server.rconPassword);
            item.put("ramMb", server.ramMb);
            item.put("startWithLauncher", server.autoStart);
            entries.add(item);
        }
        Utils.ensureDirectories(registry.getParent());
        Utils.writeString(registry, Json.write(entries));
    }

    private static boolean managedProcessAlive(Server server) {
        Path pidFile = server.directory.resolve("server.pid");
        if (!Files.isRegularFile(pidFile)) return false;
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            if (handle == null || !handle.isAlive()) {
                Files.deleteIfExists(pidFile);
                return false;
            }
            String command = handle.info().commandLine().orElse("");
            if (command.contains("-Dcw.localServerId=" + server.id)) return true;
            Log.warn("PID-файл сервера не совпадает с запущенным процессом: " + server.name);
            return false;
        } catch (Exception e) {
            Log.warn("Не удалось проверить PID сервера " + server.name + ": " + Log.reason(e));
            return false;
        }
    }

    private static boolean sendRcon(Server server, String command) {
        return rcon(server, command) != null;
    }

    /** Команда через RCON. Возвращает ответ сервера (может быть пустым) или null, если сервер не ответил. */
    private static String rcon(Server server, String command) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), rconPort(server.port)), 700);
            socket.setSoTimeout(3000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            sendRconPacket(out, 1, 3, server.rconPassword);
            String[] auth = readRconText(in);
            if (!"1".equals(auth[0])) return null;
            sendRconPacket(out, 2, 2, command);
            String[] response = readRconText(in);
            if (!"2".equals(response[0])) return null;
            return response[1];
        } catch (Exception ignored) {
            Log.debug("Локальный сервер пока не отвечает на RCON: " + server.name);
            return null;
        }
    }

    private static String[] readRconText(DataInputStream in) throws IOException {
        int size = readLittleEndian(in);
        if (size < 10 || size > 1_048_576) throw new IOException("Некорректный ответ RCON.");
        int id = readLittleEndian(in);
        readLittleEndian(in);
        byte[] payload = new byte[size - 10];
        in.readFully(payload);
        in.readByte();
        in.readByte();
        // Цветовые коды § в ответах команд не нужны в консоли лаунчера.
        String text = new String(payload, StandardCharsets.UTF_8).replaceAll("§.", "");
        return new String[]{String.valueOf(id), text};
    }

    // ------------------------------------------------------------------ управление из интерфейса

    public Server server(String id) {
        return find(id);
    }

    /** Команда консоли. Ответ или null, если сервер не отвечает. */
    public String command(String id, String command) {
        Server server = find(id);
        if (server == null || command == null || command.isBlank()) return null;
        String c = command.trim();
        if (c.startsWith("/")) c = c.substring(1);
        Log.info("Команда серверу " + server.name + ": " + c);
        return rcon(server, c);
    }

    /** Игроки онлайн по команде list. */
    public record Players(int online, int max, List<String> names) {
    }

    public Players players(String id) {
        Server server = find(id);
        if (server == null) return new Players(0, 0, List.of());
        String text = rcon(server, "list");
        int max = (int) Utils.parseInt(properties(id).getOrDefault("max-players", "20"), 20);
        if (text == null) return new Players(0, max, List.of());
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\D+?(\\d+)\\D*?players online:?\\s*(.*)", java.util.regex.Pattern.DOTALL).matcher(text);
        if (!m.find()) return new Players(0, max, List.of());
        List<String> names = new ArrayList<>();
        for (String n : m.group(3).split(",")) if (!n.isBlank()) names.add(n.trim());
        return new Players(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), names);
    }

    /** Нагрузка сервера: процессор, память процесса, диск папки, оценка TPS, время работы. */
    public record Stats(double cpu, long ramBytes, long ramMaxBytes, long diskBytes, double tps, long uptimeMs) {
    }

    private final Map<String, long[]> cpuSamples = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, long[]> diskCache = new java.util.concurrent.ConcurrentHashMap<>();

    public Stats stats(String id) {
        Server server = find(id);
        if (server == null) return new Stats(0, 0, 0, 0, 0, 0);
        ProcessHandle handle = handle(server);
        double cpu = 0;
        long ram = 0;
        long uptime = 0;
        if (handle != null && handle.isAlive()) {
            ProcessHandle.Info info = handle.info();
            long cpuNanos = info.totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L);
            long now = System.nanoTime();
            long[] prev = cpuSamples.put(id, new long[]{cpuNanos, now});
            if (prev != null && now > prev[1]) {
                cpu = (cpuNanos - prev[0]) * 100.0 / (now - prev[1]) / Runtime.getRuntime().availableProcessors();
            }
            uptime = info.startInstant().map(s -> System.currentTimeMillis() - s.toEpochMilli()).orElse(0L);
            ram = workingSet(handle.pid());
        }
        long[] disk = diskCache.get(id);
        if (disk == null || System.currentTimeMillis() - disk[1] > 30_000) {
            diskCache.put(id, new long[]{disk == null ? 0 : disk[0], System.currentTimeMillis()});
            housekeeping.submit(() -> diskCache.put(id, new long[]{Utils.dirSize(server.directory), System.currentTimeMillis()}));
        }
        long diskBytes = diskCache.get(id)[0];
        return new Stats(Math.max(0, Math.min(100, cpu)), ram, server.ramMb * 1024L * 1024L, diskBytes,
                handle != null && handle.isAlive() ? tps(server) : 0, uptime);
    }

    /**
     * Процесс сервера: запущенный в этом сеансе, по server.pid или по метке -Dcw.localServerId
     * в командной строке (если server.pid потерялся, а сервер работает с прошлого запуска лаунчера).
     */
    private static ProcessHandle handle(Server server) {
        Process p = server.process;
        if (p != null && p.isAlive()) return p.toHandle();
        Path pidFile = server.directory.resolve("server.pid");
        try {
            if (Files.isRegularFile(pidFile)) {
                ProcessHandle h = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElse(null);
                if (h != null && h.isAlive() && isJava(h)) return h;
            }
        } catch (Exception ignored) {
        }
        // Windows не отдаёт Java аргументы чужих процессов: ищем метку через системный список,
        // не чаще раза в 15 секунд, и запоминаем PID в server.pid.
        long now = System.currentTimeMillis();
        Long last = lookedUp.get(server.id);
        if (last != null && now - last < 15_000) return null;
        lookedUp.put(server.id, now);
        long pid = findByMarker(server.id);
        if (pid <= 0) return null;
        try {
            Files.writeString(pidFile, Long.toString(pid));
        } catch (IOException ignored) {
        }
        return ProcessHandle.of(pid).orElse(null);
    }

    private static final Map<String, Long> lookedUp = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean isJava(ProcessHandle h) {
        String cmd = h.info().command().orElse("").toLowerCase(Locale.ROOT);
        return cmd.endsWith("java.exe") || cmd.endsWith("javaw.exe") || cmd.endsWith("java");
    }

    /** PID процесса java с -Dcw.localServerId=id или -1. */
    private static long findByMarker(String id) {
        if (!id.matches("[0-9a-fA-F-]+")) return -1;
        try {
            Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    // без двойных кавычек: Java не экранирует их в аргументах Windows
                    "Get-CimInstance Win32_Process -Filter 'Name like ''java%''' | Where-Object { $_.CommandLine -like '*cw.localServerId="
                            + id + "*' } | Select-Object -First 1 -ExpandProperty ProcessId")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor(8, TimeUnit.SECONDS);
            String digits = out.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? -1 : Long.parseLong(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Работает ли процесс сервера (даже если RCON выключен). */
    public boolean alive(String id) {
        Server server = find(id);
        if (server == null) return false;
        ProcessHandle h = handle(server);
        return h != null && h.isAlive();
    }

    /** Включён ли RCON в server.properties: без него лаунчер не управляет сервером. */
    public boolean rconEnabled(String id) {
        return "true".equalsIgnoreCase(properties(id).getOrDefault("enable-rcon", "false"));
    }

    /** Перед запуском: RCON с паролем этого сервера, иначе лаунчер не сможет им управлять. */
    private void ensureRcon(Server server) {
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("enable-rcon", "true");
            p.put("rcon.port", String.valueOf(rconPort(server.port)));
            p.put("rcon.password", server.rconPassword);
            p.put("server-port", String.valueOf(server.port));
            saveProperties(server.id, p);
        } catch (IOException e) {
            Log.warn("RCON для " + server.name + " не включён: " + Log.reason(e));
        }
    }

    /** Рабочая память процесса через tasklist: Java не умеет узнать память чужого процесса. */
    private static long workingSet(long pid) {
        try {
            Process p = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            p.waitFor(3, TimeUnit.SECONDS);
            String[] cols = out.trim().split("\",\"");
            if (cols.length < 5) return 0;
            String digits = cols[4].replaceAll("[^0-9]", "");
            return digits.isEmpty() ? 0 : Long.parseLong(digits) * 1024L;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Оценка TPS по журналу: у ванильного сервера нет команды tps. Предупреждение
     * «Can't keep up! … N ticks behind» за последнюю минуту снижает оценку, без него — 20.
     */
    private static double tps(Server server) {
        List<String> lines = tail(server, 200);
        double tps = 20.0;
        java.time.LocalTime now = java.time.LocalTime.now();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^\\[(\\d\\d):(\\d\\d):(\\d\\d)].*Can't keep up!.*?(\\d+) ticks behind");
        for (String line : lines) {
            java.util.regex.Matcher m = p.matcher(line);
            if (!m.find()) continue;
            java.time.LocalTime t = java.time.LocalTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            long ago = java.time.Duration.between(t, now).getSeconds();
            if (ago < 0 || ago > 60) continue;
            tps = Math.min(tps, Math.max(1, 20.0 - Integer.parseInt(m.group(4)) / 3.0));
        }
        return tps;
    }

    /** Последние строки server.log. */
    public static List<String> tail(Server server, int lines) {
        Path log = server.directory.resolve("server.log");
        Path latest = server.directory.resolve("logs").resolve("latest.log");
        Path file = Files.isRegularFile(log) ? log : latest;
        if (!Files.isRegularFile(file)) return List.of();
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file.toFile(), "r")) {
            long len = f.length();
            long from = Math.max(0, len - 96 * 1024);
            f.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            f.readFully(buf);
            String[] all = new String(buf, StandardCharsets.UTF_8).replace("\r", "").split("\n");
            int start = Math.max(from > 0 ? 1 : 0, all.length - lines);
            List<String> out = new ArrayList<>();
            for (int i = start; i < all.length; i++) out.add(all[i]);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<String> tail(String id, int lines) {
        Server server = find(id);
        return server == null ? List.of() : tail(server, lines);
    }

    public void restart(String id) {
        Server server = find(id);
        if (server == null) return;
        server.stopping = true;
        worker.submit(() -> {
            try {
                server.status = "Перезапускается";
                rcon(server, "stop");
                ProcessHandle h = handle(server);
                if (h != null) h.onExit().get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.warn("Перезапуск " + server.name + ": " + Log.reason(e));
            }
        });
        start(id);
    }

    /** Принудительная остановка, если сервер завис и не отвечает. */
    public void kill(String id) {
        Server server = find(id);
        if (server == null) return;
        server.stopping = true;
        ProcessHandle h = handle(server);
        if (h != null) {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
        }
        server.status = "Остановлен";
        Log.warn("Локальный сервер остановлен принудительно: " + server.name);
    }

    /** Останавливает все серверы и ждёт их (закрытие лаунчера без «держать работающими»). */
    public void stopAllNow() {
        for (Server server : servers()) {
            ProcessHandle h = handle(server);
            if (h == null || !h.isAlive()) continue;
            server.stopping = true;
            rcon(server, "stop");
            try {
                h.onExit().get(20, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.warn("Сервер " + server.name + " не остановился вовремя");
            }
        }
    }

    public synchronized void configure(String id, int ramMb, boolean autoStart) throws IOException {
        Server server = find(id);
        if (server == null) return;
        server.ramMb = Math.max(1024, ramMb);
        server.autoStart = autoStart;
        save();
    }

    /** Убирает сервер из списка лаунчера. Папка с миром остаётся на диске. */
    public synchronized void forget(String id) throws IOException {
        Server server = find(id);
        if (server == null) return;
        servers.remove(server);
        save();
    }

    public void setAutoRestart(boolean on) {
        autoRestart = on;
    }

    /** Автобэкап всех запущенных серверов раз в minutes минут; хранится keep последних копий. */
    public void configureBackups(int minutes, int keep) {
        java.util.concurrent.ScheduledFuture<?> old = backupTask;
        if (old != null) old.cancel(false);
        backupTask = null;
        if (minutes <= 0) return;
        backupTask = housekeeping.scheduleAtFixedRate(() -> {
            for (Server server : servers()) {
                ProcessHandle h = handle(server);
                if (h == null || !h.isAlive()) continue;
                try {
                    backup(server.id);
                    pruneBackups(server, keep);
                } catch (Exception e) {
                    Log.warn("Автобэкап " + server.name + ": " + Log.reason(e));
                }
            }
        }, minutes, minutes, TimeUnit.MINUTES);
    }

    /** Следит за входом и выходом игроков на запущенных серверах. */
    public void watchJoins(java.util.function.Consumer<String> notify) {
        java.util.concurrent.ScheduledFuture<?> old = joinTask;
        if (old != null) old.cancel(false);
        joinTask = null;
        if (notify == null) return;
        Map<String, java.util.Set<String>> seen = new java.util.concurrent.ConcurrentHashMap<>();
        joinTask = housekeeping.scheduleWithFixedDelay(() -> {
            for (Server server : servers()) {
                ProcessHandle h = handle(server);
                if (h == null || !h.isAlive()) {
                    seen.remove(server.id);
                    continue;
                }
                java.util.Set<String> now = new java.util.LinkedHashSet<>(players(server.id).names());
                java.util.Set<String> before = seen.put(server.id, now);
                if (before == null) continue;
                for (String n : now) if (!before.contains(n)) notify.accept(n + " зашёл на «" + server.name + "»");
                for (String n : before) if (!now.contains(n)) notify.accept(n + " вышел с «" + server.name + "»");
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ server.properties

    public Map<String, String> properties(String id) {
        Server server = find(id);
        Map<String, String> out = new LinkedHashMap<>();
        if (server == null) return out;
        try {
            Path f = server.directory.resolve("server.properties");
            if (!Files.isRegularFile(f)) return out;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i > 0) out.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
            }
        } catch (IOException e) {
            Log.warn("server.properties: " + Log.reason(e));
        }
        return out;
    }

    /** Меняет только переданные ключи, остальное и комментарии сохраняются. */
    public void saveProperties(String id, Map<String, String> changes) throws IOException {
        Server server = find(id);
        if (server == null) return;
        Path f = server.directory.resolve("server.properties");
        List<String> lines = Files.isRegularFile(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            int eq = line.indexOf('=');
            if (line.startsWith("#") || eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            if (changes.containsKey(key)) {
                lines.set(i, key + "=" + changes.get(key));
                done.add(key);
            }
        }
        for (Map.Entry<String, String> e : changes.entrySet()) if (!done.contains(e.getKey())) lines.add(e.getKey() + "=" + e.getValue());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ миры и бэкапы

    /** Папки миров (с level.dat) в каталоге сервера. */
    public List<Path> worlds(String id) {
        Server server = find(id);
        List<Path> out = new ArrayList<>();
        if (server == null) return out;
        try (var list = Files.list(server.directory)) {
            list.filter(p -> Files.isRegularFile(p.resolve("level.dat"))).sorted().forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    public Path backupsDir(String id) {
        Server server = find(id);
        return server == null ? null : server.directory.resolve("backups");
    }

    public List<Path> backups(String id) {
        Path dir = backupsDir(id);
        List<Path> out = new ArrayList<>();
        if (dir == null || !Files.isDirectory(dir)) return out;
        try (var list = Files.list(dir)) {
            list.filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(java.util.Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    /** Архив всех миров сервера. На работающем сервере сохранение на время копирования выключается. */
    public Path backup(String id) throws IOException {
        Server server = find(id);
        if (server == null) throw new IOException("Сервер не найден");
        boolean live = rcon(server, "save-off") != null;
        if (live) rcon(server, "save-all flush");
        try {
            Path dir = server.directory.resolve("backups");
            Files.createDirectories(dir);
            String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path zip = dir.resolve(stamp + ".zip");
            try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
                for (Path world : worlds(id)) {
                    try (var walk = Files.walk(world)) {
                        for (Path p : walk.filter(Files::isRegularFile).toList()) {
                            if (p.getFileName().toString().equals("session.lock")) continue;
                            out.putNextEntry(new java.util.zip.ZipEntry(server.directory.relativize(p).toString().replace('\\', '/')));
                            Files.copy(p, out);
                            out.closeEntry();
                        }
                    }
                }
            }
            Log.info("Бэкап сервера " + server.name + ": " + zip.getFileName());
            return zip;
        } finally {
            if (live) rcon(server, "save-on");
        }
    }

    private void pruneBackups(Server server, int keep) {
        List<Path> all = backups(server.id);
        for (int i = Math.max(1, keep); i < all.size(); i++) {
            try {
                Files.deleteIfExists(all.get(i));
            } catch (IOException ignored) {
            }
        }
    }

    /** Восстанавливает миры из архива. Текущие миры переименовываются, а не удаляются. */
    public void restore(String id, Path zip) throws IOException {
        Server server = find(id);
        if (server == null) throw new IOException("Сервер не найден");
        ProcessHandle h = handle(server);
        if (h != null && h.isAlive()) throw new IOException("Сначала остановите сервер.");
        String stamp = String.valueOf(System.currentTimeMillis());
        for (Path world : worlds(id)) Files.move(world, world.resolveSibling(world.getFileName() + "-before-restore-" + stamp));
        try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            java.util.zip.ZipEntry e;
            Path root = server.directory.toAbsolutePath().normalize();
            while ((e = in.getNextEntry()) != null) {
                Path target = root.resolve(e.getName()).normalize();
                if (!target.startsWith(root)) throw new IOException("Архив повреждён");
                Files.createDirectories(target.getParent());
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Log.info("Мир сервера " + server.name + " восстановлен из " + zip.getFileName());
    }

    /**
     * Подключает существующую папку сервера. Порт берётся из server.properties,
     * RCON включается (лаунчеру он нужен для управления).
     */
    public Server importServer(String name, Path dir, String minecraftVersion) throws IOException {
        Path dest = dir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dest)) throw new IOException("Папка не найдена: " + dest);
        for (Server s : servers()) if (s.directory.equals(dest)) throw new IOException("Этот сервер уже в списке.");
        Path props = dest.resolve("server.properties");
        int port = DEFAULT_PORT;
        if (Files.isRegularFile(props)) {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                if (line.startsWith("server-port=")) {
                    try {
                        port = Integer.parseInt(line.substring(12).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        Server server = new Server(UUID.randomUUID().toString(), name == null || name.isBlank() ? dest.getFileName().toString() : name.trim(),
                "", dest, minecraftVersion, "", port, UUID.randomUUID().toString().replace("-", ""));
        synchronized (this) {
            servers.add(server);
            save();
        }
        Map<String, String> rconProps = new LinkedHashMap<>();
        rconProps.put("enable-rcon", "true");
        rconProps.put("rcon.password", server.rconPassword);
        rconProps.put("rcon.port", String.valueOf(rconPort(port)));
        saveProperties(server.id, rconProps);
        Path eula = dest.resolve("eula.txt");
        if (!Files.exists(eula)) Files.writeString(eula, "eula=true\n", StandardCharsets.UTF_8);
        Log.info("Импортирован сервер " + server.name + " из " + dest);
        return server;
    }

    private static void awaitReady(Server server) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (sendRcon(server, "list")) {
                server.status = "Работает";
                return;
            }
            Process process = server.process;
            if (process != null && !process.isAlive()) {
                throw new IOException("Сервер завершился при запуске. Подробности: "
                        + server.directory.resolve("server.log"));
            }
            Thread.sleep(1500);
        }
        throw new IOException("Сервер не подтвердил запуск за 2 минуты. Проверьте server.log.");
    }

    private static void sendRconPacket(DataOutputStream out, int id, int type, String payload) throws IOException {
        byte[] text = payload.getBytes(StandardCharsets.UTF_8);
        int size = text.length + 10;
        writeLittleEndian(out, size);
        writeLittleEndian(out, id);
        writeLittleEndian(out, type);
        out.write(text);
        out.writeByte(0);
        out.writeByte(0);
        out.flush();
    }

    private static int[] readRconPacket(DataInputStream in) throws IOException {
        int size = readLittleEndian(in);
        if (size < 10 || size > 4096) throw new IOException("Некорректный ответ RCON.");
        int id = readLittleEndian(in);
        int type = readLittleEndian(in);
        int payloadLength = size - 10;
        if (payloadLength > 0) in.skipNBytes(payloadLength);
        in.readByte();
        in.readByte();
        return new int[]{id, type};
    }

    private static void writeLittleEndian(DataOutputStream out, int value) throws IOException {
        out.writeByte(value);
        out.writeByte(value >>> 8);
        out.writeByte(value >>> 16);
        out.writeByte(value >>> 24);
    }

    private static int readLittleEndian(DataInputStream in) throws IOException {
        return in.readUnsignedByte() | in.readUnsignedByte() << 8
                | in.readUnsignedByte() << 16 | in.readUnsignedByte() << 24;
    }

    private static int rconPort(int gamePort) {
        return gamePort <= 55535 ? gamePort + 10000 : gamePort - 10000;
    }

    public static String primaryAddress() {
        List<String> addresses = addresses();
        return addresses.isEmpty() ? "127.0.0.1" : addresses.get(0);
    }

    public static List<String> addresses() {
        List<String> vpn = new ArrayList<>();
        List<String> local = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                String label = (network.getName() + " " + network.getDisplayName()).toLowerCase(Locale.ROOT);
                boolean virtualVpn = label.contains("radmin") || label.contains("hamachi");
                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()
                            || address.isLinkLocalAddress()) continue;
                    String value = address.getHostAddress();
                    (virtualVpn ? vpn : local).add(value);
                }
            }
        } catch (Exception e) {
            Log.warn("Не удалось определить сетевые адреса: " + Log.reason(e));
        }
        vpn.addAll(local);
        return List.copyOf(vpn);
    }
}
