# Backend session context — SIH26046

> **Purpose.** This file is the handoff. If a session ends mid-phase, read this and you have
> everything needed to continue without re-deriving it. It records what is built, what is
> load-bearing, and — most valuably — the traps that already cost time. Update it at every
> phase commit.

**Last updated:** 2026-09-03. B9 (Hardening) substantially complete — **uncommitted**. CSRF,
rate limiting, security headers, the global error envelope, request-id correlation, and full
audit-event coverage are all real and tested. Deployment artifacts exist (`Dockerfile`,
`docker-compose.prod.yml`, `Caddyfile`) but nothing has actually been deployed yet — no Oracle
Cloud VM or Supabase project exists. The load test and the partitioning decision it's supposed
to settle are still ahead.
**State:** B1–B9(hardening half) complete. B9's other half — deploy, load proof — not started.
**Tests:** 225 in the default suite + 4 `external`, all green.
**Next:** walk through Oracle Cloud (Always Free VM) + Supabase account creation with the user,
deploy for real, then the k6 load test against a locally seeded 50–100M row dataset (§13).

---

## 1 · Orientation: which document is authoritative

| Document | Status |
|---|---|
| `PROJECT_ARCHITECTURE.md` | **The domain spec.** §5–§23 and ADR-001..012 are authoritative — roles, scope rules, table shapes, business rules. |
| ↳ its §3.2, §3.5, §7.7, §12, §15, §24, §26, §29 | **Superseded.** These describe the original FastAPI/Celery/Redis design. A banner at the top of the file marks them. Do not implement from them. |
| `docs/superpowers/specs/2026-09-01-spring-boot-backend-design.md` | **The runtime spec.** Java/Spring decisions, free-tier constraints, module map, §14 deviation table. |
| `BACKEND_PHASES.md` | The phase plan, B1–B9. |
| `BACKEND_CONTEXT.md` | This file. Current state and accumulated hard-won knowledge. |

When the domain spec and the runtime spec disagree, the runtime spec wins on *how* and the
domain spec wins on *what*.

---

## 2 · Where we are

| Phase | Contents | State |
|---|---|---|
| B1 | Gradle 11-module build, Java 26, Spring Boot 4.1.1 | done · `8f2b577` |
| B2 | Auth (Argon2id, JWT, rotating refresh tokens), RBAC | done · `1a55085` |
| B3 | Full schema, RLS on 18 tables, audit trail, job queue | done · `e849953`, `c010c22`, `293966f` |
| B4 | Institutions, trials, sites, staff assignment | done · `60ee042` |
| B5 | Participant enrolment, visits, observations, medications, consent | done · `b26831f` |
| B6a | Adverse events, safety review, event-triggered clinical read | done · `e0874c4` |
| B6b | Ethics submission/review/decision, compliance catalogue + rollup | done · `fabcc16` |
| B6c | Documents — upload, Tika sniffing, checksum, scan queue, quarantine | done · `278614a` |
| B6c | Version chain, signed download, first audit writes | done · `853fc59` |
| B6c | Cloudinary backend, verified live | done · `96ef7ba` |
| B6c | Orphan sweep (§16.7), real-ClamAV EICAR test | done · `f4b9815` |
| B7 | GIS — base map, clustering, k-anonymity aggregates, drill-down | done · `d7f9bc3` |
| B8 | Analytics dashboard, rollup materialized view, Caffeine cache, audit endpoint | done · `37f6217` |
| **B9** | **CSRF, rate limiting, headers, error envelope, request-id, full audit coverage, deployment artifacts** | **hardening done · uncommitted — deploy and load-proof not started** |

---

## 3 · Running it

```bash
cd backend
./gradlew test                          # full suite; needs Docker running
./gradlew externalTest                  # verifies adapters against REAL Cloudinary; writes
                                        # to the live account, needs backend/.env
./gradlew :ctms-app:test --tests 'com.sih26046.ctms.EthicsApiIT'
./gradlew test --rerun-tasks            # bypass the build cache when in doubt
./gradlew :ctms-app:bootRun             # needs a live Postgres + the env below
```

- **Docker must be running** — Testcontainers starts one `postgis/postgis:17-3.5` container for
  the whole test JVM, in a static initialiser, never stopped (Ryuk reaps it).
- Toolchain: **Java 26**, Gradle **9.7.1**, configuration cache and build cache both **on**.
- Secrets live in `backend/.env`, which is gitignored. `CTMS_JWT_SECRET` has no default — the
  application refuses to start without it, on purpose.
- Tests tagged `external` hit real third-party services and are **excluded from `test`**. The
  exclusion is on the `test` task only — putting it in `configureEach` applies it to
  `externalTest` too, and a task that both includes and excludes a tag runs **zero tests while
  reporting BUILD SUCCESSFUL**.

---

## 4 · The mechanisms everything rests on

Understand these before changing anything.

### 4.1 RLS identity binding

`RlsAwareTransactionManager` overrides `doBegin` and issues
`SELECT set_config('app.current_user_id', ?, true)` on the connection, **parameterised** and
**transaction-scoped** (`is_local = true`). Policies read it through
`app.current_user_id()`. Transaction-scoped is what makes it safe behind a connection pooler.

`AccessTokenAuthFilter` binds `RlsUserContext` for HTTP requests via try-with-resources.
`RlsUserContext.callAs(userId, ...)` is the programmatic entry, used by tests.

### 4.2 Two database identities

| Identity | Used by | Why |
|---|---|---|
| owner (`ctms`) | Flyway migrations | Creates tables, policies, grants, and `ctms_app` itself |
| `ctms_app` | the application pool | Owns nothing, so **every** policy constrains it |

