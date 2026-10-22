@echo off
title CTRLX - PC Receiver
echo Starting CTRLX PC Receiver and Dashboard...
if exist "C:\Program Files\Java\jdk-25.0.4.1" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1"
) else if exist "C:\Program Files\Java\jdk-25" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0pc-client"
call mvn.cmd javafx:run
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Server stopped with exit code %ERRORLEVEL%.
    pause
)
