@echo off
REM Usage: run-server.bat [tcpPort] [udpPort]     (defaults 5000 / 5001)
cd /d "%~dp0"
java -cp "out;lib\gson-2.13.2.jar" server.AstroServer %*
