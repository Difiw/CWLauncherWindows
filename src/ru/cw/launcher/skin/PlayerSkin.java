package ru.cw.launcher.skin;

import ru.cw.launcher.accounts.Account;
import ru.cw.launcher.util.Log;
import ru.cw.launcher.util.Paths;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Скин конкретного аккаунта: голова для профиля и файл для игры. */
public final class PlayerSkin {

    private static volatile BufferedImage face;
    private static volatile Path file;
    private static long stamp = -1;
    private static String ownerId = "";
    private static final String SELECTED = ".selected";
    /** Содержимое .selected, когда выбран скин с Ely.by, а не файл из папки. */
    private static final String ELY = "@ely";

    private PlayerSkin() {
    }

    public static Path directory(Account account) {
        return account == null ? null : Paths.accountSkinDir(account.id);
    }

    public static BufferedImage face() {
        return face;
    }

    public static Path file() {
        return file;
    }

    /**
     * Голова скина этого аккаунта: выбранный локальный скин, а у Ely.by без него — скин с Ely.
     * Ничего нет — null, и тогда рисуется значок профиля.
     */
    public static BufferedImage headOfAccount(Account account) {
        if (account == null) return null;
        Path selected = selected(account);
        if (selected != null) return headOf(selected);
        ElySkin.Entry ely = ElySkin.cached(account);
        return ely == null ? null : headOf(ely.image());
    }

    /** Ely-аккаунт играет в скине с Ely.by: выбран он сам или своих скинов нет. */
    public static boolean usesEly(Account account) {
        return ElySkin.applies(account) && selected(account) == null;
    }

