# SamadhanPoint Final Smoke Test

## 1. Build
```powershell
cd .\sp\backend
mvn clean package -DskipTests
java -jar .\target\samadhanpoint.jar
```

Open `http://localhost:8080`.

## 2. Street-light routing
Submit a Marathi complaint containing:
`आमच्या परिसरातील मुख्य रस्त्यावरील अनेक पथदिवे गेल्या काही दिवसांपासून बंद आहेत. रात्रीच्या वेळी रस्ता अंधारात राहतो.`

Expected:
- Language = Marathi
- Category = Street Light
- Department = Department of Electrical & Public Lighting
- Ward = location-resolved ward
- Matching worker = same department + same ward
- Status = ASSIGNED

## 3. Pothole routing
Use:
`There is a deep pothole near the school gate and children are at risk.`
Expected: Roads & Traffic Infrastructure.

## 4. Water routing
Use:
`There has been no water supply in our area since yesterday.`
Expected: Hydraulic Engineering & Water Supply.

## 5. Citizen tracking
Citizen > Track Status. The citizen's own tracking codes should load automatically.

## 6. Resolution + appeal
Worker:
ASSIGNED -> IN_PROGRESS -> RESOLVED, with resolution notes/evidence.

Citizen:
- Verify resolution -> CLOSED, or
- Open Appeals / complaint details -> Submit Appeal
- Appeal reason + evidence -> human review.

## 7. Scope security
- Department Head sees only own Department + Ward.
- Worker sees only assigned complaints.
- Auditor is read-only city-wide.
- User Management and Department CRUD are Admin-only.

## 8. GPS
On New Complaint, click Detect my location. The server uses reverse geocoding when coordinates are supplied and derives pincode/ward from the municipal mapping table.

## 9. OpenAI
Set `OPENAI_API_KEY` before startup. `OPENAI_MODEL` defaults to `gpt-6-luna`. Java routing/security rules remain authoritative.

## 10. Appeal reopen acceptance test
1. Resolve a complaint as Worker.
2. Citizen submits an appeal.
3. Department Head/Admin opens Appeals.
4. Choose **APPROVE & REOPEN**.
5. Confirm complaint status becomes `REOPENED` and timeline records the human review.
6. Confirm the assigned worker receives a reopen notification.
7. Worker performs `REOPENED -> IN_PROGRESS -> RESOLVED`.
8. Citizen verifies the new resolution -> `CLOSED`.

## 11. Evaluation
Admin/Auditor → Evaluation. Confirm the synthetic evaluation metrics load. For a live API run:
```powershell
python .\evaluation\evaluate.py
```

## 12. Security/robustness
Execute `evaluation/SECURITY_ROBUSTNESS_TESTS.md` and record the result of each test in the final dossier.

## 13. CI / Docker
```powershell
cd .\sp\backend
mvn clean verify
cd ..
# create .env from .env.example and set POSTGRES_PASSWORD
copy .env.example .env
docker compose up --build
```
