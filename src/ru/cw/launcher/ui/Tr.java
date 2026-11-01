package ru.cw.launcher.ui;

/**
 * Перевод строк нового интерфейса прямо в месте использования: русский и английский.
 * Остальные языки, как и в {@link Lang}, показываются по-английски.
 */
public final class Tr {

    private Tr() {
    }

    public static String t(String ru, String en) {
        return Lang.russian() ? ru : en;
    }
}
