# DEMO.md — the Swagger UI demonstration, start to finish

pkill -f CtmsApplication

cd /Users/prabhavbatra/Documents/Programming/SIH26046/backend
CTMS_JWT_SECRET="local-development-signing-key-at-least-32-bytes-long" \
  ./gradlew :ctms-app:bootRun --args='--spring.profiles.active=local'


A single linear script for showing the CTMS backend to an examiner. Everything below was run
against the live app in this exact order and works; where a response matters, the real one is
quoted.

Companion to [TEST.md](TEST.md). TEST.md is the *reference* — every endpoint, organised by
module. This is the *performance* — one path through the system that tells a story in about
20 minutes.

**Base URL:** `http://localhost:8080`
**Swagger UI:** <http://localhost:8080/swagger-ui/index.html>

---

## The one thing that can ruin the demo

**Login is capped at 5 attempts per 15 minutes, per IP.** The 6th returns `429 RATE_LIMITED`.
This script logs in exactly 5 times, so there is **no slack for a typo**.

If you get a `429` at the wrong moment, don't wait 15 minutes — the counter lives in memory.
**Restart the app** (Ctrl-C the `bootRun` terminal, run it again) and the budget is fresh.

The final step of this script uses the 6th login *deliberately*, as the closing demonstration.

---

## Part 0 · Prep (do this ~10 minutes before, not in front of anyone)

### 0.1 Start the infrastructure

```bash
cd /Users/prabhavbatra/Documents/Programming/SIH26046
docker compose up -d postgres clamav
```

### 0.2 Start the application

```bash
cd /Users/prabhavbatra/Documents/Programming/SIH26046/backend
CTMS_JWT_SECRET="local-development-signing-key-at-least-32-bytes-long" \
  ./gradlew :ctms-app:bootRun --args='--spring.profiles.active=local'
```

Wait for `Started CtmsApplication` in the log. Leave this terminal open and visible — a
restart is your escape hatch for almost everything.

### 0.3 Create the demo accounts

There is no self-registration endpoint by design, so accounts are inserted directly. Paste
this whole block in a second terminal, from the repo root:

```bash
docker compose exec -T postgres psql -U ctms -d ctms <<'SQL'
INSERT INTO users (email, password_hash, full_name, role_id) VALUES
  ('admin@ctms.local',  '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Admin',              '00000000-0000-0000-0000-000000000001'),
  ('pi@ctms.local',     '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Investigator',       '00000000-0000-0000-0000-000000000002'),
  ('coord@ctms.local',  '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Coordinator',        '00000000-0000-0000-0000-000000000003'),
  ('staff@ctms.local',  '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Research Staff',     '00000000-0000-0000-0000-000000000004'),
  ('safety@ctms.local', '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Safety Officer',     '00000000-0000-0000-0000-000000000006'),
  ('reg@ctms.local',    '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk', 'Demo Regulatory Officer', '00000000-0000-0000-0000-000000000007')
ON CONFLICT (email) DO NOTHING;
SQL
```

Safe to run twice — `ON CONFLICT DO NOTHING` makes it idempotent.

The **ethics member is deliberately missing** from this block. The database refuses to store an
active `ETHICS_MEMBER` with no institution (`ck_users_ethics_needs_institution`), and the
institution doesn't exist until Step 1. You create that account in Step 1.3.

### 0.4 Have a PDF ready

Any small PDF on the Desktop, for the document upload in Step 7.

### 0.5 Confirm you're ready

```bash
curl -s http://localhost:8080/actuator/health
```

Expect `{"groups":["liveness","readiness"],"status":"UP"}`. Anything else and the demo will
fail on step 1 — fix it now, not in front of the examiner.

---

## The credentials

Every account uses the same password.

| Role | Email | Password |
|---|---|---|
| System Admin | `admin@ctms.local` | `ChangeMe123!` |
| Principal Investigator | `pi@ctms.local` | `ChangeMe123!` |
| Trial Coordinator | `coord@ctms.local` | `ChangeMe123!` |
| Research Staff | `staff@ctms.local` | `ChangeMe123!` |
| Ethics Member | `ethics@ctms.local` | `ChangeMe123!` |
| Safety Officer | `safety@ctms.local` | `ChangeMe123!` |
| Regulatory Officer | `reg@ctms.local` | `ChangeMe123!` |

