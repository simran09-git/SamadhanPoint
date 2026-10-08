param([string]$BaseUrl="http://localhost:8080")
$ErrorActionPreference='Stop'
Write-Host "[1/6] Health" -ForegroundColor Cyan
$h=Invoke-RestMethod "$BaseUrl/api/health"
$h | Format-List
if($h.database -ne 'ONLINE'){throw 'Database is not ONLINE.'}

Write-Host "[2/6] Demo citizen login" -ForegroundColor Cyan
$login=Invoke-RestMethod "$BaseUrl/api/auth/demo-login" -Method Post -ContentType 'application/json' -Body (@{role='CITIZEN'}|ConvertTo-Json)
if(-not $login.success){throw 'Citizen login failed.'}
$token=$login.token;$headers=@{Authorization="Bearer $token"}

Write-Host "[3/6] Pincode 400063" -ForegroundColor Cyan
$ward=Invoke-RestMethod "$BaseUrl/api/ward-by-pincode/400063"
$ward | ConvertTo-Json -Depth 5

Write-Host "[4/6] AI triage" -ForegroundColor Cyan
$triage=Invoke-RestMethod "$BaseUrl/api/triage/analyze" -Method Post -ContentType 'application/json' -Body (@{text='There is a deep pothole near the school and it is dangerous for children.';ward='Ward B / Zone 1'}|ConvertTo-Json)
$triage.data | Select-Object detectedLanguage,languageName,suggestedCategory,urgency,assignedDepartment,slaHours,predictedBreachProbability,humanReviewRequired | Format-List

Write-Host "[5/6] Complaint list" -ForegroundColor Cyan
Invoke-RestMethod "$BaseUrl/api/grievances" -Headers $headers | Select-Object success,total | Format-List

Write-Host "[6/6] Frontend" -ForegroundColor Cyan
$r=Invoke-WebRequest "$BaseUrl/" -UseBasicParsing
if($r.StatusCode -ne 200){throw 'Frontend did not return HTTP 200.'}
Write-Host "SMOKE TEST PASSED" -ForegroundColor Green
