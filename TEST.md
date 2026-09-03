# TEST.md — Swagger UI testing guide

Copy-paste requests for every live endpoint, written for Swagger UI's "Try it out" flow. Base URL
for all of them: `http://localhost:8080`.

Bring the stack up from the repo root:
```bash
docker compose up -d postgres clamav
cd backend
CTMS_JWT_SECRET="local-development-signing-key-at-least-32-bytes-long" \
  ./gradlew :ctms-app:bootRun --args='--spring.profiles.active=local'
```
Wait for `Started CtmsApplication` in the log, then create the one seed user — there's no
self-registration endpoint by design (§7 below explains why):
```bash
docker compose exec -T postgres psql -U ctms -d ctms -c "
INSERT INTO users (email, password_hash, full_name, role_id)
VALUES ('admin@ctms.local', '\$argon2id\$v=19\$m=65536,t=3,p=4\$TSjAw3odMThzUYSB8wQeuw\$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Local Admin', '00000000-0000-0000-0000-000000000001');"
```
Login for that user is `admin@ctms.local` / `ChangeMe123!` (§1.1). A fresh `docker compose down -v`
wipes this user along with everything else — rerun this insert after any volume reset.

## 0 · How to drive this from Swagger UI

Open **`http://localhost:8080/swagger-ui/index.html`** — a clickable page generated straight from
the live controllers. Nothing to install or configure beyond what's already running.

**Auth mostly just works.** This page is served by the app itself and opened in a real browser,
so when you run `POST /api/v1/auth/login` via "Try it out," the browser stores the response's
`access_token` cookie exactly like it would for any site, and silently attaches it to every
"Try it out" call you make afterwards on the same page. No token to copy, no header to set by
hand — for reads.

**Writes need one extra, one-time step: CSRF.** Login also sets a `csrf_token` cookie —
deliberately *not* `HttpOnly`, so a real frontend's JavaScript can read it and echo it back in an
`X-CSRF-Token` header on every state-changing request (§18.12). Swagger UI has no JavaScript of
its own to do that automatically, so you do it once per login via its **Authorize** button:
1. Log in via `POST /api/v1/auth/login` as usual.
2. Open your browser's devtools → Application (Chrome) / Storage (Firefox) → Cookies →
   `http://localhost:8080`, find `csrf_token`, copy its value.
3. Click **Authorize** at the top of the Swagger UI page, paste the value into `csrfToken`,
   click **Authorize**, then **Close**.
4. Every "Try it out" call from here on carries the header automatically — for this login. Log
   in again later (a fresh session, or as a different user) and repeat step 2–3, since a new
   login issues a new token.

Skip this and every POST/PATCH/DELETE below fails with `403 CSRF_TOKEN_MISMATCH` — that's the
mechanism working, not a bug.

**The mechanics, once, for every endpoint below:**
1. Find the controller's tag (`auth-controller`, `institution-controller`, `trial-controller`, …)
   in the list and expand it.
2. Click the operation (e.g. `POST /api/v1/institutions`), then **Try it out**.
3. For a JSON body: the "Example Value" box is a *placeholder*, not valid data — an empty string
   fails `@NotBlank`, a made-up status fails a database constraint. Delete it and paste the real
   JSON from the matching section below.
4. For path parameters (`{id}`), headers (`If-Match`, `Idempotency-Key`) and query parameters:
   Swagger UI renders one input box per parameter automatically — fill them in directly, nothing
   to construct by hand.
5. Click **Execute**. The response — status code, headers, and body — renders right below the
   button.
6. Wherever a request below needs an id from an earlier response (`<institutionId>`, `<trialId>`,
   …), that's exactly what it means: scroll to that earlier response, copy its `id` field, paste
   it in place of the placeholder. Nothing is a Postman variable here — it's manual copy-paste,
   the same way you'd read a value off any JSON response.

**To switch users** (§7 has PI/coordinator/etc. test accounts): just run `POST
/api/v1/auth/login` again with different credentials. The browser overwrites the old cookie with
the new one, so every "Try it out" call from that point on runs as the new user — no logout
needed first.

### Gotchas that bite everyone once