Copy-paste login bodies, one per role used in this script:

```json
{"email":"admin@ctms.local","password":"ChangeMe123!"}
```
```json
{"email":"pi@ctms.local","password":"ChangeMe123!"}
```
```json
{"email":"safety@ctms.local","password":"ChangeMe123!"}
```
```json
{"email":"ethics@ctms.local","password":"ChangeMe123!"}
```

---

## Values you will collect as you go

Write these down as each step hands you one — later steps need them. Nothing is automatic;
it's copy-paste from a response body into the next request.

| Placeholder | Comes from |
|---|---|
| `<institutionId>` | Step 1.2 |
| `<trialId>` | Step 2.1 |
| `<siteId>` | Step 3.1 |
| `<piUserId>` | Step 1.3 (printed by the SQL) |
| `<participantId>` | Step 5.1 |
| `<visitId>` | Step 5.2 |
| `<eventId>` | Step 6.1 |
| `<submissionId>` | Step 6.3 |
| `<requirementId>` / `<trialComplianceId>` | Step 4.1 / 4.2 |
| `<documentId>` | Step 7.1 |

---

# LOGIN 1 of 5 — System Admin

## Step 0 · Open the page and log in

Open <http://localhost:8080/swagger-ui/index.html>.

> **Say:** every operation on this page was generated from the running code — 52 endpoints
> across 16 controllers. Nothing here is hand-written documentation that can drift.

1. Expand **`auth-controller`** → `POST /api/v1/auth/login` → **Try it out**.
2. Replace the example body with:

```json
{"email":"admin@ctms.local","password":"ChangeMe123!"}
```

3. **Execute.**

Expected — `200`:

```json
{"userId":"...","email":"admin@ctms.local","role":"SYSTEM_ADMIN","mfaRequired":false}
```

> **Say:** the token came back as an HttpOnly cookie, not in the response body — JavaScript on
> a compromised page can't read it. The browser now attaches it to every call automatically.

## Step 0.1 · The CSRF authorize step (do this once per login)

Reads work now. Writes need one more thing.

1. Devtools → **Application** (Chrome) / **Storage** (Firefox) → Cookies → `http://localhost:8080`.
2. Copy the value of **`csrf_token`**.
3. Click **Authorize** at the top of the Swagger page, paste it into `csrfToken`, **Authorize**, **Close**.

> **Say:** the auth cookie is HttpOnly so scripts can't steal it; the CSRF cookie deliberately
> is *not*, because the real frontend has to read it and echo it back in an `X-CSRF-Token`
> header. A forged request from another site can send the cookie but cannot read it, so it
> cannot produce the matching header. Swagger UI has no JavaScript of its own to do that, so we
> paste it once by hand.

**Repeat this step after every login below** — a new login mints a new token.

---

## Step 1 · The institution

### 1.1 Show the empty state first

`institution-controller` → `GET /api/v1/institutions` → **Try it out** → **Execute**.

Returns `[]`. Worth doing — it makes the next step visibly real.

### 1.2 Create it

`POST /api/v1/institutions`:

```json
{
  "name": "AIIMS Delhi",
  "institutionType": "GOVERNMENT_HOSPITAL",
  "city": "New Delhi",
  "state": "Delhi",
  "latitude": 28.5672,
  "longitude": 77.2100
}
```

Expected `201`. **Copy `id` → `<institutionId>`.**

Re-run 1.1 to show it now returns one row.

### 1.3 Create the ethics member (needs the institution to exist)

Second terminal, repo root:

```bash
docker compose exec -T postgres psql -U ctms -d ctms <<'SQL'
INSERT INTO users (email, password_hash, full_name, role_id, institution_id)
VALUES ('ethics@ctms.local',
        '$argon2id$v=19$m=65536,t=3,p=4$TSjAw3odMThzUYSB8wQeuw$wwybXvgEQg4Yom2d1QP1UBJUkPgkqVxNYXXsBtFo6fk',
        'Demo Ethics Member',
        '00000000-0000-0000-0000-000000000005',
        (SELECT id FROM institutions WHERE name = 'AIIMS Delhi'))
ON CONFLICT (email) DO NOTHING;
SELECT id AS pi_user_id FROM users WHERE email = 'pi@ctms.local';
SQL
```

