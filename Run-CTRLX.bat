@echo off
title CTRLX - PC Receiver
echo Starting CTRLX PC Receiver and Dashboard...
if exist "C:\Program Files\Java\jdk-25.0.4.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "MVN_CMD=mvn.cmd"
if exist "C:\Tools\apache-maven-3.9.16\bin\mvn.cmd" (
    set "MVN_CMD=C:\Tools\apache-maven-3.9.16\bin\mvn.cmd"
)
cd /d "%~dp0"
call "%MVN_CMD%" install -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [!] Build failed with exit code %ERRORLEVEL%.
    pause
    exit /b %ERRORLEVEL%
)
call "%MVN_CMD%" -pl pc-client javafx:run
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Server stopped with exit code %ERRORLEVEL%.
    pause
)