@echo off
REM Usage: run-station.bat [stationId] [serverIp] [tcpPort] [udpPort]
REM   e.g. run-station.bat ASTRO-02 192.168.1.10
cd /d "%~dp0"
java -cp "out;lib\gson-2.13.2.jar" station.AstroStation %*
