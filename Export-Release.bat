@echo off
setlocal enabledelayedexpansion
title "CTRLX - Export Standalone Distribution"

echo ========================================================
echo       CTRLX - Release Exporter [Standalone EXE and APK]
echo ========================================================
echo.

:: 1. Detect Java Home
if exist "C:\Program Files\Java\jdk-25.0.4.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
) else if exist "C:\Program Files\Android\openjdk\jdk-21.0.8" (
    set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
)

if not defined JAVA_HOME (
    echo [!] Could not locate Java JDK. Please set JAVA_HOME.
    pause
    exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

:: 2. Detect Maven
set "MVN_CMD=mvn.cmd"
if exist "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd" (
    set "MVN_CMD=C:\Tools\apache-maven-3.9.16\bin\mvn.cmd"
)

:: 3. Detect jpackage
set "JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
if not exist "%JPACKAGE%" (
    if exist "C:\Program Files\Java\jdk-25\bin\jpackage.exe" (
        set "JPACKAGE=C:\Program Files\Java\jdk-25\bin\jpackage.exe"
    ) else if exist "C:\Program Files\Android\openjdk\jdk-21.0.8\bin\jpackage.exe" (
        set "JPACKAGE=C:\Program Files\Android\openjdk\jdk-21.0.8\bin\jpackage.exe"
    )
)

:: 4. Detect Inno Setup Compiler (Optional)
set "ISCC="
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" (
    set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
) else if exist "C:\Program Files\Inno Setup 6\ISCC.exe" (
    set "ISCC=C:\Program Files\Inno Setup 6\ISCC.exe"
)

cd /d "%~dp0"
if not exist "dist" mkdir "dist"

set "TARGET_CHOICE=%~1"
if not defined TARGET_CHOICE (
    echo Select export target:
    echo   [1] Export Both Windows [EXE/ZIP] and Android [APK] - Recommended
    echo   [2] Export Windows PC Standalone Executable only
    echo   [3] Export Android Mobile APK only
    echo.
    set /p TARGET_CHOICE="Enter choice [1-3] (Default: 1): "
)
if "%TARGET_CHOICE%"=="" set TARGET_CHOICE=1

if "%TARGET_CHOICE%"=="1" goto do_both
if "%TARGET_CHOICE%"=="all" goto do_both
if "%TARGET_CHOICE%"=="2" goto do_win
if "%TARGET_CHOICE%"=="windows" goto do_win
if "%TARGET_CHOICE%"=="3" goto do_android
if "%TARGET_CHOICE%"=="android" goto do_android

echo Invalid choice.
pause
exit /b 1

:do_both
call :build_windows
call :build_android
goto do_summary

:do_win
call :build_windows
goto do_summary

:do_android
call :build_android
goto do_summary

:do_summary
echo.
echo ========================================================
echo                 EXPORT COMPLETE!
echo ========================================================
echo Output files available in: %~dp0dist
echo.
powershell -NoProfile -Command "Get-ChildItem -Path '%~dp0dist' | Select-Object Name, @{Name='Size (MB)';Expression={[math]::Round($_.Length/1MB, 2)}}, LastWriteTime | Format-Table -AutoSize"
echo.
echo --------------------------------------------------------
echo How to publish online neatly:
echo  1. GitHub Releases:
echo     - Go to your repository -^> Releases -^> 'Draft a new release'
echo     - Upload 'CTRLX-v1.0.0-Windows-x64.zip' and 'CTRLX-v1.0.0-Android.apk'
echo  2. Windows Users:
echo     - Download 'CTRLX-v1.0.0-Windows-x64.zip', extract, and double-click 'CTRLX.exe'.
echo     - Zero prerequisites required (Java runtime is fully bundled).
echo  3. Android Users:
echo     - Download 'CTRLX-v1.0.0-Android.apk' directly on phone and tap Install.
echo     - Pre-signed with release certificate for immediate installation.
echo --------------------------------------------------------
echo.
pause
exit /b 0

:: ----------------------------------------------------------
:: Subroutine: Build Windows Standalone Executable
:: ----------------------------------------------------------
:build_windows
echo.
echo ========================================================
echo [*] [1/2] Building Windows PC Client Standalone Executable...
echo ========================================================

