@echo off
setlocal
cd /d "%~dp0backend"
echo [SamadhanPoint] Building backend...
call mvn -q -DskipTests clean package
if errorlevel 1 (
  echo Build failed. Check Maven/Java/PostgreSQL configuration.
  pause
  exit /b 1
)
echo Build successful.
echo Starting SamadhanPoint on http://localhost:8080
java -jar target\samadhanpoint.jar
