# SamadhanPoint 2.0
### Turning Concerns into Solutions.

A deployable B.Sc. IT capstone demo for multilingual citizen-grievance triage, SLA prediction, department routing and human escalation.

## Stack
- Java 21 + Spring Boot 3.5
- PostgreSQL 16+ / 18
- REST API + static responsive web UI
- AI-assistive triage: OpenAI Responses API when OPENAI_API_KEY is configured, with Java rule-based fallback for offline/demo operation; language detection, English translation, category, priority, duplicate similarity and SLA risk
- Role-based portals: CITIZEN, ADMIN, DEPARTMENT_HEAD, WORKER, AUDITOR
- Docker + Docker Compose ready

## 1. Fastest Windows setup
Requirements: Java 21+, Maven 3.9+, PostgreSQL 16/17/18.

Create database once:
```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -h localhost -p 5432 -c "CREATE DATABASE samadhanpoint;"
```
If it already exists, skip this step.

Set your password for the terminal session:
```powershell
$env:DB_PASSWORD="YOUR_POSTGRES_PASSWORD"
```
Then:
```powershell
cd "sp\backend"
mvn clean package -DskipTests
java -jar .\target\samadhanpoint.jar
```
Open: http://localhost:8080

## 2. Docker
From the `sp` folder:
```bash
docker compose up --build
```
Open: http://localhost:8080

## 3. Authentication / bootstrap
- Normal citizen registration is available from the Sign in page.
- The initial administrator account is bootstrapped only if no account with username `admin` exists.
- Set `BOOTSTRAP_ADMIN_PASSWORD` in the environment before first startup to choose the bootstrap password.
- The application does not provide a demo-login endpoint and does not display plaintext passwords.

## 4. Registration demo
The UI automatically checks pincode → demo Ward + Zone. `400063` is intentionally included so the registration flow can be demonstrated without the previous “Pincode not found” blocker. `400064` is also mapped.

## 5. Main demonstration flow
1. Login as Citizen.
2. File a complaint.
3. Enter pincode and show automatic Ward/Zone mapping.
4. Run AI Triage: language, category, urgency, department, SLA and duplicate signal.
5. Submit and note the MUM tracking code. The matching department/ward worker is auto-assigned; if no scoped worker exists, the backend provisions one safely for the demo.
6. Login as the matching Department Head.
7. Open the routed complaint → assign a matching worker from the same department.
8. Login as Worker → open My Tasks → update IN_PROGRESS → RESOLVED and add resolution evidence/note.
9. Citizen receives notifications → verifies the resolution and closes the complaint, or submits an appeal.
10. Department Head/Admin receives the appeal notification → reviews and REOPENS/APPROVES/REJECTS it.
11. Login as Admin/Auditor → analytics, appeals, users, audit trail and PDF/CSV reports.

## 6. Hosting
The application serves the entire frontend from the Spring Boot JAR, so one web service is enough. For production hosting, provide environment variables:
- `DB_URL`
- `DB_USER`
- `DB_PASSWORD`
- `PORT` (usually supplied by the hosting provider)

Use a managed PostgreSQL database for persistent hosting. Do not use the demo password in a public deployment.

## 7. Security note
This capstone demo uses an in-memory session token and SHA-256 password hashing to keep the project dependency-light. For a real municipal deployment, replace this with Spring Security, BCrypt/Argon2, persistent sessions/JWT, HTTPS, rate limiting, object storage for evidence and a secrets manager.

## 8. AI note
The AI layer is intentionally assistive and deterministic for a self-contained academic demo. It does not claim to make autonomous municipal decisions. Low-confidence or high duplicate-risk cases are flagged for human review.


## Complete Citizen Intake Features
- GPS capture using browser Geolocation API (latitude, longitude, accuracy)
- OpenStreetMap GPS preview with Google Maps link
- Mobile camera capture using `capture="environment"` plus desktop image upload
- Multiple photo/PDF evidence attachments
- Pincode -> Ward/Zone lookup before routing
- Server-authoritative AI triage; client-supplied category/language is not trusted
- Road/pothole keyword precedence prevents rainwater context from incorrectly routing a pothole complaint to Water Supply

### Browser permissions
GPS and microphone require the browser to grant location/microphone permission. Camera capture is available on supported mobile browsers; desktop browsers provide a normal file picker.

