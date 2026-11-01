# Собирает CWLauncher.exe в FullLauncherCW (jpackage app-image + иконка).
param([string]$OutDir = "")
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javac = "C:\Program Files\Java\jdk-17\bin\javac.exe"
$jar = "C:\Program Files\Java\jdk-17\bin\jar.exe"
$jpackage = "C:\Program Files\Java\jdk-17\bin\jpackage.exe"
$icon = Join-Path $root "icon\iconLauncher.ico"
if (-not (Test-Path $javac)) { throw "Не найден JDK 17: $javac" }
if (-not (Test-Path $icon)) { throw "Не найдена иконка: $icon" }

Set-Location $root
if (Test-Path build) { Remove-Item build -Recurse -Force }
New-Item -ItemType Directory -Path build | Out-Null
Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName } | Set-Content -Encoding ascii sources.rsp
& $javac -encoding UTF-8 -d build "@sources.rsp"
if ($LASTEXITCODE -ne 0) { throw "Компиляция не удалась" }

& $javac -encoding UTF-8 -cp build -d build (Join-Path $root "tests\SelfCheck.java")
if ($LASTEXITCODE -ne 0) { throw "Тесты не скомпилировались" }
& "C:\Program Files\Java\jdk-17\bin\java.exe" -cp build SelfCheck
if ($LASTEXITCODE -ne 0) { throw "SelfCheck не прошёл" }

$dist = Join-Path $root "dist"
if (Test-Path $dist) { Remove-Item $dist -Recurse -Force }
New-Item -ItemType Directory -Path $dist | Out-Null
& $jar --create --file (Join-Path $dist "CWLauncher.jar") --main-class ru.cw.launcher.Main -C build ru
if ($LASTEXITCODE -ne 0) { throw "jar не собран" }

$stage = Join-Path $root "_jpackage"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
& $jpackage --type app-image --name CWLauncher --input $dist --main-jar CWLauncher.jar `
  --main-class ru.cw.launcher.Main --icon $icon --dest $stage --app-version 4.4.5 `
  --vendor "CWLauncher" --description "CWLauncher" `
  --java-options "-Dfile.encoding=UTF-8" --java-options "-Xms64m" --java-options "-Xmx512m" `
  --java-options "-XX:+UseG1GC" --java-options "-XX:+UseStringDeduplication" --java-options "-XX:MaxGCPauseMillis=50" `
  --add-modules java.desktop,java.datatransfer,java.net.http,java.logging,java.naming,java.xml,java.prefs,jdk.crypto.ec,jdk.unsupported,jdk.management
if ($LASTEXITCODE -ne 0) { throw "jpackage не собрал приложение" }

# OutDir нужен, когда лаунчер из FullLauncherCW запущен и папку занять нельзя.
$out = if ($OutDir) { $OutDir } else { Join-Path $root "FullLauncherCW" }
$savedBg = Join-Path $env:TEMP "CWLauncher-main.png"
$existingBg = Join-Path $out "assets\background\main.png"
if (Test-Path $existingBg) { Copy-Item $existingBg $savedBg -Force }
$savedUpdater = Join-Path $env:TEMP "CWLauncher-Update.exe"
$existingUpdater = Join-Path $out "CWLauncher-Update.exe"
if (Test-Path $existingUpdater) { Copy-Item $existingUpdater $savedUpdater -Force }
$savedUninstall = Join-Path $env:TEMP "CWLauncher-Uninstall.exe"
$existingUninstall = Join-Path $out "Uninstall.exe"
if (Test-Path $existingUninstall) { Copy-Item $existingUninstall $savedUninstall -Force }
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null
Copy-Item -Path (Join-Path $stage "CWLauncher\*") -Destination $out -Recurse -Force
$jdkBin = "C:\Program Files\Java\jdk-17\bin"
Copy-Item (Join-Path $jdkBin "java.exe") (Join-Path $out "runtime\bin\java.exe") -Force
Copy-Item (Join-Path $jdkBin "javaw.exe") (Join-Path $out "runtime\bin\javaw.exe") -Force
$modsText = (& (Join-Path $out "runtime\bin\java.exe") --list-modules) -join "`n"
if ($modsText -notmatch "java\.desktop@") { throw "В runtime нет java.desktop, лаунчер не откроется" }

