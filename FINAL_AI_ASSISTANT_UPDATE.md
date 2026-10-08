# Final AI Assistant Update

The universal AI Assistant now provides role-specific quick questions with deterministic instant answers.

- Citizen: complaint filing, tracking, SLA, appeals, ward, routing, notifications, AI triage.
- Admin: user management, 24-ward monitoring, SLA radar, dashboard customization, department performance, citizen password protection, AI scope, audit trail.
- Department Head: department complaints, worker assignment, SLA, escalation, appeals, workload and AI scope.
- Worker: assigned tasks, status workflow, resolution, SLA, evidence, escalation and AI scope.
- Auditor: audit logs, read-only access, SLA compliance, credential protection, workflow evidence, AI scope and reports.

Clicking a quick question returns the built-in answer immediately without depending on OpenAI. Custom questions continue to use `/api/assistant/chat`, so OpenAI remains available when configured and the Java local fallback remains available otherwise.
