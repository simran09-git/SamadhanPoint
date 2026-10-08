# SamadhanPoint FINAL PRO V3

## Role boundaries
- **Citizen:** own complaints, tracking, resolution verification, appeals, notifications, profile settings.
- **Admin:** city-wide complaint register, all wards/departments, user CRUD split into Citizen / Department Head / Worker / Admin-Auditor sections, department CRUD, audit, analytics, reports.
- **Department Head:** only their own department complaints and appeals; can assign only workers belonging to that department; can view department analytics and generate a department report.
- **Worker:** only assigned tasks; can move tasks through the field workflow and upload before/after photo/PDF evidence; cannot close a complaint directly.
- **Auditor:** read-only city-wide complaints, ward/location register, analytics, appeals, notifications and audit trail.

## Workflow
Citizen complaint -> AI language/category/priority/duplicate check -> department routing -> Department Head -> same-department worker assignment -> worker IN_PROGRESS -> worker RESOLVED with evidence -> citizen verification -> CLOSED.

If a citizen is not satisfied after resolution, they submit an appeal with reason/evidence. Admin or the responsible Department Head reviews it. REOPENED sends the complaint back into the workflow and notifies the citizen/worker/auditor.

## Notifications
Notifications are persisted in PostgreSQL. A live unread badge is shown in every portal. The UI polls every 12 seconds and displays an alert toast when new notifications arrive. Events include complaint submission, routing, worker assignment, status changes, resolution verification, appeals and appeal review.

## Reports
Admin/Auditor can select a department or all departments. Department Heads are automatically restricted to their own department. Reports show total, open, assigned, in-progress, resolved/closed, SLA-breached and resolution rate, with CSV export and print support.

## Location visibility
Admin and Auditor complaint registers show Ward, Pincode, Locality/Street and Landmark. Complaint routing still uses category -> department; ward/location is operational context and SLA/area reporting, not the sole category classifier.

## Multilingual UI
English, Hindi and Marathi are supported for navigation, major workspace controls, assistant responses and status labels. Complaint text remains in the citizen's original language.


### Language + ward-head scope (V4 update)
- Complaint language is detected server-side from the original complaint text and stored in `sp_grievances.detected_language` with confidence.
- Admin Complaint Register has an **All languages** filter (English/Hindi/Marathi).
- A Department Head is scoped to exactly one Department + one Ward.
- Department Head queues, analytics, reports, appeals, worker lists and assignment are restricted to that same Department + Ward.
- Worker assignment requires the worker to belong to the same Department + Ward as the complaint.


V6 fix: strong pothole/road terms take precedence over incidental water words; citizen appeal availability is explicitly shown and the appeal form remains available after resolution.
