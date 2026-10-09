@echo off
title CTRLX - PC Receiver
echo Starting CTRLX PC Receiver and Dashboard...
set "JAVA_HOME=C:\Program Files\Java\jdk-25"
set "PATH=C:\Program Files\Java\jdk-25\bin;C:\Tools\apache-maven-3.9.16\bin;%PATH%"
cd /d "%~dp0pc-client"
call mvn.cmd javafx:run
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Server stopped with exit code %ERRORLEVEL%.
    pause
)
