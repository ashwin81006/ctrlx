@echo off
title CTRLX - PC Receiver
echo Starting CTRLX PC Receiver and Dashboard...
if exist "C:\Program Files\Java\jdk-25.0.4.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
) else if exist "C:\Program Files\Java\jdk-17.0.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-17.0.1"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MVN_CMD=mvn.cmd"
where mvn.cmd >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    if exist "C:\Program Files\JetBrains\IntelliJ IDEA 2023.3.6\plugins\maven\lib\maven3\bin\mvn.cmd" (
        set "MVN_CMD=C:\Program Files\JetBrains\IntelliJ IDEA 2023.3.6\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
)

cd /d "%~dp0"
call "%MVN_CMD%" -pl pc-client javafx:run
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Server stopped with exit code %ERRORLEVEL%.
    pause
)
