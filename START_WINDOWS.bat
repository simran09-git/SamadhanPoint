@echo off
setlocal
cd /d "%~dp0backend"
if not exist target\samadhanpoint.jar (
  echo JAR not found. Run SETUP_WINDOWS.bat first.
  pause
  exit /b 1
)
java -jar target\samadhanpoint.jar
