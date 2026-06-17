@echo off
REM ── Nemesis (Krypton) — Minecraft 1.19.3 server launcher ───────────────────
REM Run this from a real terminal / by double-clicking so you can see the logs.
cd /d "%~dp0"

if not exist "jar\build\libs\Krypton.jar" (
    echo Krypton.jar not found. Build it first:
    echo    gradlew :jar:shadowJar -x test
    pause
    exit /b 1
)

if not exist "world" (
    echo WARNING: no 'world' folder found. Krypton has no world generation,
    echo so it needs a vanilla world folder named per config.conf ^(name=world^).
)

echo Starting Nemesis (Minecraft 1.19.3) on port 25565 ...
java -Xms1G -Xmx2G -jar "jar\build\libs\Krypton.jar"

echo.
echo Server stopped.
pause