**PostgreSQL exempts superusers and table owners from RLS.** Pointing the application at the
owner disables every policy at once, silently. `RlsConnectionGuard` refuses to start if the
connected role is superuser or has `BYPASSRLS`. `ALTER TABLE … FORCE ROW LEVEL SECURITY` is set
on every protected table so ownership alone is not an escape.

**No role has DELETE on anything.** Deliberate: clinical records are corrected, not removed.

### 4.3 RBAC carries no role names

Permissions are `GrantedAuthority` strings shaped `resource:action`, checked with
`@PreAuthorize("hasAuthority('trial:update')")`. **No role name may appear in application
code** (§6.1) and no `hasRole`. `AuthorizationRulesTest` (ArchUnit) enforces it, and includes a
non-vacuity guard so the rule cannot pass by matching nothing.

Role names *do* appear in SQL policies, via `app.current_role_name()`. That is the intended
split: the database expresses scope by role, the application expresses capability by permission.

### 4.4 Optimistic concurrency

`@Version` → `ETag: "<n>"` on GET. Writes require `If-Match`:
**428** when absent, **409** when stale. `TrialController.loadForWrite` is the reference
implementation; `EthicsController` and `ComplianceController` follow it.

### 4.5 The job queue is Postgres, not Kafka

`jobs` table, claimed with `SELECT … FOR UPDATE SKIP LOCKED`. No RLS on it, deliberately — it is
infrastructure, not domain data. `DocumentScanWorker` is the first consumer.

**A worker sees no RLS-protected rows.** It runs on nobody's behalf, so `app.current_user_id` is
unset and every policy evaluates false — its `UPDATE` matches nothing, and an `UPDATE` that
matches nothing is not an error. The answer is a **narrow `SECURITY DEFINER` function per
operation** (V19), never a `BYPASSRLS` role and never running as the uploading user, whose
deactivation would strand their files forever. Constrain the function in SQL so calling it
grants only the one thing it exists for.

**Give every test its own job type.** The suite shares one database; claiming by a type a real
feature also drains means asserting on whichever job happened to be oldest.

### 4.6 Storage is behind an interface, and delivery is signed

`StorageBackend` (ADR-005) has two implementations, chosen by
`ctms.documents.storage-backend`: `local` (filesystem, the default and the test path) and
`cloudinary`. Nothing above the interface knows which is running, and `documents` stores a
generic handle rather than anything Cloudinary-shaped.

Download is **two steps**, because authorisation and delivery are different concerns.
`GET /documents/{id}/download` checks the permission, the row-level scope and the scan status,
writes the audit record, and 302s to a signed URL. The URL is minted per request and **never
stored or cached** (§12.2) — a cached one outlives the check that produced it. Five minutes, so
a URL leaked into a log or a screenshot is dead on arrival.

The endpoint the URL points at is deliberately **unauthenticated**: the signature *is* the
credential. The expiry is inside the signed payload, so a leaked link cannot be given a longer
life by editing the query string, and verification is constant-time.

### 4.7 The audit trail

`AuditTrail.record(...)` is the only writer, plain JDBC because `audit_logs` is append-only by
construction — V12 revokes UPDATE from the application role and a trigger stops everyone else.

**`REQUIRES_NEW`, the deliberate opposite of the job queue's `REQUIRED`.** A job must roll back
with the work that scheduled it; an audit record must not. "This caller was authorised to
download this file" stays true even if the response later fails, and the security-relevant
events are exactly the ones whose transactions do not always commit.

---

## 5 · Traps that already bit us

**This is the highest-value section in the file.** Every row cost real time.

### 5.1 Spring Boot 4 moved modules

| Symptom | Cause | Fix |
|---|---|---|
| Migrations silently never ran | Boot 4 moved Flyway autoconfiguration out of `spring-boot-autoconfigure`. `flyway-core` alone puts the library on the classpath and **never runs it, without erroring**. | `spring-boot-starter-flyway` |
| `PostgreSQLContainer` won't resolve | Testcontainers 2.x prefixes artifacts `testcontainers-`; the class moved to `org.testcontainers.postgresql` and is no longer generic | `org.testcontainers:testcontainers-postgresql` |
| `AutoConfigureMockMvc` won't resolve | moved to `org.springframework.boot.webmvc.test.autoconfigure` | artifact `spring-boot-webmvc-test` |
| Jackson imports fail | Jackson 3.1.5 lives under `tools.jackson`, not `com.fasterxml.jackson` | use JsonPath in tests instead |
| `HttpStatus.UNPROCESSABLE_ENTITY` deprecated | Spring 7 renamed it | `UNPROCESSABLE_CONTENT`, matcher `isUnprocessableContent()` |

### 5.2 Tests that passed for the wrong reason

These are the ones to be afraid of.

- **`@ServiceConnection` outranks `@DynamicPropertySource`.** It contributes a
  `JdbcConnectionDetails` bean, so a `spring.datasource.username` override is accepted and
  ignored — tests then connect as the container **superuser**, and superusers bypass RLS. Every
  scope test would pass with correct, wrong, or *absent* policies. **Never reintroduce
  `@ServiceConnection` in `AbstractPostgresIT`.** Caught only by
  `TrialScopeRlsIT.applicationConnectsAsANonSuperuserRole`, whose production counterpart is
  `RlsConnectionGuardIT`.