It also prints **`<piUserId>`**, which Step 3.2 needs.

---

## Step 2 · The trial, and its lifecycle

### 2.1 Create

`trial-controller` → `POST /api/v1/trials`:

```json
{
  "protocolNumber": "CTRI-2026-0001",
  "title": "A Phase III Study of Drug X in Adult Patients",
  "sponsorInstitutionId": "<institutionId>",
  "phase": "III",
  "targetEnrollment": 200
}
```

Expected `201`, with `"status": "DRAFT"`. **Copy `id` → `<trialId>`.**

### 2.2 Walk it to ACTIVE — three separate transitions

A trial will not accept a participant until it is `ACTIVE`, and it cannot jump there.
`DRAFT → PENDING_ETHICS → APPROVED → ACTIVE`.

Each transition needs the current `ETag` as `If-Match`, and **the ETag changes after every
write** — so you re-fetch it each time.

**For each of the three statuses, do this pair:**

1. `GET /api/v1/trials/{id}` with `<trialId>` → in the response-headers panel, copy the
   **`etag`** value (a quoted number, e.g. `"0"`).
2. `POST /api/v1/trials/{id}/status` with `<trialId>`, paste that value into the **`If-Match`**
   box, and send the body:

```json
{"status": "PENDING_ETHICS"}
```
```json
{"status": "APPROVED"}
```
```json
{"status": "ACTIVE"}
```

> **Say:** this is optimistic concurrency. If two coordinators open the same trial and both
> submit, the second one's ETag is stale and the write is rejected with `409` instead of
> silently overwriting. Skipping the header entirely gives `428 Precondition Required` — the
> API refuses to do an unguarded write at all.

**Optional, if the examiner looks interested:** paste an old ETag deliberately and show the
`409`.

---

## Step 3 · The site, and the access grant

### 3.1 Create the site

`trial-site-controller` → `POST /api/v1/sites`:

```json
{
  "trialId": "<trialId>",
  "institutionId": "<institutionId>",
  "siteCode": "SITE-001",
  "targetEnrollment": 50
}
```

Expected `201`. **Copy `id` → `<siteId>`.**

### 3.2 Assign the investigator

`trial-staff-controller` → `POST /api/v1/trial-staff`:

```json
{
  "trialId": "<trialId>",
  "trialSiteId": "<siteId>",
  "userId": "<piUserId>",
  "staffRole": "PI"
}
```

Expected `201`.

> **Say:** this row *is* the access grant. Having the PI role in the system grants nothing on
> its own — until this row exists, the database's row-level security returns that investigator
> zero rows for this trial. Authorisation is enforced in Postgres, not just in Java.

---

## Step 4 · Compliance (still as Admin)

### 4.1 Define a requirement

`compliance-controller` → `POST /api/v1/compliance/requirements`:

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

**Copy `id` → `<requirementId>`.**

### 4.2 Attach it to the trial

`POST /api/v1/compliance/trials/{trialId}/requirements` — `<trialId>` in the path:

```json
{"complianceRequirementId": "<requirementId>", "dueDate": "2026-10-01"}
```

**Copy `id` → `<trialComplianceId>`.**

### 4.3 Assess it

`GET /api/v1/compliance/trials/{trialId}/{id}` first to take the `ETag`, then
`POST /api/v1/compliance/trials/{trialId}/{id}/status` with `If-Match`:

```json
{"status": "COMPLIANT", "notes": "Registration certificate verified"}
```

### 4.4 The rollup

`GET /api/v1/compliance/trials/{trialId}/summary` — verified response:

```json
{"trialId":"...","total":1,"byStatus":{"COMPLIANT":1},"mandatoryOutstanding":0,"compliant":true}
```

---

# LOGIN 2 of 5 — Principal Investigator

