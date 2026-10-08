# SamadhanPoint Complete Feature Set

## Citizen intake
1. Complaint text in English, Hindi or Marathi.
2. Pincode lookup -> ward/zone/locality.
3. Browser GPS capture with latitude, longitude and accuracy.
4. OpenStreetMap preview and Google Maps link.
5. Camera/photo capture and additional photo/PDF evidence.
7. AI triage before submission.
8. Duplicate warning, SLA and routing preview.

## AI
- Language detection: English/Hindi/Marathi.
- Category suggestion.
- Priority/urgency.
- Department routing.
- Duplicate similarity.
- SLA breach probability.
- Human review flag for low-confidence or strong duplicate cases.
- Server recomputes triage during final submission.

## Workflow
Citizen -> AI triage -> ward routing -> department head -> worker -> evidence -> resolved -> citizen verification -> closed/appeal.

## Deployment
The project is packaged as a Spring Boot application with PostgreSQL and Docker Compose. Production environment variables are supported through `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `PORT`.

## Important
GPS, camera and microphone are browser capabilities. HTTPS (or localhost for local testing) is required by browsers for most permission-sensitive APIs.

- Voice metadata includes filename and recording duration when supplied by the browser.

## Evidence display
- Image evidence renders as an image instead of `[object Object]`.
- GPS latitude/longitude/accuracy are persisted in dedicated database columns as well as the complaint data payload.

## AI routing fixes
- Devanagari complaints default to Hindi unless distinctive Marathi vocabulary is present.
- Public-toilet, cleanliness, sanitation and similar civic-health wording routes to Public Health & Sanitation.
- Road/pothole precedence remains protected from incidental rainwater/water wording.
- Duplicate similarity is shown in complaint details from the persisted triage analysis and strong matches are stored in `duplicate_of_id` / `similarity_score`.


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

## BIT-16 completion additions in this release
- OpenAI Responses API triage enrichment: language, translation, category, priority and human-review signals.
- Server-side deterministic guard remains authoritative for location, ward, department, SLA, RBAC and workflow.
- AI operational counters: calls, successes, failures and last AI latency exposed through health telemetry.
- Appeal `APPROVE` now explicitly reopens the complaint, clears the old resolution timestamp, records a timeline event and notifies the assigned worker/department head.
- Reproducible synthetic ground truth and macro-F1/accuracy evaluator.
- Admin/Auditor Evaluation page.
- Baseline comparison, security/robustness plan, model/system card and API contracts.
- GitHub Actions CI build/test/Docker configuration.
- Secrets removed from committed runtime defaults; Docker Compose requires `POSTGRES_PASSWORD` via `.env`.
