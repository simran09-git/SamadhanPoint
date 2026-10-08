# BIT-16 Security & Robustness Test Plan

| Test | Action | Expected evidence |
|---|---|---|
| RBAC-01 | Citizen requests another citizen complaint | 404/403; no data leakage |
| RBAC-02 | Worker updates a complaint assigned to another worker | 403-style error |
| RBAC-03 | Department Head accesses another department/ward complaint | denied |
| RBAC-04 | Auditor attempts mutation | denied |
| RBAC-05 | Blocked user attempts login | denied |
| ROB-01 | Hindi/Marathi/English complaint | detected language + confidence |
| ROB-02 | Mixed road + water wording | rule guard prevents incorrect road/water routing where explicit road/light signal exists |
| ROB-03 | Duplicate complaint | similarity surfaced + human review flag |
| ROB-04 | Empty complaint | validation error |
| ROB-05 | Unsupported pincode without GPS | validation error |
| ROB-06 | GPS supplied | server resolves pincode/ward; client category is not trusted |
| ROB-07 | Appeal APPROVE | complaint becomes REOPENED and worker is notified |
| ROB-08 | Appeal REJECT | complaint state remains unchanged |
| ROB-09 | Worker attempts CLOSED | rejected; only citizen verification closes |
| ROB-10 | Evidence upload | before/after evidence persists and is visible in timeline |

Record screenshots/logs for each test in the final evaluation dossier.
