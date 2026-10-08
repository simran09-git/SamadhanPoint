# BIT-16 Final Acceptance Matrix

Implemented in the current package:
- Complaint submission and tracking
- English/Hindi/Marathi language handling
- AI-assisted triage with OpenAI Responses API + deterministic Java guard
- Category, priority/urgency, English translation and human-review signals
- Duplicate similarity search
- Complaint-location GPS/pincode/ward routing
- Department + Ward scoped heads and workers
- Worker ASSIGNED → IN_PROGRESS → RESOLVED workflow
- Citizen verification → CLOSED
- Citizen appeal → human review → APPROVED/REOPENED or REJECTED
- Notifications and audit trail
- PDF report and department reports
- Admin/Auditor operational evaluation page
- Synthetic multilingual ground truth + reproducible F1/accuracy evaluator
- Security/robustness test plan
- Baseline comparison
- Model/system card
- API contract document
- GitHub Actions CI configuration
- Docker build configuration

Final local-machine verification still required before claiming deployment success: run `mvn clean verify`, build Docker, execute the smoke/security tests, and if OpenAI is desired set `OPENAI_API_KEY` in the environment. The package intentionally does not contain a real secret.

Additional final-package controls:
- Database-managed `sp_wards` master contains all 24 wards and zones; Admin Ward Control reads live records instead of a Java hardcoded ward list.
- Admin Command Center widget order/pinned state is persisted in `sp_admin_preferences` rather than browser-only localStorage.
- SLA breach logic counts both open complaints past deadline and resolved complaints resolved after their SLA deadline; UI marks late-resolved complaints as breached while stopping the countdown.
- Mobile UI uses an off-canvas navigation menu, responsive cards/forms, horizontally scrollable wide tables and narrow-screen modal/chat layouts.
- Added pure SLA policy unit tests and expanded BIT-16 evidence documentation.
