package ru.cw.launcher.util;

import javax.swing.*;
import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Выбор папки обычным окном проводника Windows (то же, что «Открыть» в любых программах).
 * Swing JFileChooser выглядит чужеродно, а AWT FileDialog папки выбирать не умеет, поэтому окно
 * открывает системный диалог: пользователь заходит в нужную папку и жмёт «Открыть».
 */
public final class FolderPicker {

    private static final String PLACEHOLDER = "Выбор папки";

    private FolderPicker() {
    }

    /** Возвращает выбранную папку или null, если окно закрыли. start — с чего начать, может быть пустым. */
    public static Path choose(Component parent, String title, String start) {
        AtomicReference<Path> result = new AtomicReference<>();
        // Свой цикл событий: окно лаунчера остаётся живым (не «не отвечает»), пока открыт проводник.
        SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        boolean onEdt = SwingUtilities.isEventDispatchThread();
        Thread worker = new Thread(() -> {
            try {
                result.set(runDialog(title, start));
            } catch (Exception e) {
                Log.warn("Окно выбора папки: " + Log.reason(e));
            } finally {
                if (onEdt) loop.exit();
            }
        }, "cw-folder-picker");
        worker.setDaemon(true);
        worker.start();
        if (onEdt) loop.enter();
        else {
            try {
                worker.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return result.get();
    }

    private static Path runDialog(String title, String start) throws Exception {
        StringBuilder ps = new StringBuilder();
        ps.append("Add-Type -AssemblyName System.Windows.Forms;");
        ps.append("[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;");
        ps.append("$d=New-Object System.Windows.Forms.OpenFileDialog;");
        ps.append("$d.ValidateNames=$false;$d.CheckFileExists=$false;$d.CheckPathExists=$true;");
        ps.append("$d.FileName='").append(PLACEHOLDER).append("';");
        ps.append("$d.Title='").append(quote(title)).append("';");
        if (start != null && !start.isBlank()) {
            try {
                Path p = Path.of(start);
                if (Files.isDirectory(p)) ps.append("$d.InitialDirectory='").append(quote(p.toString())).append("';");
            } catch (RuntimeException ignored) {
                // неверный путь: окно откроется в папке по умолчанию
            }
        }
        // Невидимое окно сверху: диалог не прячется за лаунчером.
        ps.append("$o=New-Object System.Windows.Forms.Form;$o.TopMost=$true;");
        ps.append("if($d.ShowDialog($o) -eq 'OK'){[Console]::Out.Write((Split-Path -Parent $d.FileName))}");
        String encoded = Base64.getEncoder().encodeToString(ps.toString().getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-STA",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                .redirectErrorStream(false).start();
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            in.transferTo(out);
        }
        process.waitFor();
        String text = out.toString(StandardCharsets.UTF_8).replace("﻿", "").trim();
        if (text.isEmpty()) return null;
        Path chosen = Path.of(text);
        return Files.isDirectory(chosen) ? chosen : null;
    }

    private static String quote(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