- **An in-transaction assertion cannot detect GUC leakage.** The first `is_local` test passed
  with `is_local` flipped to `false`, because every transaction overwrites the value.
  `RlsIdentityPropagationIT.identityDoesNotSurviveOntoNonTransactionalStatements` is the one
  that discriminates.
- **The scope harness counted whole tables** and broke when an unrelated test class inserted a
  row. It now restricts to its own fixture ids so the counts stay exact.
- **A test task that runs zero tests reports `BUILD SUCCESSFUL`.** `excludeTags("external")` in
  `tasks.withType<Test>().configureEach` also reached `externalTest`, which includes that same
  tag — so it matched nothing and passed. Only the implausible three-second runtime gave it
  away. Put the exclusion on the `test` task alone, and distrust a suite that finishes far
  faster than the work it claims to have done.
- **`jdbc.update()` on a `SELECT` over a void-returning function throws *after* the function has
  run and committed.** The document reached DRAFT while its job failed and requeued forever. Use
  `jdbc.query(sql, rs -> null, args)`. More generally: when a worker writes, assert the *queue's*
  view of the outcome, not only the row's — a status written just before an exception looks
  exactly like success.
- **A test that only asserts "it failed" cannot tell which failure it saw.** The transient-scanner
  test read `attempts == 1` caused by a broken worker, not by its own scripted failure. Assert the
  recorded error text.

**Practice that follows: prove the guard fires.** Mutate the thing the test guards and confirm
the test goes red. Doing this changed the answer three separate times.

### 5.3 Rollback undoes security writes

A `@Transactional` method that **records a security fact and then throws** loses the record.
Hit twice: revoking a token family on reuse detection, and incrementing the failed-login
counter. Both need `noRollbackFor`.

> **Rule: any method that records a security fact and then throws needs `noRollbackFor`.**

### 5.4 RLS policy defects the read-side tests cannot catch

A pattern, not a one-off — it has recurred four times. A policy written as `FOR ALL` with a
`WITH CHECK` aimed at the reading role **silently forbids the writing role**, and a read-only
scope harness proves the read half correct while the endpoint does not work.

| Migration | What was broken |
|---|---|
| V14 | V7's `trial_staff` write policy required an assignment that could not yet exist — staffing a new trial was impossible. Added a creator-only bootstrap. |
| V16 | `participant_identity:create` was granted to nobody, making enrolment impossible. (`:read` is still granted to no role, correctly.) |
| V17 | `ethics_submissions` `WITH CHECK` required the PI, so `ETHICS_MEMBER` — the only holder of `ethics:decide` — could not record a decision. Split `FOR ALL` per command. |
| V18 | V11 declares the compliance catalogue readable by every authenticated session; V3 granted `compliance:read` to four roles. Both layers had passing tests; the endpoint 403'd. |

> **When adding an endpoint over an existing table, check the `WITH CHECK` before writing code.**
> `FOR ALL` + a role-specific `WITH CHECK` is the smell.

### 5.5 Never put an RLS-protected table in a policy subquery

An inline `EXISTS` against a protected table makes the predicate depend on what the caller can
*read* (§7.4). Two victims: the safety branch on `visits` was permanently unreachable, and
`ethics_reviews`' institution check. **Use a `SECURITY DEFINER` helper** with
`SET search_path = pg_catalog, public` — `app.safety_may_read_visit`,
`app.ethics_submission_institution` are the templates.

### 5.6 Postgres / JDBC specifics

- **`citext` vs bound String.** PgJDBC binds `String` as `varchar`, so `citext = varchar`
  resolves through `text` — case-**sensitive**, despite the column being case-insensitive.
  `A@x.in` could not sign in as `a@x.in`. Fix: `CAST(:email AS citext)` in the query.
  Also: `@JdbcTypeCode(SqlTypes.OTHER)` passes validation but binds `bytea`;
  `columnDefinition = "citext"` satisfies both sides.
- **SQLSTATE, not exception type.** Spring maps RLS refusal (**42501**) to
  `BadSqlGrammarException`, whose message says only "bad SQL grammar" — *not*
  `PermissionDeniedDataAccessException`. And a unique violation (**23505**) arriving through
  Hibernate is a plain `DataIntegrityViolationException`, not `DuplicateKeyException`; only the
  JdbcTemplate path refines it. Both handlers therefore walk the cause chain and read
  `getSQLState()`. See `RowLevelSecurityDenialAdvice`.
- **`ON DELETE SET NULL` is an UPDATE**, which the audit-immutability trigger blocked, so
  `DELETE FROM users` failed. The trigger now permits exactly a genuine FK detach: jsonb
  equality on all other columns, the key may only be nulled, and something must actually change.
- **Trigger-written columns need `@Generated`** (`org.hibernate.annotations.Generated`) or
  Hibernate keeps the value it sent instead of re-reading what the trigger decided. Applies to
  `updated_at`, `created_at`, and to derived columns like `adverse_events.trial_id`.
- **`end_date >= CURRENT_DATE` left access alive until midnight.** V15 made it exclusive.
- **A partial unique index cannot be deferred to commit** — only a *constraint* can.
  `uq_documents_one_current_per_family` rejected `publish()` because promoting the new version
  before retiring the old one left two `CURRENT` rows for the length of one statement. Demote,
  flush, then promote. More broadly: when a uniqueness rule spans rows you are swapping between,
  statement order is part of the design, not an implementation detail.

### 5.7 Miscellaneous

- **`@ConditionalOnMissingBean` on a component-scanned `@Component` is unreliable** and here
  registered *no* storage backend at all. It is only dependable on auto-configuration `@Bean`
  methods. Select implementations with an explicit named property instead
  (`ctms.documents.storage-backend`), which fails loudly rather than silently.
