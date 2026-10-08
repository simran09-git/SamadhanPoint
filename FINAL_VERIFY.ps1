$ErrorActionPreference = 'Stop'
Write-Host 'SamadhanPoint final verification'
if (-not (Test-Path '.\backend\pom.xml')) { throw 'Run this script from the sp folder.' }
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) { throw 'Maven 3.9+ is required.' }
Push-Location .\backend
mvn clean verify
if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed.' }
if (-not (Test-Path '.\target\samadhanpoint.jar')) { throw 'JAR was not created.' }
Pop-Location
if (Get-Command node -ErrorAction SilentlyContinue) {
  node --check .\backend\src\main\resources\static\js\app.js
  if ($LASTEXITCODE -ne 0) { throw 'Frontend JavaScript syntax check failed.' }
}
Write-Host 'BUILD + TEST + FRONTEND SYNTAX CHECK OK'
Write-Host 'For runtime: set DB_PASSWORD and optionally OPENAI_API_KEY, then run target\samadhanpoint.jar.'
