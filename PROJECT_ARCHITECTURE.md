# SIH26046 — Clinical Trial Management Platform

> Technical Architecture & Development Specification

Status: MVP Architecture
AI: Deferred
GIS: Enabled
Primary Database: PostgreSQL
Cache: Redis
File Storage: Cloudinary
Backend: FastAPI
Frontend: Next.js

---

## Document Status

This document is the single source of truth for the technical architecture of the
SIH26046 platform. It defines *what* is built and *how the pieces fit together*.
It deliberately contains no implementation code — each build phase in §26 gets its
own implementation plan written against this document.

| Field | Value |
|---|---|
| Problem statement | SIH26046 |
| Document version | 1.0 |
| Last updated | 2026-09-01 |
| Core tables | 23 (see §8) |
| Roles | 7 |
| Architecture style | Modular monolith |
| Deployment target | Docker Compose, single VM |

**Three tables in §8 go beyond the original table list.** Each is justified inline
where it appears: `participant_identities` (§8.12), `sessions` (§8.6), and the
absence of a `notifications` table (§8.25) are deliberate, argued decisions rather
than drift.

---

## Table of Contents

| § | Section | § | Section |
|---|---|---|---|
| 1 | [Project Context](#1-project-context) | 18 | [Authentication & Security](#18-authentication-and-security) |
| 2 | [Core Architecture Principles](#2-core-architecture-principles) | 19 | [Audit Logging](#19-audit-logging) |
| 3 | [Technology Stack](#3-technology-stack) | 20 | [Data Lifecycle Management](#20-data-lifecycle-management) |
| 4 | [High-Level Architecture](#4-high-level-architecture) | 21 | [API Architecture](#21-api-architecture) |
| 5 | [Roles and Permissions](#5-roles-and-permissions) | 22 | [Frontend Sitemap](#22-frontend-sitemap) |
| 6 | [RBAC Architecture](#6-rbac-architecture) | 23 | [Dashboard Architecture](#23-dashboard-architecture) |
| 7 | [Row-Level Security](#7-postgresql-row-level-security) | 24 | [Folder Structure](#24-folder-structure) |
| 8 | [Database Schema](#8-complete-database-schema) | 25 | [Environment Management](#25-environment-management) |
| 9 | [Database Relationships](#9-database-relationships) | 26 | [Development Workflow](#26-development-workflow) |
| 10 | [GIS Architecture](#10-gis-architecture) | 27 | [Testing Strategy](#27-testing-strategy) |
| 11 | [GIS Privacy](#11-gis-privacy) | 28 | [Performance Strategy](#28-performance-strategy) |
| 12 | [Redis Caching Strategy](#12-redis-caching-strategy) | 29 | [Deployment Architecture](#29-deployment-architecture) |
| 13 | [Cache Invalidation](#13-cache-invalidation) | 30 | [Observability](#30-observability) |
| 14 | [Data Consistency](#14-data-consistency) | 31 | [Architecture Decisions](#31-architecture-decision-records) |
| 15 | [Replication](#15-replication) | 32 | [Constraints](#32-important-constraints) |
| 16 | [Cloudinary File Architecture](#16-cloudinary-file-architecture) | — | — |
| 17 | [Document Versioning](#17-document-versioning) | — | — |

---

## 1. Project Context

### 1.1 What this platform is

A clinical trial management and monitoring platform for multi-site, multi-institution
trials. One deployment serves the whole national picture: institutions are organisational
units within a single instance, not isolated tenants, because the Regulatory Officer role
requires a cross-institution view. Isolation between institutions is therefore enforced by
row-level policy (§7), never by separate databases.

The platform manages the full operational lifecycle of a trial:

| Domain | Responsibility |
|---|---|
| Trials & sites | Protocol registration, site activation, staff assignment |
| Participants | Pseudonymised enrolment, consent capture, withdrawal |
| Clinical data | Visit scheduling, observations, concomitant medications |
| Safety | Adverse event capture, seriousness grading, safety review workflow |
| Ethics | IEC submission, review, approval, amendment history |
| Compliance | Regulatory requirement tracking per trial |
| Documents | Versioned protocols, consent forms, approvals, regulatory filings |
| Audit | Append-only record of every consequential action |
| GIS | Geographic view of institutions, sites, and aggregate trial activity |

### 1.2 Single platform, seven roles

The application is one unified platform. There is no separate portal per role. A user
authenticates once and is routed to a role-appropriate dashboard; every role shares the
same API surface, the same database, and the same GIS interface. What differs between
roles is *which actions they may perform* (§6) and *which rows they may see* (§7).

| # | Role constant | Short description |
|---|---|---|
| 1 | `SYSTEM_ADMIN` | Platform operation, user and institution management |
| 2 | `PRINCIPAL_INVESTIGATOR` | Scientific and operational ownership of assigned trials |
| 3 | `TRIAL_COORDINATOR` | Day-to-day operational running of trials at assigned sites |
| 4 | `RESEARCH_STAFF` | Front-line clinical data capture at an assigned site |
| 5 | `ETHICS_MEMBER` | Institutional Ethics Committee review and decision |
| 6 | `SAFETY_OFFICER` | Adverse event monitoring and safety review |
| 7 | `REGULATORY_OFFICER` | National oversight, compliance, regulatory reporting |

### 1.3 GIS is global

Every authenticated role reaches the same `/gis` page and the same GIS API. There is no
per-role GIS subsystem. What varies is the *depth of drill-down*, which is governed by the
same RBAC and RLS machinery as everything else (§11). Building seven map implementations
would be seven times the code and seven times the places to leak participant data.

### 1.4 Explicitly deferred

The following are **out of scope for this MVP** and must not appear in implementation plans:

- [ ] Predictive enrolment modelling
- [ ] AI protocol assistant
- [ ] Automated safety report generation
- [ ] Multi-language support
- [ ] Voice-based data entry
- [ ] Native mobile application
- [ ] Blockchain audit verification
- [ ] Any AI/ML risk engine

**Why the architecture still accommodates them.** Every deferred feature above is a
*consumer* of data, not a producer of it. The modular monolith (§24) keeps domain logic in
`services/` behind interfaces that a future worker can call; the audit log (§19) already
records the event stream an ML pipeline would train on; Celery (§3) already exists for
asynchronous work. Adding predictive enrolment later means adding a worker and an endpoint,
not restructuring the schema. This is the specific sense in which the design is
"AI-ready": no AI infrastructure is built, but no decision here blocks it.

---

## 2. Core Architecture Principles

These six principles are binding. Where a later section appears to conflict with one,
the principle wins and the section is wrong.

### 2.1 PostgreSQL is the source of truth

All transactional and clinical data is authoritative in PostgreSQL. Every other store is
derived and disposable.

- **Redis** holds only data that can be recomputed from PostgreSQL. Flushing Redis
  entirely must never lose information (§12).
- **Cloudinary** holds file bytes. PostgreSQL holds the metadata that gives those bytes
  meaning — which trial, which version, which status (§16). A Cloudinary asset with no
  matching `documents` row is orphaned garbage, not a document.

### 2.2 Redis is never authoritative

If Redis and PostgreSQL disagree, PostgreSQL is correct and the Redis entry is stale and
must be discarded. No code path may read a value from Redis that it cannot recompute from
PostgreSQL. In particular, **no authorization decision may depend on Redis** — see §7.6
for why this rules out a tempting performance optimisation.

### 2.3 The frontend is never trusted for authorization

The Next.js application hides navigation and controls the user cannot use. This is a
usability feature, not a security control. A user who edits JavaScript, replays a request
with a modified ID, or calls the API directly with `curl` must be stopped by the backend
and, failing that, by the database.

Security is enforced in depth:

```text
Frontend          →  hides what you cannot do          (usability)
Backend / API     →  rejects what you may not do       (authorization)
Database / RLS    →  cannot return rows you may not see (containment)
```

The third layer exists precisely because the second one will eventually have a bug. A
missing permission check in one endpoint is a defect; a missing permission check that
leaks another site's participants is a breach. RLS turns the first into the second's
absence.

### 2.4 Consequential operations are auditable

Every action that creates, modifies, or approves clinical, safety, ethics, or access
control state writes an audit record **in the same transaction as the change itself**
(§19). An audit log that can be out of step with the data it describes is worse than none,
because it is trusted.

### 2.5 Multi-table operations are transactional

Enrolling a participant touches five tables (§14.6). Either all five changes land or none
do. Partial enrolment — a participant with no consent record, a consent with no participant
— is a data integrity failure that no amount of application cleanup reliably repairs.

### 2.6 Integrity over premature optimisation

Given a choice between a correct design and a faster one, this project takes the correct
one and measures before optimising. Concretely: foreign keys stay on, constraints stay on,
RLS stays on, and caching is added where profiling shows a need (§12), not pre-emptively.
A hackathon demo that returns wrong clinical data quickly has failed.

---

## 3. Technology Stack

Each technology below carries a single, stated responsibility. Where a choice is
contestable, the reasoning is given rather than assumed.

### 3.1 Frontend

| Technology | Responsibility | Why this choice |
|---|---|---|
| **Next.js** (App Router) | Rendering, routing, role-based layouts, session cookie handling | Server Components let dashboards fetch on the server with the session cookie attached, so no access token is ever exposed to client JavaScript. Route groups map cleanly onto the seven role sitemaps (§22). |
| **TypeScript** | Compile-time contract with the API | The OpenAPI schema FastAPI generates (§3.2) is code-generated into TypeScript types, so a backend field rename becomes a frontend build error rather than a runtime `undefined` in a clinical form. |
| **Tailwind CSS** | Styling | Utility classes keep styling local to the component; no cascade to reason about across seven distinct dashboards. |
| **shadcn/ui** | Component primitives | Copied into the repo rather than installed as a dependency, so the data table, dialog, and form components can be adapted to clinical data entry needs without fighting a library. Accessible by default, which matters for form-heavy clinical screens. |

### 3.2 Backend

| Technology | Responsibility | Why this choice |
|---|---|---|
| **FastAPI** | HTTP API, request validation, dependency injection | Its dependency injection is the mechanism that makes §6 and §7 enforceable: `require_permission("participant:read")` and the RLS session setup are both dependencies, so authorization is declared on the route rather than remembered inside it. |
| **Python 3.12** | Implementation language | Mature PostGIS, Cloudinary, and Celery libraries; the ecosystem the team can move fastest in. |
| **Pydantic v2** | Request/response schemas | Validation at the boundary (§18.13). A malformed adverse event payload is rejected before it reaches a service. |
| **SQLAlchemy 2.0** | ORM and query construction | Parameterised queries by construction, which closes SQL injection (§18.14). Its session lifecycle is where `SET LOCAL` is issued for RLS (§7.4). |
| **Alembic** | Schema migrations | Every schema change, including RLS policies and grants, is a reviewed, versioned, reversible migration. Policies applied by hand in `psql` do not survive a rebuild. |
| **REST + OpenAPI/Swagger** | API style and contract | FastAPI derives the OpenAPI document from the Pydantic schemas automatically, giving the frontend generated types and the judges a browsable `/docs`. |

### 3.3 Database

| Technology | Responsibility | Why this choice |
|---|---|---|
| **PostgreSQL 16** | Source of truth for all data | The only realistic choice here, because three separate requirements depend on features it alone combines: row-level security (§7), transactional integrity across the enrolment flow (§14), and first-class geospatial support via PostGIS. |
| **PostGIS 3.4** | Spatial types, indexes, and queries | `geography(Point,4326)` with a GIST index answers "which sites are in this viewport" and "aggregate by state" in the database, so the API never ships raw coordinates it then filters in Python. |

### 3.4 Cache

| Technology | Responsibility | Why this choice |
|---|---|---|
| **Redis 7** | Cache-aside store for aggregates; Celery broker and result backend | Dashboard and GIS aggregate queries are read-heavy, expensive, and tolerant of seconds-old data — the exact profile cache-aside serves (§12). Redis doubles as the Celery transport, so the stack gains a queue without gaining a component. |

### 3.5 Background jobs

| Technology | Responsibility | Why this choice |
|---|---|---|
| **Celery** | Asynchronous work outside the request cycle | Three concrete jobs in the MVP: nightly GIS aggregate precomputation, malware scanning of uploaded documents (§16.6), and overdue-visit detection feeding the Coordinator dashboard. None of these may block an HTTP response. |
| **Redis (broker + backend)** | Queue transport | Already present. Adding RabbitMQ for a three-job workload would be complexity for its own sake (§32). |
| **Celery Beat** | Scheduled triggers | Drives the nightly aggregate and daily overdue-visit sweeps. |

### 3.6 Files

| Technology | Responsibility | Why this choice |
|---|---|---|
| **Cloudinary** | Storage and delivery of uploaded document bytes | Handles storage, CDN delivery, and signed authenticated URLs without the team operating object storage. Selected for this project; §16.9 records the production caveat honestly. |

### 3.7 Maps / GIS

| Technology | Responsibility | Why this choice |
|---|---|---|
| **PostGIS** | Spatial query and aggregation | See §3.3. Aggregation and k-anonymity suppression (§11.4) happen in SQL, so suppressed data never enters the API layer at all. |
| **Leaflet** | Client-side map rendering | Small, dependency-light, no API key, no vendor account. It renders GeoJSON — which is exactly what PostGIS emits via `ST_AsGeoJSON` — so the pipeline has no format translation step. |
| **OpenStreetMap** | Base tiles | Free, no key, no per-load billing surprise mid-demo. Attribution requirements are met in the map footer. |

### 3.8 Authentication

| Technology | Responsibility | Why this choice |
|---|---|---|
| **JWT (HS256)** | Stateless access token, 15-minute lifetime | Short enough that a leaked access token expires before it is useful; long enough to avoid refreshing on every request. |
| **Opaque refresh tokens** | Long-lived re-authentication, 14 days, rotating | Opaque rather than JWT because refresh tokens must be *revocable*, and revocation requires server-side state (§8.6) that a self-contained JWT cannot provide. |
| **HttpOnly + Secure + SameSite cookies** | Token transport | Cookies marked `HttpOnly` are unreadable by JavaScript, which removes token theft as an outcome of any XSS defect (§18.5). |
| **Argon2id** | Password hashing | Memory-hard, so GPU cracking gains far less than it does against bcrypt or PBKDF2. Current OWASP recommendation. |

### 3.9 Infrastructure and monitoring

| Technology | Responsibility | Why this choice |
|---|---|---|
| **Docker + Compose** | Environment definition, dev/demo parity | One `docker compose up` produces the entire stack, including PostGIS and ClamAV. A new team member is productive without installing PostgreSQL locally, and the demo environment is the development environment (§29). |
| **Sentry** | Error monitoring and alerting | Captures unhandled exceptions with request context. Configured with scrubbing so clinical values never reach it (§30.7). |
| **structlog** | Structured JSON application logging | Machine-parseable logs carrying request ID and user ID but never clinical content (§30.1). |

---

## 4. High-Level Architecture

```mermaid
flowchart TB
    subgraph clients["Users"]
        U["7 roles · single web application"]
    end

    subgraph edge["Edge"]
        CADDY["Caddy<br/>TLS · security headers · rate limiting"]
    end

    subgraph frontend["Next.js"]
        SSR["Server Components<br/>role-based layouts"]
        UI["Client Components<br/>forms · tables · Leaflet map"]
    end

    subgraph backend["FastAPI — modular monolith"]
        AUTH["Auth module<br/>JWT · refresh rotation · Argon2id"]
        RBAC["RBAC guard<br/>permission dependencies"]
        RLSCTX["RLS context<br/>SET LOCAL app.current_user_id"]
        API["Domain routers<br/>trials · participants · clinical<br/>safety · ethics · compliance"]
        GISAPI["GIS module<br/>aggregation · k-anonymity"]
        DOCS["Document module<br/>validation · signing · versioning"]
        ANALYTICS["Analytics module<br/>dashboard aggregates"]
        AUDIT["Audit writer<br/>same-transaction append"]
    end

    subgraph workers["Celery"]
        W1["GIS aggregate precompute"]
        W2["Malware scan"]
        W3["Overdue visit sweep"]
    end

    subgraph data["Data tier"]
        PG[("PostgreSQL 16<br/>source of truth<br/>RLS policies")]
        GIS[("PostGIS 3.4<br/>geography · GIST")]
        REDIS[("Redis 7<br/>cache-aside · broker")]
        CLOUD[("Cloudinary<br/>file bytes · signed URLs")]
    end

    subgraph obs["Observability"]
        SENTRY["Sentry"]
        LOGS["structlog JSON"]
    end

    U --> CADDY --> SSR
    SSR --> UI
    SSR -->|"session cookie"| AUTH
    UI -->|"XHR + cookie + CSRF token"| AUTH

    AUTH --> RBAC --> RLSCTX
    RLSCTX --> API
    RLSCTX --> GISAPI
    RLSCTX --> DOCS
    RLSCTX --> ANALYTICS

    API --> AUDIT
    DOCS --> AUDIT
    AUDIT --> PG

    API --> PG
    GISAPI --> GIS
    ANALYTICS --> PG
    PG --- GIS

    ANALYTICS -.->|"cache-aside"| REDIS
    GISAPI -.->|"cache-aside"| REDIS
    AUTH -.->|"session lookaside"| REDIS

    DOCS --> CLOUD
    DOCS -->|"metadata only"| PG

    API -->|"enqueue"| REDIS
    REDIS --> workers
    workers -->|"BYPASSRLS role"| PG
    W2 --> CLOUD

    backend --> SENTRY
    backend --> LOGS

    classDef truth fill:#1d4ed8,stroke:#1e3a8a,color:#fff
    classDef cache fill:#b45309,stroke:#78350f,color:#fff
    class PG,GIS truth
    class REDIS cache
```

### 4.1 Reading the diagram

Three things in the diagram carry the architecture's weight:

**The authorization chain is linear and unskippable.** Every domain router sits behind
`AUTH → RBAC → RLSCTX`. There is no arrow from the edge directly to a domain router.
This is enforced structurally by FastAPI dependencies at router level (§6.4), not by
each endpoint remembering to check.

**Redis is only ever reached by dashed lines.** Dashed edges are cache-aside reads that
fall through to PostgreSQL on a miss. No solid line — no authoritative read or write —
terminates at Redis. The one apparent exception, the session lookaside from `AUTH`, is
also a cache: a miss falls through to the `sessions` table (§8.6).

**Workers connect to PostgreSQL on a different role.** Celery jobs compute platform-wide
aggregates that legitimately cross every trial and site boundary. Rather than weakening
RLS policies so background jobs can function, workers connect as `ctms_worker`, which
holds `BYPASSRLS` (§7.7). Request handling never uses that role.

There is no AI service in this diagram, and none is planned for the MVP (ADR-007).

---

## 5. Roles and Permissions

Each role below is defined by five things: what it exists to do, what it can read, what it
can write, what it must never reach, and how deep it can drill into the map.

### 5.1 SYSTEM_ADMIN

| Aspect | Definition |
|---|---|
| **Purpose** | Keep the platform running and its access control correct. Explicitly *not* a clinical role. |
| **Dashboard** | `/admin` — platform health (§23.1) |
| **Responsibilities** | Create and deactivate users, assign roles, register institutions, register trials and sites, read the audit trail, manage system configuration |
| **Pages** | `/admin`, `/admin/users`, `/admin/roles`, `/admin/institutions`, `/admin/trials`, `/admin/sites`, `/admin/audit`, `/admin/settings`, plus global pages |
| **Can read** | All users, roles, permissions, institutions, trials, sites, staff assignments, compliance requirements, audit logs, GIS aggregates |
| **Can create** | Users, institutions, trials, sites, staff assignments, role/permission grants, compliance requirements |
| **Can update** | User status and role, institution and site records, trial registration metadata, system settings |
| **Cannot access** | **Participant identities, consents, observations, medications, individual adverse event narratives, ethics review deliberations.** The admin manages *access to* clinical data without holding it. |
| **GIS** | Full geographic view of all institutions and sites; aggregate counts only, no clinical drill-down |

> **Why an admin cannot read clinical data.** The reflex is to give administrators
> everything. That reflex is wrong here. A System Admin has no clinical or ethical basis
> for reading a participant's observations, and granting it creates a single account whose
> compromise exposes every record on the platform. The admin needs to manage *who has
> access*, which is a different capability from *having access*. This separation is
> enforced by RLS (§7.5), not by convention — an admin who queries `observations` directly
> receives zero rows.

### 5.2 PRINCIPAL_INVESTIGATOR

| Aspect | Definition |
|---|---|
| **Purpose** | Hold scientific and regulatory responsibility for assigned trials |
| **Dashboard** | `/investigator` — trial management (§23.2) |
| **Responsibilities** | Own the protocol, oversee enrolment across sites, review safety signals, drive ethics submissions, monitor compliance, assign site staff |
| **Pages** | `/investigator`, `/investigator/trials`, `/investigator/participants`, `/investigator/safety`, `/investigator/ethics`, `/investigator/compliance`, `/investigator/gis`, `/investigator/analytics` |
| **Can read** | Everything within trials where they hold a `trial_staff` assignment with role `PI`: all sites, all participants (pseudonymised), consents, visits, observations, medications, adverse events, safety reviews, ethics submissions and decisions, compliance status, documents, trial analytics |
| **Can create** | Trials (as PI of record), ethics submissions, documents, adverse event reports, staff assignments within their trials |
| **Can update** | Their trials' protocol metadata and status, their trials' compliance records, documents they own, staff assignments within their trials |
| **Cannot access** | Any trial they are not assigned to; participant identities by default (§8.12 — requires the separate `participant_identity:read` permission, granted only where the institution's SOP requires it); ethics committee internal deliberation on other institutions' submissions; platform user administration; audit logs outside their trials |
| **GIS** | Full drill-down for their own trials' sites; aggregate-only, k-suppressed view of everything else |

### 5.3 TRIAL_COORDINATOR

| Aspect | Definition |
|---|---|
| **Purpose** | Run the trial operationally at the site level, day to day |
| **Dashboard** | `/coordinator` — operational management (§23.3) |
| **Responsibilities** | Schedule and track visits, enrol participants, capture consent, chase missing data, maintain the trial master file, escalate adverse events, keep compliance items current |
| **Pages** | `/coordinator`, `/coordinator/trials`, `/coordinator/participants`, `/coordinator/visits`, `/coordinator/clinical-data`, `/coordinator/documents`, `/coordinator/adverse-events`, `/coordinator/compliance` |
| **Can read** | All data for trials and sites where assigned: participants, consents, visits, observations, medications, adverse events, documents, compliance status |
| **Can create** | Participants, consents, visits, observations, medications, adverse events, documents |
| **Can update** | Participants, visits, observations, medications, participant lifecycle status, compliance record status, document versions |
| **Cannot access** | Trials or sites where they hold no assignment; ethics review deliberation; safety review decisions (they report events, the Safety Officer adjudicates them); platform administration; audit logs |
| **GIS** | Drill-down for assigned sites; aggregate-only elsewhere |

### 5.4 RESEARCH_STAFF

| Aspect | Definition |
|---|---|
| **Purpose** | Capture clinical data accurately at the point of care |
| **Dashboard** | `/research` — daily clinical work (§23.4) |
| **Responsibilities** | Conduct scheduled visits, record observations, record concomitant medications, report adverse events as they occur, upload source documents |
| **Pages** | `/research`, `/research/participants`, `/research/visits`, `/research/clinical-data`, `/research/adverse-events`, `/research/documents` |
| **Can read** | Participants, visits, observations, medications, adverse events, and documents **for their assigned site only** — narrower than the Coordinator, who spans a trial's sites |
| **Can create** | Visits, observations, medications, adverse events, documents |
| **Can update** | Observations and medications they recorded, within the correction window; visit status |
| **Cannot access** | Other sites within the same trial; other trials entirely; consent records (create/read is Coordinator scope); safety adjudication; ethics; compliance; administration; audit logs |
| **GIS** | Geographic context only — sees the map, sees institutions and sites, sees suppressed aggregates. Gains no clinical access to trials they are not assigned to |

> This is the role that makes RLS necessary rather than optional. It is the narrowest scope
> on the platform and the most numerous account type. §7.2 works through its policy in full.

### 5.5 ETHICS_MEMBER

| Aspect | Definition |
|---|---|
| **Purpose** | Independent ethical review of trial protocols and amendments for one institution's IEC |
| **Dashboard** | `/ethics` — review workflow (§23.5) |
| **Scope decision** | **Institution-scoped.** A member belongs to exactly one institution's Institutional Ethics Committee, via `users.institution_id`, and sees only submissions routed to that committee. This mirrors how Indian IECs operate under CDSCO / New Drugs and Clinical Trials Rules, 2019, where a committee's remit is its own institution. |
| **Responsibilities** | Review submissions, record decisions and conditions, maintain approval history, review submitted documents |
| **Pages** | `/ethics`, `/ethics/pending-reviews`, `/ethics/reviews`, `/ethics/documents`, `/ethics/history` |
| **Can read** | Ethics submissions routed to their institution's committee, the documents attached to those submissions, the protocol metadata of the trials concerned, prior reviews on those submissions, aggregate safety counts for trials under their review |
| **Can create** | Ethics reviews (decision, comments, conditions) |
| **Can update** | Their own reviews, until the submission decision is finalised |
| **Cannot access** | **Participants, consents, visits, observations, medications, individual adverse event narratives** — ethical review operates on the protocol, not on subject data; submissions to other institutions' committees; operational trial management; administration |
| **GIS** | Sees institutions and sites; drill-down limited to trials under their committee's review, and to aggregate counts even there |

### 5.6 SAFETY_OFFICER

| Aspect | Definition |
|---|---|
| **Purpose** | Detect, adjudicate, and monitor safety signals across trials |
| **Dashboard** | `/safety` — safety monitoring (§23.6) |
| **Responsibilities** | Review reported adverse events, confirm seriousness and causality grading, escalate serious events, monitor safety trends across sites and trials, produce safety reports |
| **Pages** | `/safety`, `/safety/adverse-events`, `/safety/reviews`, `/safety/monitoring`, `/safety/reports` |
| **Can read** | Adverse events and safety reviews across **all trials** — safety monitoring only works if signals can be compared across trials, so this role is deliberately cross-trial. Plus: the clinical observations and medications of participants *with a reported adverse event*, since causality assessment is impossible without them; trial and site metadata |
| **Can create** | Safety reviews, safety reports |
| **Can update** | Safety reviews they authored; adverse event seriousness/causality classification |
| **Cannot access** | Participant identities; observations and medications for participants with **no** reported adverse event — the access is event-triggered, not blanket; consents; ethics deliberation; administration |
| **GIS** | Cross-trial aggregate safety view at site and institution level, k-suppressed (§11.4); no participant-level drill-down anywhere |

> **The event-triggered read is the subtle part.** A Safety Officer needs a participant's
> observation history to judge whether a drug caused an event, but needs it only for
> participants who *had* an event. The RLS policy on `observations` therefore admits this
> role conditionally, via an `EXISTS` clause against `adverse_events` (§7.5), rather than
> granting blanket clinical read.

### 5.7 REGULATORY_OFFICER

| Aspect | Definition |
|---|---|
| **Purpose** | National oversight of trial conduct, compliance, and safety |
| **Dashboard** | `/regulator` — oversight (§23.7) |
| **Scope decision** | **Read-all, writes confined to compliance and regulatory status.** Sees every trial, institution, and site nationally. Cannot enter or alter clinical data. |
| **Responsibilities** | Monitor compliance across trials, review aggregate safety, verify ethics approvals exist, produce regulatory reports, flag non-compliant trials |
| **Pages** | `/regulator`, `/regulator/trials`, `/regulator/institutions`, `/regulator/sites`, `/regulator/gis`, `/regulator/compliance`, `/regulator/safety`, `/regulator/regulatory`, `/regulator/reports` |
| **Can read** | All trials, institutions, sites, staff assignments; all compliance requirements and per-trial compliance status; ethics submission status and decisions (not deliberation content); **aggregate** safety counts and trends; regulatory documents; enrolment aggregates |
| **Can create** | Compliance requirements, regulatory findings and reports |
| **Can update** | `trial_compliance` status and evidence links; trial regulatory status flags |
| **Cannot access** | **Participant identities, individual participant records, consents, visits, observations, medications, individual adverse event narratives.** Oversight operates on aggregates and compliance artefacts, not subject data. Also excluded: clinical data entry of any kind, platform user administration |
| **GIS** | The broadest geographic view on the platform — every institution and site nationally, with aggregate enrolment, compliance, and safety indicators, k-suppressed. This is the flagship oversight view (§11.5) |

> **Why the regulator does not get individual clinical data.** A real inspection grants
> broad access under a specific legal instrument, for a defined period, with the inspection
> itself on record. Modelling that properly needs a time-boxed inspection-grant mechanism
> that this MVP does not build. Rather than approximate it with permanent blanket PHI
> access — which widens the standing exposure of every participant record on the platform
> for a capability the demo does not exercise — the MVP confines the role to aggregates and
> compliance artefacts. §31 ADR-010 records this and the shape of the future extension.

### 5.8 Role capability matrix

`F` full · `S` scoped to assignment · `A` aggregate only · `—` no access

| Resource | ADMIN | PI | COORD | STAFF | ETHICS | SAFETY | REG |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Users & roles | F | — | — | — | — | — | — |
| Institutions | F | S | S | S | S | A | F |
| Trials | F | S | S | S | S | F | F |
| Trial sites | F | S | S | S | — | F | F |
| Trial staff | F | S | S | — | — | — | F |
| Participants (pseudonymised) | — | S | S | S (site) | — | — | — |
| Participant identities | — | S* | S* | — | — | — | — |
| Consents | — | S | S | — | — | — | — |
| Visits | — | S | S | S (site) | — | — | — |
| Observations | — | S | S | S (site) | — | S** | — |
| Medications | — | S | S | S (site) | — | S** | — |
| Adverse events | — | S | S | S (site) | — | F | A |
| Safety reviews | — | S | — | — | — | F | A |
| Ethics submissions | — | S | — | — | S (inst.) | — | A |
| Ethics reviews | — | A | — | — | S (inst.) | — | A |
| Documents | — | S | S | S (site) | S (inst.) | S | S |
| Compliance | F | S | S | — | — | — | F |
| GIS aggregates | F | F | F | F | F | F | F |
| GIS clinical drill-down | — | S | S | S (site) | — | A | A |
| Audit logs | F | S | — | — | — | — | S |

\* `participant_identities` requires the separate `participant_identity:read` permission,
not granted to these roles by default (§8.12).
\*\* Safety Officer clinical read is event-triggered — only for participants with a
reported adverse event (§5.6).

---

## 6. RBAC Architecture

### 6.1 The rule that shapes this section

**No role name appears in application logic.** There is no `if user.role == "PI"` anywhere
in the codebase. Code asks about *permissions*; the mapping from role to permission lives
in data.

This matters because role checks scattered through a codebase are unauditable. Answering
"who can approve an ethics submission?" should be a database query, not a grep across
`services/`. It also means the seventh role, or a per-institution variation of an existing
one, is a data change rather than a code change.

### 6.2 The three tables

```text
roles ──< role_permissions >── permissions
              (join)
users.role_id → roles.id
```

| Table | Holds |
|---|---|
| `roles` | The seven role constants, each with a stable machine name and a display name |
| `permissions` | Every discrete capability, named `resource:action` |
| `role_permissions` | Which permissions each role holds — the only place the mapping exists |

A user has exactly one role (`users.role_id`). Multiple roles per user is a deliberate
non-goal: a person who is both a PI and an Ethics Member on the same platform is a conflict
of interest, not a feature. Scope beyond the role — *which* trials and sites — comes from
`trial_staff`, not from additional roles.

### 6.3 Permission catalogue

Permissions are `resource:action`. The full MVP set:

```text
# Trials and structure
trial:create              trial:read              trial:update            trial:archive
site:create               site:read               site:update
institution:create        institution:read        institution:update
trial_staff:create        trial_staff:read        trial_staff:delete

# Participants and consent
participant:create        participant:read        participant:update      participant:withdraw
participant_identity:read participant_identity:create
consent:create            consent:read            consent:withdraw

# Clinical data
visit:create              visit:read              visit:update
observation:create        observation:read        observation:update
medication:create         medication:read         medication:update

# Safety
adverse_event:create      adverse_event:read      adverse_event:update
adverse_event:review      safety_report:create    safety_report:read

# Ethics
ethics:submit             ethics:read             ethics:review           ethics:decide

# Compliance and regulatory
compliance:read           compliance:update       compliance:define
regulatory:report

# Documents
document:upload           document:read           document:supersede      document:archive

# Geographic
gis:read                  gis:drilldown

# Platform
user:create               user:read               user:update             user:deactivate
role:read                 role:assign
audit:read
```

`gis:read` and `gis:drilldown` are separate on purpose. Every authenticated role holds
`gis:read`, which is what makes the map global (§1.3). `gis:drilldown` gates the transition
from suppressed aggregates to per-site clinical detail, and is further narrowed by RLS to
the rows the user's scope permits (§11.3).

### 6.4 Enforcement chain

```mermaid
flowchart LR
    A["Role<br/>users.role_id"] --> B["Permissions<br/>role_permissions"]
    B --> C["API authorization<br/>require_permission dependency"]
    C --> D["Database RLS<br/>row-level policy"]
    D --> E["Rows returned"]

    C -.->|"403 Forbidden<br/>lacks permission"| X["Denied"]
    D -.->|"0 rows<br/>outside scope"| Y["Empty result"]

    style D fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

The two layers answer different questions and fail differently:

| Layer | Question | Failure mode | Response |
|---|---|---|---|
| **RBAC** | *May this user perform this action at all?* | User lacks the permission | `403 Forbidden` |
| **RLS** | *Which rows may this user touch?* | Row is outside the user's scope | Row simply is not returned |

The `403` and the empty result are deliberately different. Telling a Research Staff member
"you lack `participant:read`" is correct and useful. Telling them "participant 4f2a exists
but is not yours" confirms the existence of a record in another site — so the RLS layer
returns nothing at all rather than an error. Absence, not denial.

### 6.5 How permissions are declared

Permission checks are FastAPI dependencies attached at router or route level, never
statements inside handler bodies:

```python
# Router-level: applies to every route in the module. Cannot be forgotten per-route.
router = APIRouter(
    prefix="/api/v1/participants",
    dependencies=[Depends(require_permission("participant:read"))],
)

# Route-level: narrows further for mutating operations.
@router.post("", dependencies=[Depends(require_permission("participant:create"))])
async def enroll_participant(...): ...
```

Declaring the read permission at router level means a new endpoint added to the module
inherits it by default. The failure mode of forgetting a decorator becomes *too strict*
rather than *wide open*.

`require_permission` resolves the user's permission set once per request, from a Redis
lookaside cache keyed `perm:role:{role_id}:v1` that falls through to `role_permissions`
on a miss (§12.3). This cache is safe under ADR-002 because a miss is fully recoverable
from PostgreSQL and a stale entry only ever reflects a role definition change, which is
rare, administrative, and explicitly invalidated on write (§13.4).

### 6.6 Seed data

Role-to-permission assignments ship as an Alembic data migration, not as a runtime
bootstrap script. The mapping is therefore version-controlled, reviewable in a pull
request, and identical in every environment. Changing who can approve an ethics submission
is a reviewed migration with a diff, which is exactly the paper trail a clinical platform
should produce.

---

## 7. PostgreSQL Row-Level Security

### 7.1 RBAC and RLS are not the same control

| | RBAC | RLS |
|---|---|---|
| Question | "What actions can this user perform?" | "Which rows can this user access?" |
| Lives in | Application layer (FastAPI dependencies) | Database layer (PostgreSQL policies) |
| Granularity | Per resource type and verb | Per individual row |
| Bypassed by | An endpoint missing its check | Nothing reachable from the application role |
| Example | "Research Staff may read participants" | "…but only those at AIIMS Delhi, in Trial CT-2026-014" |

Both are necessary. RBAC alone permits the following attack, which is the single most
important scenario in this document:

> A Research Staff member at the Delhi site is authorised to read participants, so
> `require_permission("participant:read")` passes. They call
> `GET /api/v1/participants/{id}` with a participant ID belonging to the Mumbai site —
> obtained by incrementing an ID, reading a shared spreadsheet, or guessing.
>
> **With RBAC only:** the permission check passes, the service queries by primary key, and
> the Mumbai participant's clinical record is returned. The endpoint is not "insecure" in
> any obvious way; it simply forgot one `WHERE` clause.
>
> **With RLS:** the query executes with `app.current_user_id` set, the policy on
> `participants` evaluates the user's `trial_staff` scope, the Mumbai row fails the
> policy predicate, and the query returns zero rows. The API returns `404 Not Found`.
> The forgotten `WHERE` clause is contained.

This is why §2.3 places the database at the bottom of the security stack. The application
layer will eventually have a bug of exactly this shape; RLS decides whether that bug is an
inconvenience or a breach.

### 7.2 Worked example: Research Staff scope

Take a concrete user:

```text
User:        priya.n@aiims.edu
Role:        RESEARCH_STAFF
Institution: AIIMS Delhi
Assignment:  trial_staff → (trial: CT-2026-014, site: AIIMS Delhi, role: STAFF)
```

The authorised scope is exactly one (trial, site) pair. The policy on `participants` must
admit a row only if that row's trial and site match an active assignment held by this user.

```sql
CREATE POLICY participants_site_scope ON participants
FOR SELECT
USING (
    -- Site-scoped clinical roles: must match an active assignment on BOTH axes
    (
        app.current_role_name() = 'RESEARCH_STAFF'
        AND EXISTS (
            SELECT 1 FROM app.active_assignments() a
            WHERE a.trial_id = participants.trial_id
              AND a.site_id  = participants.trial_site_id
        )
    )
    -- Trial-scoped roles: PI and Coordinator span all sites of their trials
    OR (
        app.current_role_name() IN ('PRINCIPAL_INVESTIGATOR', 'TRIAL_COORDINATOR')
        AND EXISTS (
            SELECT 1 FROM app.active_assignments() a
            WHERE a.trial_id = participants.trial_id
        )
    )
);
```

Changing one ID in the request changes nothing about the outcome. The predicate is
evaluated per row against the *session's* identity, which the request body cannot influence.

### 7.3 Passing identity to PostgreSQL safely

Identity reaches the database through a session variable set at the start of every
transaction:

```sql
BEGIN;
SET LOCAL app.current_user_id = '8f14e45f-ceea-4b58-9c3a-0d1e2f3a4b5c';
-- ... all queries for this request ...
COMMIT;
```

Three properties make this safe, and each closes a specific failure mode:

**`SET LOCAL`, not `SET`.** `SET LOCAL` is scoped to the transaction and unwinds
automatically at `COMMIT` or `ROLLBACK`. A plain `SET` persists for the life of the
*connection* — and because SQLAlchemy pools and reuses connections, a plain `SET` means the
next request to borrow that connection inherits the previous user's identity. That is a
silent, intermittent cross-user data leak that passes every test written against a fresh
connection. **`SET LOCAL` is not a style preference; it is the control that makes RLS
correct under connection pooling.**

**Only the user ID is passed.** No role name, no trial list, no site list. Everything else
is derived inside the database from `users` and `trial_staff`. The client cannot influence
scope because the client never transmits scope.

**The value is a bound parameter, never string-interpolated.** The user ID comes from a
verified JWT, but it is still passed via `set_config('app.current_user_id', :uid, true)`
rather than an f-string, so a malformed value cannot alter the statement.

Implementation is a single FastAPI dependency that every authenticated router depends on:

```python
async def rls_session(user: CurrentUser, db: AsyncSession) -> AsyncSession:
    """Bind the verified identity to the transaction. Unwinds on commit/rollback."""
    await db.execute(
        text("SELECT set_config('app.current_user_id', :uid, true)"),
        {"uid": str(user.id)},
    )
    return db
```

Because it is a dependency rather than a call inside handlers, an endpoint cannot execute
without it.

### 7.4 Helper functions, and the recursion trap

Policies need to know the current user's role and assignments. Naively, the policy on
`participants` subqueries `trial_staff` — but `trial_staff` has its own RLS policy, which
itself needs to know the user's assignments, which queries `trial_staff`. **This recurses
and the query fails.**

The fix is to resolve identity through `SECURITY DEFINER` functions owned by `ctms_owner`.
They execute with the owner's privileges, so they read `users` and `trial_staff` without
triggering RLS, and they return only derived facts about the *current* session — never
arbitrary rows.

```sql
CREATE SCHEMA app;

-- Current session identity, or NULL if unset (fail-closed).
CREATE FUNCTION app.current_user_id() RETURNS uuid
LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.current_user_id', true), '')::uuid;
$$;

-- The session user's role name.
CREATE FUNCTION app.current_role_name() RETURNS text
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
    SELECT r.name
    FROM users u JOIN roles r ON r.id = u.role_id
    WHERE u.id = app.current_user_id() AND u.status = 'ACTIVE';
$$;

-- The session user's active trial/site assignments. Breaks the recursion.
CREATE FUNCTION app.active_assignments()
RETURNS TABLE (trial_id uuid, site_id uuid, staff_role text)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
    SELECT ts.trial_id, ts.trial_site_id, ts.staff_role
    FROM trial_staff ts
    WHERE ts.user_id = app.current_user_id()
      AND ts.status = 'ACTIVE'
      AND (ts.end_date IS NULL OR ts.end_date >= CURRENT_DATE);
$$;

-- The session user's institution, for IEC scoping.
CREATE FUNCTION app.current_institution_id() RETURNS uuid
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS $$
    SELECT institution_id FROM users
    WHERE id = app.current_user_id() AND status = 'ACTIVE';
$$;
```

Two safety details, both load-bearing:

- **`SET search_path` is pinned on every `SECURITY DEFINER` function.** Without it, a user
  who can create objects could shadow `users` with their own table and have the elevated
  function read it instead. This is the standard `SECURITY DEFINER` privilege escalation,
  and the pinned `search_path` closes it.
- **`STABLE`, not `VOLATILE`.** The planner may then evaluate the function once per
  statement rather than once per row, which is the difference between a policy that costs
  microseconds and one that makes a 10,000-row scan unusable.

**Fail-closed by construction.** If `app.current_user_id` is never set, `current_setting(...,
true)` returns `NULL`, every helper returns `NULL`, every policy predicate evaluates to
`NULL` — which is not `TRUE` — and every query returns zero rows. A code path that forgets
the RLS dependency returns nothing rather than everything. **The failure mode of this design
is an empty screen, never a data leak.**

### 7.5 Policy patterns by table class

Tables fall into five policy classes. Each clinical table uses exactly one.

| Class | Tables | Predicate shape |
|---|---|---|
| **Reference** | `roles`, `permissions`, `role_permissions`, `compliance_requirements` | Readable by all authenticated sessions; writable only by `ADMIN` |
| **Structural** | `institutions`, `trials`, `trial_sites`, `trial_staff` | Visible to `ADMIN`, `SAFETY_OFFICER`, `REGULATORY_OFFICER` in full; to others via `app.active_assignments()` |
| **Site-scoped clinical** | `participants`, `visits`, `observations`, `medications`, `consents`, `participant_identities` | `RESEARCH_STAFF` matches trial **and** site; `PI`/`COORDINATOR` match trial. `ADMIN` and `REGULATORY_OFFICER` excluded entirely (§5.1, §5.7) |
| **Safety** | `adverse_events`, `safety_reviews` | Assignment scope **plus** unconditional `SAFETY_OFFICER` access, since cross-trial comparison is the role's purpose |
| **Institution-scoped** | `ethics_submissions`, `ethics_reviews` | `ETHICS_MEMBER` matches `app.current_institution_id()`; submitting PI sees their own trial's submissions |

The Safety Officer's event-triggered clinical read (§5.6) is expressed as an extra `OR`
branch on the site-scoped clinical policies:

```sql
-- Additional branch on the observations SELECT policy.
OR (
    app.current_role_name() = 'SAFETY_OFFICER'
    AND EXISTS (
        SELECT 1 FROM adverse_events ae
        JOIN visits v ON v.id = observations.visit_id
        WHERE ae.participant_id = v.participant_id
    )
)
```

The Safety Officer reaches a participant's observations only once that participant has an
adverse event on record. Before the event, the rows are invisible to them; after it, they
are available for causality assessment. Access follows clinical justification.

### 7.6 The optimisation that was rejected

An obvious speed-up: compute each user's authorised trial and site IDs at login, cache them
in Redis, and inject them as a `uuid[]` session variable so policies become
`trial_id = ANY(current_setting('app.trial_scope')::uuid[])` with no subquery at all.

**Rejected, for three reasons:**

1. **It contradicts ADR-002.** Redis would hold data that determines an authorization
   outcome. A Redis eviction, restart, or network partition would then change who can read
   what — making the cache authoritative in exactly the way §2.2 forbids.
2. **Stale scope is a security defect, not a stale read.** Removing a staff member from a
   trial would not take effect until their cached scope expired. The window between
   revocation and expiry is a period of unauthorised access to clinical data.
3. **It does not scale to the broad roles.** A Regulatory Officer's scope is every trial
   nationally. Serialising that array into a session variable on every request is slower
   than the subquery it replaces.

The performance concern behind it is nonetheless real and is addressed properly in §28.2 —
with indexes on `trial_staff(user_id, status)` and `participants(trial_id, trial_site_id)`,
which let the planner hash the `EXISTS` subquery once per statement. If profiling later
shows this insufficient, the correct next step is a materialised scope table in PostgreSQL,
maintained transactionally — not a cache. Recorded as ADR-003.

### 7.7 Database roles

RLS only binds a role that is neither superuser nor table owner. Three roles, three jobs:

| Role | Privileges | Used by |
|---|---|---|
| `ctms_owner` | Owns schema and all objects; runs migrations; owns `SECURITY DEFINER` helpers | Alembic only. Never the running application |
| `ctms_app` | `SELECT`/`INSERT`/`UPDATE` per table grants. **No `BYPASSRLS`. Not the owner** | Every HTTP request |
| `ctms_worker` | `BYPASSRLS` | Celery jobs computing platform-wide aggregates, and the admin user-management path only |

Every table additionally carries:

```sql
ALTER TABLE participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE participants FORCE ROW LEVEL SECURITY;
```

`FORCE` makes policies apply to the table owner too. Without it, any code that connected as
`ctms_owner` — a misconfigured environment variable is enough — would silently see
everything. `FORCE` removes that failure mode.

**On `ctms_worker` and `BYPASSRLS`.** Some work legitimately crosses every boundary:
computing national GIS aggregates, sweeping for overdue visits platform-wide, and
administering users. The wrong fix is to add `OR app.current_role_name() = 'SYSTEM_ADMIN'`
escape branches to policies until background jobs work — that is how RLS designs erode into
decoration. Instead those jobs run under a role that bypasses RLS explicitly, from a small
and enumerable set of code paths in `workers/` and `services/admin/`. The bypass is visible
in one place rather than diffused across thirty policies.

Constraints on `ctms_worker`, enforced in code review:

- Never used by any request-handling code path
- Never returns participant-level data to an HTTP response — only aggregates
- Every task using it is listed in `backend/app/workers/README.md` with its justification

### 7.8 Audit table hardening

`audit_logs` is append-only, enforced structurally rather than by permission flag (§19.4):

```sql
REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM ctms_app, ctms_worker;
GRANT  INSERT, SELECT              ON audit_logs TO   ctms_app;

CREATE FUNCTION audit_immutable() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only (attempted %)', TG_OP;
END;
$$;

CREATE TRIGGER audit_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION audit_immutable();
```

A revoked grant plus a trigger is a guarantee. An application-level "admins cannot edit
audit logs" rule is only a promise, and it is the promise an attacker who reaches the
application breaks first.

---

## 8. Complete Database Schema

### 8.1 Conventions

These apply to every table and are not repeated per entity.

| Convention | Rule |
|---|---|
| Primary keys | `id uuid PRIMARY KEY DEFAULT gen_random_uuid()` — non-guessable, safe to expose in URLs, and no cross-table collision when merging exports |
| Timestamps | `timestamptz` always, never `timestamp`. Every table has `created_at timestamptz NOT NULL DEFAULT now()`; mutable tables add `updated_at` maintained by trigger |
| Authorship | Mutable clinical tables carry `created_by uuid REFERENCES users(id)` and `updated_by uuid REFERENCES users(id)` |
| Concurrency | Mutable clinical tables carry `version integer NOT NULL DEFAULT 1` for optimistic locking (§14.4) |
| Enumerations | PostgreSQL `CHECK` constraints over `text`, not `ENUM` types — adding a value is a one-line migration rather than a type alteration |
| Deletion | No hard deletes on clinical tables. Lifecycle is a `status` column (§20) |
| Soft-delete visibility | Status filtering lives in queries, not RLS. RLS answers *authorisation*; status answers *relevance*. Conflating them makes archived-record access impossible to reason about |
| Naming | Tables plural snake_case; foreign keys `<singular>_id`; indexes `ix_<table>_<cols>`; constraints `ck_/uq_/fk_<table>_<detail>` |
| RLS | Every table: `ENABLE` + `FORCE ROW LEVEL SECURITY` (§7.7) |

**Table count: 23.** The brief specified 21. Two additions are argued at §8.6 and §8.12;
§8.25 records what was deliberately *not* added.

---

### 8.2 `users`

**Purpose.** Authentication identity and role assignment. One row per human.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `email` | `citext` | `UNIQUE NOT NULL` — `citext` so `A@x.in` and `a@x.in` cannot both register |
| `password_hash` | `text NOT NULL` | Argon2id encoded string. Never the raw password (§18.4) |
| `full_name` | `text NOT NULL` | |
| `role_id` | `uuid NOT NULL` | → `roles(id)` `ON DELETE RESTRICT` |
| `institution_id` | `uuid` | → `institutions(id)`. Nullable: `SYSTEM_ADMIN` and `REGULATORY_OFFICER` are not institution-bound. **Required for `ETHICS_MEMBER`** — it is the IEC scope (§5.5) |
| `status` | `text NOT NULL DEFAULT 'ACTIVE'` | `CHECK IN ('ACTIVE','INACTIVE','LOCKED')` |
| `mfa_secret` | `text` | Encrypted at rest; `NULL` until MFA enrolled (§18.7) |
| `mfa_enabled` | `boolean NOT NULL DEFAULT false` | |
| `failed_login_count` | `integer NOT NULL DEFAULT 0` | Drives lockout (§18.10) |
| `last_login_at` | `timestamptz` | |
| `password_changed_at` | `timestamptz NOT NULL DEFAULT now()` | |

**Constraints.** `ck_users_ethics_needs_institution`:
`CHECK (status <> 'ACTIVE' OR role_id <> <ETHICS_MEMBER> OR institution_id IS NOT NULL)` —
an active ethics member without an institution has no computable review scope, so the
database refuses to store one.

**Indexes.** `uq_users_email` on `(email)`; `ix_users_institution_role` on
`(institution_id, role_id)` for admin listings.

**RLS.** Users read their own row unconditionally. `SYSTEM_ADMIN` reads and writes all.
Other roles read the `id`, `full_name`, and `role_id` of users sharing a trial assignment —
needed to render "recorded by" attribution — via a column-restricted view rather than the
base table, so `password_hash` and `mfa_secret` are unreachable by construction.

---

### 8.3 `roles`

**Purpose.** The seven role constants. Reference data.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `name` | `text` | `UNIQUE NOT NULL`, `CHECK` in the seven constants of §1.2 |
| `display_name` | `text NOT NULL` | UI label, e.g. "Principal Investigator" |
| `description` | `text` | |

**Indexes.** `uq_roles_name`. **RLS.** Readable by all authenticated sessions; writable only
by `SYSTEM_ADMIN`. Rows are seeded by migration (§6.6) and never created at runtime.

---

### 8.4 `permissions`

**Purpose.** The capability catalogue of §6.3. Reference data.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `name` | `text` | `UNIQUE NOT NULL`, format `resource:action` |
| `resource` | `text NOT NULL` | Denormalised from `name` for grouped admin UI |
| `action` | `text NOT NULL` | |
| `description` | `text NOT NULL` | Shown in the admin permission matrix |

**Constraints.** `ck_permissions_name_format`: `CHECK (name = resource || ':' || action)` —
keeps the denormalised parts honest.

**Indexes.** `uq_permissions_name`; `ix_permissions_resource`. **RLS.** As `roles`.

---

### 8.5 `role_permissions`

**Purpose.** The role→permission mapping. The single place the answer to "who can do X"
exists (§6.2).

| Column | Type | Notes |
|---|---|---|
| `role_id` | `uuid NOT NULL` | → `roles(id)` `ON DELETE CASCADE` |
| `permission_id` | `uuid NOT NULL` | → `permissions(id)` `ON DELETE CASCADE` |
| `granted_at` | `timestamptz NOT NULL DEFAULT now()` | |

**Primary key.** Composite `(role_id, permission_id)` — a grant is either present or absent;
a surrogate key would permit duplicates.

**Indexes.** PK covers role lookup; `ix_role_permissions_permission` on `(permission_id)`
answers "which roles hold this permission" for the admin audit view.

**RLS.** Readable by all authenticated sessions (the API needs it to build permission sets);
writable only by `SYSTEM_ADMIN`. Every write emits a `CHANGE_ROLE` audit event (§19.2).

---

### 8.6 `sessions` — *addition beyond the brief*

**Purpose.** Server-side refresh token state, enabling revocation.

> **Why this table exists.** §18 requires logout, token revocation, session management, and
> refresh token reuse detection. None of these are possible with self-contained JWTs — a
> signed token is valid until it expires, and a server with no record of it cannot revoke
> it. The state has to live somewhere.
>
> **Why not Redis.** Revocation is a security decision, and §2.2 forbids Redis holding
> anything authoritative. Redis as a *denylist* fails open — a restart would resurrect
> revoked tokens. Redis as an *allowlist* fails closed, which is safe, but makes "Redis
> restarted" mean "every user logged out" and puts an authorization decision in a cache.
> PostgreSQL is authoritative; Redis holds a lookaside copy for the per-request validity
> check, and a miss falls through to this table (§12.3).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | Also the refresh token's `jti` |
| `user_id` | `uuid NOT NULL` | → `users(id)` `ON DELETE CASCADE` |
| `family_id` | `uuid NOT NULL` | Shared by every token in a rotation chain. Reuse detection revokes the whole family (§18.6) |
| `token_hash` | `text NOT NULL` | SHA-256 of the opaque token. **The token itself is never stored** — a database leak must not yield usable tokens |
| `issued_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `expires_at` | `timestamptz NOT NULL` | |
| `revoked_at` | `timestamptz` | `NULL` while valid |
| `revoked_reason` | `text` | `CHECK IN ('LOGOUT','ROTATED','REUSE_DETECTED','ADMIN_REVOKE','PASSWORD_CHANGE')` |
| `ip_address` | `inet` | |
| `user_agent` | `text` | |

**Indexes.** `uq_sessions_token_hash` on `(token_hash)`; `ix_sessions_user_active` on
`(user_id)` `WHERE revoked_at IS NULL`; `ix_sessions_family` on `(family_id)` for
family-wide revocation; `ix_sessions_expires` on `(expires_at)` for the cleanup job.

**RLS.** Users see only their own sessions — which powers a "signed in on these devices"
view. `SYSTEM_ADMIN` may revoke any session but cannot read `token_hash` (column grant
withheld).

---

### 8.7 `institutions`

**Purpose.** Hospitals, medical colleges, and research centres. The organisational unit
that anchors both ethics scope (§5.5) and the geographic layer (§10).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `name` | `text NOT NULL` | |
| `registration_number` | `text` | `UNIQUE` — CDSCO / national registry identifier |
| `institution_type` | `text NOT NULL` | `CHECK IN ('GOVERNMENT_HOSPITAL','PRIVATE_HOSPITAL','MEDICAL_COLLEGE','RESEARCH_CENTRE','CRO')` |
| `address_line` | `text` | |
| `city` | `text NOT NULL` | |
| `state` | `text NOT NULL` | Drives state-level GIS aggregation (§10.4) |
| `country` | `text NOT NULL DEFAULT 'India'` | |
| `postal_code` | `text` | |
| `latitude` | `numeric(9,6)` | Human-readable, admin-editable |
| `longitude` | `numeric(9,6)` | |
| `location` | `geography(Point,4326)` | **Generated** from lat/long (§10.2) — cannot drift |
| `has_ethics_committee` | `boolean NOT NULL DEFAULT false` | Gates whether ethics submissions route here |
| `status` | `text NOT NULL DEFAULT 'ACTIVE'` | `CHECK IN ('ACTIVE','INACTIVE','ARCHIVED')` |

**Constraints.** `ck_institutions_lat_range`: `CHECK (latitude BETWEEN -90 AND 90)`;
`ck_institutions_lon_range`: `CHECK (longitude BETWEEN -180 AND 180)`;
`ck_institutions_coords_paired`:
`CHECK ((latitude IS NULL) = (longitude IS NULL))` — half a coordinate is worse than none,
because it silently plots on the null island.

**Indexes.** `ix_institutions_location` **GIST** on `(location)` — the index that makes
viewport queries fast (§10.3); `ix_institutions_state_city` on `(state, city)`;
`uq_institutions_registration` on `(registration_number)`.

**RLS.** Readable by every authenticated session — institution names and coordinates are
public information and the GIS layer needs them globally (§11.2). Writable only by
`SYSTEM_ADMIN`.

---

### 8.8 `trials`

**Purpose.** The clinical trial itself. Root of nearly every scope decision in §7.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `protocol_number` | `text` | `UNIQUE NOT NULL`, e.g. `CT-2026-014` |
| `ctri_number` | `text` | `UNIQUE`, Clinical Trials Registry–India identifier. Nullable until registered |
| `title` | `text NOT NULL` | |
| `short_title` | `text` | For dashboard cards and map popups |
| `sponsor_institution_id` | `uuid NOT NULL` | → `institutions(id)` `ON DELETE RESTRICT` |
| `phase` | `text NOT NULL` | `CHECK IN ('I','II','III','IV','OBSERVATIONAL')` |
| `therapeutic_area` | `text` | |
| `status` | `text NOT NULL DEFAULT 'DRAFT'` | `CHECK IN ('DRAFT','PENDING_ETHICS','APPROVED','ACTIVE','SUSPENDED','COMPLETED','TERMINATED','ARCHIVED')` (§20.2) |
| `target_enrollment` | `integer` | `CHECK (target_enrollment > 0)` |
| `current_enrollment` | `integer NOT NULL DEFAULT 0` | Maintained transactionally (§14.6), never by cache |
| `planned_start_date` | `date` | |
| `actual_start_date` | `date` | |
| `planned_end_date` | `date` | |
| `actual_end_date` | `date` | |
| `regulatory_status` | `text NOT NULL DEFAULT 'NOT_SUBMITTED'` | `CHECK IN ('NOT_SUBMITTED','SUBMITTED','APPROVED','QUERY_RAISED','REJECTED')` — the one field a `REGULATORY_OFFICER` may write (§5.7) |
| `version` | `integer NOT NULL DEFAULT 1` | Optimistic lock |

**Constraints.** `ck_trials_enrollment_bounds`:
`CHECK (current_enrollment >= 0 AND (target_enrollment IS NULL OR current_enrollment <= target_enrollment))`
— over-enrolment past the approved target is a protocol deviation the database refuses.
`ck_trials_date_order`: `CHECK (planned_end_date IS NULL OR planned_start_date IS NULL OR planned_end_date >= planned_start_date)`.

**Indexes.** `uq_trials_protocol_number`; `uq_trials_ctri_number`;
`ix_trials_status` on `(status)` `WHERE status IN ('ACTIVE','APPROVED')` — partial, because
dashboards almost always want live trials only; `ix_trials_sponsor` on
`(sponsor_institution_id)`.

**RLS.** `SYSTEM_ADMIN`, `SAFETY_OFFICER`, `REGULATORY_OFFICER` read all. `PI`,
`COORDINATOR`, `RESEARCH_STAFF` read via `app.active_assignments()`. `ETHICS_MEMBER` reads
trials with a submission to their institution's committee.

---

### 8.9 `trial_sites`

**Purpose.** A trial running at a specific institution. The junction that makes a trial
multi-site and gives `RESEARCH_STAFF` its narrow scope (§5.4).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `trial_id` | `uuid NOT NULL` | → `trials(id)` `ON DELETE RESTRICT` |
| `institution_id` | `uuid NOT NULL` | → `institutions(id)` `ON DELETE RESTRICT` |
| `site_code` | `text NOT NULL` | e.g. `DEL-01`, unique within the trial |
| `status` | `text NOT NULL DEFAULT 'PLANNED'` | `CHECK IN ('PLANNED','ACTIVATED','ENROLLING','CLOSED_TO_ENROLLMENT','COMPLETED','SUSPENDED')` |
| `activation_date` | `date` | |
| `target_enrollment` | `integer` | Site-level target |
| `current_enrollment` | `integer NOT NULL DEFAULT 0` | Maintained transactionally (§14.6) |
| `latitude` / `longitude` | `numeric(9,6)` | **Optional override.** Defaults to the institution's coordinates; set only where a site sits at a distinct campus |
| `location` | `geography(Point,4326)` | Generated; falls back to institution location (§10.2) |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_trial_sites_trial_institution` on `(trial_id, institution_id)` — one
site row per institution per trial. `uq_trial_sites_trial_code` on `(trial_id, site_code)`.
`ck_trial_sites_enrollment` mirrors the trial-level bound.

**Indexes.** Both unique constraints; `ix_trial_sites_location` **GIST**;
`ix_trial_sites_institution` on `(institution_id)`; `ix_trial_sites_status` on
`(trial_id, status)`.

**RLS.** Same shape as `trials`. This table is the join target for every site-scoped
clinical policy, so `ix_trial_sites_trial_institution` is on the hot path of RLS evaluation.

---

### 8.10 `trial_staff`

**Purpose.** Who works on which trial, at which site, in what capacity. **This table is the
authorization scope of the entire platform** (§7.2) — the single input to
`app.active_assignments()`.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `trial_id` | `uuid NOT NULL` | → `trials(id)` `ON DELETE RESTRICT` |
| `trial_site_id` | `uuid` | → `trial_sites(id)`. **`NULL` means trial-wide** — a PI spans all sites; `RESEARCH_STAFF` must have it set |
| `user_id` | `uuid NOT NULL` | → `users(id)` `ON DELETE RESTRICT` |
| `staff_role` | `text NOT NULL` | `CHECK IN ('PI','SUB_INVESTIGATOR','COORDINATOR','STAFF','MONITOR')` — the *trial* function, distinct from the platform role in `users.role_id` |
| `start_date` | `date NOT NULL DEFAULT CURRENT_DATE` | |
| `end_date` | `date` | `NULL` = open-ended. A past date ends the assignment and, by §7.4, immediately removes access |
| `status` | `text NOT NULL DEFAULT 'ACTIVE'` | `CHECK IN ('ACTIVE','INACTIVE')` |

**Constraints.** `uq_trial_staff_assignment` on
`(trial_id, COALESCE(trial_site_id,'00000000-0000-0000-0000-000000000000'::uuid), user_id)` —
prevents duplicate grants, with the `COALESCE` making `NULL` site comparable.
`ck_trial_staff_date_order`: `CHECK (end_date IS NULL OR end_date >= start_date)`.
`ck_trial_staff_site_required`:
`CHECK (staff_role <> 'STAFF' OR trial_site_id IS NOT NULL)` — site-level staff without a
site would silently gain trial-wide scope, so the database prevents it.

**Indexes.** `ix_trial_staff_user_active` on `(user_id, status)`
`INCLUDE (trial_id, trial_site_id, staff_role)` **`WHERE status = 'ACTIVE'`** — this is the
most performance-critical index on the platform: `app.active_assignments()` runs on every
policy evaluation of every query, and an index-only scan here keeps RLS cheap (§28.2).
Also `ix_trial_staff_trial` on `(trial_id)`.

**RLS.** Users always read their own assignments — otherwise the helper function has nothing
to resolve. `PI` reads and writes assignments within their trials; `SYSTEM_ADMIN` all.
Every write emits an audit event.

---

### 8.11 `participants`

**Purpose.** An enrolled trial subject, **pseudonymised**. Holds clinical linkage only —
no identifying information (ADR-011).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `trial_id` | `uuid NOT NULL` | → `trials(id)` `ON DELETE RESTRICT` |
| `trial_site_id` | `uuid NOT NULL` | → `trial_sites(id)` `ON DELETE RESTRICT`. Not nullable: every participant belongs to exactly one site, and RLS depends on it |
| `subject_code` | `text NOT NULL` | e.g. `CT-2026-014-DEL-0042`. The identifier shown everywhere in the UI |
| `screening_number` | `text` | Pre-enrolment identifier |
| `enrollment_date` | `date NOT NULL` | |
| `randomization_arm` | `text` | e.g. `TREATMENT`, `CONTROL`. Nullable for open-label |
| `status` | `text NOT NULL DEFAULT 'SCREENING'` | `CHECK IN ('SCREENING','ENROLLED','ACTIVE','COMPLETED','WITHDRAWN','LOST_TO_FOLLOWUP','SCREEN_FAILED')` (§20.3) |
| `withdrawal_date` | `date` | |
| `withdrawal_reason` | `text` | |
| `date_of_birth_year` | `integer` | **Year only.** `CHECK (BETWEEN 1900 AND 2100)`. Age stratification without a re-identifying full date of birth |
| `sex` | `text` | `CHECK IN ('MALE','FEMALE','OTHER','UNDISCLOSED')` |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_participants_trial_subject_code` on `(trial_id, subject_code)` — this
unique constraint doubles as the **idempotency key** for enrolment (§14.5).
`ck_participants_withdrawal_consistency`:
`CHECK (status <> 'WITHDRAWN' OR withdrawal_date IS NOT NULL)`.
`ck_participants_site_belongs_to_trial` — enforced by a composite FK
`(trial_id, trial_site_id)` → `trial_sites(trial_id, id)`, which makes it structurally
impossible to attach a participant to a site belonging to a different trial. A plain pair of
independent FKs would permit exactly that, and it is the kind of inconsistency that only
surfaces months later in a mis-scoped RLS result.

**Indexes.** `uq_participants_trial_subject_code`;
`ix_participants_site_status` on `(trial_site_id, status)` — the Coordinator and Staff
participant lists; `ix_participants_trial` on `(trial_id)`;
`ix_participants_enrollment_date` on `(trial_id, enrollment_date)` for enrolment curves.

**RLS.** Site-scoped clinical class (§7.5). `RESEARCH_STAFF` matches trial **and** site;
`PI`/`COORDINATOR` match trial. `SYSTEM_ADMIN` and `REGULATORY_OFFICER` are excluded
entirely — they get zero rows from this table by design (§5.1, §5.7).

---

### 8.12 `participant_identities` — *addition beyond the brief*

**Purpose.** The identifying information for a participant, isolated from all clinical data.

> **Why a separate table.** Splitting identity from clinical linkage is how real CTMS
> systems are built, and it changes the properties of the whole system rather than merely
> tidying it. Every analytics query, every GIS aggregate, every dashboard, every export, and
> every Celery job reads `participants` and never touches this table — so the privacy
> guarantee of §11 stops being a promise about careful query writing and becomes a fact
> about which tables a code path opens. A defect in an analytics query cannot leak a name it
> never selected. The re-identification key is one table, with one policy, one permission,
> and one audit event, which is a boundary a reviewer can actually verify.

| Column | Type | Notes |
|---|---|---|
| `participant_id` | `uuid` PK | → `participants(id)` `ON DELETE RESTRICT`. PK **is** the FK — strictly one identity row per participant |
| `full_name` | `text NOT NULL` | |
| `date_of_birth` | `date` | Full date lives only here |
| `phone` | `text` | |
| `email` | `citext` | |
| `address_line` | `text` | |
| `city` / `state` / `postal_code` | `text` | |
| `national_id_hash` | `text` | **Hash only**, never the raw identifier. Supports duplicate-enrolment detection without storing the number |
| `emergency_contact_name` | `text` | |
| `emergency_contact_phone` | `text` | |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Deliberately absent.** No geographic point column. Participant home addresses must never be
mappable (§11.2), and the safest way to guarantee that is to give the GIS layer nothing to
plot.

**Indexes.** PK only, plus `ix_participant_identities_national_id_hash` for duplicate
detection. **No index on `full_name`** — free-text search over participant names is not a
feature, and an index would invite one.

**RLS.** The strictest policy on the platform: the session must (a) hold
`participant_identity:read`, and (b) satisfy the same site scope as the underlying
`participants` row. The permission is not granted to any role by default; a `PI` or
`COORDINATOR` receives it only where the institution's SOP requires it. Every `SELECT`
against this table emits an audit event — the only table where reads, not just writes, are
audited (§19.3).

---

### 8.13 `consents`

**Purpose.** Informed consent records. Regulatory bedrock — a participant with clinical data
and no valid consent is a serious compliance failure.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `participant_id` | `uuid NOT NULL` | → `participants(id)` `ON DELETE RESTRICT` |
| `consent_document_id` | `uuid` | → `documents(id)` — the exact consent form version signed (§17.3) |
| `consent_version` | `text NOT NULL` | e.g. `ICF v3.0` |
| `consent_type` | `text NOT NULL DEFAULT 'INITIAL'` | `CHECK IN ('INITIAL','RE_CONSENT','AMENDMENT')` |
| `consented_at` | `timestamptz NOT NULL` | |
| `consent_method` | `text NOT NULL` | `CHECK IN ('WRITTEN','ELECTRONIC','WITNESSED_VERBAL')` |
| `witness_name` | `text` | |
| `obtained_by` | `uuid NOT NULL` | → `users(id)` — who took consent |
| `status` | `text NOT NULL DEFAULT 'ACTIVE'` | `CHECK IN ('ACTIVE','SUPERSEDED','WITHDRAWN')` |
| `withdrawn_at` | `timestamptz` | |
| `withdrawal_reason` | `text` | |

**Constraints.** `uq_consents_one_active` — partial unique index
`(participant_id) WHERE status = 'ACTIVE'`, so a participant cannot hold two simultaneously
active consents. Re-consent supersedes the prior record in the same transaction.
`ck_consents_withdrawal_consistency`:
`CHECK (status <> 'WITHDRAWN' OR withdrawn_at IS NOT NULL)`.

**Indexes.** The partial unique index above; `ix_consents_participant` on
`(participant_id, consented_at DESC)` for consent history.

**RLS.** Site-scoped clinical. **`RESEARCH_STAFF` excluded** — consent capture is a
Coordinator responsibility (§5.3, §5.4).

---

### 8.14 `visits`

**Purpose.** A scheduled or completed protocol visit. Drives the Coordinator's and Staff's
daily work (§23.3, §23.4).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `participant_id` | `uuid NOT NULL` | → `participants(id)` `ON DELETE RESTRICT` |
| `visit_name` | `text NOT NULL` | e.g. `Screening`, `Week 4`, `End of Study` |
| `visit_number` | `integer NOT NULL` | Protocol sequence |
| `scheduled_date` | `date NOT NULL` | |
| `window_start_date` / `window_end_date` | `date` | Protocol-allowed window; a visit outside it is a deviation |
| `actual_date` | `date` | `NULL` until performed |
| `status` | `text NOT NULL DEFAULT 'SCHEDULED'` | `CHECK IN ('SCHEDULED','COMPLETED','MISSED','CANCELLED','OUT_OF_WINDOW')` |
| `performed_by` | `uuid` | → `users(id)` |
| `notes` | `text` | |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_visits_participant_number` on `(participant_id, visit_number)`.
`ck_visits_completed_has_date`:
`CHECK (status <> 'COMPLETED' OR actual_date IS NOT NULL)`.
`ck_visits_window_order`:
`CHECK (window_end_date IS NULL OR window_start_date IS NULL OR window_end_date >= window_start_date)`.

**Indexes.** `ix_visits_scheduled` on `(scheduled_date, status)`
`WHERE status = 'SCHEDULED'` — partial index answering "today's visits" and "overdue
visits", the two most-run queries on the Coordinator dashboard;
`ix_visits_participant` on `(participant_id, visit_number)`.

**RLS.** Site-scoped clinical, resolved through `participants`.

---

### 8.15 `observations`

**Purpose.** A single clinical measurement or assessment recorded at a visit. The highest-row-count
table in the system.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `visit_id` | `uuid NOT NULL` | → `visits(id)` `ON DELETE RESTRICT` |
| `observation_code` | `text NOT NULL` | Protocol field code, e.g. `VITALS_SBP`, `LAB_HB` |
| `observation_name` | `text NOT NULL` | Human label |
| `category` | `text NOT NULL` | `CHECK IN ('VITAL_SIGN','LABORATORY','PHYSICAL_EXAM','QUESTIONNAIRE','IMAGING','OTHER')` |
| `value_numeric` | `numeric(12,4)` | |
| `value_text` | `text` | |
| `value_boolean` | `boolean` | |
| `unit` | `text` | e.g. `mmHg`, `g/dL` |
| `reference_range_low` / `_high` | `numeric(12,4)` | For out-of-range flagging |
| `is_abnormal` | `boolean` | Clinician judgement, not derived — a value in range may still be clinically abnormal |
| `recorded_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `status` | `text NOT NULL DEFAULT 'RECORDED'` | `CHECK IN ('RECORDED','AMENDED','QUERIED','VERIFIED')` |
| `amendment_reason` | `text` | Required when `status = 'AMENDED'` |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_observations_visit_code` on `(visit_id, observation_code)` — one value
per field per visit, and the **idempotency key** for data entry retries (§14.5).
`ck_observations_one_value`: exactly one of the three value columns is non-null:
`CHECK (num_nonnulls(value_numeric, value_text, value_boolean) = 1)`.
`ck_observations_amendment_reason`:
`CHECK (status <> 'AMENDED' OR amendment_reason IS NOT NULL)` — a clinical value cannot be
changed without a stated reason, which is a GCP requirement, not a nicety.

**Indexes.** `uq_observations_visit_code`; `ix_observations_visit` on `(visit_id)`;
`ix_observations_code_recorded` on `(observation_code, recorded_at DESC)` for trend queries.

**RLS.** Site-scoped clinical, resolved through `visits` → `participants`, **plus** the
Safety Officer's event-triggered branch (§7.5).

---

### 8.16 `medications`

**Purpose.** Concomitant and study medications recorded per participant.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `participant_id` | `uuid NOT NULL` | → `participants(id)` `ON DELETE RESTRICT` |
| `medication_name` | `text NOT NULL` | |
| `medication_type` | `text NOT NULL` | `CHECK IN ('STUDY_DRUG','CONCOMITANT','RESCUE')` |
| `dose` | `numeric(10,3)` / `dose_unit` `text` | |
| `frequency` | `text` | e.g. `BD`, `TDS` |
| `route` | `text` | `CHECK IN ('ORAL','IV','IM','SC','TOPICAL','INHALED','OTHER')` |
| `start_date` | `date NOT NULL` / `end_date` `date` | |
| `indication` | `text` | Why prescribed — needed for causality assessment |
| `is_ongoing` | `boolean NOT NULL DEFAULT true` | |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `ck_medications_date_order`:
`CHECK (end_date IS NULL OR end_date >= start_date)`.
`ck_medications_ongoing_consistency`:
`CHECK (is_ongoing = false OR end_date IS NULL)`.

**Indexes.** `ix_medications_participant` on `(participant_id, start_date DESC)`;
`ix_medications_name` on `(lower(medication_name))` for interaction lookups.

**RLS.** Site-scoped clinical, plus the Safety Officer's event-triggered branch.

---

### 8.17 `adverse_events`

**Purpose.** A reported adverse event. The most safety-critical table on the platform.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `participant_id` | `uuid NOT NULL` | → `participants(id)` `ON DELETE RESTRICT` |
| `trial_id` | `uuid NOT NULL` | **Denormalised** from the participant, so the Safety Officer's cross-trial queries and GIS aggregates need no join (§28.3). Kept correct by trigger |
| `visit_id` | `uuid` | → `visits(id)`. Nullable — events occur between visits |
| `event_term` | `text NOT NULL` | Reported term, e.g. `Nausea` |
| `meddra_code` | `text` | Coded term where available |
| `description` | `text NOT NULL` | Narrative. **Never exposed to GIS or aggregates** (§11.2) |
| `onset_date` | `date NOT NULL` / `resolution_date` `date` | |
| `severity` | `text NOT NULL` | `CHECK IN ('MILD','MODERATE','SEVERE')` |
| `seriousness` | `text NOT NULL DEFAULT 'NON_SERIOUS'` | `CHECK IN ('NON_SERIOUS','SERIOUS')` |
| `serious_criteria` | `text[]` | Which SAE criteria met — death, life-threatening, hospitalisation, disability, congenital anomaly, other |
| `causality` | `text` | `CHECK IN ('UNRELATED','UNLIKELY','POSSIBLE','PROBABLE','DEFINITE')`. Set by the Safety Officer at review, not by the reporter |
| `outcome` | `text` | `CHECK IN ('RECOVERED','RECOVERING','NOT_RECOVERED','RECOVERED_WITH_SEQUELAE','FATAL','UNKNOWN')` |
| `action_taken` | `text` | |
| `reported_by` | `uuid NOT NULL` | → `users(id)` |
| `reported_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `status` | `text NOT NULL DEFAULT 'REPORTED'` | `CHECK IN ('REPORTED','UNDER_REVIEW','REVIEWED','CLOSED')` |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `ck_adverse_events_serious_criteria`:
`CHECK (seriousness <> 'SERIOUS' OR array_length(serious_criteria,1) >= 1)` — an event
cannot be marked serious without stating why.
`ck_adverse_events_date_order`:
`CHECK (resolution_date IS NULL OR resolution_date >= onset_date)`.
`ck_adverse_events_fatal_outcome`:
`CHECK (outcome <> 'FATAL' OR seriousness = 'SERIOUS')`.

**Indexes.** `ix_adverse_events_trial_seriousness` on `(trial_id, seriousness, reported_at DESC)`
— the Safety dashboard's primary query;
`ix_adverse_events_participant` on `(participant_id)`;
`ix_adverse_events_pending` on `(status)` `WHERE status IN ('REPORTED','UNDER_REVIEW')` —
partial index for the pending-review queue;
`ix_adverse_events_onset` on `(onset_date)` for trend analysis.

**RLS.** Safety class: assignment scope **or** unconditional `SAFETY_OFFICER` access.
`REGULATORY_OFFICER` reads **aggregates only** — enforced by granting the role access to a
counting view rather than to this table.

---

### 8.18 `safety_reviews`

**Purpose.** The Safety Officer's adjudication of a reported event.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `adverse_event_id` | `uuid NOT NULL` | → `adverse_events(id)` `ON DELETE RESTRICT` |
| `reviewer_id` | `uuid NOT NULL` | → `users(id)` |
| `review_date` | `timestamptz NOT NULL DEFAULT now()` | |
| `assessed_severity` | `text NOT NULL` | May differ from the reported severity — that divergence is itself a signal |
| `assessed_causality` | `text NOT NULL` | `CHECK` as `adverse_events.causality` |
| `is_expected` | `boolean NOT NULL` | Listed in the Investigator's Brochure? Unexpected + serious + related = expedited reporting |
| `requires_expedited_reporting` | `boolean NOT NULL DEFAULT false` | |
| `reported_to_authority_at` | `timestamptz` | Regulatory submission timestamp |
| `comments` | `text` | |
| `decision` | `text NOT NULL` | `CHECK IN ('ACCEPTED','QUERY_RAISED','ESCALATED','CLOSED')` |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `ck_safety_reviews_expedited_reported`:
`CHECK (requires_expedited_reporting = false OR decision <> 'CLOSED' OR reported_to_authority_at IS NOT NULL)`
— an expedited-reportable event cannot be closed without a record of the authority
submission.

**Indexes.** `ix_safety_reviews_event` on `(adverse_event_id, review_date DESC)`;
`ix_safety_reviews_reviewer` on `(reviewer_id, review_date DESC)`;
`ix_safety_reviews_expedited` on `(requires_expedited_reporting)`
`WHERE requires_expedited_reporting = true AND reported_to_authority_at IS NULL` —
the "overdue regulatory notification" alert.

**RLS.** Safety class. The reporting `PI` reads reviews on their trials' events.

---

### 8.19 `ethics_submissions`

**Purpose.** A protocol or amendment submitted to an institution's IEC.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `trial_id` | `uuid NOT NULL` | → `trials(id)` `ON DELETE RESTRICT` |
| `institution_id` | `uuid NOT NULL` | → `institutions(id)`. **The IEC scope key** (§5.5) — this column alone determines which ethics members see the row |
| `submission_number` | `text NOT NULL` | e.g. `IEC/2026/0142` |
| `submission_type` | `text NOT NULL` | `CHECK IN ('INITIAL','AMENDMENT','CONTINUING_REVIEW','SAE_REPORT','FINAL_REPORT')` |
| `submitted_by` | `uuid NOT NULL` | → `users(id)` |
| `submitted_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `protocol_document_id` | `uuid` | → `documents(id)` — the exact protocol version submitted (§17.3) |
| `summary` | `text NOT NULL` | |
| `status` | `text NOT NULL DEFAULT 'SUBMITTED'` | `CHECK IN ('SUBMITTED','UNDER_REVIEW','APPROVED','APPROVED_WITH_CONDITIONS','REJECTED','WITHDRAWN','DEFERRED')` |
| `decision_date` | `date` | |
| `approval_valid_until` | `date` | Drives continuing-review reminders |
| `conditions` | `text` | |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_ethics_submissions_number` on `(institution_id, submission_number)`.
`ck_ethics_decision_consistency`:
`CHECK (status NOT IN ('APPROVED','APPROVED_WITH_CONDITIONS','REJECTED') OR decision_date IS NOT NULL)`.
`ck_ethics_conditions_present`:
`CHECK (status <> 'APPROVED_WITH_CONDITIONS' OR conditions IS NOT NULL)`.

**Indexes.** `ix_ethics_submissions_institution_status` on `(institution_id, status)` — the
pending-review queue, and the index RLS uses for IEC scoping;
`ix_ethics_submissions_trial` on `(trial_id, submitted_at DESC)`;
`ix_ethics_submissions_expiring` on `(approval_valid_until)`
`WHERE status IN ('APPROVED','APPROVED_WITH_CONDITIONS')`.

**RLS.** Institution-scoped: `ETHICS_MEMBER` matches `app.current_institution_id()`. The
submitting `PI` and `COORDINATOR` read their own trials' submissions.
`REGULATORY_OFFICER` reads status and decision but **not** `conditions` or linked reviews —
enforced by column grant.

---

### 8.20 `ethics_reviews`

**Purpose.** An individual committee member's review of a submission. Deliberation content —
the most access-restricted non-clinical data on the platform.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `ethics_submission_id` | `uuid NOT NULL` | → `ethics_submissions(id)` `ON DELETE RESTRICT` |
| `reviewer_id` | `uuid NOT NULL` | → `users(id)` |
| `review_date` | `timestamptz NOT NULL DEFAULT now()` | |
| `recommendation` | `text NOT NULL` | `CHECK IN ('APPROVE','APPROVE_WITH_CONDITIONS','REJECT','DEFER','REQUEST_CLARIFICATION')` |
| `comments` | `text NOT NULL` | Deliberation content |
| `conflict_of_interest_declared` | `boolean NOT NULL DEFAULT false` | |
| `is_finalized` | `boolean NOT NULL DEFAULT false` | Once true, the review is immutable |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_ethics_reviews_submission_reviewer` on
`(ethics_submission_id, reviewer_id)` — one review per member per submission.
Immutability after finalisation is enforced by a `BEFORE UPDATE` trigger that raises when
`OLD.is_finalized = true`, matching the audit table pattern of §7.8 — a finalised ethics
opinion is part of the regulatory record.

**Indexes.** The unique constraint; `ix_ethics_reviews_submission` on
`(ethics_submission_id)`; `ix_ethics_reviews_reviewer_pending` on `(reviewer_id)`
`WHERE is_finalized = false`.

**RLS.** `ETHICS_MEMBER` reads reviews on submissions to their own institution's committee.
**The submitting PI cannot read individual reviews** — only the submission's aggregate
decision. Independence of ethical review depends on it.

---

### 8.21 `documents`

**Purpose.** Metadata for every uploaded file. **File bytes live in Cloudinary; this table
gives them meaning** (§16).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `document_family_id` | `uuid NOT NULL` | Groups every version of the same logical document. Version 1 sets it to its own `id` (§17.2) |
| `trial_id` | `uuid` | → `trials(id)`. Nullable — some documents are institution-level |
| `institution_id` | `uuid` | → `institutions(id)` |
| `trial_site_id` | `uuid` | → `trial_sites(id)` |
| `document_type` | `text NOT NULL` | `CHECK IN ('PROTOCOL','CONSENT_FORM','ETHICS_APPROVAL','REGULATORY_SUBMISSION','INVESTIGATOR_BROCHURE','CV','SOURCE_DOCUMENT','SAFETY_REPORT','MONITORING_REPORT','OTHER')` |
| `title` | `text NOT NULL` | |
| `file_name` | `text NOT NULL` | Original name, sanitised |
| `mime_type` | `text NOT NULL` | From content sniffing, not the extension (§16.5) |
| `file_size_bytes` | `bigint NOT NULL` | `CHECK (> 0 AND <= 52428800)` — 50 MB |
| `checksum_sha256` | `text NOT NULL` | Integrity verification and duplicate detection |
| `cloudinary_public_id` | `text NOT NULL` | The storage handle |
| `cloudinary_resource_type` | `text NOT NULL` | `image` / `raw` / `video` |
| `cloudinary_version` | `bigint` | Cloudinary's own asset version |
| `version` | `integer NOT NULL DEFAULT 1` | **Document** version — v1, v2, v3 (§17) |
| `status` | `text NOT NULL DEFAULT 'PENDING_SCAN'` | `CHECK IN ('PENDING_SCAN','QUARANTINED','DRAFT','CURRENT','SUPERSEDED','WITHDRAWN','ARCHIVED')` |
| `superseded_by_id` | `uuid` | → `documents(id)` — forward pointer in the version chain |
| `scan_status` | `text NOT NULL DEFAULT 'PENDING'` | `CHECK IN ('PENDING','CLEAN','INFECTED','ERROR')` |
| `scanned_at` | `timestamptz` | |
| `uploaded_by` | `uuid NOT NULL` | → `users(id)` |
| `uploaded_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `effective_date` / `expiry_date` | `date` | For approvals with a validity period |

**Constraints.** `uq_documents_one_current_per_family` — partial unique index
`(document_family_id) WHERE status = 'CURRENT'`, so a family can never have two current
versions **even under a race** (§17.4). This one index is what makes versioning correct
rather than merely intended.
`uq_documents_family_version` on `(document_family_id, version)`.
`ck_documents_scope`:
`CHECK (num_nonnulls(trial_id, institution_id) >= 1)` — a document must belong to something.
`ck_documents_clean_before_current`:
`CHECK (status NOT IN ('CURRENT','DRAFT') OR scan_status = 'CLEAN')` — an unscanned or
infected file cannot become a live document.

**Indexes.** Both unique indexes above; `ix_documents_trial_type` on
`(trial_id, document_type, status)`; `ix_documents_family` on
`(document_family_id, version DESC)`; `ix_documents_pending_scan` on `(scan_status)`
`WHERE scan_status = 'PENDING'` for the worker queue;
`uq_documents_checksum_family` on `(document_family_id, checksum_sha256)` — re-uploading
identical bytes is idempotent rather than creating a spurious version.

**RLS.** Scope follows the document's own `trial_id` / `institution_id` / `trial_site_id`
through the same assignment logic as clinical tables. `ETHICS_MEMBER` reads documents
attached to submissions to their committee. `REGULATORY_OFFICER` reads
`ETHICS_APPROVAL`, `REGULATORY_SUBMISSION`, and `SAFETY_REPORT` types across all trials, and
nothing else — a type-filtered policy branch.

---

### 8.22 `compliance_requirements`

**Purpose.** The catalogue of regulatory obligations a trial must satisfy. Reference data.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `code` | `text` | `UNIQUE NOT NULL`, e.g. `CDSCO-REG-01` |
| `title` | `text NOT NULL` | |
| `description` | `text NOT NULL` | |
| `category` | `text NOT NULL` | `CHECK IN ('REGULATORY','ETHICS','SAFETY','DATA_INTEGRITY','SITE_QUALIFICATION','DOCUMENTATION')` |
| `authority` | `text` | e.g. `CDSCO`, `ICMR`, `DCGI` |
| `applies_to_phase` | `text[]` | `NULL` = all phases |
| `is_mandatory` | `boolean NOT NULL DEFAULT true` | |
| `evidence_required` | `boolean NOT NULL DEFAULT true` | Whether a linked document is required |
| `status` | `text NOT NULL DEFAULT 'ACTIVE'` | `CHECK IN ('ACTIVE','SUPERSEDED')` |

**Indexes.** `uq_compliance_requirements_code`;
`ix_compliance_requirements_category` on `(category, status)`.

**RLS.** Readable by every authenticated session. Writable by `SYSTEM_ADMIN` and
`REGULATORY_OFFICER` (`compliance:define`).

---

### 8.23 `trial_compliance`

**Purpose.** One trial's status against one requirement. The join that produces every
compliance percentage on every dashboard.

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `trial_id` | `uuid NOT NULL` | → `trials(id)` `ON DELETE RESTRICT` |
| `compliance_requirement_id` | `uuid NOT NULL` | → `compliance_requirements(id)` `ON DELETE RESTRICT` |
| `trial_site_id` | `uuid` | → `trial_sites(id)`. Nullable — some requirements are site-specific |
| `status` | `text NOT NULL DEFAULT 'PENDING'` | `CHECK IN ('PENDING','IN_PROGRESS','COMPLIANT','NON_COMPLIANT','NOT_APPLICABLE','WAIVED')` |
| `evidence_document_id` | `uuid` | → `documents(id)` |
| `due_date` | `date` | |
| `completed_date` | `date` | |
| `verified_by` | `uuid` | → `users(id)` — the regulatory officer who confirmed it |
| `verified_at` | `timestamptz` | |
| `notes` | `text` | |
| `version` | `integer NOT NULL DEFAULT 1` | |

**Constraints.** `uq_trial_compliance_scope` on
`(trial_id, compliance_requirement_id, COALESCE(trial_site_id, <nil uuid>))`.
`ck_trial_compliance_evidence`:
`CHECK (status <> 'COMPLIANT' OR evidence_document_id IS NOT NULL OR notes IS NOT NULL)` —
a compliant status needs either evidence or an explanation.
`ck_trial_compliance_completed_date`:
`CHECK (status <> 'COMPLIANT' OR completed_date IS NOT NULL)`.

**Indexes.** The unique constraint;
`ix_trial_compliance_trial_status` on `(trial_id, status)` — drives the compliance
percentage on every dashboard and the GIS compliance layer;
`ix_trial_compliance_overdue` on `(due_date)`
`WHERE status IN ('PENDING','IN_PROGRESS')`.

**RLS.** `PI` and `COORDINATOR` read and write within their trials; `REGULATORY_OFFICER`
reads and writes all; `SYSTEM_ADMIN` reads all.

---

### 8.24 `audit_logs`

**Purpose.** Append-only record of every consequential action (§19).

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `user_id` | `uuid` | → `users(id)` `ON DELETE SET NULL`. **Nullable and `SET NULL`, not `CASCADE`** — deleting a user must never erase the record of what they did |
| `action` | `text NOT NULL` | From the catalogue in §19.2 |
| `entity_type` | `text NOT NULL` | e.g. `participant`, `trial`, `adverse_event` |
| `entity_id` | `uuid` | |
| `old_values` | `jsonb` | Prior state, PHI-redacted (§19.5) |
| `new_values` | `jsonb` | New state, PHI-redacted |
| `occurred_at` | `timestamptz NOT NULL DEFAULT now()` | |
| `ip_address` | `inet` | |
| `user_agent` | `text` | |
| `request_id` | `uuid` | Correlates with application logs and Sentry (§30.3) |
| `trial_id` | `uuid` | **Denormalised**, so a PI can read their trials' audit trail without joining through six tables — and so RLS on this table is a single-column predicate |
| `outcome` | `text NOT NULL DEFAULT 'SUCCESS'` | `CHECK IN ('SUCCESS','FAILURE','DENIED')` — failed and denied attempts are the security-relevant ones |

**Constraints.** No `updated_at`, no `version`. The table is immutable by construction
(§7.8): `UPDATE`, `DELETE`, and `TRUNCATE` revoked from every application role, plus a
`BEFORE UPDATE OR DELETE` trigger that raises.

**Indexes.** `ix_audit_logs_entity` on `(entity_type, entity_id, occurred_at DESC)` —
"what happened to this record";
`ix_audit_logs_user_time` on `(user_id, occurred_at DESC)` — "what did this user do";
`ix_audit_logs_trial_time` on `(trial_id, occurred_at DESC)`;
`ix_audit_logs_action_time` on `(action, occurred_at DESC)`;
`ix_audit_logs_failures` on `(occurred_at DESC)` `WHERE outcome <> 'SUCCESS'` — small,
partial, and the index the security dashboard lives on.

**Partitioning.** Not partitioned in the MVP. `occurred_at` range partitioning by month is
the intended growth path (§28.7) and every index above is partition-compatible, so it is a
migration rather than a redesign.

**RLS.** `SYSTEM_ADMIN` reads all. `REGULATORY_OFFICER` reads entries for trials
(`trial_id IS NOT NULL`). `PI` reads entries for their assigned trials. All other roles read
only their own entries (`user_id = app.current_user_id()`). **`INSERT` is permitted to every
authenticated session** — otherwise a user could perform an action whose audit record could
not be written, and §2.4 requires the audit write to succeed in the same transaction.

---

### 8.25 Tables deliberately not created

Recording what was considered and rejected is as useful as recording what was built.

| Not created | Why |
|---|---|
| `notifications` | The `/notifications` page (§22.7) is a **derived view**, computed from existing tables: pending ethics reviews, unreviewed adverse events, overdue visits, expiring approvals. A notifications table would duplicate state that already exists and immediately raise the question of how to keep the copy correct. If read/dismissed state is needed later, the minimal addition is a `notification_reads (user_id, notification_key, read_at)` table — not a full notification store |
| `idempotency_keys` | Idempotency is provided by the natural unique constraints already present: `(trial_id, subject_code)` on participants, `(visit_id, observation_code)` on observations (§14.5). A dedicated key table would add a second mechanism for a problem the schema already solves |
| `trial_arms` | `participants.randomization_arm` as free text is sufficient for the MVP. A normalised arm table earns its place when arm-level analytics arrive — not before (§32) |
| `sites` (separate from `trial_sites`) | A site only exists in the context of a trial at an institution. A standalone site entity would be an empty join between the two |
| `audit_log_archive` | Partitioning (§28.7) handles growth without a second table and a second query path |

---

## 9. Database Relationships

```mermaid
erDiagram
    ROLES                   ||--o{ USERS                : "assigned to"
    ROLES                   ||--o{ ROLE_PERMISSIONS     : grants
    PERMISSIONS             ||--o{ ROLE_PERMISSIONS     : "granted via"
    INSTITUTIONS            ||--o{ USERS                : employs
    USERS                   ||--o{ SESSIONS             : "authenticates via"

    INSTITUTIONS            ||--o{ TRIALS               : sponsors
    INSTITUTIONS            ||--o{ TRIAL_SITES          : hosts
    TRIALS                  ||--o{ TRIAL_SITES          : "runs at"
    TRIALS                  ||--o{ TRIAL_STAFF          : "staffed by"
    TRIAL_SITES             ||--o{ TRIAL_STAFF          : "scoped to"
    USERS                   ||--o{ TRIAL_STAFF          : "assigned as"

    TRIALS                  ||--o{ PARTICIPANTS         : enrolls
    TRIAL_SITES             ||--o{ PARTICIPANTS         : "enrolled at"
    PARTICIPANTS            ||--|| PARTICIPANT_IDENTITIES : "identified by"
    PARTICIPANTS            ||--o{ CONSENTS             : "consents via"
    PARTICIPANTS            ||--o{ VISITS               : attends
    PARTICIPANTS            ||--o{ MEDICATIONS          : takes
    PARTICIPANTS            ||--o{ ADVERSE_EVENTS       : experiences
    VISITS                  ||--o{ OBSERVATIONS         : records

    ADVERSE_EVENTS          ||--o{ SAFETY_REVIEWS       : "adjudicated by"
    USERS                   ||--o{ SAFETY_REVIEWS       : reviews

    TRIALS                  ||--o{ ETHICS_SUBMISSIONS   : submits
    INSTITUTIONS            ||--o{ ETHICS_SUBMISSIONS   : "reviewed by IEC of"
    ETHICS_SUBMISSIONS      ||--o{ ETHICS_REVIEWS       : receives
    USERS                   ||--o{ ETHICS_REVIEWS       : authors

    TRIALS                  ||--o{ DOCUMENTS            : "documented by"
    INSTITUTIONS            ||--o{ DOCUMENTS            : owns
    DOCUMENTS              ||--o| DOCUMENTS             : supersedes
    DOCUMENTS              ||--o{ CONSENTS              : "signed form"
    DOCUMENTS              ||--o{ TRIAL_COMPLIANCE      : evidences

    COMPLIANCE_REQUIREMENTS ||--o{ TRIAL_COMPLIANCE     : "tracked as"
    TRIALS                  ||--o{ TRIAL_COMPLIANCE     : "measured by"

    USERS                   ||--o{ AUDIT_LOGS           : performs
```

### 9.1 Cardinality notes

| Relationship | Cardinality | Why |
|---|---|---|
| `users` → `roles` | Many-to-one | One role per user; see §6.2 on why multi-role is rejected |
| `users` → `institutions` | Many-to-one, **optional** | Admin and regulator are not institution-bound |
| `participants` → `participant_identities` | **One-to-one, optional** | PK-is-FK enforces at most one identity row. Optional because a screen-failed subject may never have identity captured |
| `trials` → `trial_sites` | One-to-many | Multi-site is the normal case |
| `trial_staff` → `trial_sites` | Many-to-one, **optional** | `NULL` site means trial-wide scope (a PI); a non-null site narrows to one site (§8.10) |
| `participants` → `trial_sites` | Many-to-one, **mandatory** | Every participant belongs to exactly one site. Enforced together with `trial_id` by a **composite FK** so the site cannot belong to a different trial (§8.11) |
| `visits` → `observations` | One-to-many | Many measurements per visit |
| `adverse_events` → `safety_reviews` | One-to-many | An event may be reviewed more than once as information arrives |
| `ethics_submissions` → `ethics_reviews` | One-to-many | Multiple committee members review one submission; unique per `(submission, reviewer)` |
| `documents` → `documents` | **Self-referential, one-to-zero-or-one** | `superseded_by_id` forms the version chain (§17.2) |
| `users` → `audit_logs` | One-to-many, **`ON DELETE SET NULL`** | The only relationship that deliberately survives deletion of its parent |

---

## 10. GIS Architecture

### 10.1 One GIS, seven roles

There is **one** GIS subsystem: one set of tables, one API module, one map component, one
set of policies. Every authenticated role reaches `/gis` and gets the same interface. Role
differences are expressed entirely through the existing RBAC and RLS machinery (§11.3).

Building a per-role map would multiply the code by seven and, more importantly, multiply by
seven the number of places a participant-level field could leak onto a public-facing view.
One implementation means one place to get privacy right and one place to test it (§27.4).

### 10.2 What geography is stored

Geography attaches to **institutions** and **trial sites** — never to participants (§8.12).

```sql
-- institutions, and identically on trial_sites
latitude   numeric(9,6),
longitude  numeric(9,6),
location   geography(Point, 4326)
    GENERATED ALWAYS AS (
        CASE WHEN latitude IS NOT NULL AND longitude IS NOT NULL
             THEN ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
        END
    ) STORED,

city         text NOT NULL,
state        text NOT NULL,
country      text NOT NULL DEFAULT 'India',
postal_code  text
```

Three deliberate choices:

**`GENERATED ALWAYS ... STORED`, not a trigger or application-side write.** The point is
derived from the lat/long columns by the database. It is impossible for an admin to update
the latitude and leave the map showing the old position, because the two cannot disagree.
The classic GIS data bug — coordinates and geometry drifting apart — is unrepresentable.

**`geography`, not `geometry`.** `geography` computes in metres on the spheroid, so
`ST_DWithin(location, point, 50000)` means "within 50 km" without projection choices. For a
national platform spanning Kashmir to Kanyakumari, planar distance in degrees would be
meaningfully wrong.

**SRID 4326 (WGS 84).** What GPS produces, what Leaflet consumes, what GeoJSON specifies.
No reprojection anywhere in the pipeline.

`trial_sites.location` falls back to its institution's location when the site has no
override, resolved in the query via `COALESCE(s.location, i.location)`.

### 10.3 How GIS data is queried

Every spatial query runs against a GIST index and resolves in the database. The API never
fetches a wide result set and filters it in Python.

**Viewport query** — sites within the visible map bounds:

```sql
SELECT s.id, s.site_code, i.name AS institution_name, i.city, i.state,
       s.status, ST_AsGeoJSON(COALESCE(s.location, i.location))::json AS geometry
FROM trial_sites s
JOIN institutions i ON i.id = s.institution_id
WHERE COALESCE(s.location, i.location) && ST_MakeEnvelope(:w, :s, :e, :n, 4326)::geography;
```

The `&&` bounding-box operator is what the GIST index accelerates; PostGIS applies the
exact predicate only to candidates the index returns.

**Radius query** — sites within N km of a point:

```sql
WHERE ST_DWithin(COALESCE(s.location, i.location),
                 ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography,
                 :radius_metres)
```

**Aggregation by administrative area** — the regulator's national view:

```sql
SELECT i.state,
       count(DISTINCT s.trial_id)  AS trial_count,
       count(DISTINCT s.id)        AS site_count,
       sum(s.current_enrollment)   AS total_enrollment
FROM trial_sites s
JOIN institutions i ON i.id = s.institution_id
WHERE s.status IN ('ACTIVATED','ENROLLING')
GROUP BY i.state
HAVING count(DISTINCT s.id) >= 1;
```

**Server-side clustering at low zoom.** Below zoom level 8, returning every individual site
is both slow and visually useless. `ST_ClusterDBSCAN` groups nearby sites into a single
marker carrying a count:

```sql
SELECT ST_ClusterDBSCAN(location, eps := :eps_degrees, minpoints := 2) OVER () AS cluster_id,
       id, location
FROM trial_sites WHERE ...
```

Clustering happens in PostgreSQL rather than in Leaflet because a national dataset should
never cross the network just to be collapsed into twelve dots in the browser (§28.6).

### 10.4 GIS data flow

```mermaid
flowchart TB
    PG[("PostgreSQL + PostGIS<br/>institutions.location · trial_sites.location<br/>GIST indexed")]
    RLS["RLS policies<br/>scope the rows"]
    AGG["Aggregation + k-anonymity<br/>SQL — suppression happens here"]
    API["FastAPI /api/v1/gis<br/>GeoJSON serialisation"]
    CACHE[("Redis<br/>cache-aside, scope-keyed")]
    LEAF["Leaflet + OpenStreetMap<br/>markers · clusters · choropleth"]
    MAP["Interactive map<br/>/gis — all roles"]

    PG --> RLS --> AGG --> API
    API <-.->|"cache-aside"| CACHE
    API --> LEAF --> MAP
    MAP -->|"viewport · filters · drill-down"| API

    style AGG fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

**The order of the stages is the privacy design.** RLS scopes rows, *then* aggregation and
suppression run, *then* the result is serialised. Suppressed values never enter the API
layer, so no serialisation bug, logging statement, or cache entry can expose them — the data
does not exist by the time those components run.

### 10.5 GIS API surface

| Endpoint | Returns | Notes |
|---|---|---|
| `GET /api/v1/gis/institutions` | GeoJSON FeatureCollection | All institutions, name/type/city/state. Public-safe data |
| `GET /api/v1/gis/sites?bbox=&trial_id=&status=` | GeoJSON FeatureCollection | Sites in viewport, RLS-scoped, k-suppressed counts |
| `GET /api/v1/gis/clusters?zoom=&bbox=` | GeoJSON with cluster counts | Server-side clustering below zoom 8 |
| `GET /api/v1/gis/aggregates?level=state\|district` | JSON | Choropleth data, k-suppressed |
| `GET /api/v1/gis/sites/{id}/detail` | JSON | **Requires `gis:drilldown` + RLS scope.** The only endpoint returning per-site clinical aggregates |

Requests carry a bounding box and zoom level; responses carry only what that viewport needs.

---

## 11. GIS Privacy

### 11.1 The governing idea

> **Same geographic interface, role-based drill-down.**

Every role sees the same map with the same base layers. What changes with role is how deeply
a marker can be opened. There is no separate "public map" and "internal map" — one map,
where the depth of a click is governed by permission and scope.

### 11.2 The hard boundary

| The map **may** show | The map must **never** show |
|---|---|
| Institution names and locations | Participant names |
| Trial site locations and status | Participant IDs or subject codes |
| Trial counts per site, state, district | Phone numbers, email addresses |
| Aggregate enrolment (subject to §11.4) | Personal or home addresses |
| Site activation status | Individual medical information |
| Aggregate compliance percentages | Individual clinical observations |
| Aggregate safety counts (subject to §11.4) | Individual adverse event narratives |
| Trial phase, therapeutic area | Anything from `participant_identities` |

This boundary is enforced in three independent places, so that any one of them failing is
not sufficient to breach it:

1. **Schema.** `participant_identities` has no geographic column (§8.12). There is nothing
   to plot.
2. **Query.** Every GIS query aggregates with `count()` / `sum()` and `GROUP BY` on
   institution, site, state, or district. No GIS query selects a participant row.
3. **Policy.** The RLS policies on `participants` and `participant_identities` exclude the
   roles that reach the map most broadly (`REGULATORY_OFFICER`, `SYSTEM_ADMIN`) from those
   tables entirely (§5.1, §5.7).

### 11.3 Drill-down levels

Drill-down is not a special GIS mechanism. It is `gis:drilldown` (RBAC) plus the ordinary
row scope (RLS) — the same two controls as every other resource.

| Level | Shows | Gate |
|---|---|---|
| **0 — Base map** | Institutions, sites, national trial and site counts | Authenticated + `gis:read`. Every role |
| **1 — Aggregate** | Per-state/district trial counts, aggregate enrolment, compliance %, aggregate safety counts | `gis:read` + k-anonymity suppression (§11.4). Every role |
| **2 — Site detail** | One site's enrolment, active trials, compliance items, adverse event counts | `gis:drilldown` **and** RLS scope covers that site |
| **3 — Trial detail** | Site-level enrolment curve, compliance breakdown, safety summary | `gis:drilldown` + trial assignment |

There is no level exposing individual participants. It is not a permission that exists.

**How this reads per role:**

| Role | Reaches | Because |
|---|---|---|
| `REGULATORY_OFFICER` | Level 1 nationally; level 2 and 3 for compliance and aggregate safety, never clinical detail | Broadest row scope, but excluded from `participants` (§5.7) |
| `PRINCIPAL_INVESTIGATOR` | Level 3 for assigned trials; level 1 elsewhere | `app.active_assignments()` covers their trials only |
| `TRIAL_COORDINATOR` | Level 3 for assigned trials and sites; level 1 elsewhere | Same mechanism |
| `RESEARCH_STAFF` | Level 2 for their own site; level 1 elsewhere | Sees the national map as geographic context, gains no clinical access to unassigned trials |
| `ETHICS_MEMBER` | Level 2 for their institution's reviewed trials; level 1 elsewhere | Institution scope |
| `SAFETY_OFFICER` | Level 1 and aggregate safety nationally | Cross-trial safety scope, aggregate only |
| `SYSTEM_ADMIN` | Level 1 and structural detail; **no clinical aggregates** | Excluded from clinical tables entirely |

### 11.4 Small-cell suppression

This is the part of GIS privacy that field-level access control does not cover, and the
reason it is called out separately.

**The problem.** Suppose a site has 2 enrolled participants. The map shows
`enrollment: 2`, `serious adverse events: 1`. No participant field has left the database —
every column is an aggregate, every policy held — and yet a viewer now knows that one of two
people at a named hospital had a serious adverse event on a named trial. In a small district
with one enrolling site, that can be enough to identify a person. Aggregation alone is not
anonymisation when the cells are small.

**The mitigation.** A k-anonymity threshold of **k = 5**, applied in SQL before results
leave the database:

```sql
CREATE FUNCTION app.suppress_small(value bigint, cohort_size bigint, k integer DEFAULT 5)
RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE
        WHEN cohort_size IS NULL OR cohort_size = 0 THEN jsonb_build_object('value', 0,    'suppressed', false)
        WHEN cohort_size <  k                       THEN jsonb_build_object('value', null, 'suppressed', true, 'label', '<' || k)
        ELSE                                             jsonb_build_object('value', value,'suppressed', false)
    END;
$$;
```

Applied to every aggregate that is derived from participant-level rows:

```sql
SELECT i.state,
       count(DISTINCT s.id) AS site_count,                    -- structural: not suppressed
       app.suppress_small(sum(s.current_enrollment)::bigint,
                          sum(s.current_enrollment)::bigint)  AS enrollment,
       app.suppress_small(count(ae.id)::bigint,
                          sum(s.current_enrollment)::bigint)  AS adverse_events
FROM trial_sites s
JOIN institutions i   ON i.id = s.institution_id
LEFT JOIN adverse_events ae ON ae.trial_id = s.trial_id
GROUP BY i.state;
```

Four rules govern its use:

1. **Suppress the numerator when the cohort is small, not merely when the numerator is
   small.** Reporting `0 adverse events` for a 2-person site discloses just as much as
   reporting `1`.
2. **Structural counts are not suppressed.** How many sites or trials exist in a state is
   organisational information, not participant information.
3. **Suppression happens in SQL.** If it were applied in the API layer, the true value would
   still be in memory, in logs, and potentially in a cache entry. In SQL, the value never
   leaves the database.
4. **Suppressed cells render as `<5`, not as `0` or a blank.** A blank invites the reader to
   assume zero; `<5` is honest about what is being withheld and why.

k = 5 is configurable via `GIS_K_ANONYMITY_THRESHOLD` (§25.3) and can be raised without a
code change.

### 11.5 The oversight view

The Regulatory Officer's national map is the platform's most compelling screen and its
sharpest privacy test simultaneously. It shows every institution and site nationally,
coloured by aggregate compliance, sized by aggregate enrolment, filterable by phase,
therapeutic area, and safety indicator — a genuine national picture of trial activity.

And it shows this while its viewer has **zero rows** of `participants`,
`participant_identities`, `observations`, and `consents`, because the RLS policies exclude
the role from those tables entirely (§5.7). The national oversight picture is assembled
without the overseer ever holding a participant record. That is the demonstration worth
making: the privacy property is not a promise about restraint, it is a consequence of
the policies.

---

## 12. Redis Caching Strategy

### 12.1 What is cached

Only data that is (a) expensive to compute, (b) read far more often than written, and
(c) tolerant of being seconds out of date.

| Cached | Key pattern | TTL | Why |
|---|---|---|---|
| Admin dashboard statistics | `dash:admin:v1` | 60 s | Aggregates across every table; identical for every admin |
| Trial dashboard statistics | `dash:trial:{trial_id}:v1` | 120 s | Enrolment, site, safety, compliance rollups per trial |
| Role dashboard statistics | `dash:{role}:{user_id}:v1` | 60 s | Per-user work queues |
| GIS viewport results | `gis:sites:{bbox_hash}:{scope_hash}:v1` | 300 s | Spatial query + serialisation; geography changes rarely |
| GIS aggregates | `gis:agg:{level}:{scope_hash}:v1` | 300 s | Full-table aggregation across sites |
| Enrolment analytics | `analytics:trial:{trial_id}:enrollment:v1` | 300 s | Time series over the enrolment history |
| Compliance percentages | `analytics:trial:{trial_id}:compliance:v1` | 300 s | Join and aggregate over `trial_compliance` |
| Aggregate safety statistics | `analytics:trial:{trial_id}:safety:v1` | 180 s | Counts only, never narratives. Shorter TTL — safety data staleness matters more |
| Compliance requirements catalogue | `ref:compliance_requirements:v1` | 3600 s | Reference data; changes monthly at most |
| Role permission sets | `perm:role:{role_id}:v1` | 900 s | Resolved on every request (§6.5) |
| Session validity lookaside | `sess:{session_id}:v1` | 300 s | Avoids a DB round trip per request. **Authoritative copy is `sessions`** (§8.6) |

### 12.2 What is never cached

| Never cached | Why |
|---|---|
| Passwords or password hashes | Never leave the database, in any form |
| Raw clinical observations | Participant-level PHI; the performance case does not exist (they are read per participant, not per dashboard) |
| Participant records or identities | PHI. Caching would place identifying data in a store with weaker access control than PostgreSQL — Redis has no RLS |
| Consent records | Regulatory documents that must be read fresh |
| Individual adverse event narratives | PHI in free text |
| Document bytes or signed URLs | Signed URLs are short-lived and per-request by design (§16.4); caching one would extend its life past its intended expiry |
| Audit logs | Append-only records read for compliance. A stale audit view is worse than a slow one |
| Ethics review deliberation | Restricted content with no read-volume justification |
| JWT signing secrets, Cloudinary API secrets | Configuration secrets belong in environment variables (§25) |
| **Authorization decisions** | §2.2 and §7.6. The permission-set and session entries above are *lookasides* whose miss falls through to PostgreSQL — not decisions |

**The line between the last two rows matters.** Caching "role X holds permissions
{a, b, c}" is caching a *fact* recoverable from `role_permissions`, invalidated on write.
Caching "user Y may read trial Z" would be caching a *decision*, and a stale one grants
access that has been revoked. The first is a cache; the second is a security defect.

### 12.3 Cache-aside pattern

**Reads:**

```mermaid
flowchart LR
    R["Request"] --> C{"Redis"}
    C -->|"HIT"| RET["Return cached"]
    C -->|"MISS"| PG[("PostgreSQL")]
    PG --> W["Write to Redis with TTL"]
    W --> RET
    C -.->|"Redis down"| PG
```

**Writes:**

```mermaid
flowchart LR
    W["Request"] --> TX["BEGIN transaction"]
    TX --> U["Write to PostgreSQL"]
    U --> A["Append audit record"]
    A --> CM["COMMIT"]
    CM --> INV["Invalidate affected keys"]
    INV --> RESP["Respond"]
    CM -.->|"rollback"| ERR["Error — no invalidation, nothing changed"]

    style CM fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

Three rules, each preventing a specific bug:

**Invalidate after commit, never before or during.** Invalidating inside the transaction
opens a window where the transaction may still roll back, but the cache has already been
cleared and repopulated — from a read that saw the *pre-transaction* state. The cache then
holds stale data with a fresh TTL and no pending invalidation to fix it. Post-commit
invalidation cannot produce this.

**Redis being down degrades performance, never correctness.** Every cache read is wrapped so
a connection error falls through to PostgreSQL and logs a warning. The platform runs slower
with Redis down; it does not run wrong, and it does not stop.

**PostgreSQL always wins.** If a cached value disagrees with the database, the cached value
is wrong. There is no reconciliation logic, because there is nothing to reconcile — the
cache entry is discarded.

### 12.4 Scope in the cache key

**Every cache key for scope-dependent data includes a scope fingerprint.** This is the
single most dangerous mistake available in this design: caching a GIS aggregate under
`gis:agg:state:v1` and serving it to every role would hand a Research Staff member the
Regulatory Officer's national view, straight from cache, with every RLS policy having
evaluated correctly on the way in.

```python
def scope_fingerprint(user: CurrentUser) -> str:
    """Stable hash of everything that changes what this user may see."""
    material = f"{user.role_name}|{user.institution_id}|" + ",".join(
        sorted(f"{a.trial_id}:{a.site_id}" for a in user.assignments)
    )
    return hashlib.sha256(material.encode()).hexdigest()[:16]
```

Two users with identical scope share a cache entry, which is correct and is where the hit
rate comes from. Two users with different scope can never collide. Data that is genuinely
scope-independent — the compliance requirements catalogue, institution locations — omits the
fingerprint deliberately and is documented as doing so.

### 12.5 Stampede protection

When a popular key expires, every concurrent request misses simultaneously and all of them
run the expensive query. Two mitigations:

**Jittered TTLs.** Each entry gets `base_ttl + random(0, base_ttl * 0.1)`, so keys written
together do not expire together.

**A short lock on recompute.** The first miss takes a `SET NX` lock with a 10-second expiry
and recomputes; concurrent missers wait briefly and re-read, falling through to PostgreSQL if
the lock holder is slow. A slow response is acceptable; a thundering herd against the
aggregate query is not.

---

## 13. Cache Invalidation

### 13.1 Principles

Invalidation is **explicit and event-driven**, triggered by the write path immediately after
commit. TTL expiry is a safety net for keys a code path forgot, not the primary mechanism —
if correctness depends on TTL, the window between the write and the expiry is a window of
wrong data on a clinical dashboard.

Every domain service declares the keys it invalidates in one place, so the answer to "what
does updating a participant invalidate?" is readable rather than discovered.

### 13.2 Invalidation map

| Write | Invalidates |
|---|---|
| **Participant created** | `participant:{id}`, `dash:trial:{trial_id}`, `dash:{role}:*` for assigned staff, `analytics:trial:{trial_id}:enrollment`, `gis:agg:*`, `gis:sites:*` (enrolment counts changed) |
| **Participant updated** | `participant:{id}`, `participant:{id}:timeline`, `dash:trial:{trial_id}`, `analytics:trial:{trial_id}:*` |
| **Participant withdrawn** | As updated, plus `analytics:trial:{trial_id}:enrollment`, `gis:agg:*` |
| **Visit created / status changed** | `dash:trial:{trial_id}`, `dash:coordinator:*`, `dash:research:*` for that site |
| **Observation recorded** | `participant:{id}:timeline`, `dash:trial:{trial_id}` |
| **Adverse event reported** | `analytics:trial:{trial_id}:safety`, `dash:trial:{trial_id}`, `dash:safety:*`, `gis:agg:*` (safety indicators are mapped) |
| **Safety review completed** | `analytics:trial:{trial_id}:safety`, `dash:safety:*` |
| **Ethics submission decided** | `dash:trial:{trial_id}`, `dash:ethics:*`, `analytics:trial:{trial_id}:compliance` |
| **Compliance record updated** | `analytics:trial:{trial_id}:compliance`, `dash:trial:{trial_id}`, `dash:regulator:*`, `gis:agg:*` (compliance is a map layer) |
| **Site geography or status changed** | `gis:sites:*`, `gis:agg:*`, `gis:clusters:*`, `dash:admin` |
| **Institution created / relocated** | `gis:*` entirely — the base layer changed |
| **Trial staff assignment changed** | Every `dash:*:{user_id}` and every `gis:*` key bearing that user's now-stale scope fingerprint (§12.4). **Scope changes invalidate broadly, on purpose** |
| **Role permissions changed** | `perm:role:{role_id}`, and every scope-fingerprinted key for users holding that role |
| **Document version superseded** | `dash:trial:{trial_id}`, `analytics:trial:{trial_id}:compliance` if it is compliance evidence |

### 13.3 Wildcard invalidation

`gis:sites:*` spans many keys. **`KEYS` is never used** — it blocks the Redis event loop for
the duration of a full keyspace scan, which on a busy instance is a self-inflicted outage.

Two mechanisms instead:

**Redis Sets as tag indexes.** Writing a GIS key also adds it to a set `tag:gis`. Invalidating
the tag reads the set members, deletes them in a pipelined batch, and deletes the set.
Bounded work, no scan.

**Version-prefix rotation for very broad invalidation.** Keys carry a generation number read
from `gis:generation`. Invalidating everything geographic is a single `INCR`, after which
every old key is unreachable and expires on its own TTL. `O(1)`, no deletion at all.

```text
gis:sites:{gen}:{bbox_hash}:{scope_hash}
                ↑ INCR gis:generation orphans every prior key at once
```

Tag sets are used for targeted invalidation; generation rotation for "the base layer moved".

### 13.4 TTL rationale

| Data class | TTL | Reasoning |
|---|---|---|
| Reference data (`ref:*`) | 3600 s | Changes at most monthly and is explicitly invalidated on write. Long TTL is nearly free |
| Permission sets (`perm:*`) | 900 s | Role definitions change rarely; explicit invalidation on `role_permissions` write means the TTL only ever covers a missed invalidation |
| GIS geography (`gis:sites`, `gis:clusters`) | 300 s | Site locations change on the order of weeks |
| GIS and trial aggregates | 300 s | Recomputed nightly by Celery; a 5-minute cached view of national statistics is not misleading |
| Safety aggregates | 180 s | Deliberately shorter. A stale safety count is the most consequential stale number on the platform |
| Dashboards | 60–120 s | Users expect a dashboard to reflect this morning's work, not this second's |
| Session lookaside | 300 s | Bounded staleness after revocation; §18.6 explains why this window is acceptable and how it is closed for the cases where it is not |

**These are starting values.** They were chosen from expected access patterns, not
measurement. Every one is set through an environment variable (§25.3), and Phase 11 (§26)
tunes them against real hit rates from a seeded dataset. Publishing a precise TTL as though
it were derived from data the project does not yet have would be dishonest; publishing a
reasoned starting point that is cheap to change is not.

---

## 14. Data Consistency

### 14.1 ACID transactions

Every request that writes runs inside one transaction, opened by the same FastAPI dependency
that sets the RLS session variable (§7.3). The transaction commits when the handler returns
successfully and rolls back on any exception — including one raised while writing the audit
record, which means an unauditable change cannot commit (§2.4).

Isolation level is PostgreSQL's default `READ COMMITTED`. Where a read-modify-write must not
interleave — enrolment counters — the row is locked explicitly rather than the isolation
level raised globally (§14.6).

### 14.2 Constraints as the last line

Application validation produces good error messages. Database constraints produce
guarantees. The platform uses both, and never relies on the first alone.

| Constraint type | Guarantee | Example from §8 |
|---|---|---|
| Foreign key | No orphans | `observations.visit_id` → `visits(id)` `ON DELETE RESTRICT` |
| **Composite** foreign key | No cross-entity mismatch | `participants (trial_id, trial_site_id)` → `trial_sites (trial_id, id)` — a site from another trial is unrepresentable |
| Unique | No duplicates | `(trial_id, subject_code)` on participants |
| **Partial** unique | No duplicate *in a state* | One `CURRENT` document per family; one `ACTIVE` consent per participant |
| Check | No invalid values | Serious AE requires criteria; amended observation requires a reason |
| Not null | No missing essentials | `participants.trial_site_id` |

Partial unique indexes deserve emphasis: `uq_documents_one_current_per_family` is what makes
document versioning correct under concurrency. Two simultaneous "publish v3" requests both
pass an application-level check that no current version exists, and then one of them fails on
the index. Without it, both succeed and the family has two current versions with no way to
tell which is authoritative.

### 14.3 Read-after-write consistency

A user who saves a form and is redirected to a list must see their change. Two rules:

1. **The write response returns the written entity**, read back inside the same transaction.
   The client renders from the response and never needs a follow-up fetch that might miss.
2. **Cache invalidation precedes the response** (§12.3). By the time the client issues its
   next request, the stale entry is already gone.

For the MVP's single-primary deployment (§15.1) this is sufficient — there is no replica lag
to reason about, because there are no replicas.

### 14.4 Optimistic concurrency

Two coordinators editing the same participant is a routine occurrence, and last-write-wins
silently discards one of them.

Every mutable clinical table carries `version integer NOT NULL DEFAULT 1`. The API surfaces
it as an `ETag`; clients return it as `If-Match`:

```sql
UPDATE participants
SET status = :status, version = version + 1, updated_at = now(), updated_by = :user_id
WHERE id = :id AND version = :expected_version
RETURNING *;
```

Zero rows updated means someone else changed the row first. The API returns
`409 Conflict` with the current state so the client can show what changed rather than
silently overwriting it. Pessimistic locking was rejected: holding a row lock across a user
filling in a clinical form is a lock held for minutes.

### 14.5 Idempotency

Retried requests must not create duplicates. A coordinator on a poor connection taps
"Enrol" twice; a mobile browser retries a timed-out POST.

**Idempotency comes from the existing unique constraints, not a separate mechanism:**

| Operation | Idempotency key | Retry behaviour |
|---|---|---|
| Enrol participant | `uq_participants_trial_subject_code` | Unique violation → look up existing → `200 OK` with that participant |
| Record observation | `uq_observations_visit_code` | Unique violation → `200 OK` with the existing observation |
| Create visit | `uq_visits_participant_number` | Unique violation → `200 OK` |
| Upload document | `uq_documents_checksum_family` | Identical bytes → returns the existing document, no new version |
| Refresh token | `sessions.family_id` chain | A reused token triggers family revocation (§18.6) — deliberately *not* idempotent, because a replayed refresh token is a security signal |

The service layer catches `UniqueViolation`, re-reads by the natural key, and returns the
existing entity with `200` instead of `201`. This needs no `idempotency_keys` table, no TTL
on stored keys, and no second source of truth — the constraint that already guarantees data
integrity also provides the idempotency (§8.25).

### 14.6 Worked example: participant enrolment

Enrolment is the platform's most consistency-sensitive operation. It touches five tables and
must be atomic:

```python
async def enroll_participant(db, user, payload) -> Participant:
    async with db.begin():                      # single transaction
        # 1. Lock the site row — serialises concurrent enrolments at this site
        site = await db.execute(
            select(TrialSite)
            .where(TrialSite.id == payload.trial_site_id)
            .with_for_update()
        )

        # 2. Capacity check, now race-free under the lock
        if site.target_enrollment and site.current_enrollment >= site.target_enrollment:
            raise SiteEnrollmentFullError(site.id)

        # 3. Create participant — unique constraint provides idempotency
        participant = await create_participant(db, payload)

        # 4. Create the consent record
        await create_consent(db, participant.id, payload.consent)

        # 5. Increment site and trial counters
        await increment_enrollment(db, site_id=site.id, trial_id=payload.trial_id)

        # 6. Audit — inside the same transaction (§2.4)
        await write_audit(db, user, "CREATE_PARTICIPANT", "participant",
                          participant.id, new_values=redact(participant))
    # COMMIT here. Invalidation only after.
    await invalidate(["dash:trial:%s" % payload.trial_id,
                      "analytics:trial:%s:enrollment" % payload.trial_id,
                      "tag:gis"])
    return participant
```

Each step exists for a reason:

- **`SELECT ... FOR UPDATE` on the site row.** Without it, two concurrent enrolments at a
  site with one remaining slot both read `current_enrollment = 49`, both pass the check
  against a target of 50, and both increment — producing 51 enrolled against an approved
  target of 50. That is a protocol deviation created by a race. The lock serialises the pair;
  the `CHECK` constraint of §8.8 catches it even if the lock is ever removed.
- **Consent inside the transaction.** A participant without a consent record is a compliance
  failure. Partial success is not an acceptable outcome here.
- **Counters updated transactionally, never from cache.** `current_enrollment` is
  authoritative in PostgreSQL (§2.1). The cached dashboard number is derived from it.
- **Audit before commit.** If the audit write fails, the enrolment rolls back with it.
- **Invalidation strictly after commit** (§12.3).

### 14.7 Consistency risk register

| Risk | Mitigation |
|---|---|
| Concurrent edits overwrite each other | Optimistic locking, `409 Conflict` (§14.4) |
| Duplicate enrolment on retry | Natural-key unique constraint → `200 OK` (§14.5) |
| Over-enrolment past target | `FOR UPDATE` lock + `CHECK` constraint (§14.6) |
| Participant with no consent | Same-transaction creation (§14.6) |
| Enrolment counter drift | Transactional increment; a nightly Celery reconciliation job recomputes from `participants` and alerts on any mismatch |
| Two current document versions | Partial unique index (§14.2) |
| Cache serves stale data after write | Post-commit invalidation + TTL floor (§13) |
| Cross-user cache leakage | Scope fingerprint in every key (§12.4) |
| Audit missing for a committed change | Audit write inside the transaction (§2.4) |
| Orphaned Cloudinary asset | Upload-then-commit ordering + nightly orphan sweep (§16.7) |

---

## 15. Replication

### 15.1 The MVP runs a single primary

```text
PostgreSQL Primary  ──  all reads, all writes
```

**And that is the correct choice**, not a limitation apologised for. A single primary gives
strict read-after-write consistency with no lag to reason about, no routing logic in the data
layer, and no class of bug where a dashboard shows a participant who was enrolled a moment
ago as absent. The expected demo workload — tens of concurrent users, tens of thousands of
rows — is comfortably within one modest PostgreSQL instance.

Adding replicas to an SIH MVP would add operational complexity, a whole category of
staleness bugs, and nothing a judge could observe. §32 names this as exactly the kind of
overengineering to refuse.

### 15.2 The production path

```mermaid
flowchart TB
    APP["FastAPI"]
    P[("Primary<br/>writes + consistency-sensitive reads")]
    R1[("Read replica 1<br/>analytics · reports")]
    R2[("Read replica 2<br/>GIS aggregates · exports")]

    APP -->|"writes · read-after-write · RLS-sensitive"| P
    APP -.->|"heavy read-only"| R1
    APP -.->|"heavy read-only"| R2
    P ==>|"streaming replication"| R1
    P ==>|"streaming replication"| R2

    style P fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

| Node | Handles |
|---|---|
| **Primary** | All `INSERT`, `UPDATE`, `DELETE`; any read that must reflect a just-committed write; all authentication and session reads |
| **Replicas** | Analytics aggregation, report generation, GIS aggregate precomputation, data exports, historical audit queries |

### 15.3 Replication lag

Streaming replication is asynchronous: a replica trails the primary by milliseconds normally,
seconds under load or during a long-running query. The consequence that matters:

> A coordinator enrols a participant (primary), is redirected to the participant list
> (replica), and the participant is not there. The data is not lost — the replica has not
> caught up. But the user has just watched their work vanish, and will re-enter it.

Three rules make this manageable, and they are why the architecture is designed for
replication now even though it uses none:

1. **Route by intent, not by SQL verb.** The service layer declares
   `@read_only(replica_ok=True)` on operations that tolerate staleness. Everything else uses
   the primary. Inferring from whether a statement is a `SELECT` is wrong, because a `SELECT`
   immediately following a write must hit the primary.
2. **Read your own writes from the primary.** Any request whose transaction wrote, and the
   redirect that follows it, reads from the primary. Implemented as a short per-session
   sticky flag.
3. **RLS applies identically on replicas.** Policies replicate with the schema, and the
   `SET LOCAL` mechanism (§7.3) works unchanged. This must be verified rather than assumed
   when replicas are introduced — it is a test in the Phase 12 suite (§27.2).

### 15.4 What this section costs today

Nothing beyond discipline. The service layer separates read-only operations from writing ones
because that is good structure regardless. Adding a replica later means configuring a second
engine and flipping the `replica_ok` operations onto it — not restructuring the data access
layer. Recorded as ADR-009.

---

## 16. Cloudinary File Architecture

### 16.1 Upload flow

```mermaid
flowchart TB
    FE["Frontend<br/>file picker"] --> API["FastAPI<br/>POST /api/v1/documents"]
    API --> AUTHZ{"document:upload<br/>+ RLS scope on trial"}
    AUTHZ -->|"denied"| D403["403"]
    AUTHZ -->|"allowed"| VAL{"Validation<br/>size · MIME sniff · extension"}
    VAL -->|"rejected"| D400["400"]
    VAL -->|"accepted"| SUM["SHA-256 checksum"]
    SUM --> DUP{"Checksum already<br/>in this family?"}
    DUP -->|"yes"| EXIST["200 — existing document"]
    DUP -->|"no"| CLOUD["Upload to Cloudinary<br/>type=authenticated, access_mode=authenticated"]
    CLOUD --> TX["BEGIN transaction"]
    TX --> META["INSERT documents<br/>status=PENDING_SCAN"]
    META --> AUD["INSERT audit_logs<br/>UPLOAD_DOCUMENT"]
    AUD --> CM["COMMIT"]
    CM --> Q["Enqueue malware scan"]
    Q --> R201["201 Created"]

    style CM fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

**Files never reach Cloudinary before authorization.** The permission check and the RLS scope
check both run before a single byte is uploaded. A user who may not write to a trial cannot
put a file into that trial's storage namespace even briefly.

**Bytes go to Cloudinary; PostgreSQL never stores file content.** Storing files as `bytea`
would inflate the database, slow every backup, and make the working set exceed memory for no
benefit (§2.1).

### 16.2 Metadata in PostgreSQL

The `documents` table (§8.21) carries everything needed to identify, authorise, version, and
verify a file:

| Field | Purpose |
|---|---|
| `id` | Platform identity — the only handle the frontend ever sees |
| `document_family_id` | Groups versions (§17.2) |
| `trial_id` / `institution_id` / `trial_site_id` | Ownership, and the input to RLS scope |
| `document_type` | Classification, and the basis of the regulator's type-filtered access |
| `file_name`, `mime_type`, `file_size_bytes` | Sanitised original name and verified content type |
| `checksum_sha256` | Integrity verification and duplicate detection |
| `cloudinary_public_id`, `cloudinary_resource_type`, `cloudinary_version` | The storage handle — **never exposed to clients** |
| `version`, `status`, `superseded_by_id` | Version chain and lifecycle |
| `scan_status`, `scanned_at` | Malware scan state |
| `uploaded_by`, `uploaded_at` | Attribution |
| `effective_date`, `expiry_date` | Validity period for approvals |

### 16.3 Private delivery

Every asset is uploaded with `type=authenticated` and `access_mode=authenticated`. There is
no publicly reachable URL. Knowing a `cloudinary_public_id` is not sufficient to fetch the
file — and the public ID is never sent to a client in any case.

### 16.4 Signed URLs

Download is a two-step exchange, and the reason is worth stating:

```text
GET /api/v1/documents/{id}/download
  → permission check: document:read
  → RLS check: is this document in the caller's scope?
  → status check: scan_status = 'CLEAN'
  → generate signed Cloudinary URL, expiry = now + 300 s
  → 302 redirect to the signed URL
  → write audit_logs (action=DOWNLOAD_DOCUMENT)
```

- **Signed URLs are generated per request, never stored, never cached** (§12.2). A cached
  signed URL would outlive the authorization that produced it.
- **Five-minute expiry** is long enough for the browser to follow the redirect and download,
  short enough that a URL leaked into a log, a screenshot, or a chat message is dead on
  arrival.
- **Every download is audited.** Who read which protocol version, and when, is exactly the
  question a regulatory inspection asks.

### 16.5 Upload validation

Layered, because each layer catches what the previous one misses:

| Check | Rule | Rejects |
|---|---|---|
| Size | ≤ 50 MB, enforced at Caddy, FastAPI, and by a `CHECK` constraint | Resource exhaustion |
| Extension allowlist | `.pdf .docx .xlsx .png .jpg .csv` — **allowlist, never denylist** | Executables and scripts |
| **Content sniffing** | `python-magic` reads the actual magic bytes; must match both the extension and the declared `Content-Type` | `malware.exe` renamed to `protocol.pdf` |
| Filename sanitisation | Strip path separators, null bytes, control characters; store the sanitised form | Path traversal (`../../etc/passwd`) |
| Checksum | SHA-256 computed server-side | Corruption; enables duplicate detection |
| Malware scan | Asynchronous ClamAV (§16.6) | Known malware |

**Content sniffing is the one that matters.** Extension and `Content-Type` are both
attacker-controlled; magic bytes are a property of the file itself. A document whose sniffed
type disagrees with its extension is rejected outright rather than corrected.

### 16.6 Malware scanning

A ClamAV container runs in the Compose stack. Scanning is asynchronous because a synchronous
scan would hold an HTTP connection open for seconds on a large file:

```text
Upload → status = PENDING_SCAN, scan_status = PENDING → 201 Created
              ↓ Celery task
       Fetch from Cloudinary → clamd scan
              ↓
   CLEAN     → scan_status = CLEAN, status = DRAFT (publishable)
   INFECTED  → scan_status = INFECTED, status = QUARANTINED,
               delete the Cloudinary asset, audit event, alert admin
   ERROR     → scan_status = ERROR, retry with backoff (3 attempts), then alert
```

The `ck_documents_clean_before_current` constraint (§8.21) means a document cannot reach
`CURRENT` or `DRAFT` while `scan_status <> 'CLEAN'`. **An unscanned file is undownloadable —
enforced by the database, not by remembering to check.** The download endpoint's status check
(§16.4) is the second layer, not the only one.

### 16.7 Orphan prevention

The upload writes to Cloudinary *before* the transaction commits, so a failed commit leaves
an asset with no metadata row. This is the correct ordering — the alternative, committing
metadata before the bytes exist, produces a document row that 404s, which is worse because
the platform believes the file exists.

Two mitigations: the upload handler deletes the Cloudinary asset in its exception path, and a
nightly Celery job lists assets in the trial namespace, compares against
`documents.cloudinary_public_id`, and removes anything unreferenced for more than 24 hours.
The 24-hour delay avoids racing an in-flight upload.

### 16.8 Access control summary

| Layer | Enforces |
|---|---|
| RBAC | `document:upload`, `document:read`, `document:supersede`, `document:archive` |
| RLS | Document's trial/institution/site must be in the caller's scope |
| Type filter | `REGULATORY_OFFICER` reads only approval, regulatory, and safety report types (§8.21) |
| Status | Only `scan_status = 'CLEAN'` is downloadable |
| Delivery | `authenticated` assets, per-request signed URLs, 5-minute expiry |
| Audit | Upload, download, supersede, and archive all recorded |

### 16.9 Production caveat

Cloudinary is the file store for this project and is a sound choice for it: authenticated
delivery, signed URLs, CDN, and no object storage for the team to operate.

For a production clinical deployment, the storage decision would be revisited against
requirements Cloudinary is not designed around: data residency guarantees under Indian
regulation, a signed Business Associate–equivalent agreement, configurable retention and
legal hold, and customer-managed encryption keys. Private object storage — S3 with SSE-KMS
and Object Lock, GCS, or Azure Blob — addresses those directly.

The architecture keeps this a contained change. All Cloudinary interaction lives behind a
`StorageBackend` interface in `services/storage/`; `documents` stores a generic storage
handle. Migrating means implementing a second backend and re-uploading assets, not touching
the schema or any calling code. Recorded as ADR-005.

---

## 17. Document Versioning

### 17.1 Why documents are never overwritten

A protocol amendment does not replace the protocol. Both versions must remain retrievable,
because the record must be able to answer questions that are only meaningful across versions:

- A participant enrolled in March consented under **ICF v2**. In June the form became **v3**.
  Their consent is valid against v2 — the version they actually read. Overwrite v2 and the
  platform can no longer show what that person agreed to.
- An ethics committee approved **protocol v1**. Amendments produced v2 and v3. The approval
  attaches to the version that was reviewed. Overwrite it and the approval refers to a
  document that no longer exists.
- An inspector asks which protocol version was in force on a given date. That question has no
  answer without a version history.
- An adverse event is assessed against the safety profile documented at the time it occurred.

Overwriting destroys the regulatory record. This is not a nice-to-have (§2.4).

### 17.2 The version chain

```text
document_family_id = 7c3e…                    ← stable across every version
 ├── v1  status=SUPERSEDED  superseded_by_id → v2
 ├── v2  status=SUPERSEDED  superseded_by_id → v3
 └── v3  status=CURRENT     superseded_by_id = NULL
```

Each version is a **separate row with its own Cloudinary asset**. Nothing is mutated but the
predecessor's `status` and `superseded_by_id`. The first version sets
`document_family_id = its own id`, so a family always has a stable identity with no separate
families table.

The chain is doubly navigable: forward through `superseded_by_id`, and backward by ordering
`(document_family_id, version DESC)`.

### 17.3 Point-in-time references

Because versions are distinct rows, other tables reference the **exact version** rather than
the family:

| Reference | Points at | Meaning |
|---|---|---|
| `consents.consent_document_id` | A specific `documents.id` | The exact ICF version this participant signed |
| `ethics_submissions.protocol_document_id` | A specific `documents.id` | The exact protocol version the committee reviewed |
| `trial_compliance.evidence_document_id` | A specific `documents.id` | The exact evidence version that satisfied the requirement |

These references never move when a new version is published. A participant who consented
under v2 still points at v2 forever, which is precisely the property that makes the consent
record trustworthy. Referencing the family instead would silently re-point every historical
consent at the newest form — rewriting what people agreed to.

### 17.4 Publishing a new version

```python
async def publish_new_version(db, user, family_id, upload) -> Document:
    async with db.begin():
        current = await get_current_version(db, family_id, for_update=True)
        new_doc = await create_document(
            db, family_id=family_id,
            version=current.version + 1,
            status="PENDING_SCAN",           # not CURRENT until scanned clean
            **upload,
        )
        # Promotion to CURRENT happens after the scan completes.
        await write_audit(db, user, "UPLOAD_DOCUMENT", "document", new_doc.id,
                          old_values={"current_version": current.version},
                          new_values={"new_version": new_doc.version})
    return new_doc

# On scan CLEAN, in a second transaction:
async def promote_to_current(db, doc_id):
    async with db.begin():
        doc = await get(db, doc_id, for_update=True)
        prior = await get_current_version(db, doc.document_family_id, for_update=True)
        if prior:
            prior.status = "SUPERSEDED"
            prior.superseded_by_id = doc.id
        doc.status = "CURRENT"
        # uq_documents_one_current_per_family guarantees this even under a race
```

The partial unique index (§8.21) is what makes the promotion safe. Two concurrent promotions
both read "no current version" and both attempt the update; the index rejects the second with
a unique violation, which the service translates into a `409 Conflict`. Without it, both
succeed and the family has two current versions with no authoritative answer.

### 17.5 Lifecycle

```text
PENDING_SCAN → QUARANTINED                     (malware found — terminal)
             → DRAFT → CURRENT → SUPERSEDED    (normal path)
                              → WITHDRAWN      (published in error)
                              → ARCHIVED       (trial closed, retained)
```

No state transitions to deletion. Archived documents remain queryable and downloadable to
users with scope; they simply stop appearing in default listings (§20.5).

---

## 18. Authentication and Security

### 18.1 Transport

TLS 1.2+ terminated at Caddy, which obtains and renews certificates from Let's Encrypt
automatically. HTTP redirects to HTTPS. HSTS is set with a one-year max-age and
`includeSubDomains` once the domain is stable — deliberately not before, because HSTS is
difficult to unwind if the certificate setup is still changing.

### 18.2 Token design

| Token | Type | Lifetime | Storage | Revocable |
|---|---|---|---|---|
| Access | JWT, HS256 | **15 minutes** | `HttpOnly` cookie | No — expiry is the control |
| Refresh | Opaque 256-bit random | **14 days**, rotating | `HttpOnly` cookie, path-scoped to `/api/v1/auth/refresh` | **Yes**, via `sessions` (§8.6) |

Access token claims:

```json
{
  "sub": "8f14e45f-…",          "sid": "b2c3d4e5-…",
  "role": "RESEARCH_STAFF",     "iat": 1772000000,
  "exp": 1772000900,            "jti": "a1b2c3d4-…"
}
```

**`role` is a claim, but never the authorization source.** It renders the correct navigation
without a round trip. Every permission check re-resolves from `role_permissions` (§6.5); a
forged or stale `role` claim buys nothing.

**Scope is not in the token.** No trial IDs, no site IDs. Scope is resolved by the database
on every query (§7.3), so revoking an assignment takes effect on the next request rather
than on the next login.

**Why the refresh token is opaque rather than a JWT.** A JWT is valid until it expires and
carries no server-side state; that is exactly wrong for a credential that must be revocable
on logout, password change, or reuse detection. An opaque token is a lookup key into
`sessions`, where revocation is a column update.

### 18.3 Cookie configuration

```python
response.set_cookie("access_token",  value=jwt,
    httponly=True, secure=True, samesite="lax",  path="/",                      max_age=900)
response.set_cookie("refresh_token", value=opaque,
    httponly=True, secure=True, samesite="strict", path="/api/v1/auth/refresh", max_age=1209600)
```

| Attribute | Effect |
|---|---|
| `HttpOnly` | Unreadable by JavaScript. **An XSS defect can no longer steal the session** — it can act within the page, but it cannot exfiltrate a credential for offline reuse |
| `Secure` | Never transmitted over plain HTTP |
| `SameSite=Lax` on access | Not sent on cross-site POST, blocking the common CSRF shape while keeping normal navigation working |
| `SameSite=Strict` on refresh | Never sent cross-site at all. The refresh token is only ever needed on a first-party call |
| `path` scoping on refresh | Not transmitted on ordinary API calls. It reaches the network only at the refresh endpoint, minimising exposure |

**Tokens are never in `localStorage`.** Anything in `localStorage` is readable by any script
on the origin, which means one XSS defect anywhere becomes full account compromise. This is
the single most common authentication mistake in SPAs and it is closed structurally here.

### 18.4 Password handling

Argon2id via `argon2-cffi`, parameters `time_cost=3`, `memory_cost=65536` (64 MiB),
`parallelism=4` — OWASP's current recommendation. Memory-hardness is the point: an attacker
with GPUs gains far less than against bcrypt or PBKDF2, because memory bandwidth does not
parallelise the way hashing does.

Policy: minimum 12 characters, checked against a list of common passwords, no composition
rules (which push users toward `Password1!` and nothing better), no forced expiry (which
pushes users toward incrementing a digit). `password_changed_at` is recorded; changing a
password revokes every session in every family (§18.6).

### 18.5 Login flow

```text
POST /api/v1/auth/login {email, password}
  → rate limit: 5 attempts / 15 min per IP + per email (§18.10)
  → look up user by email (constant-time compare on a dummy hash if absent —
     the response time must not reveal whether an account exists)
  → verify Argon2id hash
  → if user.status <> 'ACTIVE' → 401, generic message
  → if mfa_enabled → 200 with mfa_required=true, partial token; await TOTP
  → create sessions row (family_id = new uuid)
  → set access + refresh cookies
  → reset failed_login_count, set last_login_at
  → audit LOGIN (outcome SUCCESS)

On failure: increment failed_login_count; at 10 → status = 'LOCKED';
            audit LOGIN (outcome FAILURE). Response is always
            "Invalid email or password" regardless of cause.
```

### 18.6 Refresh rotation and reuse detection

Every refresh issues a **new** refresh token and revokes the one presented. Both belong to
the same `family_id`.

```text
POST /api/v1/auth/refresh  (refresh cookie)
  → hash the presented token, look up sessions by token_hash
  → not found                    → 401
  → expired                      → 401
  → revoked_at IS NOT NULL       → ⚠ REUSE DETECTED
        → revoke EVERY session in that family_id
        → audit TOKEN_REUSE_DETECTED, alert via Sentry
        → 401, user must re-authenticate
  → valid → revoke this row (reason ROTATED), insert successor in the same family
          → issue new access + refresh cookies
```

**Why family-wide revocation on reuse.** A refresh token is single-use. If one is presented
twice, either the legitimate client retried, or a stolen token is being replayed — and the
server cannot distinguish the two. Revoking the whole family logs out both the attacker and
the legitimate user, who re-authenticates. Choosing safety over convenience is correct here:
the alternative leaves a thief with a valid session.

**The session lookaside cache (§12.1) and revocation.** Per-request validity checks read a
Redis lookaside with a 300-second TTL, so an ordinary logout can leave an access token
working for up to 5 minutes — bounded by the 15-minute access token lifetime regardless. For
the cases where that window is unacceptable — reuse detection, admin revocation, password
change — the Redis key is **deleted explicitly** at revocation time, closing the window to
zero. The TTL covers only the ordinary case; the security-critical paths do not rely on it.

### 18.7 MFA

TOTP (RFC 6238), 30-second step, 6 digits, ±1 window tolerance for clock drift.
`users.mfa_secret` is encrypted at rest with a key from `MFA_ENCRYPTION_KEY` (§25.3), so a
database dump alone does not yield working MFA secrets.

Ten single-use recovery codes are generated at enrolment, shown once, and stored Argon2id-hashed.

MFA is **mandatory** for `SYSTEM_ADMIN`, `SAFETY_OFFICER`, and `REGULATORY_OFFICER` — the
roles whose compromise has the widest blast radius — and optional for the rest in the MVP.

### 18.8 Session management and logout

| Action | Effect |
|---|---|
| Logout | Revoke the current session (`LOGOUT`), delete its Redis key, clear both cookies |
| Logout everywhere | Revoke every session for the user, delete every Redis key |
| Password change | Revoke all sessions (`PASSWORD_CHANGE`) — a password change must invalidate anything obtained with the old one |
| Admin deactivation | Revoke all sessions (`ADMIN_REVOKE`), set `users.status = 'INACTIVE'` |
| Reuse detection | Revoke the family (`REUSE_DETECTED`) |

Users see their active sessions (device, IP, last used) at `/profile` and can revoke any of
them individually.

### 18.9 Password reset

```text
POST /api/v1/auth/password-reset-request {email}
  → ALWAYS respond 200 with the same message, whether or not the account exists
     (a differing response is an account enumeration oracle)
  → if it exists: generate a 256-bit token, store only its SHA-256 hash with a
     30-minute expiry, email a single-use link
  → rate limit: 3 requests / hour / email

POST /api/v1/auth/password-reset {token, new_password}
  → hash and look up; reject if expired or already used
  → validate the new password against policy
  → update password_hash, set password_changed_at
  → revoke ALL sessions
  → audit PASSWORD_RESET, mark the token used
```

### 18.10 Rate limiting

Two layers: Caddy for coarse per-IP limits, and `slowapi` in FastAPI for per-endpoint,
per-identity limits backed by Redis.

| Endpoint | Limit |
|---|---|
| `POST /auth/login` | 5 / 15 min per IP **and** per email |
| `POST /auth/password-reset-request` | 3 / hour per email |
| `POST /auth/refresh` | 30 / hour per session |
| `POST /documents` (upload) | 20 / hour per user |
| Read endpoints | 300 / min per user |
| Write endpoints | 60 / min per user |
| `GET /gis/*` | 120 / min per user — spatial aggregation is expensive |

Limiting login per email as well as per IP matters: per-IP alone lets a distributed attempt
spread across addresses while hammering one account.

### 18.11 CORS

The Next.js origin only, explicitly listed — never `*`, and never reflected from the
`Origin` header. `allow_credentials=True` is required for cookie auth, and the CORS
specification forbids combining it with a wildcard origin, which is a useful forcing function.

```python
app.add_middleware(CORSMiddleware,
    allow_origins=[settings.FRONTEND_ORIGIN],
    allow_credentials=True,
    allow_methods=["GET","POST","PATCH","DELETE"],
    allow_headers=["Content-Type","X-CSRF-Token","If-Match"],
    expose_headers=["ETag","X-Request-ID"],
    max_age=600)
```

### 18.12 CSRF

Cookie authentication means the browser attaches credentials to cross-site requests
automatically, so CSRF protection is mandatory. Three layers:

1. **`SameSite`** — `Lax` on access, `Strict` on refresh (§18.3). This blocks the classic
   cross-site form post on its own.
2. **Double-submit token** — on login the server sets a non-`HttpOnly` `csrf_token` cookie;
   the frontend echoes it in an `X-CSRF-Token` header on every state-changing request. The
   server requires the two to match. An attacker's page can cause the cookie to be *sent* but
   cannot *read* it to construct the header.
3. **Origin validation** — `Origin`/`Referer` checked against the allowlist on every
   mutation.

The CSRF cookie is deliberately readable by JavaScript. It is not a credential — it is a
value the legitimate first-party page must be able to read and echo. Its security comes from
the same-origin policy preventing a foreign page from reading it.

### 18.13 Input validation

Every request body, query parameter, and path parameter is a Pydantic model. Validation
happens at the boundary, before any service code runs:

- Types are coerced and checked; a `uuid` path parameter that is not a UUID never reaches a
  handler.
- `constr`, `conint`, and `Literal` enforce lengths, ranges, and enumerations.
- Extra fields are **rejected**, not ignored (`model_config = ConfigDict(extra="forbid")`) —
  a typo'd field name fails loudly rather than being silently dropped, which in a clinical
  data form is the difference between an error and a missing measurement.
- Clinical values carry domain validators: an observation's numeric value against plausible
  physiological ranges, dates against `CHECK` constraint logic mirrored in the schema.

### 18.14 Injection prevention

| Vector | Control |
|---|---|
| SQL injection | SQLAlchemy parameterised queries exclusively. **No f-string or `%` interpolation into SQL, ever** — a lint rule blocks `text()` containing an f-string |
| RLS session variable | Set via bound `set_config(...)`, never interpolated (§7.3) |
| Search/filter parameters | Whitelisted sort columns and filter fields; an unrecognised value is a `422`, not a passthrough |
| NoSQL / command injection | No shell execution from request data anywhere |
| Path traversal | Filenames sanitised; storage handles are server-generated (§16.5) |
| XSS | React escapes by default; `dangerouslySetInnerHTML` is banned by lint rule; user content is never rendered as HTML |
| SSRF | The backend makes no outbound request to a user-supplied URL |

### 18.15 Security headers

Set at Caddy for every response:

```text
Strict-Transport-Security: max-age=31536000; includeSubDomains
X-Content-Type-Options:    nosniff
X-Frame-Options:           DENY
Referrer-Policy:           strict-origin-when-cross-origin
Permissions-Policy:        geolocation=(), microphone=(), camera=()
Content-Security-Policy:   default-src 'self';
                           img-src 'self' data: https://*.tile.openstreetmap.org;
                           connect-src 'self';
                           frame-ancestors 'none';
                           object-src 'none';
                           base-uri 'self'
```

The CSP `img-src` allowance for OpenStreetMap tiles is the map's only external dependency —
worth noting explicitly, because a CSP that has to be loosened later usually gets loosened
too far.

### 18.16 Secrets management

- Secrets come from environment variables, never from committed files (§25).
- `.env` files are in `.gitignore`; only `.env.example` with placeholder values is committed.
- `JWT_SECRET` and `JWT_REFRESH_SECRET` are independent 256-bit random values. Reusing one
  for both means a token confusion bug becomes a privilege escalation.
- Production secrets live in the deployment host's secret store, injected at container start.
- Rotation: JWT secrets support a previous-key grace window so rotation does not log everyone
  out mid-demo.
- `gitleaks` runs in CI; a commit containing a credential-shaped string fails the build.

### 18.17 Error handling

Clients receive a stable error shape and never an internal detail:

```json
{ "error": { "code": "PARTICIPANT_NOT_FOUND",
             "message": "Participant not found",
             "request_id": "a1b2c3d4-…" } }
```

- Stack traces, SQL, and internal paths go to logs and Sentry — never to a response.
- `404`, not `403`, for a row outside the caller's RLS scope (§6.4). A `403` would confirm
  the record exists.
- The `request_id` correlates a user's report with the server-side log and audit entries
  (§30.3) without exposing anything.
- Validation errors are the exception: they name the offending field, because the user needs
  to know what to fix, and the field names are already known to them.

### 18.18 Security control summary

| Threat | Control | Section |
|---|---|---|
| Credential theft via XSS | `HttpOnly` cookies, CSP, React escaping | §18.3, §18.15 |
| CSRF | `SameSite`, double-submit token, Origin check | §18.12 |
| Brute force | Rate limiting per IP and per email, account lockout | §18.10 |
| Token replay | 15-min access tokens, rotation with reuse detection | §18.6 |
| Privilege escalation | Permission checks re-resolved server-side; role claim never trusted | §6.5, §18.2 |
| **Horizontal access (ID tampering)** | **RLS — the primary control** | §7.2 |
| SQL injection | Parameterised queries, lint enforcement | §18.14 |
| Malicious upload | Allowlist, content sniffing, ClamAV, DB constraint | §16.5, §16.6 |
| Account enumeration | Identical responses and timing on login and reset | §18.5, §18.9 |
| Session persistence after revocation | Server-side `sessions`, explicit cache deletion | §18.6, §18.8 |
| Data exfiltration via aggregates | k-anonymity suppression | §11.4 |
| Repudiation | Append-only audit log | §19 |

---

## 19. Audit Logging

### 19.1 Design

`audit_logs` (§8.24) is **append-only, enforced at the database level** (§7.8): `UPDATE`,
`DELETE`, and `TRUNCATE` are revoked from every application role and a trigger raises on
either. There is no application code path that can modify an audit record, because there is
no database privilege that would let one succeed.

Records are written **inside the transaction that makes the change** (§2.4). An audit entry
cannot describe a change that rolled back, and a change cannot commit if its audit write
failed. The log and the data are consistent by construction rather than by a background
reconciliation.

### 19.2 Event catalogue

| Category | Actions |
|---|---|
| **Authentication** | `LOGIN`, `LOGIN_FAILED`, `LOGOUT`, `TOKEN_REFRESH`, `TOKEN_REUSE_DETECTED`, `PASSWORD_RESET`, `MFA_ENROLLED`, `ACCOUNT_LOCKED` |
| **Access control** | `CREATE_USER`, `UPDATE_USER`, `DEACTIVATE_USER`, `CHANGE_ROLE`, `GRANT_PERMISSION`, `REVOKE_PERMISSION`, `ASSIGN_TRIAL_STAFF`, `REMOVE_TRIAL_STAFF` |
| **Trial structure** | `CREATE_TRIAL`, `UPDATE_TRIAL`, `CHANGE_TRIAL_STATUS`, `CREATE_SITE`, `UPDATE_SITE`, `ACTIVATE_SITE` |
| **Participants** | `CREATE_PARTICIPANT`, `UPDATE_PARTICIPANT`, `WITHDRAW_PARTICIPANT`, `READ_PARTICIPANT_IDENTITY` |
| **Consent** | `CREATE_CONSENT`, `WITHDRAW_CONSENT`, `RE_CONSENT` |
| **Clinical data** | `CREATE_VISIT`, `UPDATE_VISIT`, `CREATE_OBSERVATION`, `UPDATE_OBSERVATION`, `CREATE_MEDICATION`, `UPDATE_MEDICATION` |
| **Safety** | `CREATE_ADVERSE_EVENT`, `UPDATE_ADVERSE_EVENT`, `REVIEW_ADVERSE_EVENT`, `ESCALATE_ADVERSE_EVENT`, `REPORT_TO_AUTHORITY` |
| **Ethics** | `SUBMIT_ETHICS`, `REVIEW_ETHICS`, `APPROVE_ETHICS`, `REJECT_ETHICS`, `WITHDRAW_ETHICS_SUBMISSION` |
| **Compliance** | `UPDATE_COMPLIANCE`, `VERIFY_COMPLIANCE`, `DEFINE_REQUIREMENT` |
| **Documents** | `UPLOAD_DOCUMENT`, `DOWNLOAD_DOCUMENT`, `SUPERSEDE_DOCUMENT`, `ARCHIVE_DOCUMENT`, `QUARANTINE_DOCUMENT` |

### 19.3 Reads that are audited

Writes are audited universally. **Reads are audited in exactly three cases**, where the read
itself is the sensitive act:

| Read | Why |
|---|---|
| `READ_PARTICIPANT_IDENTITY` | Re-identifying a participant is the single most sensitive operation available (§8.12). Who did it and when must be answerable |
| `DOWNLOAD_DOCUMENT` | "Who accessed protocol v2, and when" is a standard inspection question |
| `audit:read` on another user's records | Reading the audit trail is itself auditable |

Auditing every read would multiply write volume by an order of magnitude and bury the
consequential events in noise. Auditing these three keeps the log useful.

### 19.4 Why audit records are immutable

An audit log exists to answer questions after something went wrong, and its value rests
entirely on the assumption that it was not edited afterwards. If a user with sufficient
privilege can alter it, then every entry is only as trustworthy as the least trustworthy
account that could have reached it — and the entries most worth altering are precisely the
ones an attacker would target first.

So the guarantee is structural rather than administrative:

| Layer | Control |
|---|---|
| Grants | `REVOKE UPDATE, DELETE, TRUNCATE ... FROM ctms_app, ctms_worker` |
| Trigger | `BEFORE UPDATE OR DELETE` raises unconditionally |
| Permissions | No `audit:update` or `audit:delete` permission exists in the catalogue (§6.3) |
| API | No endpoint accepts a mutation on `/audit` |
| Foreign key | `user_id ON DELETE SET NULL` — deleting a user cannot erase what they did |

"Admins cannot edit audit logs" is a policy. A revoked grant plus a trigger is a fact, and it
holds even for an attacker who reaches the application with admin credentials.

### 19.5 PHI redaction in `old_values` / `new_values`

The audit log records *what changed*, not *the clinical content*. Storing full clinical
payloads would duplicate PHI into a table many roles can read for compliance purposes.

The redaction rule, applied by a shared `redact()` helper before serialisation:

| Field class | Recorded as |
|---|---|
| Status, dates, codes, identifiers, enumerations | Full value |
| Clinical values (`value_numeric`, `value_text`, AE `description`) | `"<redacted>"` with the field name preserved |
| Anything from `participant_identities` | Field name only, never a value |
| Free-text clinical narrative | `"<redacted>"` |

The result records that "observation `VITALS_SBP` on visit X was amended by user Y at
time Z with reason R" — everything an inspection needs to trace the change — without copying
the blood pressure reading into a second table. The prior clinical value remains recoverable
from the versioned clinical record itself.

### 19.6 Querying the trail

| Question | Query |
|---|---|
| What happened to this participant? | `WHERE entity_type='participant' AND entity_id=? ORDER BY occurred_at DESC` |
| What did this user do? | `WHERE user_id=? AND occurred_at >= ?` |
| Everything on this trial | `WHERE trial_id=? ORDER BY occurred_at DESC` |
| Failed access attempts | `WHERE outcome IN ('FAILURE','DENIED') AND occurred_at >= ?` |
| Who read identities? | `WHERE action='READ_PARTICIPANT_IDENTITY'` |
| Full request trace | `WHERE request_id=?` — joins to application logs and Sentry (§30.3) |

Each is served by a dedicated index (§8.24). The failure query uses a partial index, so the
security dashboard stays fast even as the table grows.

---

## 20. Data Lifecycle Management

### 20.1 No hard deletes on clinical records

Deleting clinical, safety, ethics, or consent data destroys the regulatory record. Every such
entity moves through **status transitions**; rows persist.

`ON DELETE RESTRICT` on clinical foreign keys means the database refuses a deletion that
would orphan dependent data, so even a mistaken `DELETE` in a migration or a console fails
rather than cascading.

**What may be hard-deleted:** expired `sessions` rows (a cleanup job), Redis entries, and
orphaned Cloudinary assets (§16.7). Nothing clinical.

### 20.2 Trial lifecycle

```text
DRAFT → PENDING_ETHICS → APPROVED → ACTIVE → COMPLETED → ARCHIVED
                             ↓         ↓
                         REJECTED  SUSPENDED → ACTIVE
                                          ↓
                                    TERMINATED → ARCHIVED
```

| Status | Meaning | Enrolment | Data entry |
|---|---|---|---|
| `DRAFT` | Being set up | No | No |
| `PENDING_ETHICS` | Submitted, awaiting IEC | No | No |
| `APPROVED` | Ethics approved, not yet started | No | No |
| `ACTIVE` | Running | **Yes** | **Yes** |
| `SUSPENDED` | Temporarily halted (often a safety signal) | No | Yes — follow-up on enrolled participants continues |
| `COMPLETED` | Finished normally | No | Corrections only |
| `TERMINATED` | Stopped early | No | Corrections only |
| `ARCHIVED` | Closed and retained | No | No — read-only |

`SUSPENDED` allowing data entry is deliberate: suspension stops *new* enrolment while
participants already enrolled must still be followed up safely.

### 20.3 Participant lifecycle

```text
SCREENING → SCREEN_FAILED                            (terminal)
          → ENROLLED → ACTIVE → COMPLETED            (terminal)
                             → WITHDRAWN             (terminal, reason required)
                             → LOST_TO_FOLLOWUP      (terminal)
```

A withdrawn participant's clinical data **is retained**. Withdrawal ends participation; it
does not erase the data collected while consent was valid — which is both the regulatory
expectation and what the consent form states. `WITHDRAWN` requires `withdrawal_date`, enforced
by `CHECK` (§8.11).

### 20.4 Other lifecycles

| Entity | States |
|---|---|
| `trial_sites` | `PLANNED → ACTIVATED → ENROLLING → CLOSED_TO_ENROLLMENT → COMPLETED`; `SUSPENDED` from any active state |
| `consents` | `ACTIVE → SUPERSEDED` (re-consent) or `→ WITHDRAWN` |
| `adverse_events` | `REPORTED → UNDER_REVIEW → REVIEWED → CLOSED` |
| `ethics_submissions` | `SUBMITTED → UNDER_REVIEW → APPROVED / APPROVED_WITH_CONDITIONS / REJECTED / DEFERRED`; `WITHDRAWN` from any pre-decision state |
| `documents` | §17.5 |
| `users` | `ACTIVE ↔ INACTIVE`; `LOCKED` on repeated failures, cleared by admin or reset |

### 20.5 Archival

Archival changes visibility, never existence:

- Archived rows are excluded from default list queries by an explicit `status <> 'ARCHIVED'`
  predicate in the query — **not** by RLS. RLS answers authorisation; status answers
  relevance, and conflating them makes legitimate archived-record access impossible to
  express (§8.1).
- An explicit `?include_archived=true` retrieves them, subject to unchanged permissions.
- Archived records remain fully readable to users with scope, remain in the audit trail, and
  remain downloadable in the case of documents.

Archiving a trial cascades in *status* only: its sites, participants, and documents are
marked archived. No row is removed.

### 20.6 Retention

Clinical trial records are subject to retention periods set by the New Drugs and Clinical
Trials Rules 2019, ICMR guidance, ICH-GCP, and institutional SOPs — commonly measured in
years after trial completion, and in some cases considerably longer.

**This platform does not implement automated deletion, and that is a deliberate choice.**
Retention policy is jurisdictional, institution-specific, and consequential; a hackathon MVP
guessing at it and deleting records on a schedule would be worse than not implementing it at
all. The architecture instead ensures nothing is lost:

- No hard deletes on clinical data (§20.1)
- `ARCHIVED` status preserves records indefinitely (§20.5)
- The audit trail is append-only and never pruned (§19)
- Documents keep every version (§17)

When a real deployment defines its retention policy, the mechanism to add is a scheduled job
reading a `retention_policies` configuration — a contained addition to the existing lifecycle
model, requiring no schema redesign.

---

## 21. API Architecture

### 21.1 Conventions

| Aspect | Convention |
|---|---|
| Base path | `/api/v1` — versioned from the first release, because retrofitting a version prefix breaks every client |
| Format | JSON; GeoJSON for spatial responses |
| Authentication | `HttpOnly` cookies (§18.3); `X-CSRF-Token` on mutations |
| Pagination | Cursor-based: `?cursor=&limit=` (default 50, max 200). Cursor, not offset — offset pagination skips or repeats rows when the underlying data changes between pages, which on a clinical list is a silently missed record |
| Filtering | Whitelisted fields only; unknown filters are `422`, not ignored |
| Sorting | `?sort=field:asc\|desc` from a per-resource whitelist |
| Concurrency | `ETag` on reads, `If-Match` required on updates (§14.4) |
| Errors | `{error: {code, message, request_id}}` (§18.17) |
| Status codes | `200` ok · `201` created · `204` no content · `400` malformed · `401` unauthenticated · `403` lacks permission · `404` not found or out of scope · `409` conflict · `422` validation · `429` rate limited |

### 21.2 Domain map

```text
/api/v1/auth              Authentication, session lifecycle
/api/v1/users             User administration
/api/v1/roles             Role definitions
/api/v1/permissions       Permission catalogue

/api/v1/institutions      Institutions (incl. geography)
/api/v1/trials            Trials
/api/v1/sites             Trial sites
/api/v1/trial-staff       Staff assignments — the scope table

/api/v1/participants      Pseudonymised participants
/api/v1/consents          Informed consent
/api/v1/visits            Protocol visits
/api/v1/observations      Clinical observations
/api/v1/medications       Concomitant medications

/api/v1/adverse-events    AE reporting
/api/v1/safety            Safety review and reports

/api/v1/ethics            Ethics submissions and reviews
/api/v1/compliance        Requirements and per-trial status

/api/v1/documents         Upload, download, versioning

/api/v1/gis               Geographic data (§10.5)

/api/v1/analytics         Dashboard and trial analytics
/api/v1/audit             Audit trail (read-only)
```

### 21.3 Example endpoints

**Auth** — `/api/v1/auth`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| POST | `/login` | Authenticate, set cookies | Public |
| POST | `/mfa/verify` | Complete TOTP challenge | Partial token |
| POST | `/refresh` | Rotate tokens | Valid refresh cookie |
| POST | `/logout` | Revoke current session | Authenticated |
| POST | `/logout-all` | Revoke every session | Authenticated |
| GET | `/me` | Current user, role, permissions, assignments | Authenticated |
| GET | `/sessions` | List own active sessions | Authenticated |
| DELETE | `/sessions/{id}` | Revoke one session | Authenticated (own) |
| POST | `/password-reset-request` | Begin reset | Public, rate limited |
| POST | `/password-reset` | Complete reset | Valid token |

**Trials** — `/api/v1/trials`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `` | List, RLS-scoped, filter by status/phase/institution | `trial:read` |
| POST | `` | Create | `trial:create` |
| GET | `/{id}` | Detail | `trial:read` + scope |
| PATCH | `/{id}` | Update (`If-Match` required) | `trial:update` + scope |
| POST | `/{id}/status` | Transition status | `trial:update` + scope |
| GET | `/{id}/sites` | Sites on this trial | `site:read` + scope |
| GET | `/{id}/staff` | Staff assignments | `trial_staff:read` + scope |
| GET | `/{id}/summary` | Enrolment, safety, compliance rollup | `trial:read` + scope |

**Participants** — `/api/v1/participants`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `` | List, RLS-scoped to trial/site | `participant:read` |
| POST | `` | Enrol — transactional (§14.6) | `participant:create` |
| GET | `/{id}` | Detail, pseudonymised | `participant:read` + scope |
| PATCH | `/{id}` | Update (`If-Match`) | `participant:update` + scope |
| POST | `/{id}/withdraw` | Withdraw with reason | `participant:withdraw` + scope |
| GET | `/{id}/timeline` | Visits, observations, meds, AEs merged | `participant:read` + scope |
| **GET** | **`/{id}/identity`** | **Re-identify — audited read (§19.3)** | **`participant_identity:read` + scope** |

**Clinical data** — `/api/v1/visits`, `/observations`, `/medications`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/visits?date=&status=&site_id=` | Visit list; drives "today's visits" | `visit:read` |
| POST | `/visits` | Schedule | `visit:create` |
| PATCH | `/visits/{id}` | Update status/actual date | `visit:update` |
| GET | `/visits/{id}/observations` | Observations at a visit | `observation:read` |
| POST | `/observations` | Record — idempotent on `(visit_id, observation_code)` | `observation:create` |
| PATCH | `/observations/{id}` | Amend — reason required | `observation:update` |
| GET/POST | `/medications` | Concomitant medications | `medication:read` / `:create` |

**Safety** — `/api/v1/adverse-events`, `/api/v1/safety`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/adverse-events?seriousness=&status=&trial_id=` | List | `adverse_event:read` |
| POST | `/adverse-events` | Report | `adverse_event:create` |
| PATCH | `/adverse-events/{id}` | Update | `adverse_event:update` |
| POST | `/adverse-events/{id}/review` | Adjudicate | `adverse_event:review` |
| GET | `/safety/pending` | Review queue | `adverse_event:review` |
| GET | `/safety/trends?trial_id=&interval=` | Aggregate trends | `safety_report:read` |
| POST | `/safety/reports` | Generate a report | `safety_report:create` |

**Ethics** — `/api/v1/ethics`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/submissions` | Institution-scoped list | `ethics:read` |
| POST | `/submissions` | Submit protocol or amendment | `ethics:submit` |
| GET | `/submissions/{id}` | Detail with documents | `ethics:read` + scope |
| POST | `/submissions/{id}/reviews` | Record a review | `ethics:review` |
| POST | `/submissions/{id}/decision` | Final committee decision | `ethics:decide` |
| GET | `/pending` | This member's review queue | `ethics:review` |

**Compliance** — `/api/v1/compliance`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/requirements` | Catalogue | `compliance:read` |
| POST | `/requirements` | Define | `compliance:define` |
| GET | `/trials/{trial_id}` | Per-trial status | `compliance:read` + scope |
| PATCH | `/trials/{trial_id}/items/{id}` | Update an item | `compliance:update` |
| POST | `/trials/{trial_id}/items/{id}/verify` | Regulator verification | `regulatory:report` |

**Documents** — `/api/v1/documents`

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `?trial_id=&type=&status=` | List metadata | `document:read` |
| POST | `` | Upload (multipart) | `document:upload` |
| GET | `/{id}` | Metadata | `document:read` + scope |
| GET | `/{id}/download` | Signed-URL redirect — audited | `document:read` + scope |
| POST | `/{id}/new-version` | Publish a new version (§17.4) | `document:supersede` |
| GET | `/families/{family_id}/versions` | Full version history | `document:read` |
| POST | `/{id}/archive` | Archive | `document:archive` |

**Analytics and audit**

| Method | Path | Purpose | Permission |
|---|---|---|---|
| GET | `/analytics/dashboard` | Role-appropriate dashboard payload | Authenticated |
| GET | `/analytics/trials/{id}/enrollment` | Enrolment over time | `trial:read` + scope |
| GET | `/analytics/trials/{id}/safety` | Aggregate safety | `trial:read` + scope |
| GET | `/analytics/trials/{id}/compliance` | Compliance breakdown | `compliance:read` + scope |
| GET | `/audit?entity_type=&entity_id=&user_id=&from=&to=` | Audit trail — **read-only, no mutations exist** | `audit:read` + scope |

### 21.4 One dashboard endpoint, seven shapes

`GET /api/v1/analytics/dashboard` returns a payload shaped by the caller's role, rather than
seven role-specific endpoints. The role is known from the session, so passing it would be
both redundant and spoofable-looking; the response schema is a discriminated union on
`dashboard_type`, so the frontend gets a typed payload per role from the generated OpenAPI
types (§3.1). Adding a role means adding a variant, not a route.

---

## 22. Frontend Sitemap

### 22.1 Route group structure

Next.js route groups map role sections onto shared layouts without polluting URLs:

```text
app/
├── (public)/          → no auth, marketing layout
├── (auth)/            → login, password reset
├── (dashboard)/       → authenticated shell: nav, role switcher, notifications
│   ├── admin/         → SYSTEM_ADMIN
│   ├── investigator/  → PRINCIPAL_INVESTIGATOR
│   ├── coordinator/   → TRIAL_COORDINATOR
│   ├── research/      → RESEARCH_STAFF
│   ├── ethics/        → ETHICS_MEMBER
│   ├── safety/        → SAFETY_OFFICER
│   ├── regulator/     → REGULATORY_OFFICER
│   └── (global)/      → gis, notifications, profile — every role
```

### 22.2 Public

| Route | Purpose |
|---|---|
| `/` | Landing page — problem statement, platform overview |
| `/about` | Project and team |
| `/features` | Capability overview |
| `/contact` | Contact form |
| `/login` | Authentication |
| `/privacy` | Privacy policy — how participant data is handled |
| `/terms` | Terms of use |

### 22.3 Role sections

| Section | Routes |
|---|---|
| **Admin** | `/admin` · `/users` · `/roles` · `/institutions` · `/trials` · `/sites` · `/audit` · `/settings` |
| **Investigator** | `/investigator` · `/trials` · `/participants` · `/safety` · `/ethics` · `/compliance` · `/gis` · `/analytics` |
| **Coordinator** | `/coordinator` · `/trials` · `/participants` · `/visits` · `/clinical-data` · `/documents` · `/adverse-events` · `/compliance` |
| **Research** | `/research` · `/participants` · `/visits` · `/clinical-data` · `/adverse-events` · `/documents` |
| **Ethics** | `/ethics` · `/pending-reviews` · `/reviews` · `/documents` · `/history` |
| **Safety** | `/safety` · `/adverse-events` · `/reviews` · `/monitoring` · `/reports` |
| **Regulator** | `/regulator` · `/trials` · `/institutions` · `/sites` · `/gis` · `/compliance` · `/safety` · `/regulatory` · `/reports` |

### 22.4 Global

| Route | Purpose |
|---|---|
| `/gis` | The shared geographic interface — every authenticated role (§11) |
| `/notifications` | Derived work queue — no table backs it (§8.25) |
| `/profile` | Own details, password, MFA, active sessions (§18.8) |

### 22.5 Shared versus role-specific

The route tree looks duplicative — `/investigator/participants`, `/coordinator/participants`,
and `/research/participants` all exist. They are **not** three implementations. Each is a thin
page composing the same feature components from `features/participants/`, differing only in
which columns and actions are rendered.

| Layer | Shared? |
|---|---|
| Feature components (`features/participants/ParticipantTable`) | **Shared** — one implementation |
| Data fetching hooks | **Shared** — one typed API client |
| Page routes | Role-specific — different composition, different defaults |
| Available actions | Driven by the permission set from `/auth/me`, not hardcoded per route |
| GIS map component | **Shared** — one implementation, drill-down gated by permission (§11.3) |

**Why separate routes at all, if the components are shared?** Two reasons. Users bookmark and
share URLs, and a URL that renders differently depending on who opens it is confusing to talk
about. And role-specific routes let each section set its own defaults — the Coordinator's
participant list defaults to their active site and sorts by next visit, the PI's spans all
sites and sorts by enrolment date — without a tangle of conditionals in one page.

### 22.6 Route protection

Three layers, mirroring §2.3:

1. **Middleware** — checks the session cookie and redirects unauthenticated requests to
   `/login` before any page renders.
2. **Layout guard** — each role layout verifies the session's role matches the section and
   redirects to the correct dashboard otherwise.
3. **The API** — the only layer that actually matters. A user who reaches
   `/regulator/trials` by typing the URL sees the page shell and then empty data, because
   RBAC and RLS return nothing (§6.4).

Layers 1 and 2 are usability. Layer 3 is security.

### 22.7 The notifications page

`/notifications` is a **derived view**, computed from existing tables (§8.25):

| Notification | Derived from |
|---|---|
| Pending ethics reviews | `ethics_submissions` where status is pre-decision and institution matches |
| Unreviewed adverse events | `adverse_events` where `status IN ('REPORTED','UNDER_REVIEW')` |
| Overdue visits | `visits` where `scheduled_date < today AND status = 'SCHEDULED'` |
| Expiring ethics approvals | `ethics_submissions` where `approval_valid_until` within 30 days |
| Overdue compliance items | `trial_compliance` where `due_date < today` and status is incomplete |
| Documents pending scan | `documents` where `scan_status = 'PENDING'` beyond a threshold |

Each is a query behind the partial index that already exists for it (§8.14, §8.17, §8.23), and
each is RLS-scoped automatically — a coordinator sees only their sites' overdue visits without
any extra filtering.

---

## 23. Dashboard Architecture

Every dashboard answers one question: **what needs my attention right now?** Not "here is
everything we could count." Cards link directly into the work they describe.

### 23.1 System Admin — platform health

| Widget | Shows | Source |
|---|---|---|
| User summary | Total, active, locked; new this week | `users` |
| Institutions | Count by type and state | `institutions` |
| Active trials | By status and phase | `trials` (partial index) |
| Sites | Total, activated, enrolling | `trial_sites` |
| System activity | Audit events per hour, 24 h sparkline | `audit_logs` |
| **Security alerts** | Failed logins, lockouts, token reuse, denied access | `audit_logs` partial index on `outcome <> 'SUCCESS'` |
| Background jobs | Celery queue depth, failures | Celery inspection |
| Storage | Document count and total bytes | `documents` |

**No clinical metrics.** Enrolment and safety counts are absent by design (§5.1).

### 23.2 Principal Investigator — trial management

| Widget | Shows |
|---|---|
| Enrolment progress | Current vs target per trial, with a projection line |
| Enrolment by site | Bar chart — identifies under-recruiting sites |
| Participant status | Active / completed / withdrawn / screen-failed breakdown |
| Site status | Activation and enrolment state per site |
| **Safety summary** | AE count, serious count, unreviewed — flagged when serious events are outstanding |
| Ethics status | Submissions by state, approvals expiring within 60 days |
| Compliance | Percentage complete, overdue items listed |
| Recent activity | Last 20 audit events across their trials |

### 23.3 Trial Coordinator — operational management

The most operational dashboard; today-first.

| Widget | Shows | Index |
|---|---|---|
| **Today's visits** | Scheduled today at their sites, with participant subject codes | `ix_visits_scheduled` |
| Upcoming visits | Next 7 days |  |
| **Missed visits** | Past scheduled, not completed — the escalation queue | `ix_visits_scheduled` |
| Out-of-window visits | Completed outside the protocol window — deviations |  |
| Data entry queue | Completed visits with missing observations |  |
| Participants | Active count, enrolment this month |  |
| Documents | Pending upload; expiring |  |
| Adverse events | Reported at their sites, awaiting review |  |
| Compliance | Overdue items for their trials |  |

### 23.4 Research Staff — daily clinical work

The narrowest and most focused dashboard. A short, actionable list.

| Widget | Shows |
|---|---|
| **Today's visits** | Assigned to them at their site, ordered by time |
| Assigned participants | Active participants at their site |
| Data entry queue | Visits they completed with observations outstanding |
| Recent observations | Their last 10 entries, for quick correction |
| **Report adverse event** | A prominent action, not a metric — AE reporting must never be hard to find |
| Site documents | Current protocol and consent form versions |

### 23.5 Ethics Member — review workflow

| Widget | Shows |
|---|---|
| **Pending submissions** | Awaiting this member's review, oldest first | 
| In-progress reviews | Started, not finalised |
| Recent decisions | Committee decisions in the last 30 days |
| Approvals expiring | Within 90 days — continuing review is due |
| Submissions by type | Initial / amendment / continuing / SAE report |
| Review history | This member's completed reviews |

**No participant or clinical widgets.** Ethical review operates on the protocol (§5.5).

### 23.6 Safety Officer — safety monitoring

| Widget | Shows |
|---|---|
| **Pending review** | Reported AEs awaiting adjudication, serious first | 
| **Serious events** | All open serious events across every trial |
| **Expedited overdue** | Expedited-reportable events with no authority submission recorded — the highest-priority item on the platform (§8.18 partial index) |
| Safety trends | AE rate over time by trial, with a comparison line |
| By trial | AE counts and rates, ranked |
| By site | Aggregate safety indicators, k-suppressed |
| Severity distribution | Mild / moderate / severe |
| Causality distribution | Unrelated through definite |

### 23.7 Regulatory Officer — oversight

| Widget | Shows |
|---|---|
| **National overview** | Total trials, institutions, sites, aggregate enrolment |
| Trials by phase and status | Portfolio composition |
| **Compliance overview** | Percentage compliant across all trials; non-compliant trials named |
| Overdue compliance | Items past due, by trial |
| Aggregate safety | Serious event counts by trial, k-suppressed |
| Ethics coverage | Trials with a current approval vs without — a hard compliance gate |
| **GIS panel** | Embedded national map, compliance-coloured (§11.5) |
| Regulatory queue | Submissions awaiting action |

### 23.8 Implementation

All seven are served by `GET /api/v1/analytics/dashboard` (§21.4), returning a discriminated
union on `dashboard_type`. Each payload is cached under a scope-fingerprinted key with a
60–120 second TTL (§12.1, §12.4), so a dashboard refresh does not re-run a dozen aggregate
queries. Widgets render from one payload — never one request per card.

---

## 24. Folder Structure

```text
sih26046/
│
├── frontend/
│   ├── app/
│   │   ├── (public)/            # landing, about, features, contact, privacy, terms
│   │   ├── (auth)/              # login, password reset
│   │   ├── (dashboard)/
│   │   │   ├── layout.tsx       # authenticated shell: nav, notifications, role context
│   │   │   ├── admin/  investigator/  coordinator/  research/
│   │   │   ├── ethics/ safety/        regulator/
│   │   │   └── (global)/        # gis, notifications, profile
│   │   ├── layout.tsx
│   │   └── middleware.ts        # session check, redirect (§22.6)
│   │
│   ├── components/
│   │   ├── ui/                  # shadcn/ui primitives
│   │   ├── layout/              # shell, sidebar, header, breadcrumbs
│   │   └── shared/              # DataTable, StatCard, StatusBadge, EmptyState
│   │
│   ├── features/                # domain modules — the real application code
│   │   ├── auth/  trials/  sites/  participants/  clinical/
│   │   ├── safety/ ethics/ compliance/ documents/
│   │   ├── gis/                 # MapContainer, layers, popups, legend
│   │   └── analytics/           # dashboard widgets per role
│   │
│   ├── lib/
│   │   ├── api/                 # typed client, generated from OpenAPI (§3.1)
│   │   ├── auth/                # session context, permission helpers
│   │   ├── permissions.ts       # can(user, "participant:create")
│   │   └── utils/
│   │
│   ├── hooks/                   # useParticipants, usePermission, useMapBounds
│   ├── types/                   # generated API types + hand-written domain types
│   └── tests/
│
├── backend/
│   ├── app/
│   │   ├── api/v1/              # routers — one module per domain (§21.2)
│   │   │   ├── auth.py users.py roles.py institutions.py trials.py sites.py
│   │   │   ├── trial_staff.py participants.py consents.py visits.py
│   │   │   ├── observations.py medications.py adverse_events.py safety.py
│   │   │   ├── ethics.py compliance.py documents.py gis.py
│   │   │   └── analytics.py audit.py
│   │   │
│   │   ├── core/                # config, security, exceptions, logging, db session
│   │   ├── models/              # SQLAlchemy ORM — one file per aggregate
│   │   ├── schemas/             # Pydantic request/response models
│   │   ├── services/            # business logic and transactions (§14.6)
│   │   │   ├── enrollment.py safety.py ethics.py compliance.py
│   │   │   ├── documents.py gis.py analytics.py audit.py
│   │   │   └── storage/         # StorageBackend interface + Cloudinary impl (§16.9)
│   │   ├── repositories/        # query construction, keeps SQL out of services
│   │   ├── permissions/         # permission constants, require_permission dependency
│   │   ├── middleware/          # request ID, logging, rate limiting, CSRF, error handler
│   │   ├── workers/             # Celery tasks — README lists every BYPASSRLS use (§7.7)
│   │   └── main.py
│   │
│   ├── alembic/versions/        # migrations incl. RLS policies, grants, seed data
│   └── tests/
│       ├── unit/ integration/ api/
│       ├── security/            # authz, RLS bypass attempts, upload, rate limit (§27.4)
│       └── conftest.py
│
├── infrastructure/
│   ├── docker/                  # Dockerfiles: frontend, backend, worker
│   ├── postgres/                # init: PostGIS extension, roles, grants
│   ├── caddy/                   # Caddyfile: TLS, headers, rate limits
│   └── scripts/                 # seed, backup, restore
│
├── docs/
│   ├── PROJECT_ARCHITECTURE.md  # ← this document, canonical copy at repo root
│   ├── api/  runbooks/  decisions/
│
├── docker-compose.yml           # dev
├── docker-compose.prod.yml      # demo/production
├── .env.example
└── README.md
```

### 24.1 Directory responsibilities

| Directory | Owns | Must not |
|---|---|---|
| `frontend/app/` | Routing, layouts, page composition | Contain business logic — pages compose `features/` |
| `frontend/features/` | Domain UI and data hooks, shared across roles (§22.5) | Duplicate per role |
| `frontend/lib/api/` | The single typed API client | Be bypassed by ad-hoc `fetch` |
| `backend/app/api/` | HTTP concerns: routing, status codes, permission dependencies | Contain business logic or raw SQL |
| `backend/app/services/` | Business logic, transaction boundaries, audit writes | Know about HTTP |
| `backend/app/repositories/` | Query construction | Contain business rules |
| `backend/app/permissions/` | Permission constants and the `require_permission` dependency | Hardcode role names (§6.1) |
| `backend/app/workers/` | Async tasks; the only `ctms_worker` consumers | Be called synchronously from a request |
| `backend/alembic/` | Every schema change, including policies and grants | Be edited after being applied |
| `infrastructure/` | Container, database init, and proxy configuration | Contain secrets |

### 24.2 Why this layering

The `api → services → repositories` split exists so a transaction boundary has an obvious
home. Enrolment (§14.6) spans five tables and must be atomic; that belongs in
`services/enrollment.py`, where the transaction opens and closes, not scattered across a
router. Routers stay thin enough to read at a glance, which is what makes a missing
permission dependency visible in review.

The `features/` directory on the frontend is what prevents seven copies of the participant
table. It is the structural answer to §22.5.

---

## 25. Environment Management

### 25.1 Files

| File | Purpose | Committed |
|---|---|---|
| `.env.example` | Every variable, with placeholder values and comments | **Yes** — it is the contract |
| `.env.local` | Individual developer overrides | No |
| `.env.development` | Shared dev defaults for Compose | No |
| `.env.production` | Demo/production values | **No** — never |

`.gitignore` excludes `.env*` and re-includes `!.env.example`. `gitleaks` in CI catches
anything that slips past (§18.16).

### 25.2 Loading

Backend configuration is a single Pydantic `Settings` object validated at startup. **The
application refuses to start if a required variable is missing or malformed** — a fast, loud
failure at boot rather than a `NoneType` error during a demo.

### 25.3 Required variables

```bash
# ── Database ────────────────────────────────────────────────
DATABASE_URL=postgresql+asyncpg://ctms_app:CHANGE_ME@postgres:5432/ctms
DATABASE_MIGRATION_URL=postgresql+asyncpg://ctms_owner:CHANGE_ME@postgres:5432/ctms
DATABASE_WORKER_URL=postgresql+asyncpg://ctms_worker:CHANGE_ME@postgres:5432/ctms
DATABASE_POOL_SIZE=20
DATABASE_MAX_OVERFLOW=10

# ── Redis ───────────────────────────────────────────────────
REDIS_URL=redis://redis:6379/0
CELERY_BROKER_URL=redis://redis:6379/1
CELERY_RESULT_BACKEND=redis://redis:6379/2

# ── Authentication ──────────────────────────────────────────
JWT_SECRET=CHANGE_ME_32_BYTE_RANDOM            # openssl rand -hex 32
JWT_REFRESH_SECRET=CHANGE_ME_DIFFERENT_VALUE   # MUST differ from JWT_SECRET (§18.16)
JWT_ALGORITHM=HS256
ACCESS_TOKEN_EXPIRE_MINUTES=15
REFRESH_TOKEN_EXPIRE_DAYS=14
MFA_ENCRYPTION_KEY=CHANGE_ME_32_BYTE_RANDOM    # encrypts users.mfa_secret (§18.7)

# ── Cloudinary ──────────────────────────────────────────────
CLOUDINARY_CLOUD_NAME=your_cloud_name
CLOUDINARY_API_KEY=your_api_key
CLOUDINARY_API_SECRET=CHANGE_ME
CLOUDINARY_UPLOAD_FOLDER=sih26046
CLOUDINARY_SIGNED_URL_TTL_SECONDS=300

# ── Files ───────────────────────────────────────────────────
MAX_UPLOAD_SIZE_BYTES=52428800                 # 50 MB
ALLOWED_UPLOAD_EXTENSIONS=pdf,docx,xlsx,png,jpg,csv
CLAMAV_HOST=clamav
CLAMAV_PORT=3310

# ── GIS ─────────────────────────────────────────────────────
GIS_K_ANONYMITY_THRESHOLD=5                    # §11.4 — raise without a code change
GIS_CLUSTER_ZOOM_THRESHOLD=8
GIS_MAX_FEATURES_PER_REQUEST=2000

# ── Cache TTLs (seconds) — §13.4 starting values ────────────
CACHE_TTL_DASHBOARD=60
CACHE_TTL_TRIAL_DASHBOARD=120
CACHE_TTL_GIS=300
CACHE_TTL_ANALYTICS=300
CACHE_TTL_SAFETY=180
CACHE_TTL_REFERENCE=3600
CACHE_TTL_PERMISSIONS=900
CACHE_TTL_SESSION=300

# ── Application ─────────────────────────────────────────────
ENVIRONMENT=development                        # development | staging | production
FRONTEND_ORIGIN=http://localhost:3000
API_BASE_URL=http://localhost:8000
LOG_LEVEL=INFO

# ── Observability ───────────────────────────────────────────
SENTRY_DSN=                                    # blank disables Sentry
SENTRY_TRACES_SAMPLE_RATE=0.1
SENTRY_ENVIRONMENT=development

# ── Frontend (NEXT_PUBLIC_* is exposed to the browser) ──────
NEXT_PUBLIC_API_URL=http://localhost:8000/api/v1
NEXT_PUBLIC_MAP_TILE_URL=https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png
NEXT_PUBLIC_MAP_ATTRIBUTION=© OpenStreetMap contributors
NEXT_PUBLIC_SENTRY_DSN=
```

### 25.4 Rules

1. **Nothing secret is ever `NEXT_PUBLIC_*`.** That prefix means "compiled into the browser
   bundle." A `NEXT_PUBLIC_CLOUDINARY_API_SECRET` would ship the secret to every visitor.
2. **`JWT_SECRET` and `JWT_REFRESH_SECRET` must differ.** Startup validation rejects equal
   values.
3. **Three database URLs, three roles** (§7.7). The application never holds the owner or
   worker credentials in its request path.
4. **Every tunable is a variable.** The k-anonymity threshold and every cache TTL are
   configuration, so tuning in Phase 11 is a restart rather than a release.
5. **Startup validates.** Missing or malformed required values abort the boot.

---

## 26. Development Workflow

### 26.1 Phases

| Phase | Delivers | Depends on |
|---|---|---|
| **1 · Setup** | Compose stack (Postgres+PostGIS, Redis, backend, frontend, ClamAV), FastAPI and Next.js skeletons, CI, `.env.example` | — |
| **2 · Auth + RBAC** | `users`/`roles`/`permissions`/`role_permissions`/`sessions`; Argon2id; JWT + refresh rotation; `require_permission`; login and `/auth/me` | 1 |
| **3 · Schema + migrations** | All 23 tables, constraints, indexes; **RLS policies, helper functions, and the three DB roles**; seed data | 2 |
| **4 · Institutions, trials, sites** | Institution/trial/site CRUD with geography columns; `trial_staff` — the scope table RLS depends on | 3 |
| **5 · Participants + clinical** | Pseudonymised enrolment (§14.6), identities, consent, visits, observations, medications | 4 |
| **6 · Safety** | AE reporting, safety review workflow, event-triggered clinical read (§7.5) | 5 |
| **7 · Ethics + compliance** | IEC submission and review with institution scoping; requirements and per-trial status | 4, 5 |
| **8 · Documents** | Cloudinary upload, validation, ClamAV scanning, versioning (§17), signed download | 4 |
| **9 · GIS** | PostGIS queries, aggregation, k-anonymity, clustering, Leaflet map, drill-down | 4, 5, 6, 7 |
| **10 · Dashboards + analytics** | All seven dashboards, the union endpoint, charts | 5, 6, 7 |
| **11 · Caching** | Cache-aside, scope fingerprinting, invalidation, TTL tuning against measured hit rates | 9, 10 |
| **12 · Hardening + audit + testing** | Complete audit coverage, rate limiting, CSRF, headers, the full security test suite (§27.4) | all |
| **13 · Deployment** | Production Compose, Caddy TLS, Sentry, backups, seeded demo data, runbooks | 12 |

### 26.2 Dependency graph

```mermaid
flowchart LR
    P1["1 Setup"] --> P2["2 Auth+RBAC"] --> P3["3 Schema+RLS"] --> P4["4 Trials/Sites"]
    P4 --> P5["5 Participants+Clinical"]
    P5 --> P6["6 Safety"]
    P4 --> P7["7 Ethics+Compliance"]
    P5 --> P7
    P4 --> P8["8 Documents"]
    P4 --> P9["9 GIS"]
    P5 --> P9
    P6 --> P9
    P7 --> P9
    P5 --> P10["10 Dashboards"]
    P6 --> P10
    P7 --> P10
    P9  --> P11["11 Caching"]
    P10 --> P11
    P11 --> P12["12 Hardening+Testing"]
    P12 --> P13["13 Deployment"]

    style P3 fill:#1d4ed8,stroke:#1e3a8a,color:#fff
    style P12 fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

### 26.3 The two ordering decisions that matter

**RLS lands in Phase 3, not Phase 12.** The strongest temptation in this plan is to build the
domain features first and "add RLS at the end." That fails, reliably. Policies written after
the fact must be retrofitted against queries that assumed unrestricted access, and every one
of them has to be re-tested. Worse, the team spends nine phases developing habits that RLS
then breaks. Building on RLS from Phase 3 means every subsequent query is written against the
constraint from the start, and the cost is paid once.

**Caching lands in Phase 11, after the features it caches.** Caching a query that is still
changing shape means invalidating a cache whose keys keep moving. It also hides slow queries
behind a cache instead of fixing them — Phase 11 begins by profiling, and the first response
to a slow endpoint is an index (§28.2), not a cache entry.

Phases 6, 7, and 8 are independent of each other and can be built in parallel by different
team members once Phase 5 lands. Phase 9 depends on all three because the map's aggregate
layers draw from safety, ethics, and compliance data.

### 26.4 Working practices

| Practice | Rule |
|---|---|
| Branching | `main` protected; feature branches; PR review required |
| Migrations | Every schema change is an Alembic migration; **RLS policies and grants are migrations too** |
| Tests with features | A PR adding an endpoint adds its API test and, on any RLS-scoped table, its scope test |
| CI | Lint, type-check (mypy + tsc), test, `gitleaks`, migration up/down check |
| Definition of done | Merged, tests green, migration applied cleanly, endpoint documented in OpenAPI, permission registered in the catalogue |

---

## 27. Testing Strategy

### 27.1 Shape

```text
        ╱  E2E  ╲          ~10 flows      Playwright
      ╱  API tests ╲        ~120          httpx + test DB
    ╱  Integration   ╲      ~80           real Postgres + PostGIS
  ╱   Unit tests       ╲    ~200          pure functions
 ╱  Security suite      ╲   ~40           its own layer (§27.4)
```

The security suite is drawn as its own layer rather than folded into API tests, because it
tests properties rather than behaviour — "this must not be possible" rather than "this must
work." It is the suite that would catch a regression in RLS, and it is the one most likely to
be skipped if it is not named separately.

### 27.2 Backend

| Level | Covers |
|---|---|
| **Unit** | Permission resolution, k-anonymity suppression, scope fingerprinting, redaction, document version transitions, validators |
| **Integration** | Enrolment transaction atomicity and rollback; optimistic locking `409`; cache invalidation after commit; PostGIS spatial queries against known coordinates; document version chain under concurrency |
| **API** | Every endpoint: success, validation failure, permission denial, out-of-scope `404`, pagination, `ETag`/`If-Match` |
| **Authorization** | Each of the seven roles × each endpoint × allowed/denied — a generated matrix, not hand-written cases |
| **RLS** | See §27.4 |
| **Constraints** | Each `CHECK` and unique constraint has a test asserting the database rejects the invalid case. Constraints are load-bearing (§14.2); an untested constraint is an assumption |

Integration and RLS tests run against **real PostgreSQL with PostGIS**, never SQLite. Policies,
`geography`, partial indexes, and `SET LOCAL` do not exist in a substitute engine, so testing
against one would test something other than the system being built.

### 27.3 Frontend

| Level | Covers |
|---|---|
| Component | Rendering, states (loading/empty/error), form validation, permission-gated controls |
| Page | Data fetching with a mocked client, role-appropriate composition |
| Role-based UI | Each role sees its own navigation and only its permitted actions |
| Map | Marker rendering from GeoJSON, cluster behaviour, popup content, **suppressed cells render `<5`, never `0` or blank** (§11.4) |
| Accessibility | Keyboard navigation and labelling on clinical forms — `axe` in CI |

### 27.4 Security suite

The suite that matters most, and the one to demonstrate.

**RLS bypass attempts** — each asserts *zero rows*, not an error:

| Attempt | Expected |
|---|---|
| Research Staff requests another site's participant by ID | `404` |
| Research Staff lists participants, another site's exist | Absent from results |
| Coordinator requests another trial's data | `404` |
| Ethics member requests another institution's submission | `404` |
| Regulator queries `participants` directly | Zero rows |
| **System Admin queries `observations` directly** | **Zero rows** (§5.1) |
| Safety Officer reads observations for a participant with no AE | Zero rows (§5.6) |
| Safety Officer reads observations after an AE is reported | Rows returned |
| Any role reads `participant_identities` without the permission | Zero rows |
| Query executed with `app.current_user_id` unset | **Zero rows from every table** (§7.4 fail-closed) |
| Filter/sort parameter injection attempt | `422` |
| Expired staff assignment (`end_date` in the past) | Access removed immediately |

**Connection pooling leakage** — the test that catches the `SET` vs `SET LOCAL` bug (§7.3):
run request A as user 1 and request B as user 2 on the same pooled connection, in sequence
and interleaved, asserting neither sees the other's rows. A `SET` regression passes every
single-user test and fails only this one.

**Authentication:** expired tokens rejected; tampered JWT signature rejected; refresh reuse
revokes the family; logout invalidates immediately; lockout after 10 failures; timing and
response parity on login and password reset for existent vs non-existent accounts.

**Upload:** oversized rejected; disallowed extension rejected; **`.exe` renamed to `.pdf`
rejected by content sniffing**; path traversal in the filename sanitised; EICAR test file
quarantined and its Cloudinary asset removed; unscanned document not downloadable.

**Rate limiting:** login limit enforced per IP and per email; limits reset after the window;
`429` returned with `Retry-After`.

**GIS privacy:** no endpoint response contains a participant field; a site with 2
participants returns `suppressed: true` with label `<5`; a cache key for role A never serves
role B (scope fingerprint test).

### 27.5 End-to-end

One Playwright test walks the full clinical lifecycle across five roles — the flow that
demonstrates the platform:

```text
Admin logs in → creates institution → creates trial → adds site → assigns PI, Coordinator, Staff
  → PI submits protocol to ethics
  → Ethics Member reviews and approves
  → Coordinator enrols participant (consent captured, counters increment)
  → Staff records visit → records observations
  → Staff reports adverse event
  → Safety Officer reviews, grades causality, marks serious
  → Regulator views compliance and the national GIS map
  → Admin opens the audit trail and every step above is present, in order
```

The final assertion is the point: the audit trail is the platform's compliance claim, and
this test proves the claim end to end rather than asserting it.

Additional E2E: login/logout/refresh; participant withdrawal; document versioning
(v1 → v2 → v3 with the correct current version); GIS drill-down as each role.

### 27.6 Fixtures

A seed script builds a realistic dataset: 5 institutions across 4 states with real
coordinates, 3 trials in different phases, 8 sites, 12 users across all 7 roles, ~120
participants with clinical data, ~30 adverse events including serious ones, ethics
submissions in several states, and compliance records.

**One site is seeded with 2 participants on purpose**, so the k-anonymity suppression path is
exercised by the default dataset rather than only by a contrived test.

### 27.7 CI gates

| Gate | Rule |
|---|---|
| Lint + format | `ruff`, `eslint`, `prettier` |
| Types | `mypy --strict` on backend; `tsc --noEmit` on frontend |
| Unit + integration | All pass |
| **Security suite** | All pass — **no merge on any failure, no exceptions** |
| Coverage | ≥ 80% backend; **100% on `permissions/` and every RLS policy path** |
| Migrations | Up and down apply cleanly on a fresh database |
| Secrets | `gitleaks` clean |

---

## 28. Performance Strategy

### 28.1 Order of operations

Measure, then index, then cache. In that order, always.

Caching a slow query hides it; the query is still slow on every miss, and now there is a
cache to invalidate correctly as well. The first response to a slow endpoint is `EXPLAIN
ANALYZE` and usually an index (§26.3).

### 28.2 Indexes

Every index in §8 exists for a named query. The performance-critical ones:

| Index | Serves |
|---|---|
| `ix_trial_staff_user_active` (partial, covering) | **`app.active_assignments()` — evaluated on every RLS policy check on every query.** The single hottest index on the platform |
| `ix_participants_site_status` | Coordinator and Staff participant lists |
| `ix_visits_scheduled` (partial) | "Today's visits" and "overdue visits" |
| `ix_adverse_events_pending` (partial) | Safety review queue |
| `ix_institutions_location` (GIST) | Map viewport queries |
| `ix_trial_sites_location` (GIST) | Site markers and clustering |
| `ix_audit_logs_failures` (partial) | Security dashboard |
| `ix_trial_compliance_trial_status` | Compliance percentages on every dashboard |

**Partial indexes are used heavily** because clinical data skews hard: most visits are not
scheduled for today, most adverse events are not pending review, most audit entries are
successes. A partial index on the interesting subset is a fraction of the size and stays in
memory.

**RLS makes index design more important, not less.** Every policy predicate is a subquery
executed per statement; an unindexed `trial_staff` turns every query on the platform into a
sequential scan of assignments. This is the concrete reason §7.6 rejects caching scope — the
correct fix is the index, and the index is sufficient.

### 28.3 Query optimisation

- **Eager loading by default.** SQLAlchemy relationships use `selectinload` on list
  endpoints. The N+1 query is the default failure mode of an ORM, and a participant list that
  issues one query per participant is unusable at 200 rows.
- **Deliberate denormalisation, kept honest by trigger.** `adverse_events.trial_id` is
  denormalised from the participant so cross-trial safety queries and GIS aggregates avoid a
  three-table join on the hottest safety path (§8.17).
- **Aggregate in SQL, never in Python.** Every dashboard number is a `count()`/`sum()` with
  `GROUP BY`. Fetching rows to count them in the application is the most common cause of a
  slow dashboard.
- **Cursor pagination** everywhere (§21.1) — constant cost per page, unlike `OFFSET` on a
  large table.
- **`EXPLAIN ANALYZE` on every endpoint touching more than two tables**, as part of the
  definition of done.

### 28.4 Connection pooling

`pool_size=20`, `max_overflow=10`, `pool_pre_ping=True`, `pool_recycle=3600`.

`pool_pre_ping` costs a trivial round trip and prevents the "server closed the connection
unexpectedly" error that appears after an idle period — which, during a live demo, is the
worst possible moment.

**Pooling interacts directly with RLS**, and this is the interaction to remember: connections
are shared between users, so identity must be set per transaction and must unwind on commit.
`SET LOCAL` does exactly that (§7.3). A regression here is invisible in normal testing and is
covered by a dedicated test (§27.4).

### 28.5 Caching

Per §12. Applied in Phase 11, after profiling, to endpoints measured as expensive: dashboard
aggregates, GIS aggregates, trial analytics.

### 28.6 GIS performance

| Technique | Effect |
|---|---|
| GIST indexes on `geography` | Viewport queries use an index, not a scan |
| Bounding-box filter first | `&&` narrows candidates before the exact predicate runs |
| **Server-side clustering below zoom 8** | A national query returns ~20 cluster markers instead of ~2,000 sites |
| `GIS_MAX_FEATURES_PER_REQUEST` | Hard cap; exceeding it forces clustering rather than returning a huge payload |
| Aggregate precomputation | Nightly Celery job materialises state and district aggregates |
| GeoJSON built in PostGIS | `ST_AsGeoJSON` in SQL; no Python serialisation of geometry |

### 28.7 Growth path

Deliberately not built now, documented so it is not a redesign later:

| Signal | Response |
|---|---|
| `audit_logs` exceeds ~10M rows | Range-partition by `occurred_at` monthly. Every index in §8.24 is partition-compatible |
| `observations` exceeds ~5M rows | Partition by `trial_id` |
| Analytics contend with transactional load | Add a read replica (§15.2) |
| GIS aggregates slow despite caching | Materialised views refreshed by Celery |

### 28.8 Frontend

Server Components for data-heavy dashboards; route-based code splitting; `next/image`;
dynamic import of Leaflet (it is client-only and sizeable, and must not be in the initial
bundle for users who never open the map); virtualised tables beyond 100 rows; debounced
search; optimistic UI on status toggles.

### 28.9 Background jobs

Moved off the request path: malware scanning (§16.6), nightly GIS aggregate precomputation,
daily overdue-visit sweep, nightly enrolment-counter reconciliation (§14.7), expired session
cleanup, orphaned Cloudinary asset sweep (§16.7).

### 28.10 What is deliberately not done

No microservices, no Kubernetes, no Kafka, no data warehouse, no CQRS, no GraphQL layer, no
service mesh. The backend is a **modular monolith** (ADR-006) and stays one until measurement
says otherwise. At this scale each of those adds operational surface without removing a
bottleneck that exists (§32).

---

## 29. Deployment Architecture

### 29.1 Topology

```mermaid
flowchart TB
    U["Users — HTTPS"]

    subgraph VM["Single cloud VM · Docker Compose"]
        CADDY["Caddy<br/>TLS (Let's Encrypt) · security headers<br/>rate limiting · reverse proxy"]
        FE["next<br/>Next.js :3000"]
        BE["api<br/>FastAPI + Uvicorn :8000"]
        WK["worker<br/>Celery"]
        BEAT["beat<br/>Celery Beat"]
        PG[("postgres<br/>PostgreSQL 16 + PostGIS 3.4<br/>volume: pgdata")]
        RD[("redis<br/>Redis 7<br/>volume: redisdata")]
        AV["clamav<br/>ClamAV daemon"]
    end

    CLOUD[("Cloudinary<br/>external")]
    SENTRY["Sentry — external"]
    OSM["OpenStreetMap tiles<br/>browser → tile server"]

    U --> CADDY
    CADDY --> FE
    CADDY --> BE
    FE -->|"server-side fetch"| BE
    BE --> PG
    BE --> RD
    BE --> CLOUD
    BE -->|"enqueue"| RD
    RD --> WK
    BEAT --> RD
    WK --> PG
    WK --> AV
    WK --> CLOUD
    BE --> SENTRY
    WK --> SENTRY
    U -.->|"tiles load directly"| OSM

    style PG fill:#1d4ed8,stroke:#1e3a8a,color:#fff
```

**Only Caddy is exposed.** Every other container is on an internal Docker network with no
published port. PostgreSQL and Redis are unreachable from the internet — not merely
password-protected, but not routable.

### 29.2 Why single-VM Compose

| Property | Consequence |
|---|---|
| **Dev and demo topology are identical** | The stack that ran on a laptop runs on the VM. No "works locally" gap discovered at the venue |
| **One command** | `docker compose -f docker-compose.prod.yml up -d` |
| **Runs offline** | If venue networking fails, the entire stack runs on a laptop and the demo proceeds. Only Cloudinary and OSM tiles need the network, and both degrade visibly rather than breaking the app |
| **No managed-service surprises** | No cold starts, no free-tier throttling, no per-service dashboard |
| **PostGIS and RLS guaranteed** | The official PostGIS image, with full control over extensions, roles, and grants — not every managed Postgres permits `BYPASSRLS` roles |
| **Migration path preserved** | Each service is already a container; splitting one out later is a Compose file change |

### 29.3 Environments

| | Development | Staging | Production (demo) |
|---|---|---|---|
| Host | Laptop | VM or second Compose project | Cloud VM |
| Compose file | `docker-compose.yml` | `docker-compose.prod.yml` | `docker-compose.prod.yml` |
| TLS | None (localhost) | Let's Encrypt staging | Let's Encrypt production |
| Data | Seed fixtures (§27.6) | Anonymised seed | Seeded demo data |
| Debug | On, hot reload | Off | Off |
| Sentry | Disabled | Enabled, `staging` | Enabled, `production` |
| Log level | `DEBUG` | `INFO` | `INFO` |
| Secrets | `.env.local` | Host env | Host secret store |
| Migrations | Manual | Automatic on deploy | Automatic on deploy |

**Staging exists to rehearse the deploy**, not to host a second demo. It is the environment
where "the migration applies cleanly on a fresh database" is proven before it is proven in
front of judges.

### 29.4 Container responsibilities

| Container | Image | Notes |
|---|---|---|
| `caddy` | `caddy:2-alpine` | Only published ports (80, 443). Automatic TLS |
| `next` | Node 20, multi-stage build | Standalone Next.js output; non-root user |
| `api` | Python 3.12-slim, multi-stage | Uvicorn workers; runs migrations at startup |
| `worker` | Same image as `api` | Celery worker; connects as `ctms_worker` (§7.7) |
| `beat` | Same image as `api` | Scheduled triggers |
| `postgres` | `postgis/postgis:16-3.4` | Named volume; init script creates the three roles |
| `redis` | `redis:7-alpine` | AOF persistence — for Celery task durability, not for cached data (ADR-002) |
| `clamav` | `clamav/clamav:latest` | Signature database updated on start |

All images are pinned to a digest, run as non-root, and declare health checks. `api` waits on
`postgres` and `redis` health before starting.

### 29.5 Deploy sequence

```text
1. git pull on the VM
2. docker compose build
3. docker compose run --rm api alembic upgrade head    # migrations first, as ctms_owner
4. docker compose up -d                                # rolling restart
5. Health check: GET /api/v1/health → 200
6. Smoke test: login as each of the 7 roles
```

Migrations run as a separate step before the new containers start, so a failed migration
aborts the deploy with the previous version still serving.

### 29.6 Backup and recovery

| What | How | Cadence |
|---|---|---|
| PostgreSQL | `pg_dump -Fc` to a mounted volume and off-host | Nightly + before every deploy |
| Cloudinary | Assets persist independently; the manifest is in `documents` | Continuous |
| Redis | Not backed up — **by design**, it holds nothing authoritative (§2.2) | — |
| Config | `.env.production` in the host secret store, off-repo | On change |

Restore is rehearsed once during Phase 13, and the runbook records the measured time.
A backup never restored is not a backup.

### 29.7 Health checks

| Endpoint | Checks |
|---|---|
| `GET /api/v1/health` | Process alive — liveness only, no dependencies |
| `GET /api/v1/health/ready` | PostgreSQL `SELECT 1`, Redis `PING`, Cloudinary reachable |
| `GET /api/v1/health/detail` | Above plus migration revision, Celery queue depth, pool utilisation. **Admin-only** |

Liveness excludes dependencies deliberately: a Redis outage must not cause the orchestrator to
restart a healthy API container that is correctly degrading (§12.3).

---

## 30. Observability

### 30.1 Structured logging

`structlog` emits JSON. Every line carries `timestamp`, `level`, `event`, `request_id`,
`user_id`, `role`, `method`, `path`, `status_code`, `duration_ms`.

**Never logged:** clinical values, participant identities, adverse event narratives, consent
content, passwords or hashes, tokens, signed URLs, cookies, `Authorization` headers.

A redaction processor sits in the structlog pipeline and drops known-sensitive keys before
serialisation, so the guarantee does not depend on every call site remembering.

```json
{"timestamp":"2026-09-01T10:23:45.123Z","level":"info","event":"participant.enrolled",
 "request_id":"a1b2c3d4-…","user_id":"8f14e45f-…","role":"TRIAL_COORDINATOR",
 "trial_id":"3c4d5e6f-…","participant_id":"9a8b7c6d-…","duration_ms":142}
```

The entity IDs are present and the clinical content is not — enough to trace the operation,
nothing to expose if the log is read by someone who should not see PHI.

### 30.2 Error monitoring

Sentry captures unhandled exceptions with request context, release version, and environment.
Configured with `send_default_pii=False` and a `before_send` hook that scrubs request bodies
on clinical endpoints entirely — an exception in the observation handler must not ship the
observation to a third-party service (§30.7).

`traces_sample_rate=0.1` in production; 1.0 in staging.

### 30.3 Request correlation

A `X-Request-ID` middleware generates a UUID per request (or honours an inbound one),
attaches it to every log line, includes it in error responses (§18.17), tags the Sentry
event, and **writes it to `audit_logs.request_id`** (§8.24).

One identifier therefore links: the user's error message, every application log line, the
Sentry event, and the audit record. A support question — "what happened at 10:23?" — becomes
one query instead of four correlations by timestamp.

### 30.4 Metrics

Structured log fields, aggregated. No Prometheus or Grafana in the MVP (§32) — the
operational surface is not worth it at this scale.

| Metric | Source |
|---|---|
| API latency p50/p95/p99 by endpoint | `duration_ms` |
| Error rate by endpoint | `status_code >= 500` |
| Slow queries | SQLAlchemy logs statements over 500 ms with the statement (never parameters) |
| Cache hit rate | Explicit `cache.hit` / `cache.miss` events |
| Redis health | `INFO` sampled by the health endpoint |
| Connection pool | Checked-out vs available, on `/health/detail` |
| Celery failures | Task failure handler → structured log + Sentry |
| **Authentication failures** | `audit_logs` where `action IN ('LOGIN_FAILED','TOKEN_REUSE_DETECTED')` |

### 30.5 What is alerted on

Signals only — an alert nobody acts on trains everyone to ignore alerts.

| Alert | Threshold |
|---|---|
| API error rate | > 5% over 5 minutes |
| Database unreachable | Any readiness failure |
| **Token reuse detected** | Any occurrence — a possible token theft (§18.6) |
| **Malware detected** | Any occurrence |
| Failed logins | > 20 for one account in 15 minutes |
| Celery queue depth | > 100 tasks |
| Disk usage | > 85% |
| **Expedited AE report overdue** | Any — a regulatory obligation, not merely a system metric |

### 30.6 The audit trail as observability

`audit_logs` answers questions application logs cannot, because it is append-only, contains
before/after state, and is queryable by entity: *who changed this participant's status and
when*, *which documents did this user download*, *what did this account do in the hour before
lockout*, *who read a participant identity*. Application logs answer "what did the system do";
the audit trail answers "what did people do" (§19.6).

### 30.7 Not logging clinical data

Every observability tool is a place data can escape to. Sentry is third-party; log files are
read by people with no clinical access; log aggregation retains data outside PostgreSQL where
RLS does not apply.

The rule: **observability records identifiers and outcomes, never clinical content.** Enough
to diagnose, nothing to disclose. Three enforcement points — the structlog redaction
processor, Sentry's `before_send` scrubber, and the SQLAlchemy slow-query logger that logs
statements without parameter values — so no single omission at a call site breaks it.

---

## 31. Architecture Decision Records

### ADR-001 — PostgreSQL is the source of truth
**Status:** Accepted
**Context:** Clinical, safety, and regulatory data must be correct, consistent, and auditable.
**Decision:** PostgreSQL is authoritative for all transactional and clinical data. Redis is a
cache; Cloudinary holds bytes; PostgreSQL holds the metadata that gives them meaning.
**Consequences:** Every write is a database transaction. Cache and file store can be lost and
rebuilt without data loss. Redis is not backed up (§29.6).

### ADR-002 — Redis is cache-only
**Status:** Accepted
**Context:** Dashboard and GIS aggregates are expensive and read-heavy.
**Decision:** Redis holds only recomputable data. No authorization decision, no clinical
record, no authoritative state. Its loss degrades performance, never correctness.
**Consequences:** Rules out caching authorization scope (§7.6). Session validity is cached as
a lookaside whose miss falls through to `sessions` (§8.6). Every cache read tolerates Redis
being down.

### ADR-003 — RBAC in the application, RLS in the database
**Status:** Accepted
**Context:** Seven roles with differing capabilities and differing row visibility. Application
authorization eventually has a bug.
**Decision:** RBAC answers "what actions" via `roles`/`permissions`/`role_permissions`; RLS
answers "which rows" via PostgreSQL policies. Identity reaches policies via
`SET LOCAL app.current_user_id` (§7.3); scope is resolved in-database from `trial_staff`.
**Alternatives rejected:** Caching resolved scope in Redis — contradicts ADR-002, and stale
scope means access after revocation (§7.6). Service-layer scoping alone — one forgotten
`WHERE` clause becomes a cross-site PHI breach (§7.1).
**Consequences:** Policies must be written and tested per table. `SET LOCAL` is mandatory
under connection pooling. Fail-closed: no identity means no rows. Indexes on `trial_staff`
become performance-critical (§28.2).

### ADR-004 — GIS is globally accessible; drill-down is role-controlled
**Status:** Accepted
**Context:** Geographic oversight is valuable to every role, but the map must never expose
participant data.
**Decision:** One GIS subsystem, reachable by all authenticated roles. Depth of drill-down is
governed by `gis:read` / `gis:drilldown` plus ordinary RLS scope. Aggregates carry k-anonymity
suppression at k=5, applied in SQL (§11.4).
**Consequences:** One map implementation to secure and test. Suppression must be applied to
every participant-derived aggregate. Cache keys must carry a scope fingerprint (§12.4).

### ADR-005 — Cloudinary stores files; PostgreSQL stores metadata
**Status:** Accepted
**Context:** The platform handles protocols, consent forms, approvals, and regulatory filings.
**Decision:** Bytes to Cloudinary as `authenticated` assets with per-request signed URLs;
metadata, versioning, and access control in PostgreSQL. All interaction behind a
`StorageBackend` interface.
**Consequences:** Database stays small and fast to back up. Orphaned-asset handling required
(§16.7). Migrating to S3/GCS for a production clinical deployment is a backend
implementation, not a schema change (§16.9).

### ADR-006 — Modular monolith
**Status:** Accepted
**Context:** A student team, a hackathon timeline, and a domain spanning ten modules.
**Decision:** One FastAPI application with clear internal module boundaries
(`api → services → repositories`). No microservices.
**Consequences:** One deployment, one database, one transaction scope — which is what makes
§14.6 possible at all. Module boundaries are maintained by convention and review. Extraction
later is possible because the boundaries already exist.

### ADR-007 — No AI in the MVP
**Status:** Accepted
**Context:** Predictive enrolment, protocol assistance, and automated safety reports are
attractive but none is necessary for the core platform.
**Decision:** No AI or ML in this MVP. The architecture stays modular so it can be added:
services behind interfaces, Celery already present, the audit log already recording the event
stream such a feature would consume (§1.4).
**Consequences:** Effort goes to correctness of the core clinical platform. Adding AI later
means a worker and an endpoint, not a redesign.

### ADR-008 — No separate data warehouse
**Status:** Accepted
**Context:** Dashboards and analytics need aggregation.
**Decision:** Aggregate in PostgreSQL with indexed queries, cached in Redis, precomputed
nightly by Celery where needed. No warehouse, no ETL, no OLAP store.
**Consequences:** One source of truth for analytics; no ETL to keep correct; no risk of a
dashboard disagreeing with the record. Growth path is materialised views and partitioning
(§28.7), then a read replica (§15.2).

### ADR-009 — Single PostgreSQL primary for the MVP
**Status:** Accepted
**Context:** Replication adds availability and read capacity, and adds replication lag.
**Decision:** One primary for the MVP. Design for replication without deploying it: the
service layer separates read-only operations from writing ones, and read-after-write paths
are identified.
**Consequences:** Strict read-after-write consistency, no lag bugs, no routing logic. Adding a
replica later is configuration plus flipping `replica_ok` operations, and requires verifying
RLS behaviour on replicas (§15.3).

### ADR-010 — Regulatory Officer sees aggregates, not individual clinical data
**Status:** Accepted
**Context:** A real regulatory inspection can compel broad access under a specific legal
instrument, for a defined period, with the inspection itself on record.
**Decision:** In the MVP the role reads all trials, institutions, sites, compliance, and
aggregate safety — but no participant records, identities, consents, observations, or
individual adverse event narratives. Writes are confined to compliance and regulatory status.
**Alternatives rejected:** Permanent full clinical read — widens the standing exposure of
every participant record for a capability the demo does not exercise, and models an
inspection badly by making it permanent and unlogged.
**Consequences:** The national oversight map is built without its viewer holding a single
participant record (§11.5). A future inspection-grant mechanism — time-boxed, audited,
approved — is a contained addition: a scoped grant table plus an extra RLS branch.

### ADR-011 — Participants are pseudonymised, identities isolated
**Status:** Accepted
**Context:** Every dashboard, analytics query, GIS aggregate, and export reads participant
data. Any one of them could leak identity.
**Decision:** `participants` holds a subject code and clinical linkage only. Identifying
information lives in `participant_identities`, keyed one-to-one, with its own RLS policy, its
own permission (`participant_identity:read`, granted to no role by default), and audited reads
(§19.3). Only a year of birth is kept on `participants`; the full date lives with the identity.
**Consequences:** 23 tables rather than 22. Re-identification is a single, audited, permitted
operation. **The privacy guarantee of §11 becomes a property of which tables a code path
opens, not a promise about careful query writing** — a defect in an analytics query cannot leak
a name it never selected.

### ADR-012 — Audit immutability enforced by grant and trigger
**Status:** Accepted
**Context:** The audit trail's value rests entirely on not having been edited.
**Decision:** `UPDATE`, `DELETE`, `TRUNCATE` revoked from every application role; a
`BEFORE UPDATE OR DELETE` trigger raises; no `audit:update` permission exists; `user_id` uses
`ON DELETE SET NULL`.
**Consequences:** Corrections are appended, never applied in place. Growth is handled by
partitioning (§28.7). The guarantee holds even against an attacker with application admin
credentials (§19.4).

---

## 32. Important Constraints

### 32.1 Deliberately not built

| Rejected | Why |
|---|---|
| Microservices per module | Ten services, ten deployments, and distributed transactions — for a workload one process handles. It would make §14.6 a saga instead of a transaction |
| Kubernetes | Compose runs this stack on one VM. K8s would add a control plane to operate and nothing a judge can see |
| Kafka or an event bus | Three background jobs. Celery over Redis, already present, is sufficient |
| Separate data warehouse | Indexed aggregation plus caching serves every dashboard. A warehouse adds ETL and a second version of the truth (ADR-008) |
| Event sourcing / CQRS | The audit log already provides the history that motivates event sourcing, without rebuilding state from a stream |
| AI/ML infrastructure | ADR-007 |
| Blockchain audit verification | Append-only tables with revoked grants and a trigger (ADR-012) provide tamper-evidence within a trust model where the database administrator is trusted — which is the actual model here. A chain would add cryptographic machinery without changing that model |
| Native mobile application | Responsive web serves clinical data entry on a tablet. A second codebase would halve the effort available for the first |
| GraphQL | REST with OpenAPI generates typed clients and a browsable `/docs` for free. GraphQL would add a resolver layer where RLS and permission checks must be re-established per field |
| Prometheus + Grafana | Structured logs answer the operational questions at this scale (§30.4) |
| Multi-language support | Explicitly deferred (§1.4) |

### 32.2 What is not compromised

Cutting scope is not the same as cutting rigour. These are non-negotiable regardless of
timeline:

- [x] RLS on every table, from Phase 3 (§26.3)
- [x] Argon2id password hashing (§18.4)
- [x] `HttpOnly` cookies; no tokens in `localStorage` (§18.3)
- [x] Append-only audit log, enforced by grant and trigger (§19.4)
- [x] Transactional multi-table writes (§14.6)
- [x] Foreign keys, unique constraints, and check constraints on (§14.2)
- [x] Pseudonymised participants with isolated identities (ADR-011)
- [x] k-anonymity suppression on GIS aggregates (§11.4)
- [x] Malware scanning before a document becomes downloadable (§16.6)
- [x] The security test suite as a merge gate (§27.7)

### 32.3 The target

> **A secure, modular monolith that a student team can build, test, demonstrate, and extend.**

Read as four requirements, each cashed out somewhere above:

**Secure** — RBAC and RLS in depth, pseudonymised participants, append-only audit,
`HttpOnly` cookies, a security suite that gates merges.

**Modular** — clear boundaries (`api → services → repositories`, `features/` on the
frontend), so work parallelises across the team after Phase 5 and so extraction is possible
later.

**Buildable** — one language per tier, one database, one deployment command, thirteen ordered
phases with an explicit dependency graph.

**Demonstrable** — the national GIS oversight map, the RLS bypass test suite passing live, and
the end-to-end audit trail are three things a judge can be shown rather than told.

**Extensible** — AI, replication, object storage, partitioning, and inspection grants each
have a documented path that adds a component rather than restructuring the core.

---

*End of specification. Changes to this document are reviewed like code: the architecture is
only a single source of truth if it stays in step with what is built.*