- **Spring's default multipart limit is 1 MB**, far under the schema's 50 MB, so a legitimate
  upload failed as a 500 before any validator ran. Set `spring.servlet.multipart.*`, including
  `file-size-threshold` so large uploads spill to disk instead of heap.
- Nested repository **interfaces** are not scanned by Spring Data (nested *projection*
  interfaces are fine — `TrialComplianceRepository.StatusTally` works).
- `@BeforeAll` runs before the Spring context refreshes.
- `ResponseEntity.ok()` has no `.status()`.
- zsh does not word-split unquoted variables.
- **GraalVM has no Java 26 build at all** — native image was dropped, fat jar instead.

---

## 6 · Working rhythm

1. **Write the integration test first. Run it. Watch it fail for the right reason** — feature
   missing, not a typo or a 500.
2. Implement minimally, rerun, then run the **full** suite (`--rerun-tasks`) because migrations
   and permission grants are shared state.
3. Commit at each phase boundary with a message explaining *why*, especially any defect found.
4. Prefer real libraries over hand-rolled code (standing instruction from the user).
5. Ground new work in the markdown docs, citing section numbers in comments — the codebase
   does this throughout and it is how policy intent stays traceable.
6. Comments explain **why**, not what. Match the surrounding density.

---

## 7 · Third-party libraries, and why each one

Standing instruction: prefer real libraries to hand-rolled code. What that bought, and the
reason each is the right call rather than a default:

| Library | Version | Why not by hand |
|---|---|---|
| `org.apache.tika:tika-core` | 3.3.2 | Magic-byte detection. **Core only** — the parser modules extract document *content*, which is unwanted here and drags in a large, historically CVE-prone tree. |
| `xyz.capybara:clamav-client` | 2.1.2 | clamd's INSTREAM framing. Chunked framing and reply parsing are exactly what works in testing and truncates on a 40 MB file. |
| `com.cloudinary:cloudinary-http5` | 2.4.0 | Its signature scheme is underdocumented in the details that matter and shifts across API versions; a subtly wrong one works today and 401s after an upgrade. |
| BouncyCastle (Argon2id), Nimbus (JWT) | via Boot BOM | Never hand-roll a KDF or a JWS. |

Deliberately **not** added: a JSON library in `DocumentScanWorker` — Postgres extracts
`payload->>'documentId'`, so the worker needs no opinion about which Jackson package this Boot
version ships.

---

## 8 · Migrations

| # | Contents |
|---|---|
| V1 | postgis + pgcrypto |
| V2 | users, roles, permissions, role_permissions, sessions |
| V3 | 7 roles, 58 permissions, grants derived from §5.8. `grant_perms` is dropped at the end, so later grants must INSERT directly (see V18). |
| V4 | institutions, trials, trial_sites, trial_staff; generated `location` columns |
| V5 | `ctms_app` role, default privileges, `app.*` helper functions; **no DELETE granted** |
| V6 | FORCE RLS + policies on the 4 structural tables |
| V7 | trial_staff policy narrowed to §5.8 |
| V8 / V9 | 6 clinical tables + their policies |
| V10 / V11 | safety, ethics, compliance, documents + their policies |
| V12 | audit_logs, immutable via revoked grants + trigger |
| V13 | jobs queue (no RLS, deliberately) |
| V14 | trial_staff creator-only bootstrap |
| V15 | assignment end_date exclusive |
| V16 | `participant_identity:create` grant |
| V17 | ethics per-command policies; PI withdraw pinned to destination status |
| V18 | `compliance:read` for ETHICS_MEMBER and SAFETY_OFFICER |
| V19 | `app.document_storage_handle` and `app.record_scan_result` — how the scan worker touches RLS-protected rows |
| V20 | `app.referenced_storage_public_ids` — how the orphan sweep touches RLS-protected rows |
| V21 | Fixes `trial_sites_located`'s accidental RLS bypass (`security_invoker = true`) |
| V22 | `app.suppress_small` — k-anonymity suppression (§11.4) |
| V23 | `app.gis_site_markers`, `app.gis_area_aggregates` — the global GIS base map and Level-1 aggregates |
| V24 | `app.gis_may_see_trial_safety` — tells "no events" from "not visible to you" apart at GIS drill-down |
| V25 | `mv_trial_rollup`, `app.trial_rollup_source`, `app.refresh_trial_rollup` — the dashboard read model |

---

## 9 · Deferred, with the trigger that should undefer it

| Deferred | Undefer when |
|---|---|
| Idempotency table | A second app instance runs, **or** subject codes become server-generated. Today `uq_participants_trial_subject_code` holds the safety property and `IdempotencyStore` is in-process. |
| Table partitioning | B8, after profiling. Moved out of B3 deliberately. |
| CSRF | B9. Currently disabled at `SecurityConfig.java:36`, with a pointer to §18.12. |
| RLS on `trial_compliance` is looser than RBAC | Intentional. §5.8 gives PI/COORD `compliance:read` only; the API is the narrower layer. Narrower is the safe direction. |
| `CloudinaryStorageBackend.exists()` treats any failure as "absent" | Did not become the orphan sweep's problem: the sweep (§16.7, built) decides orphan status from `storage.list()` cross-referenced against `app.referenced_storage_public_ids()`, never from a per-object `exists()` probe, so a network fault during the sweep fails the whole run closed (§10) rather than misreading one object as absent. The method itself is still unsafe for any future caller that needs to tell "gone" from "could not check" apart — fix that before relying on it for anything that deletes. |
| `DocumentScanScheduler` polls every 5 s on one instance | Fine now. A second instance is safe (`SKIP LOCKED` handles it) but doubles the poll rate; move to a longer interval or `LISTEN/NOTIFY` if that matters. |

