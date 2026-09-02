# Backend session context — SIH26046

> **Purpose.** This file is the handoff. If a session ends mid-phase, read this and you have
> everything needed to continue without re-deriving it. It records what is built, what is
> load-bearing, and — most valuably — the traps that already cost time. Update it at every
> phase commit.

**Last updated:** 2026-09-02, after `96ef7ba`.
**State:** B1–B6b complete. B6c is done except the orphan sweep and the real-ClamAV test.
**Tests:** 203 in the default suite + 2 `external`, all green.
**Next:** orphan sweep (§16.7) · `ClamAvScanIT` against a live clamd · then B7 GIS.

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
| **B6c** | **remainder — orphan sweep, real-ClamAV EICAR test** | **next** |
| B7 | GIS | not started |
| B8 | Analytics, caching, partitioning decision | not started |
| B9 | Hardening, CSRF, deploy | not started |

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

---

## 9 · Deferred, with the trigger that should undefer it

| Deferred | Undefer when |
|---|---|
| Idempotency table | A second app instance runs, **or** subject codes become server-generated. Today `uq_participants_trial_subject_code` holds the safety property and `IdempotencyStore` is in-process. |
| Table partitioning | B8, after profiling. Moved out of B3 deliberately. |
| CSRF | B9. Currently disabled at `SecurityConfig.java:36`, with a pointer to §18.12. |
| RLS on `trial_compliance` is looser than RBAC | Intentional. §5.8 gives PI/COORD `compliance:read` only; the API is the narrower layer. Narrower is the safe direction. |
| `CloudinaryStorageBackend.exists()` treats any failure as "absent" | Acceptable only because its callers re-check. If the orphan sweep ever deletes on the strength of this alone, a network fault becomes data loss — make it distinguish NotFound from everything else first. |
| `DocumentScanScheduler` polls every 5 s on one instance | Fine now. A second instance is safe (`SKIP LOCKED` handles it) but doubles the poll rate; move to a longer interval or `LISTEN/NOTIFY` if that matters. |

---

## 10 · B6c — what is built, and what is left

**Goal:** `/documents`. Upload validation, async malware scan, quarantine until clean,
immutable version chain, short-lived signed download.

### Built

| Piece | Notes |
|---|---|
| `StorageBackend` + `LocalStorageBackend` + `CloudinaryStorageBackend` | Selected by `ctms.documents.storage-backend`. |
| Upload through the backend | §16.7 settles it — "the upload writes to Cloudinary *before* the transaction commits… the handler deletes the asset in its exception path" is server-side. "Signed upload" means the server signs its own API call. Spooled to a temp file, never buffered in heap. |
| Validation (§16.5) | Size · extension allowlist · **content sniff** · filename sanitisation · SHA-256. |
| Scan pipeline | `DocumentScanWorker` drains `DOCUMENT_SCAN`; `PENDING_SCAN` → `CLEAN`/`INFECTED`. An infected asset's bytes are **deleted**, not merely flagged. |
| Version chain (§17.2) | `document_family_id` groups versions; v1 sets it to its own id. |
| Signed download (§16.4) | 302, 300 s, audited, never cached. |

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

### Left

1. **Orphan sweep** (§16.7). A nightly job listing assets in the trial namespace, comparing
   against `documents.cloudinary_public_id`, removing anything unreferenced for more than 24
   hours. The delay avoids racing an in-flight upload.
2. **`ClamAvScanIT`** — one test against a real `clamav/clamav` container asserting an EICAR
   upload never leaves `QUARANTINED`. The fake in `DocumentUploadIT` proves the state machine
   and nothing about detection, and "an infected upload never reaches AVAILABLE" is B6's stated
   done-condition. Expect a ~1 GB one-time image pull and ~40 s for clamd to load signatures, so
   tag it `external` alongside the Cloudinary tests.

---

## 11 · Standing constraints

- Free tier throughout: Supabase Postgres (500 MB, no replicas, no backups, 7-day pause),
  Oracle Always Free VM (4 ARM cores / 24 GB) for the app + Redis + ClamAV, Vercel for the
  frontend with a rewrite proxy so cookies stay first-party.
- **Upstash is 500k commands per _month_.** This is why sessions live in Postgres and rate
  limiting is in-process Bucket4j + Caffeine, not Redis.
- Scale target is **~10³ concurrent staff against ~10⁹ rows**, not 10⁶ users. That makes
  partitioning, materialized views and query plans the priorities — not app-tier scale-out.
