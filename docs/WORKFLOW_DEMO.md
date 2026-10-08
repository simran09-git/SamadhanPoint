# SamadhanPoint end-to-end demo

Use a real account created through registration or an administrator-created operational account. Do not put demo passwords in the repository.

## 1. Citizen
1. Register/sign in as a Citizen.
2. Use a supported pincode such as `400063` for the included ward directory.
3. Enter a complaint such as: `There is a deep pothole near the school gate.`
4. Run AI triage. It should suggest `ROADS_POTHOLES` and route to the Roads department.
5. Submit and note the tracking code.

## 2. Department Head
1. Sign in with an authorized Department Head account for the routed department + ward.
2. Open Complaints and open the routed complaint.
3. Assign a matching worker.
4. The worker and citizen receive notifications.

## 3. Worker
1. Sign in with the assigned Worker account.
2. Open My Tasks.
3. Update status to `IN_PROGRESS`, add a note/evidence, then update to `RESOLVED`.

## 4. Citizen verification / appeal
1. Sign in again as the complaint owner.
2. Open the resolved complaint.
3. Verify resolution to close it, or submit an appeal with a reason/evidence.

## 5. Appeal review
1. Sign in as an authorized Department Head or Admin.
2. Open Appeals.
3. Review the appeal and follow the available human-review action.

## 6. Notifications
Notifications cover complaint submission/routing, assignment, status changes, resolution, verification/closure and appeal review.