---

## 10 · B6c — what is built

**Goal:** `/documents`. Upload validation, async malware scan, quarantine until clean,
immutable version chain, short-lived signed download, orphan cleanup. **Complete.**

### Built

| Piece | Notes |
|---|---|
| `StorageBackend` + `LocalStorageBackend` + `CloudinaryStorageBackend` | Selected by `ctms.documents.storage-backend`. |
| Upload through the backend | §16.7 settles it — "the upload writes to Cloudinary *before* the transaction commits… the handler deletes the asset in its exception path" is server-side. "Signed upload" means the server signs its own API call. Spooled to a temp file, never buffered in heap. |
| Validation (§16.5) | Size · extension allowlist · **content sniff** · filename sanitisation · SHA-256. |
| Scan pipeline | `DocumentScanWorker` drains `DOCUMENT_SCAN`; `PENDING_SCAN` → `CLEAN`/`INFECTED`. An infected asset's bytes are **deleted**, not merely flagged. Proven against a real `clamav/clamav` container by `ClamAvScanIT` (tagged `external`), not only the scripted verdict in `DocumentUploadIT`. |
| Version chain (§17.2) | `document_family_id` groups versions; v1 sets it to its own id. |
| Signed download (§16.4) | 302, 300 s, audited, never cached. |
| Orphan sweep (§16.7) | `DocumentOrphanSweepWorker.sweep()`, timed by `DocumentOrphanSweepScheduler` (nightly, off in tests). Lists the storage backend's own namespace via the new `StorageBackend.list()`, diffs against `app.referenced_storage_public_ids()` (V20), deletes anything unreferenced older than `ctms.documents.orphan-sweep.min-age` (default 24 h). |

**Content sniffing runs on content only.** Tika is never given the filename as a hint — a
detector told what to expect agrees with an attacker's chosen extension and the check becomes a
mirror. A mismatch is **rejected, not corrected**. Two honest limits are documented in the
allowlist: `.docx` and `.xlsx` are both a ZIP container when detected from bytes alone, and CSV
is indistinguishable from any other delimited text. Neither weakens the actual threat model — an
executable wearing a document's extension is caught regardless.

**The chain's two rules**, both about what an inspection asks. A superseded version keeps its
bytes, checksum and version number and stays readable, because "which protocol was in force on
this date" is unanswerable if history is overwritten. And the current version **stays current
until its replacement is published** — uploading an amendment is not approving one. Publishing
is gated on `document:supersede`, not `document:upload`: a coordinator may upload an amendment,
but retiring the protocol in force is the investigator's call.

**Cloudinary, as built.** Assets upload as `type: authenticated`, because a default upload is
publicly reachable by URL forever with nothing to revoke. That forces the download design:
Cloudinary's plain `signed: true` URLs are tamper-proof but **never expire**, and its expiring
`auth_token` scheme needs a paid add-on. The **private download API** carries `expires_at`
inside the signature and *is* on the free tier, so that is what `signedDownloadUrl` uses — which
is what makes §16.4's five minutes real rather than aspirational. `CloudinaryStorageIT` proves
it against the live service: a valid link serves the bytes, a tampered one and an expired one do
not. Credentials are in `backend/.env` and verified.

### How the last two pieces were proven

**Orphan sweep.** `DocumentOrphanSweepIT` (default suite, real Postgres, local storage
backend) covers the three cases that matter: an object still referenced by a `documents` row
survives even when its file is backdated past the grace period (this is also the test that
would fail first if V20's SECURITY DEFINER function stopped seeing past RLS — a worker with no
bound identity would then read "referenced" as empty and delete it anyway); a fresh,
genuinely unreferenced object survives because it might just be a commit that has not landed
yet; and only an unreferenced object past the grace period is actually removed.

**`ClamAvScanIT`** (tagged `external`, `./gradlew externalTest`) runs an EICAR upload — the
standard antivirus test string, uploaded as `eicar.csv` because Tika sniffs plain ASCII as
`text/plain`, which the CSV allowlist already accepts — through the real `DocumentScanWorker`
against a real `clamav/clamav:stable` container (`Wait.forHealthcheck()`, matching the image's
own healthcheck used in `docker-compose.yml`, plus a `ping()` retry loop before any test runs).
Asserts `QUARANTINED`, bytes deleted, and — through the actual endpoint, not just internal
state — that `GET /documents/{id}/download` on a quarantined document is `409`, never a
redirect. A companion test scans a genuine clean PDF, so the suite cannot pass by a scanner
that always says INFECTED. Expect a ~1 GB one-time image pull and well under a minute for
clamd to answer once pulled — both tests together run in a few seconds after that.

---

## 11 · B7 — GIS

**Goal:** `/gis`. One map, seven roles (§1.3, §10, §11). **Complete.**

### The design problem B7 actually was

The domain spec's SQL examples (§10.3, §11.4) query `trial_sites`/`trial_compliance` directly
and assume that returns the right rows for every role. It does not: those tables' RLS is
*clinical* scoping — who may work on this trial — built in B4 for `/sites` and `/compliance`,
and §11.3 requires something different at Level 0/1: a Research Staff member scoped to one
site still sees the *national* base map, and an Ethics Member (whose RLS reaches nothing on
`trials` at all, an existing B4 gap — see below) still sees aggregate figures. Loosening the
clinical policies to get there would also loosen `/sites` and `/compliance` themselves, since
RLS is table-wide, not endpoint-scoped.