`POST /api/v1/auth/login`:

```json
{"email":"pi@ctms.local","password":"ChangeMe123!"}
```

**Then redo the CSRF Authorize step (Step 0.1) — new login, new token.**

> **Say:** no logout needed. The new cookie overwrites the old one, so every call from here
> runs as the investigator.

---

## Step 5 · Enrol a participant and record their data

### 5.1 Enrol

`participant-controller` → `POST /api/v1/participants`. Optionally put any string in the
`Idempotency-Key` box (e.g. `demo-subj-001`):

```json
{
  "trialId": "<trialId>",
  "trialSiteId": "<siteId>",
  "subjectCode": "SUBJ-001",
  "dateOfBirthYear": 1985,
  "sex": "FEMALE",
  "identity": {
    "fullName": "Asha Verma",
    "dateOfBirth": "1985-06-15",
    "phone": "+91-9800000001"
  },
  "consent": {
    "consentVersion": "v1.0",
    "consentMethod": "WRITTEN"
  }
}
```

Verified response — `201`:

```json
{"id":"...","trialId":"...","trialSiteId":"...","subjectCode":"SUBJ-001",
 "enrollmentDate":"2026-09-04","status":"ENROLLED","dateOfBirthYear":1985,
 "sex":"FEMALE","version":0}
```

**Copy `id` → `<participantId>`.**

> **Say — this is the strongest single point in the demo.** We sent a name, a date of birth and
> a phone number. Look at what came back: a subject code and a *birth year*. The identifying
> fields went to a separate, more tightly restricted table and are never echoed by this API.
> Clinical researchers work with `SUBJ-001`; re-identification is a separate, audited privilege.

**Optional:** resend the identical request with the *same* `Idempotency-Key` — you get the same
participant back, not a duplicate.

### 5.2 A visit

`clinical-controller` → `POST /api/v1/visits`:

```json
{
  "participantId": "<participantId>",
  "visitName": "Baseline",
  "visitNumber": 1,
  "scheduledDate": "2026-09-10"
}
```

**Copy `id` → `<visitId>`.**

### 5.3 An observation

`POST /api/v1/observations`:

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

### 5.4 A medication

`POST /api/v1/medications`:

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

---

## Step 6 · Safety and ethics (still as the PI)

### 6.1 Report a serious adverse event

`safety-controller` → `POST /api/v1/adverse-events`:

```json
{
  "participantId": "<participantId>",
  "visitId": "<visitId>",
  "eventTerm": "Acute pancreatitis",
  "description": "Admitted for observation and IV fluids",
  "onsetDate": "2026-09-12",
  "severity": "SEVERE",
  "seriousness": "SERIOUS",
  "seriousCriteria": ["HOSPITALIZATION"]
}
```

Expected `201`. **Copy `id` → `<eventId>`.**

### 6.2 Show the constraint bite (worth 30 seconds)

Same endpoint, a `SERIOUS` event with **no** `seriousCriteria`:

```json
{
  "participantId": "<participantId>",
  "eventTerm": "Rash",
  "description": "Demonstrating the database constraint",
  "onsetDate": "2026-09-12",
  "severity": "SEVERE",
  "seriousness": "SERIOUS"
}
```

Verified: **`422`**.

> **Say:** that rejection came from a CHECK constraint in the database, not from a Java
> validator. Regulators require that a serious event names its seriousness criteria. Even if
> someone bypassed the API and wrote SQL directly, the database would still refuse.

### 6.3 Submit for ethics review

`ethics-controller` → `POST /api/v1/ethics/submissions`:

```json
{
  "trialId": "<trialId>",
  "institutionId": "<institutionId>",
  "submissionNumber": "IEC-2026-001",
  "submissionType": "INITIAL",
  "summary": "Initial ethics review request for CTRI-2026-0001"
}
```

**Copy `id` → `<submissionId>`.**

---

## Step 7 · Upload a document through a real virus scanner

### 7.1 Upload

`document-controller` → `POST /api/v1/documents` → **Try it out**. Swagger renders a real form —
no JSON here:

