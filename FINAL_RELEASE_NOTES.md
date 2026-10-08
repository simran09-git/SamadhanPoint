# SamadhanPoint — Final Advanced Release

## Added in this final package
- Forgot password from the sign-in page.
- OTP verification appears in the existing top-level modal popup.
- OTP is stored hashed in PostgreSQL with expiry and max-attempt protection.
- Optional Brevo transactional email delivery.
- Local fallback OTP popup for testing when `RESET_OTP_DEV_MODE=true`.
- Password reset invalidates active sessions for the reset account.
- Admin-only 24-Ward Management Centre.
- Each ward shows total/open/resolved/SLA-breached complaints, priority counts, mapped pincodes/localities, citizens, workers, department heads, top department and top category.
- Ward drill-down shows departments, categories, status distribution, staff and recent complaints.

## Local OTP testing
PowerShell:

```powershell
$env:DB_PASSWORD="YOUR_POSTGRES_PASSWORD"
$env:RESET_OTP_DEV_MODE="true"
mvn spring-boot:run
```

Use **Forgot password?** on the sign-in page. The OTP will appear in the same popup for local testing.

## Real email OTP
Set:
- `BREVO_API_KEY`
- `RESET_OTP_SENDER_EMAIL`
- `RESET_OTP_SENDER_NAME`
- `RESET_OTP_DEV_MODE=false`

The application sends the OTP through the Brevo transactional email API.


### Final patch
- Strong server-side category signals remain authoritative for department routing.
- Existing complaint routing repair now reads the stored category correctly.
