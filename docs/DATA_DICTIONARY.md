# BIT-16 Data Dictionary

| Entity | Field | Meaning |
|---|---|---|
| sp_users | role | CITIZEN / ADMIN / DEPARTMENT_HEAD / WORKER / AUDITOR |
| sp_users | ward_jurisdiction | Authorized ward scope |
| sp_wards | name | Database-managed municipal ward master |
| sp_wards | zone | Database-managed zone |
| sp_pincode_wards | pincode | Input used to resolve civic location |
| sp_grievances | tracking_code | Citizen-facing complaint identifier |
| sp_grievances | detected_language | en / hi / mr |
| sp_grievances | category | Server-authoritative complaint category |
| sp_grievances | assigned_department | Final server-side routing target |
| sp_grievances | priority | CRITICAL / HIGH / MEDIUM / LOW |
| sp_grievances | sla_hours | SLA duration stored for that complaint |
| sp_grievances | created_at | SLA start timestamp |
| sp_grievances | resolved_at | Resolution timestamp used for SLA compliance |
| sp_grievances | duplicate_of_id | Possible duplicate target |
| sp_grievances | similarity_score | Duplicate similarity signal |
| sp_grievances | latitude/longitude | Complaint GPS when supplied |
| sp_grievances | data | Structured location/evidence/AI metadata |
| sp_appeals | status | Human-review appeal state |
| sp_audit_logs | action | Immutable-style operational event record |
| sp_admin_preferences | widget_order/pinned_widgets | DB-backed Admin dashboard customization |

Passwords are stored as hashes and are never returned by user APIs.
