import ru.cw.launcher.accounts.ElyAuthManager;
import ru.cw.launcher.core.Progress;
import ru.cw.launcher.core.Settings;
import ru.cw.launcher.mods.ModManager;
import ru.cw.launcher.security.Secrets;
import ru.cw.launcher.skin.ElySkin;
import ru.cw.launcher.skin.PlayerSkin;
import ru.cw.launcher.updates.UpdateParser;
import ru.cw.launcher.util.Json;
import ru.cw.launcher.util.Utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Проверки без сети и без окна: версии, ZIP Slip, битый config, редактирование секретов. */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        check(Utils.compareVersions("1.0.1", "1.0.0") > 0, "1.0.1 новее 1.0.0");
        check(Utils.compareVersions("1.0.0", "1.0.0") == 0, "равные версии");
        check(Utils.compareVersions("1.2", "1.10") < 0, "1.2 старше 1.10");
        check(Utils.compareVersions("3.2.1", "3.2.0") > 0, "3.2.1 новее 3.2.0");
        check(Utils.compareVersions("3.10.0", "3.9.9") > 0, "3.10.0 новее 3.9.9");
        check("3.2.1".equals(UpdateParser.versionOf("UpdateV3.2.1.txt")), "UpdateV3.2.1");
        check("5.0.4.1".equals(UpdateParser.versionOf("UpdateV5.0.4.1.txt")), "UpdateV5.0.4.1");
        check(Utils.compareVersions("5.0.4.1", "5.0.4") > 0, "5.0.4.1 новее 5.0.4");
        check(UpdateParser.newestFirst(java.util.List.of(
                UpdateParser.note("UpdateV5.0.4.txt", ""),
                UpdateParser.note("UpdateV5.0.4.1.txt", ""))).get(0).version().equals("5.0.4.1"),
                "5.0.4.1 первым в списке");
        check(ru.cw.launcher.updater.UpdateChecker.launcherUpdateNeeded("3.6.6", "3.6.7"), "3.6.7 — обновление");
        check(!ru.cw.launcher.updater.UpdateChecker.launcherUpdateNeeded("3.6.7", "3.6.7"), "та же версия — нет кнопки");
        check(!ru.cw.launcher.updater.UpdateChecker.launcherUpdateNeeded("3.6.7", "3.6.6"), "старая версия — нет кнопки");
        check(UpdateParser.versionOf("Update3.2.1.txt") == null, "без V не читается");
        check(UpdateParser.versionOf("Update_3.2.1.txt") == null, "подчёркивание не читается");
        check(UpdateParser.versionOf("Update-3.2.1.txt") == null, "дефис не читается");
        check("Обновление 3.2.1".equals(UpdateParser.titleOf("3.2.1")), "название без V и txt");
        check(ModManager.isModsVersionName("modsVersion.txt"), "modsVersion.txt");
        check(ModManager.isModsVersionName("modsVersion.txt.txt"), "двойное .txt от Windows");
        check(ModManager.isModsVersionName("version.txt"), "version.txt");
        check("3.1.1".equals(ModManager.versionToken("3.1.1")), "токен 3.1.1");
        check("3.1.1".equals(ModManager.versionToken("\uFEFF3.1.1\r\n")), "BOM и перевод строки");
        check(Utils.compareVersions("3.1.1", "3.1.1") == 0, "совпадающие версии модов");
        Progress pace = new Progress();
        long mb = 1024L * 1024L;
        pace.progress(40 * mb, 56 * mb, "загрузка");
        check(pace.percent() == 71, "40 из 56 МБ это 71%");

        Settings broken = Settings.fromMap(null);
        broken.validate();
        check("1.20.1".equals(broken.minecraftVersion), "пустой config даёт 1.20.1");
        check(broken.autoUpdateBuild && broken.autoUpdateLauncher, "два автообновления включены по умолчанию");
        check(!broken.uiAnim, "анимации интерфейса выключены по умолчанию");
        broken.ramMb = 1;
        broken.validate();
        check(broken.ramMb >= 512, "слишком маленький RAM поднят до минимума");

        String red = Secrets.redact("accessToken=secret-value password=qwerty");
        check(red != null && !red.contains("secret-value") && !red.contains("qwerty"), "секреты вырезаются из текста");

        Path dir = Files.createTempDirectory("cw-zip");
        Path zip = dir.resolve("bad.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.putNextEntry(new ZipEntry("../escape.txt"));
            zos.write("no".getBytes());
            zos.closeEntry();
        }
        Path target = dir.resolve("out");
        boolean slipped = false;
        try {
            Utils.extractZip(zip, target);
        } catch (Exception e) {
            slipped = e.getMessage() != null && e.getMessage().contains("ZIP Slip");
        }
        String written = Json.write(java.util.Map.of("minecraftVersion", "1.20.1", "autoUpdateBuild", true));
        check(written.contains("\"minecraftVersion\":") && !written.contains("\"minecraftVersion\"\""), "JSON без лишней кавычки");
        check("1.20.1".equals(Json.str(Json.parseObject(written), "minecraftVersion", "")), "JSON читается обратно");

        String auth = ElyAuthManager.authorizeUrl("cw-id", "https://cw.example/oauth/ely", "state-1");
        check(auth.startsWith("https://account.ely.by/oauth2/v1?"), "OAuth URL Ely.by");
        check(auth.contains("response_type=code"), "response_type=code");
        check(auth.contains("minecraft_server_session"), "scope minecraft_server_session");
        check(auth.contains("offline_access") && auth.contains("account_info"), "scope account_info и offline_access");
        check(!auth.contains("client_secret") && !auth.contains("password"), "в ссылке нет секрета и пароля");
        check(ElyAuthManager.redirectAllowed("https://cw.example/oauth/ely"), "https redirect допустим");
        check(!ElyAuthManager.redirectAllowed("http://127.0.0.1:47622/callback"), "localhost запрещён");
        check(!ElyAuthManager.backendAllowed("http://cw.example/oauth/ely"), "сервер CW только https");
        check(ElyAuthManager.sameState("abc", "abc") && !ElyAuthManager.sameState("abc", "abd"), "state сравнивается");
        check(!ElyAuthManager.sameState("abc", null), "пустой state отклоняется");
        check("Вход отменён".equals(ElyAuthManager.explainOauthError("access_denied", "denied")), "отмена входа");

        // Ответ sessionserver Ely.by для Gelrd (снят 01.11.2026): скин 64×64, модель Steve.
        String gelrd = "{\"id\":\"e019cfa93b074b7d9281601bc1cc8ba3\",\"name\":\"Gelrd\",\"properties\":[{\"name\":\"textures\",\"value\":\""
                + "eyJ0aW1lc3RhbXAiOjE3OTM1NDY1Nzk0MzYsInByb2ZpbGVJZCI6ImUwMTljZmE5M2IwNzRiN2Q5MjgxNjAxYmMxY2M4YmEzIiwicHJvZmlsZU5hbWUiOiJHZWxyZCIsInRleHR1cmVzIjp7IlNLSU4iOnsidXJsIjoiaHR0cDovL2VseS5ieS9zdG9yYWdlL3NraW5zLzM2YmFlZDkwMmJkYzA0ZWM2NDVhNWVjMjdjNjE5MDZiLnBuZyJ9fX0="
                + "\"},{\"name\":\"ely\",\"value\":\"x\"}]}";
        ElySkin.Texture tex = ElySkin.fromProfile(gelrd);
        check(tex != null && tex.url().endsWith("/storage/skins/36baed902bdc04ec645a5ec27c61906b.png"), "URL скина Ely из профиля");
        check(tex != null && !tex.slim(), "модель Steve без metadata");
        check(ElySkin.fromProfile("{\"id\":\"x\",\"properties\":[]}") == null, "профиль без текстур — скина нет");
        ElySkin.Texture alex = ElySkin.fromTextures(Json.parseObject(
                "{\"SKIN\":{\"url\":\"http://ely.by/s.png\",\"metadata\":{\"model\":\"slim\"}}}"));
        check(alex != null && alex.slim(), "модель Alex из metadata");
        check(ElySkin.fromTextures(Json.parseObject("{}")) == null, "пустые textures — скина нет");
        check(ElySkin.decode(new byte[]{1, 2, 3, 4}) == null, "битый PNG не читается и не роняет лаунчер");
        check(ElySkin.validLayout(new java.awt.image.BufferedImage(64, 64, 2)), "64×64 подходит");
        check(ElySkin.validLayout(new java.awt.image.BufferedImage(64, 32, 2)), "64×32 подходит");
        check(ElySkin.validLayout(new java.awt.image.BufferedImage(128, 128, 2)), "HD 128×128 подходит");
        check(!ElySkin.validLayout(new java.awt.image.BufferedImage(50, 50, 2)), "50×50 отклоняется");
        java.awt.image.BufferedImage old = new java.awt.image.BufferedImage(64, 32, 2);
        check(PlayerSkin.headOf(old).getWidth() == 8, "голова из старого скина 64×32");

        check(slipped, "ZIP Slip отклонён");
        check(!Files.exists(dir.resolve("escape.txt")), "файл за пределами каталога не создан");

        System.out.println("SelfCheck OK");
    }

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
        System.out.println("OK  " + name);
    }
}
