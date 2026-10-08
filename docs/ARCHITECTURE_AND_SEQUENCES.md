# BIT-16 Architecture, Data Flow and Sequence Evidence

## High-level architecture
```text
Citizen / Staff Web Browser
        |
        v
Responsive Spring Boot static UI
        |
        v
REST Controller + Session/RBAC
        |
        +--> AI Assist (OpenAI Responses API when configured)
        |       |
        |       +--> language / translation / category / priority signals
        |
        +--> Deterministic Java guard
        |       |
        |       +--> ward + department routing + SLA + duplicate rules
        |
        v
PostgreSQL
  |-- users / roles
  |-- wards / pincode directory
  |-- grievances / updates / appeals
  |-- SLA fields / evidence / notifications
  |-- audit logs / admin preferences
        |
        v
Admin / Head / Worker / Auditor operational panels
```

## Complaint sequence
```text
Citizen -> New Complaint -> Server validation
        -> GPS/pincode -> Ward lookup
        -> language detection -> category/priority
        -> duplicate search -> department routing
        -> SLA deadline -> worker assignment
        -> notifications + audit log
```

## Resolution sequence
```text
Worker: ASSIGNED -> IN_PROGRESS -> RESOLVED
Citizen: verify -> CLOSED
Citizen: appeal after resolved -> human review -> REOPENED or REJECTED
```

## API/data contract evidence
See `docs/API_CONTRACTS.md`.
