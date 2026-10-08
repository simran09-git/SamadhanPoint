# BIT-16 Baseline Comparison

## Baseline: manual municipal triage
- Citizen/desk operator reads the complaint.
- Language and category are manually interpreted.
- Department is manually selected.
- Duplicate checking is manual.
- SLA is manually tracked.
- Escalation is handled outside the complaint record.

## SamadhanPoint
- Multilingual intake in English/Hindi/Marathi.
- AI-assisted language/category/translation/priority signals.
- Deterministic Java routing guard using complaint location + category.
- Text similarity duplicate detection with human-review threshold.
- Server-calculated SLA timer and breach signal.
- Role-scoped worker assignment and audit trail.
- Citizen appeal and human review with reopen workflow.
- Notifications, PDF reports, operational health and evaluation evidence.

The baseline is deliberately simple so the project can document where automation adds measurable value without claiming that the AI model alone controls civic routing.