    /**
     * Выбирает скин с Ely.by. Локальные скины остаются в библиотеке, но при запуске
     * игры в Ely ничего не перезаписывается.
     */
    public static void chooseEly(Account account) throws IOException {
        if (!ElySkin.applies(account)) throw new IOException("Это не аккаунт Ely.by");
        Path dir = Paths.accountSkinDir(account.id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SELECTED), ELY);
        refresh(account, true);
    }

    /** Один сохранённый вариант скина. */
    public record SkinEntry(Path file, String name, boolean selected) {
    }

    private static final String ORDER = "order.txt";

    /**
     * Все скины профиля в порядке, который задал пользователь (order.txt). Выбор скина порядок
     * не меняет; новые скины добавляются в конец.
     */
    public static List<SkinEntry> all(Account account) {
        if (account == null) return List.of();
        Path dir = Paths.accountSkinDir(account.id);
        Path chosen = selected(account);
        List<Path> files = new ArrayList<>();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile).filter(PlayerSkin::image)
                    .sorted(Comparator.comparingLong(PlayerSkin::modified))
                    .forEach(files::add);
        } catch (IOException ignored) {
        }
        List<String> order = readOrder(dir);
        files.sort(Comparator.comparingInt(p -> {
            int i = order.indexOf(p.getFileName().toString());
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        List<SkinEntry> result = new ArrayList<>();
        for (Path p : files) result.add(new SkinEntry(p, displayName(p), same(p, chosen)));
        return List.copyOf(result);
    }

    private static List<String> readOrder(Path dir) {
        try {
            Path f = dir.resolve(ORDER);
            if (!Files.isRegularFile(f)) return List.of();
            List<String> out = new ArrayList<>();
            for (String line : Files.readAllLines(f)) if (!line.isBlank()) out.add(line.trim());
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Сохраняет порядок скинов профиля после перестановки. */
    public static void saveOrder(Account account, List<Path> skins) throws IOException {
        if (account == null) return;
        Path dir = Paths.accountSkinDir(account.id);
        Files.createDirectories(dir);
        List<String> names = new ArrayList<>();
        for (Path p : skins) names.add(p.getFileName().toString());
        Files.write(dir.resolve(ORDER), names);
    }

    /**
     * Сохраняет скин из редактора. existing — файл, который правили (перезаписывается),
     * null — новый скин с именем name; новый сразу выбирается. Возвращает путь файла.
     */
    public static Path save(Account account, Path existing, BufferedImage image, String name) throws IOException {
        if (account == null || image == null) throw new IOException("Нет профиля или картинки");
        Path dir = Paths.accountSkinDir(account.id);
        Files.createDirectories(dir);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        byte[] png = bytes.toByteArray();
        if (existing != null) {
            Path normalized = existing.toAbsolutePath().normalize();
            if (!normalized.startsWith(dir.toAbsolutePath().normalize())) throw new IOException("Скин не принадлежит профилю");
            Files.write(normalized, png);
            if (same(normalized, selected(account))) refresh(account, true);
            return normalized;
        }
        String base = safeBase((name == null || name.isBlank() ? "skin" : name.trim()) + ".png");
        Path target = dir.resolve(base + "-" + digest(png).substring(0, 12) + ".png");
        Files.write(target, png);
        choose(account, target);
        return target;
    }

    /** Добавляет скин в библиотеку этого профиля и сразу выбирает его. */
    public static Path assign(Account account, Path source) throws IOException {
        if (account == null || source == null) throw new IOException("Нет аккаунта или файла");
        BufferedImage image = read(source);
        if (image == null) throw new IOException("Файл не является читаемым изображением");
        if (!validLayout(image)) throw new IOException("Нужен скин Minecraft 64×32, 64×64 или кратного размера");
        Path dir = Paths.accountSkinDir(account.id);
        Files.createDirectories(dir);
        byte[] png = gamePng(source);
        if (png == null || png.length == 0) throw new IOException("Не удалось подготовить PNG для Minecraft");
        String base = safeBase(source.getFileName().toString());
        String hash = digest(png).substring(0, 12);
        Path target = dir.resolve(base + "-" + hash + ".png");
        Files.write(target, png);
        choose(account, target);
        return target;
    }

    /** Выбирает уже сохранённый скин профиля. */
    public static void choose(Account account, Path skin) throws IOException {
        if (account == null || skin == null) throw new IOException("Нет профиля или скина");
        Path dir = Paths.accountSkinDir(account.id).toAbsolutePath().normalize();
        Path normalized = skin.toAbsolutePath().normalize();
        if (!normalized.startsWith(dir) || !Files.isRegularFile(normalized) || !image(normalized)) {
            throw new IOException("Скин не принадлежит выбранному профилю");
        }
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(SELECTED), normalized.getFileName().toString());
        refresh(account, true);
    }

    /** Удаляет вариант. Если он был выбран, выбирается следующий. */
    public static void remove(Account account, Path skin) throws IOException {
        if (account == null || skin == null) return;
        Path dir = Paths.accountSkinDir(account.id).toAbsolutePath().normalize();
        Path normalized = skin.toAbsolutePath().normalize();
        if (!normalized.startsWith(dir)) throw new IOException("Скин не принадлежит выбранному профилю");
        boolean wasSelected = same(normalized, selected(account));
        Files.deleteIfExists(normalized);
        if (wasSelected) {
            Path next = newest(dir);
            // У Ely-аккаунта после удаления выбранного скина возвращается скин с Ely.by.
            if (ElySkin.applies(account)) Files.writeString(dir.resolve(SELECTED), ELY);
            else if (next == null) Files.deleteIfExists(dir.resolve(SELECTED));
            else Files.writeString(dir.resolve(SELECTED), next.getFileName().toString());
        }
        refresh(account, true);
    }

    /** Перечитывает папку скина аккаунта. Возвращает true, если голова изменилась. */
    public static boolean refresh(Account account) {
        return refresh(account, false);
    }

    private static boolean refresh(Account account, boolean force) {
        if (account != null) adoptLegacy(account);
        Path newest = account == null ? null : selected(account);
        ElySkin.Entry ely = newest == null ? ElySkin.cached(account) : null;
        String nextOwner = account == null || account.id == null ? "" : account.id;
        long next = stampOf(newest) ^ (ely == null ? 0 : System.identityHashCode(ely.image()) * 31L);
        if (!force && nextOwner.equals(ownerId) && next == stamp && (account != null || face == null)) return false;
        ownerId = nextOwner;
        stamp = next;
        // file — только локальный скин: его лаунчер сам отдаёт игре. Скин Ely игра берёт у Ely.
        file = newest;
        face = newest != null ? headOf(newest) : ely != null ? headOf(ely.image()) : null;
        if (newest != null) Log.info("Скин " + account.username + ": " + newest.getFileName());
        else if (ely != null) Log.info("Скин " + account.username + ": с Ely.by");
        return true;
    }

    /** Старый общий файл из папки игры один раз переносится активному аккаунту. */
    private static void adoptLegacy(Account account) {
        Path marker = Paths.root().resolve("skins").resolve(".legacy-adopted");
        if (Files.exists(marker)) return;
        Path own = Paths.accountSkinDir(account.id);
        if (newest(own) != null) {
            try {
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, account.id);
            } catch (IOException ignored) {
            }
            return;
        }
        Path legacy = newest(Paths.gameDir().resolve("skin"));
        if (legacy == null) return;
        try {
            Files.createDirectories(own);
            Path adopted = own.resolve(legacy.getFileName().toString());
            Files.copy(legacy, adopted, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(own.resolve(SELECTED), adopted.getFileName().toString());
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, account.id);
            Log.info("Скин из общей папки закреплён за аккаунтом " + account.username);
        } catch (IOException e) {
            Log.warn("Скин не перенесён: " + Log.reason(e));
        }
    }

    private static Path selected(Account account) {
        if (account == null) return null;
        Path dir = Paths.accountSkinDir(account.id);
        Path marker = dir.resolve(SELECTED);
        try {
            if (Files.isRegularFile(marker)) {
                String name = Files.readString(marker).trim();
                if (ELY.equals(name)) {
                    if (ElySkin.applies(account)) return null;
                    name = "";
                }
                Path candidate = dir.resolve(name).normalize();
                if (candidate.startsWith(dir.normalize()) && Files.isRegularFile(candidate) && image(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
        }
        Path fallback = newest(dir);
        if (fallback != null) {
            try {
                Files.writeString(marker, fallback.getFileName().toString());
            } catch (IOException ignored) {
            }
        }
        return fallback;
    }

    private static boolean same(Path a, Path b) {
        return a != null && b != null && a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static boolean validLayout(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        return w >= 64 && w % 64 == 0 && (h == w || h * 2 == w);
    }

    private static String safeBase(String name) {
        String base = name == null ? "skin" : name.replaceFirst("(?i)\\.(png|jpe?g)$", "");
        base = base.replaceAll("[^A-Za-z0-9А-Яа-яЁё_-]+", "-").replaceAll("^-+|-+$", "");
        return base.isBlank() ? "skin" : base.substring(0, Math.min(48, base.length()));
    }

    private static String displayName(Path path) {
        String n = path.getFileName().toString().replaceFirst("(?i)\\.png$", "");
        return n.replaceFirst("-[0-9a-f]{12}$", "");
    }

    private static String digest(byte[] data) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IOException("Не удалось вычислить идентификатор скина", e);
        }
    }

    private static long stampOf(Path path) {
        if (path == null) return 0;
        try {
            return Files.getLastModifiedTime(path).toMillis() ^ Files.size(path);
        } catch (IOException e) {
            return -2;
        }
    }

    private static Path newest(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (Stream<Path> list = Files.list(dir)) {
            return list.filter(Files::isRegularFile)
                    .filter(PlayerSkin::image)
                    .max(Comparator.comparingLong(PlayerSkin::modified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean image(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** PNG 64×32 или 64×64, который Minecraft принимает без модов. */
    public static byte[] gamePng(Path path) {
        BufferedImage img = read(path);
        if (img == null) return null;
        BufferedImage skin = minecraftSize(img);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(skin, "png", out)) return null;
            return out.toByteArray();
        } catch (IOException e) {
            Log.warn("Скин не подготовлен для игры: " + Log.reason(e));
            return null;
        }
    }

    /** Узкие руки, если в файле модель Alex. */
    public static boolean slim(Path path) {
        BufferedImage img = read(path);
        if (img == null || img.getWidth() < 64 || img.getHeight() < 32) return false;
        int scale = Math.max(1, img.getWidth() / 64);
        int x = 54 * scale;
        int y = 20 * scale;
        if (x >= img.getWidth() || y >= img.getHeight()) return false;
        return (img.getRGB(x, y) >>> 24) == 0;
    }

    private static BufferedImage read(Path path) {
        try {
            return ImageIO.read(path.toFile());
        } catch (IOException e) {
            return null;
        }
    }

    private static BufferedImage minecraftSize(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < 64 || w % 64 != 0 || h < 32) return img;
        int scale = w / 64;
        int targetH = h >= w ? 64 : 32;
        if (scale == 1 && (h == 32 || h == 64)) return img;
        BufferedImage out = new BufferedImage(64, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        int srcH = Math.min(h, targetH * scale);
        g.drawImage(img, 0, 0, 64, targetH, 0, 0, w, Math.max(1, srcH), null);
        g.dispose();
        return out;
    }

    private static BufferedImage headOf(Path path) {
        try {
            return headOf(ImageIO.read(path.toFile()));
        } catch (IOException e) {
            Log.warn("Скин не прочитан: " + Log.reason(e));
            return null;
        }
    }

    /** Лицо 8×8 со слоем шлема. Голова есть и у старых скинов 64×32. */
    public static BufferedImage headOf(BufferedImage img) {
        if (img == null) return null;
        int scale = img.getWidth() / 64;
        if (scale >= 1 && img.getWidth() == scale * 64 && img.getHeight() >= scale * 32) {
            BufferedImage face = new BufferedImage(8 * scale, 8 * scale, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = face.createGraphics();
            g.drawImage(img, 0, 0, 8 * scale, 8 * scale, 8 * scale, 8 * scale, 16 * scale, 16 * scale, null);
            g.drawImage(img, 0, 0, 8 * scale, 8 * scale, 40 * scale, 8 * scale, 48 * scale, 16 * scale, null);
            g.dispose();
            return face;
        }
        return img;
    }

    public static void paint(Graphics2D g, BufferedImage face, int x, int y, int size) {
        if (face == null || size <= 0) return;
        Graphics2D cg = (Graphics2D) g.create();
        cg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        cg.drawImage(face, x, y, size, size, null);
        cg.dispose();
    }
}
