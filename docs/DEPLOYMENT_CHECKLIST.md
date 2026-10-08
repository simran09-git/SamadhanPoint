# SamadhanPoint Hosting Checklist

## Before deployment
- [ ] Java 21 runtime available, or use the supplied Dockerfile.
- [ ] Managed PostgreSQL database created.
- [ ] `DB_URL`, `DB_USER`, `DB_PASSWORD` configured as secrets.
- [ ] `PORT` configured if the host requires it.
- [ ] HTTPS enabled by the hosting provider.
- [ ] Demo passwords changed or demo users disabled.

## Smoke test
- [ ] GET `/api/health` returns HEALTHY + database ONLINE.
- [ ] Login as each demo role.
- [ ] Register a new citizen with `400063`.
- [ ] Run AI triage in English, Hindi and Marathi.
- [ ] Submit complaint and capture tracking code.
- [ ] Assign matching worker.
- [ ] Update status to IN_PROGRESS and RESOLVED.
- [ ] Verify SLA panel.
- [ ] Verify appeal workflow.
- [ ] Verify analytics and CSV export.

## Demo pincode examples
400063, 400064, 400067, 400101, 400070, 400071, 400076, 400079.
