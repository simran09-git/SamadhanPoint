# API Contracts — Core Endpoints

- `POST /api/auth/login` — authenticate a user.
- `POST /api/grievances` — create a citizen grievance; server recomputes triage and location routing.
- `POST /api/triage/analyze` — preview multilingual triage.
- `GET /api/grievances/{id}` — scoped complaint detail.
- `POST /api/grievances/{id}/assign` — Admin/Department Head assignment.
- `PUT /api/grievances/{id}` — worker/head/admin workflow update.
- `POST /api/grievances/{id}/verify` — citizen verification and closure.
- `POST /api/grievances/{id}/appeal` — citizen human-escalation request.
- `GET /api/appeals` — role-scoped appeals.
- `POST /api/appeals/{id}/review` — human review; APPROVED reopens the complaint.
- `GET /api/evaluation/summary` — Admin/Auditor evaluation evidence.
- `GET /api/actuator/health` — Spring operational health endpoint.

All mutating endpoints must enforce server-side authorization and never trust a client-provided role, department, ward or category for security-sensitive decisions.
