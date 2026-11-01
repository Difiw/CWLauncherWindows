package ru.cw.launcher.minecraft;

import ru.cw.launcher.util.Json;
import ru.cw.launcher.util.Log;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Назначение клавиш Java Minecraft 1.20: действия по разделам, как в меню игры,
 * названия клавиш модов из их языковых файлов и перевод между клавишами Windows
 * и именами GLFW из options.txt.
 */
public final class KeyBindings {

    public static final String UNBOUND = "key.keyboard.unknown";

    public record Action(String id, String title, String def) {
    }

    public record Group(String title, List<Action> actions) {
    }

    private KeyBindings() {
    }

    private static Action a(boolean ru, String id, String rus, String eng, String def) {
        return new Action(id, ru ? rus : eng, def);
    }

    public static List<Group> vanilla(boolean ru) {
        List<Group> g = new ArrayList<>();
        g.add(new Group(ru ? "Движение" : "Movement", List.of(
                a(ru, "key.forward", "Вперёд", "Walk forwards", "key.keyboard.w"),
                a(ru, "key.left", "Влево", "Strafe left", "key.keyboard.a"),
                a(ru, "key.back", "Назад", "Walk backwards", "key.keyboard.s"),
                a(ru, "key.right", "Вправо", "Strafe right", "key.keyboard.d"),
                a(ru, "key.jump", "Прыжок", "Jump", "key.keyboard.space"),
                a(ru, "key.sneak", "Красться", "Sneak", "key.keyboard.left.shift"),
                a(ru, "key.sprint", "Бег", "Sprint", "key.keyboard.left.control"))));
        g.add(new Group(ru ? "Игровой процесс" : "Gameplay", List.of(
                a(ru, "key.attack", "Атаковать / разрушить", "Attack / destroy", "key.mouse.left"),
                a(ru, "key.use", "Использовать / поставить", "Use item / place block", "key.mouse.right"),
                a(ru, "key.pickItem", "Выбрать блок", "Pick block", "key.mouse.middle"))));
        List<Action> inv = new ArrayList<>();
        inv.add(a(ru, "key.drop", "Выбросить предмет", "Drop selected item", "key.keyboard.q"));
        inv.add(a(ru, "key.inventory", "Инвентарь", "Open/close inventory", "key.keyboard.e"));
        inv.add(a(ru, "key.swapOffhand", "Сменить руку", "Swap item with offhand", "key.keyboard.f"));
        for (int i = 1; i <= 9; i++) {
            inv.add(a(ru, "key.hotbar." + i, "Ячейка " + i, "Hotbar slot " + i, "key.keyboard." + i));
        }
        g.add(new Group(ru ? "Инвентарь" : "Inventory", inv));
        g.add(new Group(ru ? "Сетевая игра" : "Multiplayer", List.of(
                a(ru, "key.chat", "Открыть чат", "Open chat", "key.keyboard.t"),
                a(ru, "key.command", "Ввести команду", "Open command", "key.keyboard.slash"),
                a(ru, "key.playerlist", "Список игроков", "List players", "key.keyboard.tab"),
                a(ru, "key.socialInteractions", "Социальные взаимодействия", "Social interactions", "key.keyboard.p"))));
        g.add(new Group(ru ? "Разное" : "Miscellaneous", List.of(
                a(ru, "key.screenshot", "Снимок экрана", "Take screenshot", "key.keyboard.f2"),
                a(ru, "key.togglePerspective", "Сменить вид", "Toggle perspective", "key.keyboard.f5"),
                a(ru, "key.smoothCamera", "Кинематографичная камера", "Cinematic camera", UNBOUND),
                a(ru, "key.fullscreen", "Полный экран", "Toggle fullscreen", "key.keyboard.f11"),
                a(ru, "key.spectatorOutlines", "Подсветка игроков (наблюдатель)", "Highlight players (spectators)", UNBOUND),
                a(ru, "key.advancements", "Достижения", "Advancements", "key.keyboard.l"))));
        g.add(new Group(ru ? "Творческий режим" : "Creative mode", List.of(
                a(ru, "key.saveToolbarActivator", "Сохранить панель", "Save hotbar", "key.keyboard.c"),
                a(ru, "key.loadToolbarActivator", "Загрузить панель", "Load hotbar", "key.keyboard.x"))));
        return g;
    }

