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

    public LocalServerManager(JavaManager javaManager) {
        this.javaManager = javaManager;
        load();
    }

    public synchronized List<Server> servers() {
        return List.copyOf(servers);
    }

    /** Поднимает ранее созданные серверы при следующем запуске лаунчера. */
    public void resume() {
        for (Server server : servers()) start(server.id);
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
            progress.accept("Запуск сервера");
            startNow(server);
            awaitReady(server);
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
            if (managedProcessAlive(server)) {
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
            Path log = server.directory.resolve("server.log");
            Process process = new ProcessBuilder(runtime.path(), "-Dcw.localServerId=" + server.id,
                    "-Xms512M", "-Xmx2G",
                    "-jar", "fabric-server-launch.jar", "nogui")
                    .directory(server.directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            server.process = process;
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
                Server server = new Server(id, Json.str(item, "name", "Сервер"),
                        Json.str(item, "description", ""), directory,
                        Json.str(item, "minecraftVersion", ""), Json.str(item, "loaderVersion", ""),
                        Json.integer(item, "port", DEFAULT_PORT), password);
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
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), rconPort(server.port)), 700);
            socket.setSoTimeout(1200);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            sendRconPacket(out, 1, 3, server.rconPassword);
            int[] auth = readRconPacket(in);
            if (auth[0] == -1 || auth[0] != 1) return false;
            sendRconPacket(out, 2, 2, command);
            int[] response = readRconPacket(in);
            if (response[0] != 2) return false;
            return true;
        } catch (Exception ignored) {
            Log.debug("Локальный сервер пока не отвечает на RCON: " + server.name);
            return false;
        }
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
