# SamadhanPoint — Final Advanced Feature Pack

## Added in this release

- Forgot Password on Sign-in page.
- In-app 6-digit OTP verification popup with 10-minute expiry.
- Password reset through OTP.
- OTP is hashed in PostgreSQL and is single-use.
- Admin Command Center with customizable, draggable and pinnable widgets.
- 24-ward Municipal Operations Board.
- Ward-wise complaint totals, open/resolved counts, SLA breaches, Critical/High alerts, workers, Department Heads, departments and issue categories.
- Ward Control page available to ADMIN only.
- Admin can manage non-citizen accounts and securely reset passwords for ADMIN, AUDITOR, DEPARTMENT_HEAD and WORKER accounts.
- Citizen password information is hidden.
- Plaintext passwords are never stored or exposed; only SHA-256 password hashes are stored by the existing project design.
- Existing role-based AI Assistant remains available to all panels.
- Existing complaint, routing, SLA, evidence, appeal, notification, reporting and audit workflows are preserved.

## Local OTP behavior

This academic/local build intentionally shows the generated OTP in the in-app verification popup so the password-reset workflow can be tested without requiring an external SMS/email provider. For a production deployment, replace the popup delivery with an approved SMS/email provider and keep OTPs server-side.

## Admin Command Center widgets

The dashboard includes:
- SLA Breach & Escalation Radar
- Gateway Telemetry & Latency
- Departmental Task Distribution
- Citizen Ingestion Pulse
- Critical Emergency Triage Feed
- Ward SLA Compliance Scorecard
- Issue Category Composition
- Department Detection & Match Accuracy
- Hourly Resolution Velocity
- Microservices & DB Health Status

Widget order and pin state are saved in browser localStorage for the Admin account.