The actual design, then: two SECURITY DEFINER functions (V23) — `app.gis_site_markers()` and
`app.gis_area_aggregates()` — expose exactly the fields §11.2 lists as public, deliberately
global, the same pattern V19/V20 already established for background workers with a legitimate
need that ordinary RLS cannot grant. Level 2/3 drill-down does the opposite on purpose: it
queries `trial_sites` directly under the caller's own RLS, so an out-of-scope site is a
genuine 404, not a hidden field.

### A real bug found on the way, fixed before it had a caller

`trial_sites_located` (built in B4, V4) is an ordinary view over two `FORCE ROW LEVEL
SECURITY` tables. Before PostgreSQL's `security_invoker` option, a view checks its access to
underlying tables — both grants and row security — as the *view's owner*, not the querying
role. The owner here is whichever role ran the migrations, and in this project's own
Testcontainers harness that role is a literal superuser, which bypasses row security
unconditionally regardless of FORCE (§7.7 — FORCE only ever closes the owner-without-superuser
loophole). The view would therefore have returned every row to every caller, silently, the
moment anything queried it under RLS. Nothing had — its one reference was a schema test on the
unscoped owner connection — but GIS was exactly the kind of consumer that would have reached
for it next. **V21** sets `security_invoker = true`, closing it before it had a caller.

### What was deliberately left out, and why

| Left out | Why | Undefer when |
|---|---|---|
| **`district`-level aggregates** | The schema has only `city` and `state` — no `district` column anywhere. Approximating one from city data would misrepresent a real administrative boundary. `?level=` accepts `state` and `city`; `district` is `422`. | A migration adds a real `district` column to `institutions`/`trial_sites`. |
| **Aggregate/site-detail adverse-event counts for `REGULATORY_OFFICER`** | `adverse_events_scope` (V11) has no branch for this role at all, on purpose — the migration's own comment says their aggregate safety view is meant to come from a B8 rollup, not row access. `REGULATORY_OFFICER` holds `adverse_event:read` at the RBAC layer regardless (§6.3), so gating on that permission alone would run the count query, get zero rows back from RLS, and report a **false "0"** — indistinguishable from a site where nothing happened. **V24**'s `app.gis_may_see_trial_safety()` restates `adverse_events_scope`'s own predicate so the field can be *omitted* rather than zeroed for this one role. `SAFETY_OFFICER` (unconditional RLS access) and PI/COORDINATOR/RESEARCH_STAFF (via their own scope) get the real count today. | B8 builds the AE rollup; `REGULATORY_OFFICER`'s field can then read from it instead of being absent. |
| **Materialized views / caching** | Spec §7 and BACKEND_PHASES.md put this in B8 on purpose — profile before caching, and the queries this phase adds are exactly what B8 would need to have stopped changing shape first. Every query here runs live. | B8, after profiling against a seeded dataset. |

### The other RBAC/RLS mismatch this phase surfaced, not fixed

`trials_read` / `trial_sites_read` (V6) have no `ETHICS_MEMBER` branch — only
`app.reads_all_structure()` (`SYSTEM_ADMIN`, `SAFETY_OFFICER`, `REGULATORY_OFFICER`) or trial
assignment. `ethics_submissions` RLS *does* scope to the member's institution, but that is a
different table with no path back to `trials` itself. §5.8's own capability matrix already
shows `ETHICS_MEMBER` holding no `gis:drilldown`, so this has no practical effect on B7 — the
role reaches Level 0/1 (global, via V23) same as everyone and was never going to reach Level 2
regardless. Left as a known B4 gap rather than patched here, since fixing it is a `trials`/
`trial_sites` RLS change with a blast radius belonging to whoever next needs an Ethics Member
to read a trial for a non-GIS reason.

### Built