| Field | Value |
|---|---|
| `file` | pick your PDF |
| `trialId` | `<trialId>` |
| `documentType` | `PROTOCOL` |
| `title` | `Study Protocol v1.0` |

Expected `201` with **`"scanStatus": "PENDING"`**. **Copy `id` → `<documentId>`.**

### 7.2 Watch it flip to CLEAN

`GET /api/v1/documents/{id}` with `<documentId>`. Run it once immediately, then again after a
few seconds — `scanStatus` goes `PENDING` → `CLEAN`. (Verified: clean within ~4 seconds.)

> **Say:** that's a real ClamAV daemon in a container, not a stub. Until it reports clean, the
> file cannot be downloaded at all.

### 7.3 Download

`GET /api/v1/documents/{id}/download` — returns **`302`**, not the file.

Copy the **`Location`** response header into a new browser tab to actually fetch it.

> **Say:** the API never streams the bytes itself. It issues a signed URL that expires in five
> minutes. The signature *is* the credential, which is why that one URL is the single endpoint
> in the system reachable without a login — and why it's useless ten minutes later.

---

# LOGIN 3 of 5 — Safety Officer

```json
{"email":"safety@ctms.local","password":"ChangeMe123!"}
```

**Redo the CSRF Authorize step.**

## Step 8 · Adjudicate the adverse event

`POST /api/v1/safety/reviews`:

```json
{
  "adverseEventId": "<eventId>",
  "assessedSeverity": "SEVERE",
  "assessedCausality": "POSSIBLE",
  "isExpected": false,
  "requiresExpeditedReporting": true,
  "comments": "Expedited report filed with CDSCO",
  "decision": "ESCALATED"
}
```

Expected `201`.

## Step 9 · Now show what this role *cannot* do

`GET /api/v1/participants` with `trialId` = `<trialId>`.

Verified: **`403`**.

> **Say:** the safety officer just reviewed a serious event on this participant, and still
> cannot list participants. They see the event, never the person. That separation is enforced
> per-role, and the same query as the investigator returns rows.

---

# LOGIN 4 of 5 — Ethics Member

```json
{"email":"ethics@ctms.local","password":"ChangeMe123!"}
```

**Redo the CSRF Authorize step.**

## Step 10 · Committee review

`POST /api/v1/ethics/reviews`:

```json
{
  "ethicsSubmissionId": "<submissionId>",
  "recommendation": "APPROVE",
  "comments": "Protocol and consent form are adequate"
}
```

## Step 11 · The decision

`GET /api/v1/ethics/submissions/{id}` with `<submissionId>` → copy the `ETag`, then
`POST /api/v1/ethics/submissions/{id}/decision` with `If-Match`:

```json
{"status": "APPROVED", "approvalValidUntil": "2027-09-10"}
```

Expected `200`, status now `APPROVED`.

> **Say:** the committee's individual deliberation comments are only readable by the committee.
> An investigator can see that their submission was approved; they cannot read who said what
> about it.

---

# LOGIN 5 of 5 — back to System Admin

```json
{"email":"admin@ctms.local","password":"ChangeMe123!"}
```

CSRF Authorize is only needed if you plan to write — the rest of this section is all reads.

## Step 12 · The map

`gis-controller`, in this order:

| Call | Parameters |
|---|---|
| `GET /api/v1/gis/institutions` | none |
| `GET /api/v1/gis/sites` | `bbox` = `68,6,98,36` |
| `GET /api/v1/gis/clusters` | `bbox` = `68,6,98,36`, `zoom` = `5` |
| `GET /api/v1/gis/aggregates` | `level` = `state` |

The aggregates response, verified with one participant enrolled:

```json
{"level":"state","areas":[{"area":"Delhi","institutionCount":1,"siteCount":1,"trialCount":1,
 "enrollment":{"value":null,"suppressed":true,"label":"<5"},
 "complianceTotal":1,"complianceCompliant":1,"complianceMandatoryOpen":0}]}
```

