# SamadhanPoint V3 Build Fix

This package fixes the V2 backend compilation issue caused by helper methods being omitted from `SamadhanService.java` during the V2 patch.

Restored/implemented:
- notifications()
- unreadNotificationCount()
- markNotification()
- notifyDepartmentHeads()
- notifyRole()
- notifyUser()
- audit()
- error()
- isRole()
- isAnyRole()
- notBlank()

The existing V2 changes for Citizen Appeals, OpenAI assistant, GPS registration/complaint routing, scoped Department Head/Worker rules, and routing repair are retained.

On Windows, run from `sp\\backend`:

    mvn clean package -DskipTests

Then:

    mvn spring-boot:run

Note: this environment does not have Maven installed, so the final Maven build must be confirmed on the user's Windows machine.
