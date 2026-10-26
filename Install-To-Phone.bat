@echo off
title "CTRLX - Build & Install to Phone"
echo ========================================================
echo       CTRLX Mobile Client - Build ^& Install
echo ========================================================
echo.

if exist "C:\Program Files\Android\openjdk\jdk-21.0.8" (
    set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
) else if exist "C:\Program Files\Android\Android Studio\jbr" (
    set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
) else if exist "C:\Program Files\Java\jdk-25.0.4.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not defined ADB (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else if exist "C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe" (
        set "ADB=C:\Program Files (x86)\Android\android-sdk\platform-tools\adb.exe"
    ) else (
        set "ADB=adb.exe"
    )
)

echo [*] Building Android APK...
cd /d "%~dp0mobile-client"
call gradlew.bat assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Gradle build failed with error %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)

set "APK_PATH=%~dp0mobile-client\app\build\outputs\apk\debug\app-debug.apk"
echo.
echo [OK] Build successful!
echo APK Location: "%APK_PATH%"
echo.

echo [*] Detecting connected Android phone...
set "TARGET_DEV="

for /f "tokens=1,2" %%A in ('"%ADB%" devices') do (
    if "%%B"=="device" (
        if not defined TARGET_DEV (
            set "TARGET_DEV=%%A"
        )
    )
)

if not defined TARGET_DEV (
    echo [!] No active phone detected via ADB.
    echo.
    echo [i] Note: You can simply copy this APK file to your phone and install it:
    echo     "%APK_PATH%"
    echo.
    pause
    exit /b 0
)

echo [*] Installing to device: %TARGET_DEV%
"%ADB%" -s "%TARGET_DEV%" install -r "%APK_PATH%"

if %ERRORLEVEL% EQU 0 (
    echo.
    echo [OK] Installed successfully!
    echo [*] Launching CTRLX on phone...
    "%ADB%" -s "%TARGET_DEV%" shell am start -n com.ctrlx.mobile/.MainActivity
) else (
    echo.
    echo [!] Install command failed.
)

echo.
pause