if not exist "%JPACKAGE%" (
    echo [!] jpackage.exe not found at %JPACKAGE%. Cannot build standalone executable.
    exit /b 1
)

echo [*] Compiling Java modules with Maven...
call "%MVN_CMD%" clean package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo [!] Maven build failed.
    pause
    exit /b %ERRORLEVEL%
)

echo [*] Resolving runtime dependencies...
call "%MVN_CMD%" -pl pc-client dependency:copy-dependencies -DincludeScope=runtime
if %ERRORLEVEL% NEQ 0 (
    echo [!] Dependency copy failed.
    pause
    exit /b %ERRORLEVEL%
)

echo [*] Staging input packages for jpackage...
set "STAGE_DIR=%~dp0pc-client\target\jpackage-input"
if exist "%STAGE_DIR%" rmdir /s /q "%STAGE_DIR%"
mkdir "%STAGE_DIR%"

copy /y "%~dp0pc-client\target\dependency\*.jar" "%STAGE_DIR%\" >nul
copy /y "%~dp0pc-client\target\pc-client-1.0.0.jar" "%STAGE_DIR%\" >nul

if exist "%~dp0dist\CTRLX" rmdir /s /q "%~dp0dist\CTRLX"

echo [*] Running jpackage (bundling stripped JRE + CTRLX.exe)...
"%JPACKAGE%" ^
    --type app-image ^
    --dest "%~dp0dist" ^
    --name CTRLX ^
    --input "%STAGE_DIR%" ^
    --main-jar pc-client-1.0.0.jar ^
    --main-class com.ctrlx.desktop.Launcher ^
    --java-options "--enable-native-access=ALL-UNNAMED" ^
    --app-version 1.0.0

if %ERRORLEVEL% NEQ 0 (
    echo [!] jpackage failed with error %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)

echo [*] Bundling native ViGEmBus client libraries...
if not exist "%~dp0dist\CTRLX\bridge" mkdir "%~dp0dist\CTRLX\bridge"
if exist "%~dp0bridge\ViGEmClient.dll" copy /y "%~dp0bridge\ViGEmClient.dll" "%~dp0dist\CTRLX\bridge\" >nul
if exist "%~dp0bridge\ViGEmClient.dll" copy /y "%~dp0bridge\ViGEmClient.dll" "%~dp0dist\CTRLX\" >nul

echo [*] Creating portable release archive: CTRLX-v1.0.0-Windows-x64.zip...
if exist "%~dp0dist\CTRLX-v1.0.0-Windows-x64.zip" del /f /q "%~dp0dist\CTRLX-v1.0.0-Windows-x64.zip"
powershell -NoProfile -Command "Compress-Archive -Path '%~dp0dist\CTRLX' -DestinationPath '%~dp0dist\CTRLX-v1.0.0-Windows-x64.zip' -Force"

if defined ISCC (
    echo [*] Inno Setup compiler detected! Building setup installer...
    "%ISCC%" "%~dp0installer\ctrlx_setup.iss"
)

echo [OK] Windows Standalone Executable created successfully!
exit /b 0

:: ----------------------------------------------------------
:: Subroutine: Build Android Release APK
:: ----------------------------------------------------------
:build_android
echo.
echo ========================================================
echo [*] [2/2] Building Android Mobile Client Release APK...
echo ========================================================

if exist "C:\Program Files\Android\openjdk\jdk-21.0.8" (
    set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
    set "PATH=%JAVA_HOME%\bin;%PATH%"
)

cd /d "%~dp0mobile-client"
call gradlew.bat assembleRelease
if %ERRORLEVEL% NEQ 0 (
    echo [!] Android assembleRelease failed.
    pause
    exit /b %ERRORLEVEL%
)

cd /d "%~dp0"
set "SRC_APK=%~dp0mobile-client\app\build\outputs\apk\release\app-release.apk"
set "DEST_APK=%~dp0dist\CTRLX-v1.0.0-Android.apk"

if exist "%SRC_APK%" (
    copy /y "%SRC_APK%" "%DEST_APK%" >nul
    echo [OK] Android Release APK created: %DEST_APK%
) else (
    echo [!] Could not locate generated APK at %SRC_APK%.
)
exit /b 0