# Full Java 17 for Minecraft. The launcher runtime is trimmed and cannot start the game.
$jreZip = Join-Path $root ".cache\temurin-17-jre-windows-x64.zip"
if (-not (Test-Path $jreZip) -or (Get-Item $jreZip).Length -lt 20000000) {
    New-Item -ItemType Directory -Path (Split-Path $jreZip) -Force | Out-Null
    $jreUrl = "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jre/hotspot/normal/eclipse?project=jdk"
    Write-Output "Downloading Temurin 17 JRE"
    Invoke-WebRequest -Uri $jreUrl -OutFile $jreZip -UseBasicParsing
}
$zipHead = [IO.File]::ReadAllBytes($jreZip)[0..1]
if ($zipHead[0] -ne 0x50 -or $zipHead[1] -ne 0x4B) { throw "Downloaded Java file is not a ZIP" }
$jreDest = Join-Path $out "jre\17"
if (Test-Path $jreDest) { Remove-Item $jreDest -Recurse -Force }
New-Item -ItemType Directory -Path $jreDest -Force | Out-Null
Expand-Archive -Path $jreZip -DestinationPath $jreDest -Force
$gameJava = Get-ChildItem $jreDest -Recurse -Filter java.exe | Where-Object { $_.Directory.Name -eq "bin" } | Select-Object -First 1
if (-not $gameJava) { throw "Bundled Java has no java.exe" }
$gameJaw = Join-Path $gameJava.Directory.FullName "javaw.exe"
if (-not (Test-Path $gameJaw)) { throw "Bundled Java has no javaw.exe" }
$verFile = Join-Path $env:TEMP "cw-jre-ver.txt"
Start-Process -FilePath $gameJava.FullName -ArgumentList "-version" -Wait -NoNewWindow -RedirectStandardError $verFile -RedirectStandardOutput "$verFile.out" | Out-Null
$gameVer = Get-Content $verFile -Raw
if ($gameVer -notmatch "17\.0") { throw "Bundled Java is not 17" }
Write-Output "Bundled Java 17: $($gameJava.FullName)"
$bg = Join-Path $out "assets\background"
New-Item -ItemType Directory -Path $bg -Force | Out-Null
$repoBg = Join-Path $root "assets\background\main.png"
if (Test-Path $repoBg) { Copy-Item $repoBg (Join-Path $bg "main.png") -Force }
elseif (Test-Path $savedBg) { Copy-Item $savedBg (Join-Path $bg "main.png") -Force }
$ui = Join-Path $out "assets\ui"
New-Item -ItemType Directory -Path $ui -Force | Out-Null
# Картинки интерфейса: макет с логотипом и фон боковой панели.
$uiSrc = Join-Path $root "assets\ui"
if (Test-Path $uiSrc) { Copy-Item (Join-Path $uiSrc "*.png") $ui -Force }
$publishedUpdater = "W:\ProjectCode\CWLInstaller\dist\CWLauncher-Update.exe"
$updaterDest = Join-Path $out "CWLauncher-Update.exe"
if (Test-Path $publishedUpdater) { Copy-Item $publishedUpdater $updaterDest -Force }
elseif (Test-Path $savedUpdater) { Copy-Item $savedUpdater $updaterDest -Force }
$publishedSetup = "W:\ProjectCode\CWLInstaller\dist\CWLauncher-Setup.exe"
$uninstallDest = Join-Path $out "Uninstall.exe"
if (Test-Path $publishedSetup) { Copy-Item $publishedSetup $uninstallDest -Force }
elseif (Test-Path $savedUninstall) { Copy-Item $savedUninstall $uninstallDest -Force }
else { throw "Uninstall.exe is missing" }
Copy-Item (Join-Path $root "README.md") (Join-Path $out "README.md") -Force
Copy-Item (Join-Path $root "config\example-config.json") (Join-Path $out "example-config.json") -Force
Write-Output "READY $(Join-Path $out 'CWLauncher.exe')"

# v3 shell tests and its start script next to the exe; CWLauncher.exe itself runs ru.cw.launcher.Main.
$testsOut = Join-Path $out "tests"
New-Item -ItemType Directory -Path $testsOut -Force | Out-Null
$testBuild = Join-Path $root "build_tests"
if (Test-Path $testBuild) { Remove-Item $testBuild -Recurse -Force }
New-Item -ItemType Directory -Path $testBuild | Out-Null
& $javac -encoding UTF-8 -cp build -d $testBuild (Join-Path $root "tests\ShellCheck.java") (Join-Path $root "tests\ShellFlow.java") (Join-Path $root "tests\ShellShot.java")
if ($LASTEXITCODE -ne 0) { throw "Shell tests did not compile" }
& $jar --create --file (Join-Path $testsOut "shell-tests.jar") -C $testBuild .
Remove-Item $testBuild -Recurse -Force
Copy-Item (Join-Path $root "scripts\*.cmd") $out -Force
Write-Output "SHELL TESTS READY"