    public static boolean isVanilla(String id) {
        for (Group g : vanilla(true)) for (Action x : g.actions()) if (x.id().equals(id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ клавиши модов

    /** Переводы из модов: ключ перевода → текст, id мода → имя мода. */
    private record ModTexts(Map<String, String> text, Map<String, String> modNames) {
    }

    private static final Map<String, ModTexts> CACHE = new HashMap<>();

    /**
     * Клавиши модов по группам, с названиями как в игре. Имена берутся из
     * assets/МОД/lang/ru_ru.json (или en_us.json) внутри jar модов.
     */
    public static List<Group> modGroups(Path modsDir, List<String> ids, Map<String, String> current, boolean ru) {
        ModTexts t = texts(modsDir, ru);
        Map<String, List<Action>> byMod = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (String id : ids) {
            String mod = modOf(id);
            String group = t.text().get("key.categories." + mod);
            if (group == null) group = t.modNames().get(mod);
            if (group == null) group = humanize(mod);
            titles.putIfAbsent(mod, group);
            String title = t.text().get(id);
            if (title == null || title.isBlank()) {
                String rest = id.length() > 5 + mod.length() ? id.substring(5 + mod.length()) : id;
                title = humanize(rest);
            }
            byMod.computeIfAbsent(mod, k -> new ArrayList<>()).add(new Action(id, title, current.getOrDefault(id, UNBOUND)));
        }
        List<Group> out = new ArrayList<>();
        for (Map.Entry<String, List<Action>> e : byMod.entrySet()) out.add(new Group(titles.get(e.getKey()), e.getValue()));
        out.sort((x, y) -> x.title().compareToIgnoreCase(y.title()));
        return out;
    }

    /** Читает языковые файлы модов заранее, чтобы раздел «Управление» открывался без задержки. */
    public static void warm(Path modsDir, boolean ru) {
        texts(modsDir, ru);
    }

    private static String modOf(String id) {
        String s = id.startsWith("key.") ? id.substring(4) : id;
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }

    private static String humanize(String s) {
        String r = s.replace('.', ' ').replace('_', ' ').replace('-', ' ').trim();
        r = r.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return r.isEmpty() ? s : Character.toUpperCase(r.charAt(0)) + r.substring(1);
    }

    private static synchronized ModTexts texts(Path modsDir, boolean ru) {
        String key = modsDir + "|" + ru + "|" + lastModified(modsDir);
        ModTexts cached = CACHE.get(key);
        if (cached != null) return cached;
        Map<String, String> text = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        String want = ru ? "ru_ru.json" : "en_us.json";
        if (modsDir != null && Files.isDirectory(modsDir)) {
            try (var list = Files.list(modsDir)) {
                for (Path jar : list.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                    readJar(jar, want, text, names);
                }
            } catch (Exception e) {
                Log.warn("Названия клавиш модов: " + Log.reason(e));
            }
        }
        ModTexts t = new ModTexts(text, names);
        CACHE.put(key, t);
        return t;
    }

    private static long lastModified(Path dir) {
        try {
            return Files.getLastModifiedTime(dir).toMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    private static void readJar(Path jar, String want, Map<String, String> text, Map<String, String> names) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry meta = zip.getEntry("fabric.mod.json");
            if (meta != null) {
                try {
                    Map<String, Object> m = Json.parseObject(new String(zip.getInputStream(meta).readAllBytes(), StandardCharsets.UTF_8));
                    Object id = m.get("id");
                    Object name = m.get("name");
                    if (id != null && name != null) names.put(String.valueOf(id), String.valueOf(name));
                } catch (Exception ignored) {
                    // Нестандартный fabric.mod.json: имя мода возьмём из id.
                }
            }
            Map<String, String> en = new HashMap<>();
            Map<String, String> wanted = new HashMap<>();
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                String n = e.getName();
                if (!n.startsWith("assets/") || !n.contains("/lang/")) continue;
                boolean isWanted = n.endsWith("/" + want);
                boolean isEn = n.endsWith("/en_us.json");
                if (!isWanted && !isEn) continue;
                try {
                    Map<String, Object> m = Json.parseObject(new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8));
                    for (Map.Entry<String, Object> kv : m.entrySet()) {
                        if (!kv.getKey().startsWith("key.") || kv.getValue() == null) continue;
                        (isWanted ? wanted : en).put(kv.getKey(), String.valueOf(kv.getValue()));
                    }
                } catch (Exception ignored) {
                    // Битый языковой файл одного мода не мешает остальным.
                }
            }
            for (Map.Entry<String, String> kv : en.entrySet()) text.putIfAbsent(kv.getKey(), kv.getValue());
            text.putAll(wanted);
        } catch (Exception e) {
            Log.warn("Jar " + jar.getFileName() + ": " + Log.reason(e));
        }
    }

    // ------------------------------------------------------------------ коды клавиш

    private static final Map<Integer, String> AWT = new LinkedHashMap<>();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<String, String> EN_NAMES = Map.of("space", "Space", "left.shift", "Left Shift",
            "right.shift", "Right Shift", "left.control", "Left Ctrl", "right.control", "Right Ctrl", "left.alt", "Left Alt",
            "right.alt", "Right Alt", "up", "Up arrow", "down", "Down arrow", "left", "Left arrow");

    static {
        for (char c = 'A'; c <= 'Z'; c++) AWT.put((int) c, String.valueOf(Character.toLowerCase(c)));
        for (char c = '0'; c <= '9'; c++) AWT.put((int) c, String.valueOf(c));
        for (int i = 1; i <= 12; i++) AWT.put(KeyEvent.VK_F1 + i - 1, "f" + i);
        for (int i = 13; i <= 24; i++) AWT.put(KeyEvent.VK_F13 + i - 13, "f" + i);
        AWT.put(KeyEvent.VK_SPACE, "space");
        AWT.put(KeyEvent.VK_TAB, "tab");
        AWT.put(KeyEvent.VK_CAPS_LOCK, "caps.lock");
        AWT.put(KeyEvent.VK_ENTER, "enter");
        AWT.put(KeyEvent.VK_BACK_SPACE, "backspace");
        AWT.put(KeyEvent.VK_INSERT, "insert");
        AWT.put(KeyEvent.VK_DELETE, "delete");
        AWT.put(KeyEvent.VK_HOME, "home");
        AWT.put(KeyEvent.VK_END, "end");
        AWT.put(KeyEvent.VK_PAGE_UP, "page.up");
        AWT.put(KeyEvent.VK_PAGE_DOWN, "page.down");
        AWT.put(KeyEvent.VK_UP, "up");
        AWT.put(KeyEvent.VK_DOWN, "down");
        AWT.put(KeyEvent.VK_LEFT, "left");
        AWT.put(KeyEvent.VK_RIGHT, "right");
        AWT.put(KeyEvent.VK_SLASH, "slash");
        AWT.put(KeyEvent.VK_BACK_SLASH, "backslash");
        AWT.put(KeyEvent.VK_SEMICOLON, "semicolon");
        AWT.put(KeyEvent.VK_QUOTE, "apostrophe");
        AWT.put(KeyEvent.VK_COMMA, "comma");
        AWT.put(KeyEvent.VK_PERIOD, "period");
        AWT.put(KeyEvent.VK_MINUS, "minus");
        AWT.put(KeyEvent.VK_EQUALS, "equal");
        AWT.put(KeyEvent.VK_OPEN_BRACKET, "left.bracket");
        AWT.put(KeyEvent.VK_CLOSE_BRACKET, "right.bracket");
        AWT.put(KeyEvent.VK_BACK_QUOTE, "grave.accent");
        AWT.put(KeyEvent.VK_NUM_LOCK, "num.lock");
        AWT.put(KeyEvent.VK_SCROLL_LOCK, "scroll.lock");
        AWT.put(KeyEvent.VK_PRINTSCREEN, "print.screen");
        AWT.put(KeyEvent.VK_PAUSE, "pause");
        AWT.put(KeyEvent.VK_CONTEXT_MENU, "menu");
        AWT.put(KeyEvent.VK_NUMPAD0, "keypad.0");
        AWT.put(KeyEvent.VK_NUMPAD1, "keypad.1");
        AWT.put(KeyEvent.VK_NUMPAD2, "keypad.2");
        AWT.put(KeyEvent.VK_NUMPAD3, "keypad.3");
        AWT.put(KeyEvent.VK_NUMPAD4, "keypad.4");
        AWT.put(KeyEvent.VK_NUMPAD5, "keypad.5");
        AWT.put(KeyEvent.VK_NUMPAD6, "keypad.6");
        AWT.put(KeyEvent.VK_NUMPAD7, "keypad.7");
        AWT.put(KeyEvent.VK_NUMPAD8, "keypad.8");
        AWT.put(KeyEvent.VK_NUMPAD9, "keypad.9");
        AWT.put(KeyEvent.VK_ADD, "keypad.add");
        AWT.put(KeyEvent.VK_SUBTRACT, "keypad.subtract");
        AWT.put(KeyEvent.VK_MULTIPLY, "keypad.multiply");
        AWT.put(KeyEvent.VK_DIVIDE, "keypad.divide");
        AWT.put(KeyEvent.VK_DECIMAL, "keypad.decimal");

        NAMES.put("space", "Пробел");
        NAMES.put("left.shift", "Левый Shift");
        NAMES.put("right.shift", "Правый Shift");
        NAMES.put("left.control", "Левый Ctrl");
        NAMES.put("right.control", "Правый Ctrl");
        NAMES.put("left.alt", "Левый Alt");
        NAMES.put("right.alt", "Правый Alt");
        NAMES.put("left.win", "Левый Win");
        NAMES.put("right.win", "Правый Win");
        NAMES.put("tab", "Tab");
        NAMES.put("caps.lock", "Caps Lock");
        NAMES.put("enter", "Enter");
        NAMES.put("backspace", "Backspace");
        NAMES.put("insert", "Insert");
        NAMES.put("delete", "Delete");
        NAMES.put("home", "Home");
        NAMES.put("end", "End");
        NAMES.put("page.up", "Page Up");
        NAMES.put("page.down", "Page Down");
        NAMES.put("up", "Стрелка вверх");
        NAMES.put("down", "Стрелка вниз");
        NAMES.put("left", "Стрелка влево");
        NAMES.put("right", "Стрелка вправо");
        NAMES.put("slash", "/");
        NAMES.put("backslash", "\\");
        NAMES.put("semicolon", ";");
        NAMES.put("apostrophe", "' (Э)");
        NAMES.put("comma", ", (запятая)");
        NAMES.put("period", ". (точка)");
        NAMES.put("minus", "-");
        NAMES.put("equal", "=");
        NAMES.put("left.bracket", "[");
        NAMES.put("right.bracket", "]");
        NAMES.put("grave.accent", "` (Ё)");
        NAMES.put("num.lock", "Num Lock");
        NAMES.put("scroll.lock", "Scroll Lock");
        NAMES.put("print.screen", "Print Screen");
        NAMES.put("pause", "Pause");
        NAMES.put("menu", "Menu");
        NAMES.put("keypad.enter", "Num Enter");
        NAMES.put("keypad.add", "Num +");
        NAMES.put("keypad.subtract", "Num -");
        NAMES.put("keypad.multiply", "Num *");
        NAMES.put("keypad.divide", "Num /");
        NAMES.put("keypad.decimal", "Num .");
    }

    /** Код Minecraft для нажатой клавиши, null — клавишу нельзя назначить. */
    public static String fromKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean right = e.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT;
        boolean pad = e.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD;
        String name = switch (code) {
            case KeyEvent.VK_SHIFT -> right ? "right.shift" : "left.shift";
            case KeyEvent.VK_CONTROL -> right ? "right.control" : "left.control";
            case KeyEvent.VK_ALT -> right ? "right.alt" : "left.alt";
            case KeyEvent.VK_ALT_GRAPH -> "right.alt";
            case KeyEvent.VK_WINDOWS -> right ? "right.win" : "left.win";
            case KeyEvent.VK_ENTER -> pad ? "keypad.enter" : "enter";
            default -> AWT.get(code);
        };
        if (name == null) {
            // Русская раскладка: буква берётся по физической клавише.
            int ex = e.getExtendedKeyCode();
            if (ex >= 'A' && ex <= 'Z') name = String.valueOf((char) Character.toLowerCase(ex));
        }
        return name == null ? null : "key.keyboard." + name;
    }

