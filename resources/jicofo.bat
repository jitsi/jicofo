@echo off

if "%1"=="/h" goto usage
if "%1"=="/?" goto usage
goto :begin

:usage
echo Usage: %0
exit /B 1

:begin

:: needed to overcome weird loop behavior in conjunction with variable expansion
SETLOCAL enabledelayedexpansion

set mainClass=org.jitsi.jicofo.Main
set cp=jicofo.jar
FOR %%F IN (lib/*.jar) DO (
  SET cp=!cp!;lib/%%F%
)

java -Djava.util.logging.config.file=lib/logging.properties -Djdk.tls.ephemeralDHKeySize=2048 -cp %cp% %mainClass% %*
