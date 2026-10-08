# BIT-16 Stakeholder Map, Personas and Misuse Cases

## Stakeholders
- Citizen: submits, tracks, verifies and appeals complaints.
- Administrator: city-wide management, departments, wards, SLA, reports and audit operations.
- Department Head: manages complaints in an authorized department + ward scope.
- Worker: performs assigned field work and submits resolution evidence.
- Auditor: read-only workflow, SLA and audit evidence review.

## Pain points
- Multilingual intake can cause inconsistent classification.
- Manual routing can delay assignment.
- Duplicate complaints can create repeated work.
- SLA deadlines can be missed without a live clock.
- Citizens need transparent tracking and appeal paths.
- Supervisors need ward/department workload visibility.

## Misuse / abuse cases
1. Citizen attempts to open another citizen's complaint.
2. Worker attempts to modify another worker's assignment.
3. Department Head requests another department's private records.
4. Auditor attempts a mutation endpoint.
5. Blocked user attempts login.
6. User attempts to submit an unsupported pincode without GPS.
7. Client attempts to override server-side category/language/routing.
8. User attempts to expose password/token data through the AI assistant.

Each case has a server-side authorization or validation control and should be demonstrated in the final security evidence pack.