    public static String fromMouse(MouseEvent e) {
        return switch (e.getButton()) {
            case MouseEvent.BUTTON1 -> "key.mouse.left";
            case MouseEvent.BUTTON2 -> "key.mouse.middle";
            case MouseEvent.BUTTON3 -> "key.mouse.right";
            default -> e.getButton() > 3 ? "key.mouse." + e.getButton() : null;
        };
    }

    /** Подпись клавиши для интерфейса. */
    public static String label(String code, boolean ru) {
        if (code == null || code.isBlank() || UNBOUND.equals(code)) return ru ? "Не назначено" : "Not bound";
        if (code.startsWith("key.mouse.")) {
            String b = code.substring(10);
            return switch (b) {
                case "left" -> ru ? "ЛКМ" : "Left mouse";
                case "right" -> ru ? "ПКМ" : "Right mouse";
                case "middle" -> ru ? "Колесо мыши" : "Middle mouse";
                default -> (ru ? "Кнопка мыши " : "Mouse button ") + b;
            };
        }
        String k = code.startsWith("key.keyboard.") ? code.substring(13) : code;
        String named = NAMES.get(k);
        if (named != null) return ru ? named : EN_NAMES.getOrDefault(k, named.replaceAll(" \\(.*\\)$", ""));
        if (k.startsWith("keypad.")) return "Num " + k.substring(7);
        return k.toUpperCase(Locale.ROOT);
    }
}