- **PATCH / decision / status / assess endpoints need `If-Match`.** Run the matching `GET` first, copy its `ETag` response header (a quoted number, e.g. `"3"`) from the response panel, paste it into the `If-Match` parameter box on the write operation. Skip it and you get `428 Precondition Required`; paste a stale one and you get `409 Conflict`.
- **Enrolling a participant has an optional `Idempotency-Key` parameter box** (any string, e.g. a UUID) — resend the same key and you get the same participant back instead of a duplicate.
- **Clinical writes (visits/observations/medications) need the trial `ACTIVE` or `SUSPENDED`** and the participant's consent not withdrawn — `EnrollmentRequest` already creates that consent, so a freshly enrolled participant is ready.
- **Documents are multipart.** Swagger UI renders this correctly on its own — expanding `document-controller → POST /api/v1/documents` gives you a native file picker plus text boxes for the other fields, no configuration needed.
- 404 is also the answer for "exists but out of your RLS scope" everywhere in this API, on purpose (§6.4 of the spec) — don't read a 404 as "definitely doesn't exist."
- **CSRF is real and enforced** (§18.12) — every non-GET request except `POST /auth/login` needs the `X-CSRF-Token` header from §0's Authorize step. A `403 CSRF_TOKEN_MISMATCH` means you skipped it or your session's token changed (e.g. you logged in again).
- **Rate limits are real too** (§18.10, §9.2) — login is capped at 5 attempts per 15 minutes, per IP *and* per email (whichever is hit first); ordinary writes at 60/min, reads at 300/min, GIS drill-down at a much tighter 20/min (it's the endpoint that can differ overlapping bounding boxes to defeat k-anonymity suppression, §11.4). Hit one and you get `429` with a `Retry-After` header — wait it out or use a different account/IP for the next test.
- A handful of request/response shapes used to share an auto-generated schema name across two unrelated controllers (`CreateReview`/`ReviewView` in both safety and ethics; `ComplianceSummary` in both compliance and GIS) — fixed with `@Schema(name=...)` overrides, so `EthicsCreateReview`/`EthicsReviewView`/`GisComplianceSummary` now show correctly as their own thing in the docs.
- Every error response now has a stable shape: `{"error":{"code","message","requestId"}}`. The `requestId` also comes back as an `X-Request-Id` response header on every call, successful or not — handy for matching a specific request to a specific audit-log row (§16.3) if something looks wrong.

---

## 1 · Auth — `ctms-security`

### 1.1 Login
`POST /api/v1/auth/login`
```json
{"email":"admin@ctms.local","password":"ChangeMe123!"}
```
A `200` with `{userId, email, role, mfaRequired}` confirms the browser now holds a valid session cookie.

### 1.2 Who am I
`GET /api/v1/auth/me` — no body. Returns your role and full permission list. Good sanity check that the cookie carried over.

### 1.3 Refresh
`POST /api/v1/auth/refresh` — no body; the browser's cookie jar carries the `refresh_token` cookie automatically. If it 401s, just log in again.

### 1.4 Logout
`POST /api/v1/auth/logout` — no body.

---

## 2 · Admin reads — `ctms-security`

Requires `user:read` / `role:read` (SYSTEM_ADMIN holds both).

- `GET /api/v1/users`
- `GET /api/v1/roles`
- `GET /api/v1/permissions`

---

## 3 · Institutions — `ctms-trials`

### 3.1 List
`GET /api/v1/institutions` — requires `institution:read` (every role has it).

### 3.2 Create
`POST /api/v1/institutions` — requires `institution:create` (ADMIN).
```json
{
  "name": "All India Institute of Medical Sciences",
  "institutionType": "GOVERNMENT_HOSPITAL",
  "city": "New Delhi",
  "state": "Delhi",
  "latitude": 28.5672,
  "longitude": 77.2100
}
```
`institutionType` ∈ `GOVERNMENT_HOSPITAL | PRIVATE_HOSPITAL | MEDICAL_COLLEGE | RESEARCH_CENTRE | CRO`.

Copy the `id` from the response — you'll need it below as `<institutionId>`.

### 3.3 Get one
`GET /api/v1/institutions/{id}` — paste `<institutionId>` into the `id` parameter box.

### 3.4 Update
`PATCH /api/v1/institutions/{id}` — requires `institution:update`. Needs `If-Match` (see §0's gotchas).
```json
{"city": "New Delhi", "state": "Delhi", "addressLine": "Ansari Nagar"}
```

---

## 4 · Trials — `ctms-trials`

### 4.1 Create
`POST /api/v1/trials` — requires `trial:create` (ADMIN today; a real deployment would give this to PI).
```json
{
  "protocolNumber": "CTRI-2026-0001",
  "title": "A Phase III Study of Drug X in Adult Patients",
  "sponsorInstitutionId": "<institutionId>",
  "phase": "III",
  "targetEnrollment": 200
}
```
`phase` ∈ `I | II | III | IV | OBSERVATIONAL`.

Copy the `id` from the response as `<trialId>`.

### 4.2 List / Get
`GET /api/v1/trials`
`GET /api/v1/trials/{id}` — `<trialId>`.

### 4.3 Update
`PATCH /api/v1/trials/{id}` — requires `trial:update`, needs `If-Match`.
```json
{"shortTitle": "Drug X Phase III", "therapeuticArea": "Oncology"}
```

### 4.4 Change status
`POST /api/v1/trials/{id}/status` — requires `trial:update`, needs `If-Match`.
```json
{"status": "PENDING_ETHICS"}
```
Legal path: `DRAFT → PENDING_ETHICS → APPROVED → ACTIVE → SUSPENDED|COMPLETED → ARCHIVED` (`SUSPENDED → ACTIVE|TERMINATED` too). A trial only accepts enrolment while `ACTIVE`, so walk it there before §8 — each transition is its own `Execute`, re-fetching `If-Match` each time since the `ETag` advances with every write.

---

## 5 · Trial sites — `ctms-trials`

### 5.1 Create
`POST /api/v1/sites` — requires `site:create`.
```json
{
  "trialId": "<trialId>",
  "institutionId": "<institutionId>",
  "siteCode": "SITE-001",
  "targetEnrollment": 50
}
```
Copy the `id` from the response as `<siteId>`.

### 5.2 List
`GET /api/v1/sites` — query parameter `trialId` = `<trialId>`.

---

## 6 · Trial staff — `ctms-trials`

This table *is* the access grant — a user isn't scoped to a trial until a row exists here.

### 6.1 Assign
`POST /api/v1/trial-staff` — requires `trial_staff:create`.
```json
{
  "trialId": "<trialId>",
  "trialSiteId": "<siteId>",
  "userId": "<a user id — see §7>",
  "staffRole": "COORDINATOR"
}
```
`staffRole` ∈ `PI | SUB_INVESTIGATOR | COORDINATOR | STAFF | MONITOR`. `trialSiteId` is optional (omit for a trial-wide assignment, e.g. the PI).

### 6.2 List
`GET /api/v1/trial-staff` — query parameter `trialId` = `<trialId>`.

### 6.3 End an assignment
`DELETE /api/v1/trial-staff/{id}` — requires `trial_staff:delete`. (Ends it — this table has no hard delete, §20.1.)

---

## 7 · Creating test users for the other roles

No self-registration or admin "create user" API exists yet — insert directly, same as the test suite does. Run this from a terminal, not Swagger UI:

```bash
docker compose exec -T postgres psql -U ctms -d ctms -c "
INSERT INTO users (email, password_hash, full_name, role_id)
VALUES ('pi@ctms.local', '\$argon2id\$v=19\$m=65536,t=3,p=4\$TSjAw3odMThzUYSB8wQeuw\$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Test PI', (SELECT id FROM roles WHERE name = 'PRINCIPAL_INVESTIGATOR'))
ON CONFLICT (email) DO NOTHING RETURNING id, email;"
```
Password for every user created this way is `ChangeMe123!`. Role names: `SYSTEM_ADMIN | PRINCIPAL_INVESTIGATOR | TRIAL_COORDINATOR | RESEARCH_STAFF | ETHICS_MEMBER | SAFETY_OFFICER | REGULATORY_OFFICER`.

`ETHICS_MEMBER` needs an institution — add a 5th column and value: `institution_id) VALUES (..., '<institutionId>'::uuid)`.

The `RETURNING id, email` gives you the id to use as `userId` in §6.1. Once that assignment exists, go back to `auth-controller → POST /api/v1/auth/login` in Swagger UI and log in with the new email/password — the browser's cookie now belongs to this user for every subsequent call.

**Capability matrix** (who can do what — see `FRONTEND_REQUIREMENTS.md` §3.1 for the full table):

| Resource | ADMIN | PI | COORD | STAFF | ETHICS | SAFETY | REG |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Participants / clinical data | — | scoped | scoped | site-scoped | — | — | — |
| Adverse events | — | scoped | scoped | site-scoped | — | full | aggregate |
| Ethics submissions | — | scoped | — | — | inst.-scoped | — | aggregate |
| Compliance write | full | scoped | scoped | — | — | — | — |
| GIS aggregates | full | full | full | full | full | full | full |
| GIS drill-down | — | scoped | scoped | site-scoped | — | aggregate | aggregate |

---

## 8 · Participants — `ctms-clinical`

Trial must be `ACTIVE` first (§4.4). Caller must be `trial_staff`-assigned to the trial/site (§6.1).

### 8.1 Enrol
`POST /api/v1/participants` — requires `participant:create`.
Optional `Idempotency-Key` parameter box: any unique string.
```json
{
  "trialId": "<trialId>",
  "trialSiteId": "<siteId>",
  "subjectCode": "SUBJ-001",
  "dateOfBirthYear": 1985,
  "sex": "FEMALE",
  "identity": {
    "fullName": "Test Participant",
    "dateOfBirth": "1985-06-15",
    "phone": "+91-9999999999"
  },
  "consent": {
    "consentVersion": "v1.0",
    "consentMethod": "WRITTEN"
  }
}
```
Note what's absent from the response: `identity` goes straight to a separate identity table and is never echoed back (ADR-011).

Copy the `id` from the response as `<participantId>`.

### 8.2 List / Get
`GET /api/v1/participants` — query parameter `trialId` = `<trialId>`.
`GET /api/v1/participants/{id}` — `<participantId>`.

### 8.3 Withdraw
`POST /api/v1/participants/{id}/withdrawal` — requires `participant:withdraw`.
```json
{"reason": "Participant relocated"}
```

---

## 9 · Consents — `ctms-clinical`

### 9.1 List
`GET /api/v1/consents` — query parameter `participantId` = `<participantId>`. Requires `consent:read`.

### 9.2 Withdraw
`POST /api/v1/consents/{id}/withdrawal` — requires `consent:withdraw`.
```json
{"reason": "Participant revoked permission to collect further data"}
```

---

## 10 · Clinical data — `ctms-clinical`

### 10.1 Create a visit
`POST /api/v1/visits` — requires `visit:create`.
```json
{
  "participantId": "<participantId>",
  "visitName": "Baseline",
  "visitNumber": 1,
  "scheduledDate": "2026-09-10"
}
```
Copy the `id` from the response as `<visitId>`.

`GET /api/v1/visits` — query parameter `participantId` = `<participantId>`. Requires `visit:read`.

### 10.2 Create an observation
`POST /api/v1/observations` — requires `observation:create`.
```json
{
  "visitId": "<visitId>",
  "observationCode": "VITALS-SBP",
  "observationName": "Systolic Blood Pressure",
  "category": "VITAL_SIGN",
  "valueNumeric": 120,
  "unit": "mmHg"
}
```
`category` ∈ `VITAL_SIGN | LABORATORY | PHYSICAL_EXAM | QUESTIONNAIRE | IMAGING | OTHER`. Exactly one of `valueNumeric` / `valueText` / `valueBoolean` is required per observation (at least one — the DB rejects a row with none) — the others should be left out of the JSON entirely.

`GET /api/v1/observations` — query parameter `visitId` = `<visitId>`. Requires `observation:read`.

### 10.3 Create a medication
`POST /api/v1/medications` — requires `medication:create`.
```json
{
  "participantId": "<participantId>",
  "medicationName": "Metformin",
  "medicationType": "CONCOMITANT",
  "dose": 500,
  "route": "ORAL",
  "startDate": "2026-09-10"
}
```
`medicationType` ∈ `STUDY_DRUG | CONCOMITANT | RESCUE`. `route` ∈ `ORAL | IV | IM | SC | TOPICAL | INHALED | OTHER`.

`GET /api/v1/medications` — query parameter `participantId` = `<participantId>`. Requires `medication:read`.

---

## 11 · Safety — `ctms-safety`

### 11.1 Report an adverse event
`POST /api/v1/adverse-events` — requires `adverse_event:create`.
```json
{
  "participantId": "<participantId>",
  "visitId": "<visitId>",
  "eventTerm": "Headache",
  "description": "Mild headache reported two hours after dosing",
  "onsetDate": "2026-09-10",
  "severity": "MILD",
  "seriousness": "NON_SERIOUS"
}
```
`severity` ∈ `MILD | MODERATE | SEVERE`. `seriousness` ∈ `NON_SERIOUS | SERIOUS` (defaults to `NON_SERIOUS` if omitted). If `SERIOUS`, `seriousCriteria` (a string array, e.g. `["HOSPITALIZATION"]`) is required or the DB rejects it with 422.

Copy the `id` from the response as `<eventId>`.

`GET /api/v1/adverse-events` — query parameter `participantId` = `<participantId>` (or `trialId` = `<trialId>`). Requires `adverse_event:read`.

### 11.2 Record a safety review
`POST /api/v1/safety/reviews` — requires `adverse_event:review` (SAFETY_OFFICER only — log in as one, see §7).
```json
{
  "adverseEventId": "<eventId>",
  "assessedSeverity": "MILD",
  "assessedCausality": "POSSIBLE",
  "isExpected": true,
  "requiresExpeditedReporting": false,
  "comments": "Consistent with known drug profile",
  "decision": "ACCEPTED"
}
```
`assessedCausality` ∈ `UNRELATED | UNLIKELY | POSSIBLE | PROBABLE | DEFINITE`. `decision` ∈ `ACCEPTED | QUERY_RAISED | ESCALATED | CLOSED`.

`GET /api/v1/safety/reviews` — query parameter `adverseEventId` = `<eventId>`. Requires `safety_report:read`.

---

## 12 · Ethics — `ctms-ethics`

### 12.1 Submit
`POST /api/v1/ethics/submissions` — requires `ethics:submit` (caller must be assigned to the trial — enforced by RLS, not by this check).
```json
{
  "trialId": "<trialId>",
  "institutionId": "<institutionId>",
  "submissionNumber": "IEC-2026-001",
  "submissionType": "INITIAL",
  "summary": "Initial ethics review request for CTRI-2026-0001"
}
```
`submissionType` ∈ `INITIAL | AMENDMENT | CONTINUING_REVIEW | SAE_REPORT | FINAL_REPORT`.

Copy the `id` from the response as `<submissionId>`.

### 12.2 List / Get
`GET /api/v1/ethics/submissions` — query parameter `trialId` = `<trialId>` (or `institutionId`). Requires `ethics:read`.
`GET /api/v1/ethics/submissions/{id}` — `<submissionId>`.

### 12.3 Review (committee deliberation — log in as ETHICS_MEMBER first, see §7)
`POST /api/v1/ethics/reviews` — requires `ethics:review`.
```json
{
  "ethicsSubmissionId": "<submissionId>",
  "recommendation": "APPROVE",
  "comments": "Protocol and consent form are adequate"
}
```
`GET /api/v1/ethics/reviews` — query parameter `submissionId` = `<submissionId>`. Requires `ethics:review` (deliberation is committee-only; a PI never sees this).

### 12.4 Decide
`POST /api/v1/ethics/submissions/{id}/decision` — requires `ethics:decide`, needs `If-Match`.
```json
{"status": "APPROVED", "approvalValidUntil": "2027-09-10"}
```
`status` must actually be a decision: `APPROVED | APPROVED_WITH_CONDITIONS | REJECTED | DEFERRED`. `APPROVED_WITH_CONDITIONS` requires a non-null `conditions` string or the DB rejects it.

### 12.5 Withdraw (the investigator retracting their own submission)
`POST /api/v1/ethics/submissions/{id}/withdraw` — requires `ethics:submit`, needs `If-Match`. No body.

---

## 13 · Compliance — `ctms-ethics`

### 13.1 The catalogue (reference data, everyone reads it)
`GET /api/v1/compliance/requirements` (optional query parameter `category`) — requires `compliance:read`.

`POST /api/v1/compliance/requirements` — requires `compliance:define` (ADMIN).
```json
{
  "code": "REG-CT-01",
  "title": "CTRI Registration",
  "description": "Trial must be registered with the Clinical Trials Registry of India before enrolment",
  "category": "REGULATORY",
  "authority": "CDSCO",
  "isMandatory": true,
  "evidenceRequired": true
}
```
`category` ∈ `REGULATORY | ETHICS | SAFETY | DATA_INTEGRITY | SITE_QUALIFICATION | DOCUMENTATION`.

Copy the `id` from the response as `<requirementId>`.

### 13.2 Attach a requirement to a trial
`POST /api/v1/compliance/trials/{trialId}/requirements` — requires `compliance:update`.
```json
{"complianceRequirementId": "<requirementId>", "dueDate": "2026-10-01"}
```
Copy the `id` from the response as `<trialComplianceId>`.

### 13.3 List / summary
`GET /api/v1/compliance/trials/{trialId}` — requires `compliance:read`.
`GET /api/v1/compliance/trials/{trialId}/summary` — the rollup: `{trialId, total, byStatus, mandatoryOutstanding, compliant}`.
`GET /api/v1/compliance/trials/{trialId}/{id}` — `<trialComplianceId>`.

### 13.4 Assess
`POST /api/v1/compliance/trials/{trialId}/{id}/status` — requires `compliance:update`, needs `If-Match`.
```json
{"status": "COMPLIANT", "notes": "Registration certificate verified"}
```
`status` ∈ `PENDING | IN_PROGRESS | COMPLIANT | NON_COMPLIANT | NOT_APPLICABLE | WAIVED`.

---

## 14 · Documents — `ctms-documents`

All multipart — Swagger UI renders a file picker and text boxes automatically, nothing to configure.

### 14.1 Upload
`POST /api/v1/documents` — requires `document:upload`. Fill in the rendered form:

| Field | Value |
|---|---|
| `file` | pick any PDF/image from your machine |
| `trialId` | `<trialId>` |
| `documentType` | `PROTOCOL` |
| `title` | `Study Protocol v1.0` |

Either `trialId` or `institutionId` is required (at least one). `documentType` ∈ `PROTOCOL | CONSENT_FORM | ETHICS_APPROVAL | REGULATORY_SUBMISSION | INVESTIGATOR_BROCHURE | CV | SOURCE_DOCUMENT | SAFETY_REPORT | MONITORING_REPORT | OTHER`. Max 50MB; it goes through a real ClamAV scan (the container from setup) before it's downloadable — expect `scanStatus: "PENDING"` immediately, then `"CLEAN"` a few seconds later (re-run §14.2's `GET` to see it flip).

Copy the `id` from the response as `<documentId>`.

### 14.2 List / Get
`GET /api/v1/documents` — query parameter `trialId` = `<trialId>`. Requires `document:read`.
`GET /api/v1/documents/{id}` — `<documentId>`.

### 14.3 New version
`POST /api/v1/documents/{id}/versions` — requires `document:upload`. Same rendered form: `file` (required), `title` (optional).

### 14.4 Publish (declare a version authoritative)
`POST /api/v1/documents/{id}/publish` — requires `document:supersede`. No body.

### 14.5 Download
`GET /api/v1/documents/{id}/download` — requires `document:read`. Returns `302` with a signed, short-lived `Location` header (5 min TTL) rather than the file itself — Swagger UI shows the redirect response rather than following it, so copy the `Location` header value into a new browser tab to actually fetch the file. Fails with `409` until `scanStatus` is `CLEAN`.

---

## 15 · GIS — `ctms-gis`

Every role holds `gis:read`; the base map endpoints work no matter who's logged in.

### 15.1 Global base map
`GET /api/v1/gis/institutions` — every institution/site marker, unfiltered by RLS (that's the point — it's public geography, not clinical data).

### 15.2 Site markers
`GET /api/v1/gis/sites` — all query parameters optional: `bbox=68,6,98,36`, `trialId=<trialId>`, `status=ACTIVE`.
`bbox` format is `west,south,east,north` — the example roughly covers India.

### 15.3 Clusters
`GET /api/v1/gis/clusters` — query parameters `bbox=68,6,98,36` (required here) and `zoom=5`.

### 15.4 Area aggregates
`GET /api/v1/gis/aggregates` — query parameter `level` ∈ `state | city` only. Small cohorts (k<5) come back suppressed rather than as a real number — that's k-anonymity working as designed, not a bug.

### 15.5 Site drill-down
`GET /api/v1/gis/sites/{id}/detail` — `<siteId>`. Requires `gis:drilldown` on top of `gis:read`. Fields like enrollment/compliance/AE count only populate if the caller also holds the matching base permission (`participant:read`, `compliance:read`, `adverse_event:read`) *and* is in RLS scope for that site — log in as different roles (§7) to see the same site's detail view grow or shrink.

---

## 16 · Analytics — `ctms-analytics`

### 16.1 Dashboard
`GET /api/v1/analytics/dashboard` — one shape per role, decided server-side by whoever's logged in. Cached 90s per user — if a number looks stale right after a write, that's why.

### 16.2 Per-trial enrolment / safety trend
`GET /api/v1/analytics/trials/{id}/enrollment` — `<trialId>`. Requires `trial:read`. Cumulative enrolment by date.
`GET /api/v1/analytics/trials/{id}/safety` — `<trialId>`. Requires `trial:read`. Counts by date/severity/seriousness, never a narrative.

(Compliance trend is deliberately not duplicated here — that's §13.3's `/compliance/trials/{id}/summary`.)

### 16.3 Audit trail
`GET /api/v1/audit` — all query parameters optional: `entityType`, `entityId`, `userId`, `from` (e.g. `2026-01-01T00:00:00Z`), `to`. Requires `audit:read`. RLS scopes which rows your filters can ever match, on top of the filters themselves.

Don't expect much here yet: `audit_logs` is only written to by one code path today — §14.5's document download. Do that once and this comes back with one row.

---

## 17 · A realistic end-to-end walkthrough

Run these in order (as ADMIN unless noted) to see the whole system react to real data:

1. §3.2 create institution → §4.1 create trial → §4.4 walk status to `ACTIVE` → §5.1 create site
2. §7 create a PRINCIPAL_INVESTIGATOR user → §6.1 assign them to the trial → log in as them (§0)
3. §8.1 enrol a participant (as the PI or a coordinator assigned to the site)
4. §10.1–10.3 record a visit, an observation, a medication
5. §11.1 report an adverse event → log in as SAFETY_OFFICER → §11.2 review it
6. §7 create an ETHICS_MEMBER (with `institutionId`) → §12.1 submit (as PI) → log back in as the ethics member → §12.3 review → §12.4 decide
7. §13.1–13.4 define a compliance requirement, attach it to the trial, assess it
8. §14.1 upload a document, wait a few seconds, §14.5 download it
9. Back to ADMIN (or anyone) — §15 hit the GIS endpoints, §16.1 hit the dashboard as a few different roles and watch the shape change, §16.3 pull the audit trail and see every write above show up in it

---

## Appendix A · A bigger test data set

One of everything (§17) proves the plumbing works. It doesn't prove much about GIS or analytics,
because **k-anonymity suppresses any cohort under 5** (§15.4) — with one institution and one
participant, every aggregate you look at will just say `"suppressed": true` and you'll never see
a real number. This appendix is 7 institutions, 3 trials, 7 sites and 7 participants, deliberately
shaped so one state clears the k=5 threshold and one doesn't — you get to see both a real
aggregate and a suppressed one side by side, plus enough sites for the clustering endpoint
(§15.3) to actually cluster something. Every request below was run against the live app while
writing this, in this exact order, with these exact values.

Create these in order — each depends on the `id` from an earlier one, exactly like the main
walkthrough. All as ADMIN unless noted.

### A.1 Institutions — 5 in Delhi, 2 in Mumbai

Delhi first, so it clears k=5 for state-level aggregates:

```json
{"name": "AIIMS Delhi", "institutionType": "GOVERNMENT_HOSPITAL", "city": "New Delhi", "state": "Delhi", "latitude": 28.5672, "longitude": 77.2100}
```
```json
{"name": "Safdarjung Hospital", "institutionType": "GOVERNMENT_HOSPITAL", "city": "New Delhi", "state": "Delhi", "latitude": 28.5697, "longitude": 77.2073}
```
```json
{"name": "Fortis Escorts Delhi", "institutionType": "PRIVATE_HOSPITAL", "city": "New Delhi", "state": "Delhi", "latitude": 28.5921, "longitude": 77.2507}
```
```json
{"name": "Max Super Speciality Saket", "institutionType": "PRIVATE_HOSPITAL", "city": "New Delhi", "state": "Delhi", "latitude": 28.5245, "longitude": 77.2066}
```
```json
{"name": "Dr Ram Manohar Lohia Hospital", "institutionType": "GOVERNMENT_HOSPITAL", "city": "New Delhi", "state": "Delhi", "latitude": 28.6304, "longitude": 77.2177}
```

Mumbai — only 2 institutions, so its trial will end up with a small, suppressed enrollment count for contrast (§A.5 explains why *small* has to mean *nonzero* for that to actually happen):

```json
{"name": "Tata Memorial Hospital", "institutionType": "RESEARCH_CENTRE", "city": "Mumbai", "state": "Maharashtra", "latitude": 19.0056, "longitude": 72.8434}
```
```json
{"name": "KEM Hospital Mumbai", "institutionType": "GOVERNMENT_HOSPITAL", "city": "Mumbai", "state": "Maharashtra", "latitude": 19.0011, "longitude": 72.8414}
```

Keep all 7 `id`s handy — call them `<inst1>`…`<inst7>` in the order created above.

### A.2 Trials — 3, different phases

```json
{"protocolNumber": "CTRI-2026-1001", "title": "A Phase III Study of Drug X in Type 2 Diabetes", "sponsorInstitutionId": "<inst1>", "phase": "III", "targetEnrollment": 200}
```
```json
{"protocolNumber": "CTRI-2026-1002", "title": "A Phase II Study of Compound Y in Hypertension", "sponsorInstitutionId": "<inst6>", "phase": "II", "targetEnrollment": 80}
```
```json
{"protocolNumber": "CTRI-2026-1003", "title": "An Observational Registry of Post-Surgical Outcomes", "sponsorInstitutionId": "<inst1>", "phase": "OBSERVATIONAL", "targetEnrollment": 500}
```
Call the three ids `<trialA>`, `<trialB>`, `<trialC>`. Walk **both** `<trialA>` and `<trialB>` through `POST /status` to `ACTIVE` (§4.4, 3 calls each: `PENDING_ETHICS` → `APPROVED` → `ACTIVE`) — you'll enrol participants into both, `<trialC>` is left in `DRAFT` on purpose (something to see across different statuses in the list views).

### A.3 Sites — 5 for trialA (one per Delhi institution), 1 each for trialB/trialC

```json
{"trialId": "<trialA>", "institutionId": "<inst1>", "siteCode": "SITE-101", "targetEnrollment": 40}
```
```json
{"trialId": "<trialA>", "institutionId": "<inst2>", "siteCode": "SITE-102", "targetEnrollment": 40}
```
```json
{"trialId": "<trialA>", "institutionId": "<inst3>", "siteCode": "SITE-103", "targetEnrollment": 40}
```
```json
{"trialId": "<trialA>", "institutionId": "<inst4>", "siteCode": "SITE-104", "targetEnrollment": 40}
```
```json
{"trialId": "<trialA>", "institutionId": "<inst5>", "siteCode": "SITE-105", "targetEnrollment": 40}
```
```json
{"trialId": "<trialB>", "institutionId": "<inst6>", "siteCode": "SITE-201", "targetEnrollment": 80}
```
```json
{"trialId": "<trialC>", "institutionId": "<inst1>", "siteCode": "SITE-301", "targetEnrollment": 500}
```
5 sites, 5 slightly different coordinates, all in Delhi — this is what makes §15.3's clustering endpoint actually draw more than one point. Call trialA's 5 site ids `<site1>`…`<site5>`, trialB's single site `<siteB>`, trialC's `<siteC>` (not used further in this appendix, but there for you to poke at).

### A.4 Trial staff — assign yourself as PI so RLS lets you enrol

Create a PI user first (§7), then assign them to trialA at every site:
```json
{"trialId": "<trialA>", "trialSiteId": "<site1>", "userId": "<piUserId>", "staffRole": "PI"}
```
Repeat with `trialSiteId` set to `<site2>`…`<site5>` too, so the same PI can enrol at all five sites below — or create separate staff users per site if you'd rather see per-site RLS scoping in action.

And once to trialB, trial-wide (no `trialSiteId` at all — see §6.1's note that it's optional):
```json
{"trialId": "<trialB>", "userId": "<piUserId>", "staffRole": "PI"}
```

### A.5 Participants — 5 in Delhi (clears k=5), 2 in Mumbai (stays suppressed)

Log in as the PI (§0) before these. One enrolment per Delhi site, `<site1>`…`<site5>`:
```json
{"trialId": "<trialA>", "trialSiteId": "<site1>", "subjectCode": "SUBJ-101", "dateOfBirthYear": 1985, "sex": "FEMALE", "identity": {"fullName": "Asha Verma", "dateOfBirth": "1985-06-15", "phone": "+91-9800000001"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
```json
{"trialId": "<trialA>", "trialSiteId": "<site2>", "subjectCode": "SUBJ-102", "dateOfBirthYear": 1978, "sex": "MALE", "identity": {"fullName": "Rohit Sharma", "dateOfBirth": "1978-02-20", "phone": "+91-9800000002"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
```json
{"trialId": "<trialA>", "trialSiteId": "<site3>", "subjectCode": "SUBJ-103", "dateOfBirthYear": 1990, "sex": "FEMALE", "identity": {"fullName": "Priya Nair", "dateOfBirth": "1990-11-02", "phone": "+91-9800000003"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
```json
{"trialId": "<trialA>", "trialSiteId": "<site4>", "subjectCode": "SUBJ-104", "dateOfBirthYear": 1965, "sex": "MALE", "identity": {"fullName": "Suresh Kumar", "dateOfBirth": "1965-08-30", "phone": "+91-9800000004"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
```json
{"trialId": "<trialA>", "trialSiteId": "<site5>", "subjectCode": "SUBJ-105", "dateOfBirthYear": 1972, "sex": "FEMALE", "identity": {"fullName": "Meera Iyer", "dateOfBirth": "1972-04-18", "phone": "+91-9800000005"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
Call the five participant ids `<part1>`…`<part5>`. `GET /api/v1/gis/aggregates?level=state` will now show a real `enrollment.value: 5, suppressed: false` for Delhi — verified against the live app while writing this.

Then, **into trialB's Mumbai site** (`<siteB>`, the one id back from §A.3's sixth request), two more:
```json
{"trialId": "<trialB>", "trialSiteId": "<siteB>", "subjectCode": "SUBJ-201", "dateOfBirthYear": 1980, "sex": "MALE", "identity": {"fullName": "Vikram Rao", "dateOfBirth": "1980-01-01", "phone": "+91-9800000006"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
```json
{"trialId": "<trialB>", "trialSiteId": "<siteB>", "subjectCode": "SUBJ-202", "dateOfBirthYear": 1975, "sex": "FEMALE", "identity": {"fullName": "Kavita Joshi", "dateOfBirth": "1975-03-12", "phone": "+91-9800000007"}, "consent": {"consentVersion": "v1.0", "consentMethod": "WRITTEN"}}
```
*Why 2, not 0:* k-anonymity only suppresses a cohort that's **nonzero but small** — a true zero isn't sensitive to reveal (there's nobody to identify), so an empty Maharashtra would just show `enrollment.value: 0, suppressed: false`, not the suppressed example this appendix promises. This was verified live too: with 0 Mumbai participants the aggregate showed a real `0`; after enrolling exactly these 2, it flipped to `"value": null, "suppressed": true, "label": "<5"`. That flip — real number in one state, honestly-redacted small number in the other — is the actual point of building this data set instead of just one of everything.

### A.6 Adverse events — one of each severity, including a SERIOUS one

The third one demonstrates the constraint from §11.1: a `SERIOUS` event with no `seriousCriteria` is rejected by the database, not just discouraged.
```json
{"participantId": "<part1>", "eventTerm": "Headache", "description": "Mild headache, resolved without treatment", "onsetDate": "2026-09-10", "severity": "MILD", "seriousness": "NON_SERIOUS"}
```
```json
{"participantId": "<part2>", "eventTerm": "Nausea", "description": "Moderate nausea, required antiemetic", "onsetDate": "2026-09-11", "severity": "MODERATE", "seriousness": "NON_SERIOUS"}
```
```json
{"participantId": "<part3>", "eventTerm": "Acute pancreatitis", "description": "Admitted for observation and IV fluids", "onsetDate": "2026-09-12", "severity": "SEVERE", "seriousness": "SERIOUS", "seriousCriteria": ["HOSPITALIZATION"]}
```
As SAFETY_OFFICER (§7), review at least the third one via §11.2 — that's what makes `securityAlerts24h`-style dashboard widgets and the safety trend endpoint (§16.2) show something.

### A.7 Ethics submissions — initial and an amendment

```json
{"trialId": "<trialA>", "institutionId": "<inst1>", "submissionNumber": "IEC-2026-101", "submissionType": "INITIAL", "summary": "Initial ethics review request for CTRI-2026-1001"}
```
```json
{"trialId": "<trialB>", "institutionId": "<inst6>", "submissionNumber": "IEC-2026-102", "submissionType": "INITIAL", "summary": "Initial ethics review request for CTRI-2026-1002"}
```
```json
{"trialId": "<trialA>", "institutionId": "<inst1>", "submissionNumber": "IEC-2026-101-A1", "submissionType": "AMENDMENT", "summary": "Amendment: add a third dosing arm"}
```

### A.8 Compliance requirement catalogue — one per category

```json
{"code": "REG-CT-01", "title": "CTRI Registration", "description": "Trial must be registered with the Clinical Trials Registry of India before enrolment", "category": "REGULATORY", "authority": "CDSCO", "isMandatory": true, "evidenceRequired": true}
```
```json
{"code": "ETH-01", "title": "Ethics Committee Approval", "description": "Current, unexpired IEC approval on file", "category": "ETHICS", "authority": "Institutional Ethics Committee", "isMandatory": true, "evidenceRequired": true}
```
```json
{"code": "SAF-01", "title": "Pharmacovigilance Plan", "description": "A documented plan for adverse event monitoring and expedited reporting", "category": "SAFETY", "authority": "CDSCO", "isMandatory": true, "evidenceRequired": true}
```
```json
{"code": "DI-01", "title": "Data Management Plan", "description": "Source data verification and query-resolution procedures", "category": "DATA_INTEGRITY", "authority": "Sponsor", "isMandatory": false, "evidenceRequired": true}
```
```json
{"code": "SQ-01", "title": "Site Feasibility Assessment", "description": "Site capacity and staff qualification review, completed pre-activation", "category": "SITE_QUALIFICATION", "authority": "Sponsor", "isMandatory": true, "evidenceRequired": false}
```
Attach a couple of these to `<trialA>` via §13.2 and assess them via §13.4 to give `/compliance/trials/{trialId}/summary` and the REGULATORY_OFFICER dashboard (§16.1) something real to roll up.
