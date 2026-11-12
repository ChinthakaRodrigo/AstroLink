@echo off
REM Compiles AstroNet-Server into .\out (works from CMD and PowerShell). Requires JDK 17+.
cd /d "%~dp0"
if exist out rmdir /s /q out
mkdir out
javac --release 17 -encoding UTF-8 -d out -cp lib\gson-2.13.2.jar src\common\*.java src\config\*.java src\udp\*.java src\server\*.java src\demo\*.java
if errorlevel 1 (
  echo Compilation FAILED
  exit /b 1
)
echo Compilation successful - classes are in out\