> **Say — the second-strongest point in the demo.** Point at `"suppressed": true, "label": "<5"`.
> The site count is a real number; the enrolment count is withheld. With one participant in
> Delhi, publishing "1" on a public map plus a local news story is enough to identify a real
> patient. Any cohort under five comes back suppressed. The API is choosing to answer "fewer
> than five" instead of lying and instead of leaking.

If you want a *non*-suppressed number to contrast against, TEST.md Appendix A is a 7-institution
data set built exactly for that.

## Step 13 · The dashboard

`analytics-controller` → `GET /api/v1/analytics/dashboard`. Verified:

```json
{"dashboardType":"ADMIN","userTotal":7,"userActive":7,"userLocked":0,
 "institutionCount":1,"activeTrialCount":1,"securityAlerts24h":1}
```

> **Say:** there is no `?role=` parameter. The server decides the shape from who is logged in —
> an investigator gets a different set of fields from this same URL. The client cannot ask for
> a dashboard it isn't entitled to.

Then the per-trial trends, both taking `<trialId>`:

- `GET /api/v1/analytics/trials/{id}/enrollment`
- `GET /api/v1/analytics/trials/{id}/safety`

## Step 14 · The audit trail — the closing argument

`GET /api/v1/audit`, no parameters.

Everything done in this demo is in there. (Verified: 21 rows after a run of this script.)

> **Say:** every write in the last twenty minutes is here, attributed, in order. And it is
> immutable by *two independent* controls: `UPDATE`, `DELETE` and `TRUNCATE` are revoked from
> the application's database user, and a trigger rejects those operations for everyone else —
> including the schema owner and anyone who reaches the database with a psql prompt. Either
> control alone could be worked around; together they mean the application physically cannot
> rewrite its own history. That's the requirement a regulated trial actually has to meet.

---

## Step 15 · The finale — try to log in a 6th time

Go back to `POST /api/v1/auth/login` and log in as anyone.

Verified response — **`429`**:

```json
{"error":{"code":"RATE_LIMITED","message":"Too many requests; slow down and try again"}}
```

> **Say:** five login attempts per fifteen minutes, per IP. We just spent our budget switching
> between five roles — but the same limit is what makes password-guessing against this API
> impractical. It's the same mechanism, seen from the other side.

That's the end. **To run the demo again, restart the app** — the counter is in memory.

---

## If something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `429 RATE_LIMITED` on login | 5-login budget spent | Restart `bootRun`. Instant. |
| `403 CSRF_TOKEN_MISMATCH` | Skipped Authorize, or logged in again since | Re-copy `csrf_token`, Authorize again |
| `428 Precondition Required` | Missing `If-Match` | GET the resource, copy its `ETag` |
| `409 Conflict` | Stale `If-Match` | Re-GET, take the fresh `ETag` |
| `401` on everything | Access cookie expired (15 min) | Log in again — costs a login slot |
| `403` on a read that worked before | You're logged in as a different role | Intended. Log back in as the right one |
| `404` on something you just created | Out of your row-level-security scope | Intended — 404 not 403, so absence isn't confirmable |
| App won't start, `Migration checksum mismatch` | A migration file changed after being applied | `docker compose down -v && docker compose up -d postgres clamav`, restart the app, redo Prep 0.3 |
| Health `DOWN`, app running | Postgres container stopped | `docker compose up -d postgres clamav` |
| Swagger page loads, but every call says **"Failed to fetch"** with a CORS hint | Not CORS — the app is not running. The page was served from browser cache. | Check `curl -s http://localhost:8080/actuator/health`; restart `bootRun` and reload the tab |

**Full reset to a clean demo state** (~2 minutes, do it before, never during):

```bash
cd /Users/prabhavbatra/Documents/Programming/SIH26046
docker compose down -v && docker compose up -d postgres clamav
# restart bootRun, wait for "Started CtmsApplication", then redo Prep 0.3
```

---

## The 60-second version, if time collapses

If the session gets cut short, these four moments carry the project on their own:

1. **Step 5.1** — send a name, get back only a subject code.
2. **Step 9** — the safety officer reviews the event but gets `403` on the person.
3. **Step 12** — `"suppressed": true, "label": "<5"` on the map aggregate.
4. **Step 14** — the audit trail, which the application cannot rewrite.