## Final voice/evidence controls

The final schema includes dedicated `latitude`, `longitude` and `location_accuracy` columns. Existing databases are migrated with `ADD COLUMN IF NOT EXISTS`, so existing complaint records are preserved.

## 8. AI configuration
Set `OPENAI_API_KEY` on the server to enable OpenAI-assisted classification and English translation. The Java rule engine remains authoritative for department routing, ward scope, RBAC and SLA. If the key is absent or the API is unavailable, the project continues with deterministic local rules so the demo remains runnable.

## 9. Location and assignment
Citizen GPS is captured in the browser. The backend can reverse-geocode the coordinates through OpenStreetMap Nominatim, resolve the returned pincode against the included Mumbai ward directory, and store latitude/longitude/accuracy. Pincode remains the server-side routing key when reverse geocoding cannot resolve a postcode. Complaint location is independent of the citizen's registration location.

## 10. Reports
Admin, Auditor and Department Head report screens can download a PDF complaint report; CSV remains available as an optional export.


## Final QA / Known Workflow Rules (2026-10-02)
- Street Light / पथदिवे / streetlight / lamp complaints are routed to **Department of Electrical & Public Lighting**.
- Pothole / damaged-road complaints route to **Department of Roads & Traffic Infrastructure**.
- Routing is recomputed on the Java server from the original complaint text; client category values are not trusted.
- Worker assignment is matched by normalized **Department + Ward** scope. Existing legacy complaints are repaired at startup.
- Citizen Track Status loads the citizen's own tracking codes automatically.
- Citizen Appeals are available in the sidebar and from complaint details after status becomes RESOLVED/COMPLETED/CLOSED. Appeal reason + evidence are stored and sent to Admin/Department Head human review.
- Worker cannot close a complaint directly: worker resolves it; citizen verification closes it.
- GPS reverse geocoding is used when coordinates are supplied; the server derives pincode/ward from the resolved pincode mapping.
- Registration can also capture GPS and auto-fill pincode/ward.
- Supported UI languages are English, Hindi and Marathi.
- OpenAI is assistive; Java routing/security/workflow rules remain authoritative. Responses API endpoint is used when `OPENAI_API_KEY` is configured.

## 11. BIT-16 evaluation and engineering evidence
- Admin/Auditor → **Evaluation** shows reproducible synthetic classification/routing metrics and live SLA evidence.
- `evaluation/ground_truth.csv` contains de-identified multilingual ground truth.
- `evaluation/evaluate.py` calls the running triage API and prints classification accuracy + macro F1.
- `evaluation/BASELINE_COMPARISON.md` documents the manual baseline versus SamadhanPoint.
- `evaluation/SECURITY_ROBUSTNESS_TESTS.md` lists RBAC, edge-case and workflow experiments.
- `docs/MODEL_SYSTEM_CARD.md` documents the AI architecture, safeguards, limitations and privacy guidance.
- `docs/API_CONTRACTS.md` lists core REST contracts.
- `.github/workflows/ci.yml` runs Maven verification and Docker build on push/PR.

## 12. OpenAI setup
The AI layer uses the OpenAI **Responses API** when `OPENAI_API_KEY` is configured. The triage prompt requests language, English translation, category, priority and human-review signals. Java rules remain authoritative for ward mapping, routing, SLA, RBAC and workflow. If the API is unavailable, the local deterministic fallback keeps the application operational.

PowerShell:
```powershell
$env:DB_PASSWORD="YOUR_POSTGRES_PASSWORD"
$env:OPENAI_API_KEY="YOUR_OPENAI_API_KEY"
$env:OPENAI_MODEL="gpt-6-luna"
mvn clean verify
java -jar .\target\samadhanpoint.jar
```

Never put the real API key or database password in source control.

## Final Advanced Additions

See `FINAL_ADVANCED_FEATURES.md` for OTP password reset, Admin Command Center, database-backed 24-ward operations, database-backed widget customization, secure non-citizen password reset controls, live SLA countdown/breach logic, multilingual evaluation and mobile-responsive UI.

## BIT-16 evidence package

Additional capstone deliverables are under `docs/`: stakeholder personas/misuse cases, architecture and sequences, data dictionary, user guide, 5–8 minute demo script, individual 100-hour worklog template and evaluation dossier.