| Piece | Notes |
|---|---|
| `app.gis_site_markers()` (V23) | The base map's site layer — location, status, institution — global, no enrolment figure (a single site's raw count on a map every role can open is the exact re-identification risk §11.4 exists to prevent). |
| `app.gis_area_aggregates(group_by)` (V23) | State/city rollups: structural counts exact, enrolment through `app.suppress_small` (V22, verbatim from §11.4). Reads back as plain `bigint`/`boolean` columns, not jsonb — nothing above the database needs an opinion about which Jackson package Boot 4.1 ships, the same reasoning `DocumentScanWorker`'s payload already uses. |
| `app.gis_may_see_trial_safety(trial)` (V24) | Lets the drill-down endpoint skip, rather than zero, an adverse-event count the caller's RBAC permission implies but their RLS scope does not back up. |
| `GisController` / `GisService` (`ctms-gis`) | `/institutions`, `/sites` (bbox/trial/status-filtered in Java — safe only because these are public fields with nothing to suppress), `/clusters` (`ST_ClusterDBSCAN`, SQL, never Java), `/aggregates`, `/sites/{id}/detail`. |
| Role-aware drill-down composition | Same site, different answer: the assigned investigator gets raw enrolment (they already have it via `/participants`); `SAFETY_OFFICER`/`REGULATORY_OFFICER` (unconditional `trial_sites` read, no clinical stake) get it suppressed under k=5, same as a small aggregate cell. `compliance` is `null` for a caller without `compliance:read` (note: `SAFETY_OFFICER` *has* this, via V18 — a bug in this phase's own first test draft, not in V18). |

**Every `GisService` method is `@Transactional`, and that is load-bearing, not decorative.**
`RlsAwareTransactionManager` binds `app.current_user_id` only when a Spring-managed transaction
begins (§4.1) — a bare `JdbcTemplate` call outside one gets a connection with the GUC unset,
every policy (and V23's own `IS NOT NULL` guards) evaluates false, and the result is an empty
map, not an error. `ConsentController`/`ParticipantController`'s existing `@Transactional
(readOnly = true)` on GET methods is this same requirement; it just had no comment explaining
why until this phase needed to get it right from scratch.

**Test isolation trap, caught before it shipped:** `GisApiIT`'s aggregate tests group by a
fresh random state/city per test method, not a fixed name — the shared Postgres container
persists across methods in the class, and a fixed name let one test's enrolment leak into
another's tally on the first run. The clustering test needed the same fix a second way: its
fixture institutions sit at genuinely random coordinates unrelated to any other fixture in the
file, because the base map is deliberately global and a wide bbox otherwise sweeps up whatever
every other method in the class created at the same fixed Delhi/Mumbai points.

---

## 12 · B8 — Analytics + Caching

**Goal:** `/analytics/dashboard` (one endpoint, seven shapes), the trial rollup materialized
view, a cache in front of it, the partitioning decision. **Complete.**

### The read model

`mv_trial_rollup` (V25) is one row per trial — enrolment, site count, AE totals/serious/
unreviewed, compliance tally, whether a current ethics approval exists. Like B7's GIS
functions, its source query is `SECURITY DEFINER`: a materialized view carries no RLS of its
own (policies attach to tables, not views), and the rollup genuinely needs to see every trial's
adverse events regardless of who eventually reads it. **The scoping happens entirely at read
time**, in the join every dashboard widget uses — `mv_trial_rollup r JOIN trials t ON t.id =
r.trial_id` — which inherits `trials`' own RLS for free. Refresh is `REFRESH MATERIALIZED VIEW
CONCURRENTLY`, which needs ownership `ctms_app` doesn't have, so it goes through
`app.refresh_trial_rollup()`, the same `SECURITY DEFINER` answer as everywhere else a worker
needs to touch RLS-protected or owner-restricted objects (V19/V20/V23/V24).

`TrialRollupRefresher` (unconditional `@Component`) does the refresh; `TrialRollupRefreshScheduler`
(`@ConditionalOnProperty`, disabled in tests) times it every 60 s — the exact
worker/scheduler split B6c's orphan sweep already established, for the same reason: a test
refreshing on data it just wrote must not race a timer doing the same thing mid-assertion.

### Caching

`CacheProvider` (interface) / `CaffeineCacheProvider` (impl), one tier. **Redis L2 was not
built.** The design calls for it "wired but inactive at one instance" — at this project's
actual scale (one instance, no managed Redis on the free tier either) that is a container, a
dependency and a config surface bought for a property the deployment does not have. The
interface is what a second tier would sit behind later without any caller changing.
Invalidation is TTL-only (90 s, §23.8's 60–120 s window) rather than a write-triggered
`§13.2` map: precise invalidation across every write path touching seven dashboard shapes is a
much larger change than the value of shaving 90 seconds of staleness off an at-a-glance card,
and the TTL is what that window is *for*. Revisit if a dashboard number's staleness ever
matters more than that trade — a compliance or safety figure someone is about to act on, say.

### Partitioning: deferred again, on purpose

BACKEND_PHASES.md put this decision here, "against measured numbers." No load test has run
yet (that is B9's job — §14 of the phase plan, k6 against a seeded 50–100M-row dataset), so
there are no numbers to decide against. Deferring without evidence is the correct call the
spec itself asks for, not a skipped task — implementing month-range partitioning on
`observations` now would also cost `uq_observations_visit_code` (§B3's own note) for a benefit
nobody has measured yet. Revisit in B9 once the load test exists.

### Two real bugs this phase's own tests caught

**Self-invocation silently drops `@Transactional`.** `AnalyticsService.dashboard()` cached its
result via `cache.get(key, () -> compute(caller))`. Putting `@Transactional` on `compute`
instead of `dashboard` compiled fine and passed the one test that didn't depend on it — because
`compute` was called through `this`, never through the Spring proxy that actually implements
the annotation, so no transaction ever began, `app.current_user_id` was never bound, and every
RLS-scoped query returned zero rows. Silently: a dashboard with a real trial on it read back
empty, no error anywhere. Fixed by moving `@Transactional` to `dashboard` itself, which is
what the controller actually calls through the proxy. **The annotation must sit on the method
the caller invokes from outside the class — never on a method reached only via `this`.**

**A `NULL` JDBC parameter needs a cast to be compared with `IS NULL` in the same clause.**
`/audit`'s optional filters were written as `(? IS NULL OR entity_type = ?)`; PostgreSQL's
driver cannot infer the placeholder's type from a null value with no other context and refused
the query outright (`BadSqlGrammarException`) rather than silently misbehaving — a kinder
failure than V22.4.4-style test camouflage, but still a filter that would have gone the same
way for every optional audit query. Fixed with an explicit `CAST(? AS text)` per parameter,
matching its column's real type (`uuid`, `timestamptz`).

## 13 · B9 — Hardening (deploy and load-proof still ahead)

**What's built**, all in `ctms-common`/`ctms-security`/`ctms-app` unless noted:

| Item | Where | Notes |
|---|---|---|
| Request-id correlation | `ctms-common/.../web/RequestIdFilter.java` | `HIGHEST_PRECEDENCE` filter, MDC key `requestId`, echoed as `X-Request-Id` on every response and in every error body |
| Global error envelope | `ctms-common/.../web/GlobalErrorAdvice.java` | `{"error":{"code","message","requestId"}}` for anything not already handled by a per-controller `@ExceptionHandler` — those are untouched and still fire first |
| CSRF double-submit | `ctms-security/.../CsrfDoubleSubmitFilter.java`, `AuthCookies.csrf(...)` | Real enforcement on every non-GET request except `/auth/login`. Cookie is deliberately non-`HttpOnly` |
| Rate limiting | `ctms-security/.../ratelimit/*` (Bucket4j, in-memory) | Tiers from spec §18.10/§9.2: strict on login (IP *and* email), refresh, uploads, GIS drill-down; generous on ordinary reads/writes. `ctms.security.rate-limit.enabled=false` in tests (see trap below) |
| Security headers | `SecurityConfig.java`'s `.headers(...)` block | HSTS, CSP, X-Frame-Options, Referrer-Policy, Permissions-Policy, nosniff. Duplicated at the edge by `Caddyfile` in production — either layer being bypassed still leaves the other |
| Audit completeness | every domain module's write endpoints (or their backing `@Transactional` service, where the controller itself isn't transactional — see trap below) | `AuditTrail.recordChange(...)` covers every §19.2 write event with automatic PHI redaction (`Redaction.redact`, new in `ctms-common`) |
| Keep-alives | `ctms-app/.../ops/*` | A Supabase ping (prevents the 7-day pause) and a configurable dead-man's-switch health ping (no-ops until a monitoring URL exists) |
| Deployment artifacts | repo root: `Dockerfile`, `docker-compose.prod.yml`, `Caddyfile`, `.env.example` | Backend-only — no Vercel/frontend piece yet (none exists), no Redis (consistent with B8's decision, nothing actually uses one) |

**Three real bugs found while building this, in order of how embarrassing they are:**

1. **I reintroduced the exact B8 self-invocation `@Transactional` bug**, in the same file class of problem, days after documenting it as a trap to watch for. `AuditTrail.record()` (kept for `DocumentController.download()`'s one call site) originally delegated to `this.recordAccess(...)` — a plain Java call that never goes through the Spring proxy, so `recordAccess`'s `@Transactional(REQUIRES_NEW)` silently never fired, and the INSERT ran inside whatever transaction the caller already had open. For a `readOnly = true` caller (the download endpoint), that's "cannot execute INSERT in a read-only transaction." A background agent fixing the CSRF test migration found it by actually running the suite. Fixed by giving `record()` its own `@Transactional(REQUIRES_NEW)` and its own call to the private `insert(...)` helper — no delegation between the two public methods, ever. **The rule, again, because apparently once wasn't enough:** if method B needs its own propagation and is ever reached via `this.B(...)` from method A in the same class, A's transaction wins and B's annotation is silently decorative.
2. **Spring Boot 4.1.1's `spring-boot-starter-web` no longer auto-registers a classic `com.fasterxml.jackson.databind.ObjectMapper` bean** — Boot 4 defaults to Jackson 3 (`tools.jackson.*`) internally, though Jackson 2 is still on the classpath transitively (springdoc and others still expect it) and compiles fine, it just has no auto-configured bean. `AuditTrail`, `CsrfDoubleSubmitFilter`, and `RateLimitFilter` all construct-inject a Jackson 2 `ObjectMapper` — without a bean, the whole security filter chain fails to build, everywhere, tests included. Fixed with a small compatibility bean (`ctms-common/.../audit/JacksonCompatibilityConfig.java`, via `Jackson2ObjectMapperBuilder`). If a future phase touches JSON handling, know that this app now carries both Jackson major versions side by side on purpose.
3. **Bucket4j buckets live for the process lifetime with nothing to reset them.** The test suite calls `/auth/login` for real, hundreds of times, from the same JVM — a production-calibrated 5-per-15-minute login budget doesn't survive the second test class. Fixed the same way every other timer in this codebase is handled in tests: a property (`ctms.security.rate-limit.enabled`), defaulted `true`, set `false` in `AbstractPostgresIT`. A dedicated test overrides it back to exercise the real behaviour.

**Explicitly not done, and why that's fine for now:**
- **No actual deployment.** No Oracle Cloud VM, no Supabase project — the user has neither yet. Artifacts are ready; the account-creation walkthrough is the next conversation, not a code task.
- **No k6 load proof, no partitioning decision.** Needs a locally seeded 50–100M row dataset (§13.3 of the Spring design doc) — a separate, sizeable undertaking, not something to fold into a hardening pass.
- **No formal §13.2 "scope harness."** The per-module RLS/authorization tests already built across B4–B8 substantially cover the same ground per-endpoint; a single generic parameterized harness replaying every query as all seven roles was judged a separate, larger investment than this phase's actual ask.
- `participant_identity:read` has no rate-limit tier wired to an actual endpoint — no controller exposes a raw identity-read route yet (identity is write-only at enrolment, never echoed back). Nothing to limit until that endpoint exists.

## 14 · Standing constraints

- Free tier throughout: Supabase Postgres (500 MB, no replicas, no backups, 7-day pause),
  Oracle Always Free VM (4 ARM cores / 24 GB) for the app + Redis + ClamAV, Vercel for the
  frontend with a rewrite proxy so cookies stay first-party.
- **Upstash is 500k commands per _month_.** This is why sessions live in Postgres and rate
  limiting is in-process Bucket4j + Caffeine, not Redis.
- Scale target is **~10³ concurrent staff against ~10⁹ rows**, not 10⁶ users. That makes
  partitioning, materialized views and query plans the priorities — not app-tier scale-out.
