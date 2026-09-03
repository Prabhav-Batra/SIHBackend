# SIH26046 — Frontend Requirements

**Clinical Trial Management & Monitoring Platform**
Audience: the frontend team. This is the document you build from.

**Version:** 1.0 · **Date:** 2026-09-03 · **Backend state:** B1–B6c complete, B7 (GIS) / B8 (analytics) / B9 (hardening) not started.

---

## 0 · How to read this document

| Document | What it is | When you need it |
|---|---|---|
| `PROJECT_ARCHITECTURE.md` | The domain spec. §5 roles, §6 permissions, §8 schema, §21 API, §22 sitemap, §23 dashboards | The source of truth for *what a field means* |
| `BACKEND_CONTEXT.md` | What is actually built, and why | Before you assume an endpoint exists |
| **`FRONTEND_REQUIREMENTS.md`** | **This file.** Pages, fields, contracts, UX rules | Daily |

Two labels appear throughout and they are the most important thing in this document:

- 🟢 **LIVE** — the endpoint exists in the backend today. Integrate against it.
- 🟡 **PLANNED** — specified but not built (B7/B8/B9). **Build the UI, mock the data behind a single fake-adapter module**, so the swap to the real endpoint is a one-file change.

Do not let a 🟡 block you. Do not ship a 🟡 to a demo without saying it is mocked.

---

## 1 · What we are building

A national clinical trial management platform. **One application, seven roles.** There is no separate portal per role — a user signs in once and lands on a role-appropriate dashboard. Everyone shares the same API, the same database, and the same map.

The domains: trials & sites · participants & consent · clinical data (visits, observations, medications) · safety (adverse events) · ethics (IEC review) · compliance · documents · audit · GIS.

### 1.1 The three rules that shape every screen

**Rule 1 — The frontend hides; it never protects.**
We hide navigation and controls a user cannot use. That is a *usability* feature. The backend rejects what they may not do, and the database returns zero rows for what they may not see. Never build a screen whose correctness depends on the frontend having hidden something.

**Rule 2 — Participants are pseudonymous by default.**
Every list, table, chart, export, tooltip and map popup shows a **subject code** (`CT-2026-014-DEL-0042`), never a name. Real identity lives behind one deliberate, permission-gated, **audited** action (§9.4.4). If you find yourself rendering a participant's name anywhere other than that one panel, something has gone wrong.

**Rule 3 — Nothing clinical is ever deleted.**
There are no delete buttons on clinical records. Records are *withdrawn*, *amended* (with a mandatory reason), *superseded*, or *archived*. The word "Delete" should not appear in the clinical UI at all.

### 1.2 Explicitly out of scope

Do not build, mock, or hint at: predictive enrolment, AI protocol assistant, automated safety report generation, multi-language, voice entry, native mobile app, blockchain audit.

---

## 2 · Stack and project setup

| Concern | Choice | Notes |
|---|---|---|
| Framework | **Next.js (App Router)** | Server Components fetch with the session cookie attached, so no access token ever reaches client JS |
| Language | **TypeScript**, `strict: true` | |
| Styling | **Tailwind CSS** | |
| Components | **shadcn/ui** | Copied into the repo, not a dependency — the data table and form primitives get adapted for clinical entry |
| Forms | **react-hook-form + zod** | One zod schema per entity, mirroring the backend `CHECK` constraints in §10 |
| Server state | **TanStack Query** | For all client-side fetching, mutations, and cache invalidation |
| Tables | **TanStack Table** | headless, wrapped once in `<DataTable>` |
| Maps | **Leaflet + react-leaflet**, OpenStreetMap tiles | SRID 4326 / GeoJSON straight from the API — no reprojection anywhere |
| Charts | **Recharts** | See §8.6 for the chart rules |
| Dates | **date-fns**, `Asia/Kolkata` display timezone | API is `timestamptz` / ISO-8601; dates (`enrollment_date`, `onset_date`) are plain `YYYY-MM-DD` and must **not** be timezone-shifted |
| Icons | **lucide-react** | |
| Deploy | **Vercel** | With the rewrite proxy of §4.2 — this is not optional |

### 2.1 Folder structure

```text
app/
├── (public)/            # landing, about, features, contact, privacy, terms
├── (auth)/              # login, password reset
├── (dashboard)/         # authenticated shell: sidebar, topbar, notifications
│   ├── admin/
│   ├── investigator/
│   ├── coordinator/
│   ├── research/
│   ├── ethics/
│   ├── safety/
│   ├── regulator/
│   └── (global)/        # gis, notifications, profile — reachable by every role
components/
├── ui/                  # shadcn primitives
└── shared/              # DataTable, PageHeader, StatusBadge, EmptyState, ConflictDialog…
features/                # ← the real work lives here
├── trials/              # components + hooks + types, ONE implementation
├── participants/
├── clinical/
├── safety/
├── ethics/
├── compliance/
├── documents/
├── gis/
└── analytics/
lib/
├── api/                 # typed client, error normalisation, ETag handling
├── auth/                # session, permission helpers
└── mocks/               # every 🟡 endpoint's fake adapter, in ONE place
```

**`/investigator/participants`, `/coordinator/participants` and `/research/participants` are not three implementations.** Each is a ~30-line page that composes `features/participants/ParticipantTable` with different default columns, filters and sort. If you are copy-pasting a table body between role pages, stop.

Why separate routes at all? Users bookmark and share URLs, and a URL that renders differently depending on who opens it is impossible to talk about in a corridor. And each section sets its own defaults — the coordinator's participant list defaults to their active site sorted by next visit; the PI's spans all sites sorted by enrolment date — without a tangle of conditionals in one page.

---

## 3 · The seven roles

| Role constant | Landing route | One-line purpose |
|---|---|---|
| `SYSTEM_ADMIN` | `/admin` | Platform operation, users, institutions. **Not a clinical role** |
| `PRINCIPAL_INVESTIGATOR` | `/investigator` | Scientific ownership of assigned trials |
| `TRIAL_COORDINATOR` | `/coordinator` | Day-to-day operations across a trial's sites |
| `RESEARCH_STAFF` | `/research` | Front-line data capture at **one site** |
| `ETHICS_MEMBER` | `/ethics` | IEC review for **one institution** |
| `SAFETY_OFFICER` | `/safety` | Adverse event adjudication **across all trials** |
| `REGULATORY_OFFICER` | `/regulator` | National oversight — **aggregates only, never subject data** |

A user has exactly one role. There is no role switcher; do not build one.

### 3.1 The capability matrix — what each role's UI may contain

`F` full · `S` scoped to assignment · `A` aggregate only · `—` no access at all

| Resource | ADMIN | PI | COORD | STAFF | ETHICS | SAFETY | REG |
|---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| Users & roles | F | — | — | — | — | — | — |
| Institutions | F | S | S | S | S | A | F |
| Trials | F | S | S | S | S | F | F |
| Trial sites | F | S | S | S | — | F | F |
| Trial staff | F | S | S | — | — | — | F |
| Participants (pseudonymised) | **—** | S | S | S (site) | **—** | **—** | **—** |
| Participant identities | — | S* | S* | — | — | — | — |
| Consents | — | S | S | **—** | — | — | — |
| Visits | — | S | S | S (site) | — | — | — |
| Observations | — | S | S | S (site) | — | S** | — |
| Medications | — | S | S | S (site) | — | S** | — |
| Adverse events | — | S | S | S (site) | — | F | A |
| Safety reviews | — | S | — | — | — | F | A |
| Ethics submissions | — | S | — | — | S (inst.) | — | A |
| Ethics reviews | — | **A** | — | — | S (inst.) | — | A |
| Documents | — | S | S | S (site) | S (inst.) | S | S |
| Compliance | F | S | S | — | — | — | F |
| GIS aggregates | F | F | F | F | F | F | F |
| GIS clinical drill-down | — | S | S | S (site) | — | A | A |
| Audit logs | F | S | — | — | — | — | S |

\* requires the separate `participant_identity:read` permission, granted to nobody by default.
\*\* Safety Officer clinical read is **event-triggered** — only for participants who have a reported adverse event.

Three consequences you will feel while building:

- **The admin dashboard has no clinical metrics.** No enrolment count, no safety count. An admin manages *access to* clinical data without holding it. If a designer asks for "total participants" on `/admin`, the answer is no.
- **The regulator's national map is the flagship screen and its viewer holds zero participant rows.** That is the demo worth making.
- **A PI can see their submission's *decision* but never the individual ethics reviews.** Independence of ethical review depends on it. Do not add a "see reviewer comments" affordance to the investigator ethics page.

---

## 4 · Authentication and session

### 4.1 How auth actually works

- Login returns **two `HttpOnly` cookies**. Client JavaScript can never read them, and must never try.
  - `access_token` — 15 min, `SameSite=Lax`, `Secure`, `Path=/`
  - `refresh_token` — `SameSite=Strict`, `Secure`, **`Path=/api/v1/auth/refresh`** (it is transmitted on exactly one endpoint)
- There is **no token in `localStorage`**, no `Authorization` header, no bearer token in memory. If you write `localStorage.setItem('token', …)` you have broken the security model.
- Refresh tokens **rotate on every use** and reuse is detected. Replaying a used refresh token revokes the entire session family and forces re-login. See §4.4.

### 4.2 The Vercel rewrite proxy — non-negotiable

Vercel and the backend are different origins. `SameSite=Lax` cookies are not sent cross-site, so the frontend **must** proxy the API under its own origin:

```js
// next.config.js
async rewrites() {
  return [{ source: '/api/:path*', destination: `${process.env.BACKEND_ORIGIN}/api/:path*` }];
}
```

Every request the app makes goes to **`/api/v1/...` on its own origin**, with `credentials: 'include'`. There is no CORS configuration to rely on and no absolute backend URL anywhere in the client bundle.

### 4.3 Login flow

1. `POST /api/v1/auth/login` `{ email, password }`
2. On `200`: cookies are set; response body is `{ userId, email, role, mfaRequired }`
3. `GET /api/v1/auth/me` → `{ userId, email, role, permissions: string[] }`
4. Redirect to the role's landing route (§3)

On `401`: render **one** message — *"Invalid email or password."* Never distinguish "no such user" from "wrong password"; that difference is an account-enumeration oracle. The backend already returns a single `INVALID_CREDENTIALS` code for both.

> `mfaRequired` is in the response shape today but MFA is not implemented. Read the flag, branch on it, and route to a `/login/mfa` page that is stubbed. When B9 lands, the page fills in.

### 4.4 Session expiry and refresh — get this right

A clinician half-way through a 20-field observation form who loses their work to a silent 401 will not use the platform again.

```
Any request → 401
   ↓
Have we already tried refreshing for this request?  ──yes──→ hard logout
   ↓ no
POST /api/v1/auth/refresh   (single-flight: queue concurrent 401s behind one refresh)
   ↓ 200                              ↓ 401
retry the original request      hard logout
```

**Hard logout** means: clear query cache, redirect to `/login?next=<current path>`, and show *"Your session ended. Please sign in again."* If the 401 body carries `code: "SESSION_REVOKED"`, show instead: *"You were signed out for security. Please sign in again."*

**Protect unsaved work.** Any form with unsaved changes registers with a `useUnsavedGuard()` hook. On hard logout, serialise the form's draft into `sessionStorage` under a form-scoped key and restore it after the user signs back in. Clinical data entry is long and interruption-prone; this is a requirement, not a nicety.

**Proactive refresh.** Access tokens live 15 minutes. Fire a refresh on `visibilitychange → visible` when the last refresh was more than 12 minutes ago, so a user returning to a tab does not eat a 401 on their first click.

### 4.5 Route protection — three layers

1. **Middleware** (`middleware.ts`) — no session cookie → redirect to `/login`. Runs before any page renders.
2. **Layout guard** — each role layout checks that the session's role matches the section, and redirects to the correct dashboard otherwise.
3. **The API** — the only layer that actually matters.

Layers 1 and 2 are usability: a user who types `/regulator/trials` into the URL bar gets bounced for their own clarity, and if they somehow reach it, they see a page shell with empty data because RBAC and RLS return nothing.

### 4.6 CSRF — build for it now

CSRF protection is disabled in the backend today and arrives in **B9** as a double-submit token. Do this now so it is a config flip later:

- Route every mutation through one `apiMutate()` function.
- Have it read a `csrf_token` cookie (non-`HttpOnly`, when it appears) and set `X-CSRF-Token`.
- Until the backend issues it, the header is simply absent. Nothing else changes.

Never scatter `fetch(..., { method: 'POST' })` through components.

### 4.7 What `/auth/me` gives you, and what it does not

🟢 **Live today:**
```ts
{ userId: string; email: string; role: string; permissions: string[] }
```

🟡 **Missing, and you will want it** (flagged to backend in §16):
`fullName`, `institutionId`, and the user's `trial_staff` assignments. Without assignments the frontend cannot pre-select "my site" as a default filter — the app still *works* (RLS scopes everything server-side) but the coordinator's "my active site" default has nothing to key on.

**Interim:** derive defaults from the first result of the scoped list endpoints and remember the user's last selection in `localStorage`. Do not fake an assignment list.

---

## 5 · Permissions → UI

### 5.1 The one rule

**Gate on permissions, never on role names.** The `permissions` array from `/auth/me` is the whole input.

```tsx
// ✅
<Can do="participant:create"><Button>Enrol participant</Button></Can>

// ❌ never
{user.role === 'TRIAL_COORDINATOR' && <Button>Enrol participant</Button>}
```

Role names belong in exactly two places: the router (which dashboard to land on) and display text ("Signed in as Principal Investigator"). Nowhere else. A `grep -r "=== 'PRINCIPAL_INVESTIGATOR'"` returning hits in `features/` is a bug.

Ship these three primitives on day one:

```tsx
usePermissions(): { has(p: string): boolean; hasAny(...p: string[]): boolean }
<Can do="trial:update" fallback={null}>…</Can>
<RequirePermission do="audit:read">…</RequirePermission>   // whole-page guard
```

### 5.2 The permission catalogue

The complete set. Every one of these is a real string in `permissions`.

```text
# Trials and structure
trial:create   trial:read   trial:update   trial:archive
site:create    site:read    site:update
institution:create   institution:read   institution:update
trial_staff:create   trial_staff:read   trial_staff:delete

# Participants and consent
participant:create   participant:read   participant:update   participant:withdraw
participant_identity:read   participant_identity:create
consent:create   consent:read   consent:withdraw

# Clinical data
visit:create   visit:read   visit:update
observation:create   observation:read   observation:update
medication:create   medication:read   medication:update

# Safety
adverse_event:create   adverse_event:read   adverse_event:update   adverse_event:review
safety_report:create   safety_report:read

# Ethics
ethics:submit   ethics:read   ethics:review   ethics:decide

# Compliance and regulatory
compliance:read   compliance:update   compliance:define
regulatory:report

# Documents
document:upload   document:read   document:supersede   document:archive

# Geographic
gis:read   gis:drilldown

# Platform
user:create   user:read   user:update   user:deactivate
role:read   role:assign
audit:read
```

`gis:read` is held by every role — that is what makes the map global. `gis:drilldown` is the gate between suppressed aggregates and per-site detail.

### 5.3 Hiding vs. disabling

| Situation | Treatment |
|---|---|
| User lacks the permission entirely | **Hide** the control. Showing a permanently disabled button is noise |
| User has the permission but state forbids it *right now* (trial `SUSPENDED`, document `QUARANTINED`, review finalised) | **Show, disabled, with a tooltip saying why.** The user could do this; something specific is blocking it |
| Whole page they may not reach | Redirect to their dashboard with a toast, not a 403 screen |

The second row is the one teams get wrong. "Enrol participant" greyed out with *"This trial is suspended — enrolment is paused"* teaches the user something. A missing button teaches them nothing.

---

## 6 · Sitemap

### 6.1 Public — `(public)` and `(auth)`

| Route | Purpose | Notes |
|---|---|---|
| `/` | Landing — problem statement, platform overview | The only marketing-styled page. Hero, the seven roles, the privacy story, CTA to `/login` |
| `/about` | Project and team | |
| `/features` | Capability overview | |
| `/contact` | Contact form | Static submit or mailto; no backend endpoint |
| `/login` | Authentication | 🟢 |
| `/login/mfa` | TOTP challenge | 🟡 stub |
| `/forgot-password` | Begin reset | 🟡 stub |
| `/reset-password?token=` | Complete reset | 🟡 stub |
| `/privacy` | Privacy policy — how participant data is handled | Write this seriously; it is part of the pitch |
| `/terms` | Terms of use | |

### 6.2 Global — every authenticated role

| Route | Purpose | State |
|---|---|---|
| `/gis` | The shared map | 🟡 B7 |
| `/notifications` | Derived work queue | 🟡 composed client-side, see §9.9 |
| `/profile` | Own details, password, MFA, active sessions | partial |

### 6.3 Role sections

```text
/admin           /admin/users  /roles  /institutions  /trials  /sites  /audit  /settings
/investigator    /trials  /participants  /safety  /ethics  /compliance  /gis  /analytics
/coordinator     /trials  /participants  /visits  /clinical-data  /documents
                 /adverse-events  /compliance
/research        /participants  /visits  /clinical-data  /adverse-events  /documents
/ethics          /pending-reviews  /reviews  /documents  /history
/safety          /adverse-events  /reviews  /monitoring  /reports
/regulator       /trials  /institutions  /sites  /gis  /compliance  /safety
                 /regulatory  /reports
```

Detail routes follow the obvious nesting: `/coordinator/participants/[id]`, `/investigator/trials/[id]`, `/ethics/pending-reviews/[submissionId]`, `/safety/adverse-events/[id]`.

### 6.4 Full route table

| Route | Page | Primary permission | State |
|---|---|---|---|
| `/admin` | Platform health dashboard | authenticated | 🟡 |
| `/admin/users` | User list, create, deactivate, assign role | `user:read` | partial 🟢 |
| `/admin/users/[id]` | User detail | `user:read` | 🟡 |
| `/admin/roles` | Role → permission matrix (read-only view) | `role:read` | 🟢 |
| `/admin/institutions` | Institution list + map preview | `institution:read` | 🟢 |
| `/admin/institutions/[id]` | Institution detail + edit | `institution:update` | 🟢 |
| `/admin/trials` | All trials, register a trial | `trial:read` | 🟢 |
| `/admin/sites` | All sites | `site:read` | 🟢 |
| `/admin/audit` | Audit trail explorer | `audit:read` | 🟡 |
| `/admin/settings` | System configuration | `user:update` | 🟡 |
| `/investigator` | PI dashboard | authenticated | 🟡 |
| `/investigator/trials` | My trials | `trial:read` | 🟢 |
| `/investigator/trials/[id]` | Trial workspace (tabs) | `trial:read` | 🟢 |
| `/investigator/participants` | Participants across my trials | `participant:read` | 🟢 |
| `/investigator/safety` | AEs on my trials | `adverse_event:read` | 🟢 |
| `/investigator/ethics` | My submissions + decisions | `ethics:read` | 🟢 |
| `/investigator/compliance` | Compliance per trial | `compliance:read` | 🟢 |
| `/investigator/analytics` | Enrolment / safety / compliance charts | `trial:read` | 🟡 |
| `/coordinator` | Coordinator dashboard — today-first | authenticated | 🟡 |
| `/coordinator/trials` | Assigned trials | `trial:read` | 🟢 |
| `/coordinator/participants` | Participant roster | `participant:read` | 🟢 |
| `/coordinator/participants/[id]` | Participant record (tabs) | `participant:read` | 🟢 |
| `/coordinator/participants/new` | Enrolment wizard | `participant:create` | 🟢 |
| `/coordinator/visits` | Visit calendar + list | `visit:read` | 🟢 |
| `/coordinator/clinical-data` | Data entry queue | `observation:read` | 🟢 |
| `/coordinator/documents` | Trial master file | `document:read` | 🟢 |
| `/coordinator/adverse-events` | AEs at my sites | `adverse_event:read` | 🟢 |
| `/coordinator/compliance` | Compliance checklist | `compliance:read` | 🟢 |
| `/research` | Staff dashboard | authenticated | 🟡 |
| `/research/participants` | My site's participants | `participant:read` | 🟢 |
| `/research/visits` | Today's visits | `visit:read` | 🟢 |
| `/research/visits/[id]` | Visit data-entry screen | `observation:create` | 🟢 |
| `/research/clinical-data` | Data entry queue | `observation:read` | 🟢 |
| `/research/adverse-events` | Report / list AEs | `adverse_event:create` | 🟢 |
| `/research/documents` | Current protocol + ICF | `document:read` | 🟢 |
| `/ethics` | Ethics dashboard | authenticated | 🟡 |
| `/ethics/pending-reviews` | Review queue | `ethics:read` | 🟢 |
| `/ethics/pending-reviews/[id]` | Submission review workspace | `ethics:review` | 🟢 |
| `/ethics/reviews` | My reviews | `ethics:review` | 🟢 |
| `/ethics/documents` | Documents on submissions to my IEC | `document:read` | 🟢 |
| `/ethics/history` | Decision history | `ethics:read` | 🟢 |
| `/safety` | Safety dashboard | authenticated | 🟡 |
| `/safety/adverse-events` | Cross-trial AE queue | `adverse_event:read` | 🟢 |
| `/safety/adverse-events/[id]` | Adjudication workspace | `adverse_event:review` | 🟢 |
| `/safety/reviews` | My adjudications | `safety_report:read` | 🟢 |
| `/safety/monitoring` | Trends and signal detection | `safety_report:read` | 🟡 |
| `/safety/reports` | Generate / list safety reports | `safety_report:create` | 🟡 |
| `/regulator` | National oversight dashboard | authenticated | 🟡 |
| `/regulator/trials` | All trials nationally | `trial:read` | 🟢 |
| `/regulator/institutions` | All institutions | `institution:read` | 🟢 |
| `/regulator/sites` | All sites | `site:read` | 🟢 |
| `/regulator/gis` | National map, compliance-coloured | `gis:read` | 🟡 |
| `/regulator/compliance` | Compliance across all trials | `compliance:read` | 🟢 |
| `/regulator/safety` | Aggregate safety | `adverse_event:read` | 🟡 |
| `/regulator/regulatory` | Regulatory status per trial | `regulatory:report` | 🟡 |
| `/regulator/reports` | Report generation | `regulatory:report` | 🟡 |

---

## 7 · The authenticated shell

Every route under `(dashboard)` renders inside one shell. Build it once.

```text
┌────────────────────────────────────────────────────────────────────────┐
│ ▤  SIH26046 CTMS          [trial switcher ▾]      ⌘K   🔔 3   PB ▾    │  56px topbar
├──────────────┬─────────────────────────────────────────────────────────┤
│              │  Participants                              [+ Enrol]    │
│  Dashboard   │  Trial CT-2026-014 · Delhi AIIMS (DEL-01)               │  PageHeader
│  Trials      │  ─────────────────────────────────────────────────────  │
│  Participants│  [ search ] [status ▾] [site ▾]          42 results     │  FilterBar
│  Visits      │  ┌───────────────────────────────────────────────────┐  │
│  Clinical    │  │ Subject code   Status   Site   Enrolled   Next    │  │
│  Documents   │  │ …                                                 │  │
│  Adverse ev. │  └───────────────────────────────────────────────────┘  │
│  Compliance  │                                                         │
│  ──────────  │                                                         │
│  Map         │                                                         │
│  Profile     │                                                         │
└──────────────┴─────────────────────────────────────────────────────────┘
   240px                              content, max-w-[1600px]
```

### 7.1 Topbar

| Element | Behaviour |
|---|---|
| Logo / product name | Links to the role's dashboard |
| **Trial switcher** | Only where the role works within a trial context (PI, Coordinator, Research). A combobox over `GET /trials`. The selection persists in the URL as `?trialId=` **and** in `localStorage`. Admin, Ethics, Safety and Regulator do not get one |
| **Command palette** (`⌘K` / `Ctrl+K`) | Jump to a trial, a subject code, a page. Ship in v2, but reserve the shortcut now |
| **Notifications bell** | Count badge from the derived queue (§9.9). Popover shows the top 5 grouped by kind, with "See all" → `/notifications` |
| **User menu** | Full name, role display name, institution. Links: Profile, Sign out |

**Environment banner.** When `NEXT_PUBLIC_ENV !== 'production'`, a 24px amber strip above the topbar reading **"Demonstration environment — synthetic data only."** The platform runs on free-tier infrastructure with no BAA; this banner is a compliance statement, not decoration. Do not make it dismissible.

### 7.2 Sidebar

Nav items are declared once, per role, as data — never as JSX with inline conditionals:

```ts
type NavItem = { label: string; href: string; icon: LucideIcon; permission?: string; badge?: 'notifications' | 'pendingReviews' };
const NAV: Record<RoleName, NavItem[]> = { … };
```

Items whose `permission` the user lacks are filtered out before render. Collapsible to 64px icons-only; the state persists. On mobile it becomes a sheet.

### 7.3 PageHeader

Every page uses the same header component: **title**, a **context line** (trial protocol number · site code · status badge), a **description** where the page needs explaining, and a **primary action slot** on the right. Never more than one primary (filled) button per page.

### 7.4 Breadcrumbs

Only on detail pages, and only where the parent is a real navigable list:
`Participants › CT-2026-014-DEL-0042 › Visit 4 — Week 8`

---

## 8 · Design system

The visual target is **calm, dense, and unmistakably clinical**. Not a consumer SaaS dashboard; not a hospital EMR from 2004. Think: a well-made instrument. Restraint everywhere except where safety demands attention.

### 8.1 Colour

Semantic tokens only. No component ever references a raw Tailwind colour.

```css
--bg              #FAFAFA   /* page ground */
--surface         #FFFFFF   /* cards, tables */
--surface-sunken  #F4F4F5   /* table headers, code blocks */
--border          #E4E4E7
--text            #18181B
--text-muted      #71717A

--primary         #1D4ED8   /* actions, links, focus */
--primary-fg      #FFFFFF

--success         #15803D   /* compliant, clean, completed */
--warning         #B45309   /* due soon, pending, out-of-window */
--danger          #B91C1C   /* serious AE, non-compliant, infected, overdue */
--info            #0E7490   /* informational, in-progress */
```

**Dark mode is required** (clinical staff work night shifts, and half the demo audience will have it on). Define the light palette on `:root`, then redefine the same tokens under `@media (prefers-color-scheme: dark)` and `[data-theme="dark"]`. Never give a colour its only definition inside a media query.

**Colour is never the only signal.** Every status carries a shape or a word: `● Serious`, `▲ Overdue`, `✓ Compliant`. Around 1 in 12 men has a colour vision deficiency and this is safety data.

**Reserve red.** `--danger` means *serious adverse event*, *non-compliant*, *infected file*, or *overdue expedited report*. If red appears on a routine screen the eye stops trusting it. A validation error is red-bordered on its field; it is not a red banner across the page.

### 8.2 Type

| Role | Spec |
|---|---|
| Body / UI | `Inter`, 14px / 20px |
| Dense table cells | 13px / 18px |
| Page title | 20px / 28px, 600 |
| Section title | 15px / 22px, 600 |
| Caption, help text | 12px / 16px, `--text-muted` |
| **Numeric & coded data** | `JetBrains Mono` or `ui-monospace`, `font-variant-numeric: tabular-nums` |

Monospace + tabular numerals for: subject codes, protocol numbers, site codes, submission numbers, checksums, observation values, doses, counts in tables. Columns of numbers that do not align are columns of numbers that get misread.

### 8.3 Spacing and density

4px base scale. Two table densities, user-togglable and persisted: **comfortable** (44px rows) and **compact** (32px rows). Data-capture users will pick compact within a day; do not make comfortable the only option.

Page content: `max-width: 1600px`, `padding: 24px`. Forms: `max-width: 720px` for single-column, full width for multi-column clinical grids.

### 8.4 The status badge — one component, one map

Every status in the system renders through `<StatusBadge domain="trial" value="ACTIVE" />`. The domain→value→(colour, icon, label) mapping lives in **one file**. There are 14 status enums in this platform; if each page maps its own, they will drift by week three.

| Domain | Values → tone |
|---|---|
| `trial` | `DRAFT` neutral · `PENDING_ETHICS` info · `APPROVED` info · `ACTIVE` success · `SUSPENDED` warning · `COMPLETED` neutral · `TERMINATED` danger · `REJECTED` danger · `ARCHIVED` muted |
| `site` | `PLANNED` neutral · `ACTIVATED` info · `ENROLLING` success · `CLOSED_TO_ENROLLMENT` warning · `SUSPENDED` warning · `COMPLETED` neutral |
| `participant` | `SCREENING` info · `ENROLLED` info · `ACTIVE` success · `COMPLETED` neutral · `WITHDRAWN` warning · `LOST_TO_FOLLOWUP` warning · `SCREEN_FAILED` neutral |
| `visit` | `SCHEDULED` info · `COMPLETED` success · `MISSED` danger · `CANCELLED` muted · `OUT_OF_WINDOW` warning |
| `observation` | `RECORDED` neutral · `AMENDED` warning · `QUERIED` warning · `VERIFIED` success |
| `consent` | `ACTIVE` success · `SUPERSEDED` muted · `WITHDRAWN` danger |
| `adverseEvent` | `REPORTED` warning · `UNDER_REVIEW` info · `REVIEWED` neutral · `CLOSED` muted |
| `seriousness` | `NON_SERIOUS` neutral · `SERIOUS` **danger, always with the ● dot** |
| `severity` | `MILD` neutral · `MODERATE` warning · `SEVERE` danger |
| `causality` | `UNRELATED` · `UNLIKELY` · `POSSIBLE` warning · `PROBABLE` warning · `DEFINITE` danger |
| `ethicsSubmission` | `SUBMITTED` info · `UNDER_REVIEW` info · `APPROVED` success · `APPROVED_WITH_CONDITIONS` warning · `REJECTED` danger · `WITHDRAWN` muted · `DEFERRED` warning |
| `document` | `PENDING_SCAN` info · `QUARANTINED` danger · `DRAFT` neutral · `CURRENT` success · `SUPERSEDED` muted · `WITHDRAWN` muted · `ARCHIVED` muted |
| `scan` | `PENDING` info · `CLEAN` success · `INFECTED` danger · `ERROR` warning |
| `compliance` | `PENDING` neutral · `IN_PROGRESS` info · `COMPLIANT` success · `NON_COMPLIANT` danger · `NOT_APPLICABLE` muted · `WAIVED` muted |
| `regulatory` | `NOT_SUBMITTED` neutral · `SUBMITTED` info · `APPROVED` success · `QUERY_RAISED` warning · `REJECTED` danger |

Labels are Title Case with underscores replaced: `APPROVED_WITH_CONDITIONS` → "Approved with conditions". Do the transform in the component, once.

### 8.5 Motion

150ms ease-out for hovers and popovers; 200ms for sheets and dialogs. Nothing animates on data arrival — a table that fades in on every refetch reads as latency. Respect `prefers-reduced-motion` by disabling all non-essential transitions.

### 8.6 Charts

- One categorical palette, defined once, colour-blind safe, at most 6 series before you switch to "top 5 + Other".
- **Always label the axes and state the unit.** A safety chart with an unlabelled y-axis is a liability.
- Line for time series (enrolment over time, AE rate). Bar for comparison across categories (enrolment by site). Stacked bar only for parts of a whole that genuinely sum. **No pie charts** beyond 3 slices, and never for anything a reader needs to compare precisely.
- Every chart has a **"View as table"** toggle. It is the accessibility answer and it is also what a regulator will actually ask for.
- **Suppressed cells never plot as zero.** See §11.3.
- Empty chart → an explicit "No data for this period" panel, not blank axes.

---

## 9 · Cross-cutting patterns

These are the patterns that make the platform feel like one product. Build them before building pages.

### 9.1 `<DataTable>` — the workhorse

One wrapper over TanStack Table, used by every list in the app.

**Required behaviour**

| Feature | Spec |
|---|---|
| Columns | Declared per usage. Column visibility toggle, persisted per table id |
| Sort | Client-side today (lists are unpaginated); designed for `?sort=field:asc\|desc` when the backend adds it |
| Filter bar | Above the table. Filters serialise **into the URL query string** — a filtered list must be shareable and back-button-safe |
| Row click | Navigates to detail. The whole row is clickable, but keyboard users get a focusable link in the first cell |
| Selection | Only where a bulk action genuinely exists. Do not add checkboxes speculatively |
| Density | Comfortable / compact toggle |
| Sticky header | Always |
| Empty state | See §9.2 |
| Loading | Skeleton rows matching the real row height — never a spinner that collapses the layout |
| Error | Inline panel with the request id and a Retry button |
| Export | CSV of the **currently visible, filtered** rows. **Never includes identity fields.** A confirm step states what is being exported |

**Pagination.** The backend returns plain arrays with no pagination today (§14.1). Build the table so `data`, `total`, `cursor` come from a hook; the hook currently returns the whole array. When cursor pagination lands, only the hook changes. **Do not build offset page numbers** — the API is specified as cursor-based, because offset pagination silently skips or repeats rows when data changes between pages, and on a clinical list that is a missed record.

### 9.2 Empty states — the important subtlety

The API deliberately cannot distinguish "does not exist" from "outside your scope". A `404` and an empty list mean the same thing on purpose: telling a research nurse that participant `4f2a` exists but belongs to another site confirms a record they may not know about.

**So the UI must never say "no results found" in a way that implies the data does not exist.** Three distinct empty states:

| Case | Copy | Action |
|---|---|---|
| Nothing created yet, and the user can create | *"No participants enrolled at this site yet."* | Primary: "Enrol participant" |
| Nothing created yet, user cannot create | *"No participants have been enrolled at this site."* | none |
| Filters returned nothing | *"No participants match these filters."* | "Clear filters" |

Never: *"You do not have access to these records."* We do not have that information, and asserting it is both wrong and a disclosure.

### 9.3 Optimistic concurrency — `ETag` / `If-Match`

This is mandatory on updates and it is the pattern most likely to be got wrong.

**How it works.** `GET` a resource → response carries `ETag: "3"`. To update, send `If-Match: "3"`. If the header is missing the backend returns **428 Precondition Required**. If the version has moved on, **409 Conflict**.

**Frontend contract**

1. The API client stores the `ETag` from every `GET` of a versioned resource, keyed by resource URL.
2. Every `PATCH` / `POST /{id}/status` automatically attaches the stored `ETag` as `If-Match`. **Never hand-write this per call site.**
3. `428` is a bug in our code, not a user-facing state. Log it loudly in dev; in production, refetch and retry once, then surface a generic error.
4. `409` is a real, expected, user-facing event and needs a real dialog:

```
┌─ This record changed while you were editing ───────────────┐
│                                                            │
│  Someone else updated this trial since you opened it.      │
│  Your changes have not been saved.                         │
│                                                            │
│  Field           Their value        Your value             │
│  Status          SUSPENDED          ACTIVE                 │
│  Title           …                  …                      │
│                                                            │
│         [ Discard my changes ]   [ Reopen with their       │
│                                     version and re-apply ] │
└────────────────────────────────────────────────────────────┘
```

Never silently retry a 409 with the new ETag. That is last-write-wins with extra steps, and it discards a colleague's edit — which is exactly what the mechanism exists to prevent.

Resources carrying a version, and therefore this whole flow: `trials`, `trial_sites`, `participants`, `visits`, `observations`, `medications`, `adverse_events`, `safety_reviews`, `ethics_submissions`, `ethics_reviews`, `trial_compliance`, `documents`, `institutions`.

### 9.4 Reason-required actions

Some writes are meaningless without a stated reason. The backend enforces it with a `CHECK` constraint; the UI must ask for it properly rather than let the user discover a 422.

| Action | Field | Rule |
|---|---|---|
| Amend an observation | `amendment_reason` | **Required.** GCP requirement, not a nicety — a clinical value cannot change without a stated reason |
| Withdraw a participant | `withdrawal_reason` + `withdrawal_date` | Required |
| Withdraw consent | `withdrawal_reason` | Required |
| Ethics decision `APPROVED_WITH_CONDITIONS` | `conditions` | Required |
| Mark compliance `COMPLIANT` | `evidence_document_id` **or** `notes` | At least one |
| Mark an AE `SERIOUS` | `serious_criteria[]` | At least one criterion |

Pattern: a dialog with the reason field **focused on open**, the confirm button **disabled until the reason is non-empty**, and a plain-language restatement of the consequence above the field. Minimum 10 characters — "asdf" is not a reason, and a soft minimum discourages it without being a puzzle.

### 9.4.4 Re-identification — the most sensitive interaction in the app

Revealing a participant's real identity is gated on `participant_identity:read`, which **no role holds by default**, and every read is written to the audit trail. The UI must make that visible, not incidental.

```
┌─ Participant identity ─────────────────────────────────────┐
│  🔒  Identifying information is hidden.                     │
│                                                            │
│  Viewing this reveals the participant's name and contact   │
│  details. This access is recorded in the audit trail       │
│  against your account.                                     │
│                                                            │
│                              [ Reveal identity ]           │
└────────────────────────────────────────────────────────────┘
```

After reveal: the panel shows the fields, a persistent line reading *"Viewed by you at 14:32 — recorded in the audit trail"*, and it **auto-collapses after 5 minutes or on navigation away**. Identity fields are never copied into any table, chart, export, tooltip, page title, or URL. `document.title` must not contain a participant name.

If the user lacks the permission, the panel is absent entirely — not disabled. There is nothing to explain.

### 9.5 Idempotency on enrolment

`POST /participants` accepts an **`Idempotency-Key`** header. A retried enrolment must return the original participant, not create a second person.

Generate a UUID **once when the enrolment form is first opened**, hold it in form state, and send it with every submit attempt of that form — including retries after a network failure and after the user hits the button twice. Generate a new key only when the form is reset for a new participant. Getting this wrong produces a duplicate human being in a clinical trial.

The submit button disables on click and shows an inline spinner. Double-submit protection in the UI **and** the idempotency key in the request; neither alone is enough.

### 9.6 Error handling

Backend errors are shaped `{ error: { code, message, request_id } }` (auth endpoints today return `{ error: { code, message } }`).

| Status | Meaning | UI |
|---|---|---|
| `400` | Malformed | Inline form error; log it — usually our bug |
| `401` | Unauthenticated | Refresh flow (§4.4). Never a toast |
| `403` | Lacks permission | *"You do not have permission to do this."* This should be **unreachable** if §5 gating is right — log it as a UI defect |
| `404` | Not found **or out of scope** | The empty state of §9.2. Never "access denied" |
| `409` | Conflict — stale write, or an illegal state transition | Conflict dialog (§9.3), or the transition message from the body |
| `422` | Validation | Map to field-level errors. Fall back to a form-level message |
| `428` | Missing `If-Match` | Our bug. §9.3 |
| `429` | Rate limited | *"Too many attempts. Try again in a moment."* With a countdown where `Retry-After` is present |
| `5xx` | Server | Error panel with **the `request_id` shown and one-click copyable**. That id is what makes a bug report actionable |

Toasts are for **confirmations of things the user did** ("Visit marked completed"), never for errors that need action. Errors live next to the thing that failed.

### 9.7 Loading

- Route transitions: Next.js `loading.tsx` with a skeleton that matches the real layout.
- In-page refetch: keep old data visible, show a subtle top progress bar. Never blank a populated table.
- Mutations: the button spins and disables; the rest of the page stays interactive.
- Anything expected to exceed 2 s (document upload, report generation) gets **determinate progress**, not a spinner.

### 9.8 Confirmation and destruction

There are no clinical deletes. The actions that warrant confirmation are the irreversible *state* changes:

| Action | Confirmation |
|---|---|
| Withdraw a participant | Dialog + reason + type the subject code to confirm |
| Publish a document version (supersedes the current one) | Dialog naming both versions: *"v3 will replace v2 as the current protocol"* |
| Record a final ethics decision | Dialog — the decision is part of the regulatory record |
| Finalise an ethics review | Dialog — **it becomes immutable** |
| Transition a trial to `TERMINATED` / `ARCHIVED` | Dialog + type the protocol number |
| Deactivate a user | Dialog naming the user |
| Revoke a session | Simple confirm |

Type-to-confirm only for the four genuinely unrecoverable ones. Applied everywhere it becomes a reflex and stops being a safeguard.

### 9.9 The notifications page

`/notifications` is a **derived view**. There is no notifications table and no notifications endpoint. It is composed client-side from queries the user is already entitled to make, each one automatically scoped by RLS:

| Notification | Derived from | Route to |
|---|---|---|
| Pending ethics reviews | `GET /ethics/submissions?institutionId=&status=SUBMITTED` | `/ethics/pending-reviews` |
| Unreviewed adverse events | `GET /adverse-events?trialId=` filtered to `REPORTED`/`UNDER_REVIEW` | `/safety/adverse-events` |
| Overdue visits | `GET /visits?participantId=` where `scheduled_date < today && status === 'SCHEDULED'` | `/coordinator/visits` |
| Expiring ethics approvals | submissions where `approvalValidUntil` within 30 days | `/investigator/ethics` |
| Overdue compliance items | `GET /compliance/trials/{id}` where `dueDate < today` and status incomplete | `/coordinator/compliance` |
| Documents pending scan | `GET /documents?trialId=` where `scanStatus === 'PENDING'` beyond a threshold | `/coordinator/documents` |

Group by kind, sort by urgency then age, cap at 50 per group. Each row states **what**, **which trial/participant**, **how overdue**, and links straight into the work.

> Note the shape mismatch: several of these need a "for all my trials" query the API does not offer yet (§14.1 lists the required params). Until then, fan out across the user's trials from `GET /trials` and merge client-side. Keep that fan-out in **one hook** so it becomes one request later.

### 9.10 Search

No global search endpoint exists. Per-list client-side filtering only, over the fields already loaded. **Participant search is by subject code and screening number only** — never by name. There is deliberately no index on participant names in the database, precisely so that free-text name search cannot become a feature.

---

## 10 · Entity field reference

Everything below is what the form must collect and what the table may show. Enum values are exact strings — send them uppercase, exactly as written.

### 10.1 Trial

| Field | Type | Form | Rules |
|---|---|---|---|
| `protocolNumber` | text | **create only**, required | e.g. `CT-2026-014`. Unique — a duplicate returns 409 |
| `title` | text | required | |
| `shortTitle` | text | optional | Used on cards and map popups; keep under 60 chars |
| `sponsorInstitutionId` | uuid | required, combobox over institutions | |
| `phase` | enum | required | `I` `II` `III` `IV` `OBSERVATIONAL` |
| `therapeuticArea` | text | optional | |
| `ctriNumber` | text | optional | Clinical Trials Registry–India id 🟡 not in the API yet |
| `targetEnrollment` | int > 0 | optional | |
| `currentEnrollment` | int | **read-only** | Maintained by the backend transactionally |
| `status` | enum | via the status action only | See the state machine below |
| `regulatoryStatus` | enum | regulator only 🟡 | `NOT_SUBMITTED` `SUBMITTED` `APPROVED` `QUERY_RAISED` `REJECTED` |
| dates | date | 🟡 | `plannedStartDate` `actualStartDate` `plannedEndDate` `actualEndDate`; planned end ≥ planned start |

**Trial state machine — enforce it in the UI.** Only offer legal transitions:

```
DRAFT ──▸ PENDING_ETHICS ──▸ APPROVED ──▸ ACTIVE ──▸ COMPLETED ──▸ ARCHIVED
                │                            │  ▲          
                ▼                            ▼  │          
             REJECTED                    SUSPENDED ──▸ TERMINATED ──▸ ARCHIVED
```

| From | Legal next |
|---|---|
| `DRAFT` | `PENDING_ETHICS` |
| `PENDING_ETHICS` | `APPROVED`, `REJECTED` |
| `APPROVED` | `ACTIVE` |
| `ACTIVE` | `SUSPENDED`, `COMPLETED` |
| `SUSPENDED` | `ACTIVE`, `TERMINATED` |
| `COMPLETED`, `TERMINATED` | `ARCHIVED` |
| `REJECTED`, `ARCHIVED` | *terminal* |

The transition that matters is `DRAFT → ACTIVE`: it is **not legal**, and the reason is that enrolling participants into a trial no ethics committee has approved is exactly the failure this state machine exists to prevent. An illegal transition returns `409` with a message; render it.

Derived rules the UI should honour:

- **Enrolment is allowed only when `ACTIVE`.** Otherwise disable "Enrol participant" with a tooltip naming the status.
- **New clinical data** is allowed when `ACTIVE` or `SUSPENDED` (suspension halts new enrolment while follow-up continues).
- **Corrections** are allowed when `ACTIVE`, `SUSPENDED`, `COMPLETED` or `TERMINATED`.

### 10.2 Institution

| Field | Type | Rules |
|---|---|---|
| `name` | text | required |
| `institutionType` | enum | required — `GOVERNMENT_HOSPITAL` `PRIVATE_HOSPITAL` `MEDICAL_COLLEGE` `RESEARCH_CENTRE` `CRO` |
| `registrationNumber` | text | optional, unique — CDSCO / national registry id |
| `city`, `state` | text | **required**. `state` drives all state-level map aggregation |
| `country` | text | defaults `India` |
| `addressLine`, `postalCode` | text | optional |
| `latitude` / `longitude` | decimal(9,6) | **Both or neither.** Half a coordinate silently plots on Null Island, and the database rejects it. Range −90..90 / −180..180 |
| `hasEthicsCommittee` | boolean | Gates whether ethics submissions can be routed here — filter the ethics submission form's institution picker on it |
| `status` | enum | `ACTIVE` `INACTIVE` `ARCHIVED` |

**Coordinate entry UX.** Two number inputs plus a small Leaflet picker: click the map to fill both fields, drag the pin to adjust, or type coordinates and watch the pin move. Validate as a pair — disable save when exactly one is filled and say why.

### 10.3 Trial site

| Field | Type | Rules |
|---|---|---|
| `trialId`, `institutionId` | uuid | required. One site row per institution per trial |
| `siteCode` | text | required, unique within the trial. e.g. `DEL-01` |
| `status` | enum | `PLANNED` `ACTIVATED` `ENROLLING` `CLOSED_TO_ENROLLMENT` `COMPLETED` `SUSPENDED` |
| `activationDate` | date | |
| `targetEnrollment` | int | site-level target |
| `currentEnrollment` | int | read-only |
| `latitude`/`longitude` | decimal | **optional override.** Blank means "use the institution's location". Say so in the help text |

### 10.4 Trial staff assignment

| Field | Type | Rules |
|---|---|---|
| `trialId` | uuid | required |
| `trialSiteId` | uuid | **`null` means trial-wide.** A PI spans all sites |
| `userId` | uuid | required, combobox over users |
| `staffRole` | enum | `PI` `SUB_INVESTIGATOR` `COORDINATOR` `STAFF` `MONITOR` — the *trial* function, distinct from the platform role |
| `startDate` / `endDate` | date | `endDate` null = open-ended. **Setting a past end date removes access immediately** — warn in the confirm dialog |

**Hard rule:** `staffRole === 'STAFF'` **requires** a `trialSiteId`. Site-level staff without a site would silently gain trial-wide scope. Make the site field required and visible the moment `STAFF` is chosen.

This table *is* the authorization scope of the entire platform. The assignment form deserves a review-before-save step showing, in plain words: *"Priya Nair will be able to read and enter clinical data for CT-2026-014 at site DEL-01, from today, indefinitely."*

### 10.5 Participant (pseudonymised)

| Field | Type | Rules |
|---|---|---|
| `trialId`, `trialSiteId` | uuid | required; the site must belong to the trial |
| `subjectCode` | text | required, unique within the trial. **The identifier shown everywhere** |
| `screeningNumber` | text | optional 🟡 |
| `enrollmentDate` | date | required, not in the future |
| `randomizationArm` | text | optional, e.g. `TREATMENT` / `CONTROL` 🟡 |
| `status` | enum | `SCREENING` `ENROLLED` `ACTIVE` `COMPLETED` `WITHDRAWN` `LOST_TO_FOLLOWUP` `SCREEN_FAILED` |
| `dateOfBirthYear` | int | **year only**, 1900–2100. Age stratification without a re-identifying full DOB |
| `sex` | enum | `MALE` `FEMALE` `OTHER` `UNDISCLOSED` |
| `withdrawalDate`, `withdrawalReason` | date, text | Required together when status is `WITHDRAWN` |

### 10.6 Participant identity — handle with care

Collected **only** in the enrolment wizard, displayed **only** in the reveal panel of §9.4.4.

| Field | Notes |
|---|---|
| `fullName` | required at enrolment |
| `dateOfBirth` | full date — lives only here |
| `phone`, `email` | |
| `addressLine`, `city`, `state`, `postalCode` | |
| `nationalIdHash` | **the UI never sends a raw national id.** If collected at all, it is hashed before transmission — confirm the algorithm with backend before building this field |
| `emergencyContactName` / `Phone` | |

**Deliberately absent: any geographic point.** Participant addresses are never mappable. Do not add a map picker to this form.

### 10.7 Consent

| Field | Type | Rules |
|---|---|---|
| `participantId` | uuid | |
| `consentVersion` | text | required, e.g. `ICF v3.0` |
| `consentDocumentId` | uuid | the exact consent form version signed — link it |
| `consentType` | enum | `INITIAL` `RE_CONSENT` `AMENDMENT` |
| `consentedAt` | timestamp | required |
| `consentMethod` | enum | `WRITTEN` `ELECTRONIC` `WITNESSED_VERBAL` |
| `witnessName` | text | required when method is `WITNESSED_VERBAL` |
| `obtainedBy` | uuid | who took consent |
| `status` | enum | `ACTIVE` `SUPERSEDED` `WITHDRAWN` |

**A participant can hold exactly one `ACTIVE` consent.** Re-consent supersedes the previous one in the same transaction — the UI presents this as "Record re-consent", never as "edit consent".

**Consent is a hard gate.** No clinical write is permitted without a valid active consent. The participant record header shows a prominent consent chip; if it is missing or withdrawn, every data-entry action on that participant is disabled with the reason stated. `RESEARCH_STAFF` cannot see or capture consent at all — it is a coordinator responsibility.

### 10.8 Visit

| Field | Type | Rules |
|---|---|---|
| `participantId` | uuid | required |
| `visitName` | text | required, e.g. `Screening`, `Week 4`, `End of Study` |
| `visitNumber` | int | required, unique per participant |
| `scheduledDate` | date | required |
| `windowStartDate` / `windowEndDate` | date | protocol-allowed window; end ≥ start |
| `actualDate` | date | **required when marking `COMPLETED`** |
| `status` | enum | `SCHEDULED` `COMPLETED` `MISSED` `CANCELLED` `OUT_OF_WINDOW` |
| `performedBy` | uuid | |
| `notes` | text | |

**Window visualisation.** On the visit row and detail, render the window as a small bar with the scheduled and actual dates marked. A completed visit outside its window is a protocol deviation — badge it `OUT_OF_WINDOW` in warning and surface it on the coordinator's dashboard. This is the kind of thing a monitor asks about and the platform should answer at a glance.

### 10.9 Observation — the highest-volume form in the app

| Field | Type | Rules |
|---|---|---|
| `visitId` | uuid | required |
| `observationCode` | text | required, e.g. `VITALS_SBP`, `LAB_HB`. **Unique per visit** — that pair is also the idempotency key |
| `observationName` | text | required, human label |
| `category` | enum | `VITAL_SIGN` `LABORATORY` `PHYSICAL_EXAM` `QUESTIONNAIRE` `IMAGING` `OTHER` |
| `valueNumeric` / `valueText` / `valueBoolean` | one of three | **Exactly one must be non-null.** The form picks the input type from the field definition; never render all three |
| `unit` | text | e.g. `mmHg`, `g/dL` |
| `referenceRangeLow` / `High` | numeric | for out-of-range flagging 🟡 |
| `isAbnormal` | boolean | **clinician judgement, not derived.** A value inside the reference range may still be clinically abnormal — so this is a checkbox the clinician sets, pre-suggested by the range but never auto-locked |
| `status` | enum | `RECORDED` `AMENDED` `QUERIED` `VERIFIED` |
| `amendmentReason` | text | **required** when amending |

**Data entry UX — this is where the platform is won or lost.**

- A **grid**, not a stack of cards: one row per observation code, columns for value, unit, range, abnormal, notes. Clinicians enter dozens per visit.
- **Keyboard-first.** `Tab` moves across the row, `Enter` moves down to the next observation, `Esc` reverts the cell. Never trap focus in a dialog for a single value.
- **Autosave per row**, debounced ~800 ms, with a per-row status: *saving · saved 14:32 · failed, retry*. Never one giant Save button at the bottom of 40 fields.
- **Out-of-range values flag immediately** — amber left border on the cell plus a range hint. They are **not blocked**; an abnormal result is real data.
- **Numeric inputs are `inputMode="decimal"`**, never spinners, and never silently round.
- **Amendment is a distinct action, not an edit.** Once `RECORDED`, changing the value opens a dialog: old value, new value, mandatory reason. The row then shows `AMENDED` with the history accessible.

### 10.10 Medication

| Field | Type | Rules |
|---|---|---|
| `participantId` | uuid | required |
| `medicationName` | text | required |
| `medicationType` | enum | `STUDY_DRUG` `CONCOMITANT` `RESCUE` |
| `dose` / `doseUnit` | numeric(10,3) / text | |
| `frequency` | text | e.g. `BD`, `TDS` |
| `route` | enum | `ORAL` `IV` `IM` `SC` `TOPICAL` `INHALED` `OTHER` |
| `startDate` / `endDate` | date | end ≥ start |
| `indication` | text | why prescribed — **needed for causality assessment**, so treat it as important, not optional-looking |
| `isOngoing` | boolean | defaults true. **`isOngoing === true` requires `endDate` to be null** — wire the two controls together: setting an end date unchecks ongoing |

### 10.11 Adverse event — the safety-critical form

| Field | Type | Rules |
|---|---|---|
| `participantId` | uuid | required |
| `visitId` | uuid | **optional** — events occur between visits |
| `eventTerm` | text | required, the reported term e.g. `Nausea` |
| `meddraCode` | text | optional coded term 🟡 |
| `description` | text | required. The narrative. **Never shown in any aggregate, chart, export or map** |
| `onsetDate` | date | required |
| `resolutionDate` | date | ≥ onset |
| `severity` | enum | required — `MILD` `MODERATE` `SEVERE` |
| `seriousness` | enum | `NON_SERIOUS` (default) `SERIOUS` |
| `seriousCriteria` | string[] | **required, ≥1, when `SERIOUS`** — death · life-threatening · hospitalisation · disability · congenital anomaly · other |
| `causality` | enum | **not set by the reporter.** Set by the Safety Officer at review |
| `outcome` | enum | `RECOVERED` `RECOVERING` `NOT_RECOVERED` `RECOVERED_WITH_SEQUELAE` `FATAL` `UNKNOWN`. **`FATAL` requires `seriousness = SERIOUS`** |
| `actionTaken` | text | |
| `status` | enum | `REPORTED` `UNDER_REVIEW` `REVIEWED` `CLOSED` |

**Reporting an AE must never be hard to find.** On `/research` and `/coordinator` it is a prominent primary action on the dashboard itself, not buried in a submenu. On a participant record it is a persistent button in the header.

**The seriousness interaction.** Selecting `SERIOUS` expands the criteria checklist inline with a short explanation of what changes: *"Serious events are escalated to the Safety Officer for adjudication and may require expedited regulatory reporting."* Submit stays disabled until at least one criterion is ticked. Do not hide this behind an accordion.

**Do not offer `causality` to a reporter.** It is the adjudicator's field. Showing it greyed out on the report form invites the reporter to think they should have filled it.

### 10.12 Safety review (adjudication)

| Field | Type | Rules |
|---|---|---|
| `adverseEventId` | uuid | |
| `assessedSeverity` | enum | required — **may differ from the reported severity, and that divergence is itself a signal.** Show both side by side and highlight a difference |
| `assessedCausality` | enum | required — `UNRELATED` `UNLIKELY` `POSSIBLE` `PROBABLE` `DEFINITE` |
| `isExpected` | boolean | required — listed in the Investigator's Brochure? |
| `requiresExpeditedReporting` | boolean | **Unexpected + serious + related ⇒ expedited.** Auto-suggest it when those three hold, with the reasoning shown, and let the officer override |
| `reportedToAuthorityAt` | timestamp | **required before a review with expedited reporting can be `CLOSED`** — enforce in the UI, the database enforces it too |
| `comments` | text | |
| `decision` | enum | required — `ACCEPTED` `QUERY_RAISED` `ESCALATED` `CLOSED` |

The adjudication workspace shows, on one screen: the event, the participant's **observations and medications** (the safety officer's event-triggered clinical read), prior reviews, and the form. Causality cannot be judged without the concomitant medications — put them beside the form, not behind a tab.

### 10.13 Ethics submission

| Field | Type | Rules |
|---|---|---|
| `trialId` | uuid | required |
| `institutionId` | uuid | required — **the IEC scope key.** Only institutions with `hasEthicsCommittee` |
| `submissionNumber` | text | required, unique per institution. e.g. `IEC/2026/0142` |
| `submissionType` | enum | `INITIAL` `AMENDMENT` `CONTINUING_REVIEW` `SAE_REPORT` `FINAL_REPORT` |
| `summary` | text | required |
| `protocolDocumentId` | uuid | the exact protocol version submitted — pick from the trial's documents |
| `status` | enum | `SUBMITTED` `UNDER_REVIEW` `APPROVED` `APPROVED_WITH_CONDITIONS` `REJECTED` `WITHDRAWN` `DEFERRED` |
| `decisionDate` | date | required for `APPROVED` / `APPROVED_WITH_CONDITIONS` / `REJECTED` |
| `approvalValidUntil` | date | drives continuing-review reminders — surface "expiring in N days" everywhere it appears |
| `conditions` | text | **required** for `APPROVED_WITH_CONDITIONS` |

### 10.14 Ethics review

| Field | Type | Rules |
|---|---|---|
| `ethicsSubmissionId` | uuid | |
| `recommendation` | enum | `APPROVE` `APPROVE_WITH_CONDITIONS` `REJECT` `DEFER` `REQUEST_CLARIFICATION` |
| `comments` | text | required — deliberation content |
| `conflictOfInterestDeclared` | boolean | **Ask this explicitly, before the recommendation fields.** It is a real governance step, not a checkbox at the bottom |
| `isFinalized` | boolean | **Once true the review is immutable.** Two-step: Save draft (editable) → Finalise (confirm dialog, then read-only forever) |

One review per member per submission. The submitting PI can never read individual reviews — only the submission's decision.

### 10.15 Document

| Field | Type | Rules |
|---|---|---|
| `documentType` | enum | `PROTOCOL` `CONSENT_FORM` `ETHICS_APPROVAL` `REGULATORY_SUBMISSION` `INVESTIGATOR_BROCHURE` `CV` `SOURCE_DOCUMENT` `SAFETY_REPORT` `MONITORING_REPORT` `OTHER` |
| `title` | text | required |
| `trialId` / `institutionId` / `trialSiteId` | uuid | **At least one of trial or institution is required.** An unscoped document is invisible to everyone including its uploader — validate before submitting |
| `file` | multipart | **max 50 MB** |
| `effectiveDate` / `expiryDate` | date | for approvals with a validity period |
| `version` | int | read-only, assigned by the backend |
| `status` | enum | `PENDING_SCAN` `QUARANTINED` `DRAFT` `CURRENT` `SUPERSEDED` `WITHDRAWN` `ARCHIVED` |
| `scanStatus` | enum | `PENDING` `CLEAN` `INFECTED` `ERROR` |
| `checksumSha256` | text | read-only — show it in the detail panel, monospace, copyable |

**The upload lifecycle is asynchronous and the UI must show it honestly.**

```
uploading ──▸ PENDING_SCAN ──▸ CLEAN ──▸ DRAFT ──▸ (publish) ──▸ CURRENT
                    │
                    └──▸ INFECTED ──▸ QUARANTINED   (the bytes are deleted)
```

- After upload, the row shows **"Scanning for malware…"** with an indeterminate indicator. Poll `GET /documents/{id}` every 3 s, backing off to 10 s, for up to 2 minutes.
- **`QUARANTINED` is a real outcome and must be shown plainly:** *"This file was found to contain malware and has been removed. Nothing was stored."* Not a generic error.
- **Download is disabled until `scanStatus === 'CLEAN'`**, with the reason on the tooltip.
- `ERROR` → *"The scan could not complete. This file cannot be downloaded until it is re-scanned."*

**Download is a two-step redirect.** `GET /documents/{id}/download` returns a **302 to a signed URL valid for 5 minutes**. Follow it with a plain browser navigation (`window.location` or a real link) — **do not `fetch()` it and re-serve the blob**, and never cache, log, or store the signed URL. It is minted per request precisely so it cannot outlive the permission check that produced it.

**Versioning is the point of this module.** A document detail page shows the whole family chain — v1, v2, v3 with dates, uploaders, checksums and status — because "which protocol version was in force on this date" must be answerable. Superseded versions stay readable, never hidden.

**Publishing ≠ uploading.** Adding v3 leaves v2 `CURRENT`; someone with `document:supersede` must explicitly publish. The UI must express this: uploading an amendment is not approving one. Two separate actions, two separate permissions, and a confirm dialog naming both versions.

### 10.16 Compliance

**Requirement (catalogue)** — `code`, `title`, `description`, `category` (`REGULATORY` `ETHICS` `SAFETY` `DATA_INTEGRITY` `SITE_QUALIFICATION` `DOCUMENTATION`), `authority` (`CDSCO` / `ICMR` / `DCGI`), `appliesToPhase[]` (null = all), `isMandatory`, `evidenceRequired`, `status`.

**Per-trial item** — `complianceRequirementId`, `trialSiteId` (nullable), `status` (`PENDING` `IN_PROGRESS` `COMPLIANT` `NON_COMPLIANT` `NOT_APPLICABLE` `WAIVED`), `evidenceDocumentId`, `dueDate`, `completedDate`, `verifiedBy` / `verifiedAt`, `notes`.

Rules to enforce in the form: `COMPLIANT` requires a `completedDate` **and** either an evidence document or notes. Render the checklist grouped by category, mandatory items first, overdue items pinned to the top with a `▲ Overdue by N days` marker.

### 10.17 User (admin)

`email` (unique, case-insensitive), `fullName`, `roleId`, `institutionId`, `status` (`ACTIVE` `INACTIVE` `LOCKED`), `mfaEnabled`, `failedLoginCount`, `lastLoginAt`.

**An active `ETHICS_MEMBER` must have an institution** — it is their IEC scope and the database refuses to store one without it. Make the institution field required the moment that role is selected, and say why.

---

## 11 · Key screens in detail

Everything else is a list or a form built from §9 and §10. These five screens carry the product.

### 11.1 The enrolment wizard — `/coordinator/participants/new`

The most consequential form in the platform: one `POST` writes five tables atomically. Either all of it lands or none of it does, so the UI must collect everything before submitting.

**Four steps, with a persistent step indicator and a review step.**

| Step | Collects |
|---|---|
| 1 · Trial & site | `trialId`, `trialSiteId`. Pre-filled from the trial switcher. Site options filtered to the trial. **Blocks with an explanation if the trial is not `ACTIVE`** |
| 2 · Participant | `subjectCode`, `enrollmentDate`, `dateOfBirthYear`, `sex`, `screeningNumber` |
| 3 · Identity | `fullName` (required), `dateOfBirth`, `phone`. Panel headed *"Identifying information — stored separately from all clinical data and visible only with explicit permission"* |
| 4 · Consent | `consentVersion`, `consentMethod`, `consentedAt`, witness where applicable |
| Review | Everything, with identity fields masked behind a "Show" toggle. One **"Enrol participant"** button |

**Requirements**

- One `Idempotency-Key`, generated when the wizard opens, sent on every submit attempt (§9.5).
- Draft persisted to `sessionStorage` on every step change — a dropped connection at step 4 must not cost the whole form.
- `409` on `subjectCode` → land the user back on step 2 with the field focused and *"This subject code is already used in this trial."*
- On success: toast, then navigate to the new participant's record.
- **Never a partial save.** No "save and continue later" that writes to the server.

### 11.2 The participant record — `/coordinator/participants/[id]`

The spine of the clinical UI. A header plus tabs.

```
┌────────────────────────────────────────────────────────────────────────┐
│  CT-2026-014-DEL-0042                      ● Active                    │
│  CT-2026-014 · DEL-01 AIIMS Delhi · Enrolled 12 Mar 2026 · F · b.1987  │
│  ✓ Consent active — ICF v3.0, written, 12 Mar 2026                     │
│                                    [Report adverse event] [Actions ▾]  │
├────────────────────────────────────────────────────────────────────────┤
│  Overview │ Visits │ Observations │ Medications │ Adverse events │     │
│           │        │              │             │ Consent │ Documents  │
└────────────────────────────────────────────────────────────────────────┘
```

| Tab | Contents |
|---|---|
| **Overview** | A merged **timeline** — visits, observations, medications and AEs on one vertical chronology. This is the single most useful view in the app: it is how a clinician actually reads a participant |
| Visits | Table + calendar toggle. Row → visit data-entry screen |
| Observations | Grouped by visit, then by category. Trend sparkline per repeated code |
| Medications | Ongoing first, then ended. Type and route as badges |
| Adverse events | Chronological. Serious ones pinned and marked |
| Consent | History with the active one first; "Record re-consent" and "Withdraw consent" actions |
| Documents | Source documents scoped to this participant |

The **identity panel** (§9.4.4) sits in the header area, collapsed, only when the user holds `participant_identity:read`.

`Actions ▾` holds: Edit details · Withdraw participant · Mark completed. Withdrawal is reason-required and type-to-confirm.

### 11.3 The visit data-entry screen — `/research/visits/[id]`

Optimised for one thing: a clinician entering 20–40 values with a participant in front of them.

```
┌────────────────────────────────────────────────────────────────────────┐
│  Week 8 · Visit 4          CT-2026-014-DEL-0042      ● Scheduled       │
│  Scheduled 14 May · window 12–18 May          [Mark completed]         │
├────────────────────────────────────────────────────────────────────────┤
│  Vital signs                                                           │
│  Code        Observation           Value      Unit    Range     Abn.   │
│  VITALS_SBP  Systolic BP          [ 148 ]     mmHg    90–140     ☑  ⚠ │
│  VITALS_DBP  Diastolic BP         [  88 ]     mmHg    60–90      ☐    │
│  VITALS_HR   Heart rate           [  72 ]     bpm     60–100     ☐  ✓ │
│  Laboratory                                                            │
│  LAB_HB      Haemoglobin          [ 11.2 ]    g/dL    12–16      ☑  ⚠ │
│                                                    saved 14:32         │
└────────────────────────────────────────────────────────────────────────┘
```

- **Autosave per row**, ~800 ms debounce, per-row save state. No global Save button.
- `Tab` across, `Enter` down, `Esc` reverts the cell.
- Out-of-range → amber cell border + the range shown. Never blocked.
- **"Mark completed" requires `actualDate`** and warns if the actual date falls outside the protocol window: *"This visit is outside its protocol window (12–18 May). It will be recorded as a deviation."* — and then records it, because that is the truth.
- A visit with no consent on the participant shows the whole grid disabled with the reason at the top.

### 11.4 The safety adjudication workspace — `/safety/adverse-events/[id]`

Two columns. The officer needs the clinical context and the form on one screen.

```
┌────────────────────────────┬───────────────────────────────────────────┐
│  ● SERIOUS · Reported      │  Adjudication                             │
│  Nausea and vomiting       │                                           │
│  CT-2026-014-DEL-0042      │  Assessed severity  [ Moderate    ▾ ]     │
│  Onset 3 Jun · Severe      │    reported: SEVERE  — differs ⚠          │
│  Criteria: hospitalisation │  Assessed causality [ Probable    ▾ ]     │
│                            │  Expected in IB?    ( ) Yes  (•) No       │
│  Narrative                 │                                           │
│  …                         │  ⚠ Unexpected + serious + related.        │
│                            │    Expedited reporting is required.       │
│  Concomitant medications   │  ☑ Requires expedited reporting           │
│  Metformin 500mg BD ORAL   │  Reported to authority [ date/time ]      │
│  Ondansetron 4mg IV        │                                           │
│                            │  Comments  [                        ]     │
│  Observations near onset   │                                           │
│  LAB_CREAT 1.8 mg/dL ⚠     │  Decision  [ Accepted ▾ ]  [ Record ]     │
└────────────────────────────┴───────────────────────────────────────────┘
```

- A divergence between reported and assessed severity is **highlighted, not hidden** — it is a signal in its own right.
- The expedited-reporting suggestion appears with its reasoning spelled out, and remains overridable.
- `CLOSED` + expedited is **blocked** until `reportedToAuthorityAt` is filled, with the reason stated inline.
- The left column is the event-triggered clinical read: this officer can see these observations and medications **because this participant has a reported event**. A short line saying so is honest and reassuring.

### 11.5 The ethics review workspace — `/ethics/pending-reviews/[id]`

Split view: the submission and its documents on the left, the review form on the right.

- **Conflict of interest is asked first**, above the recommendation. Declaring one does not block the review; it records it.
- Documents open in an inline PDF viewer where the type allows, with the download falling back to the signed-URL redirect.
- Prior reviews by other members are visible **to committee members only**.
- **Save draft** and **Finalise** are separate buttons with different weights. Finalise carries a confirm dialog: *"Once finalised, this review cannot be changed. It becomes part of the regulatory record."*
- The **committee decision** (`ethics:decide`) is a separate action on the submission, not part of a member's review. Only holders of that permission see it.

---

## 12 · GIS — `/gis` 🟡 (B7)

One map, seven roles. There is no per-role map implementation and you must not build one — that would be seven times the code and seven times the places a participant field could leak onto a public-facing view.

### 12.1 Layout

```
┌──────────────┬─────────────────────────────────────────────────────────┐
│  Layers      │                                                         │
│  ☑ Institut. │                    [ Leaflet + OSM ]                    │
│  ☑ Sites     │          markers · clusters · state choropleth          │
│  ☐ Choroplth │                                                         │
│              │                                        ┌──────────────┐ │
│  Filters     │                                        │ Site DEL-01  │ │
│  Phase   ▾   │                                        │ AIIMS Delhi  │ │
│  Status  ▾   │                                        │ Enrolled: 42 │ │
│  Ther.a. ▾   │                                        │ Trials: 3    │ │
│              │                                        │ [Open site]  │ │
│  Legend      │                                        └──────────────┘ │
└──────────────┴─────────────────────────────────────────────────────────┘
```

### 12.2 Endpoints

| Endpoint | Returns |
|---|---|
| `GET /gis/institutions` | GeoJSON FeatureCollection — name, type, city, state |
| `GET /gis/sites?bbox=&trial_id=&status=` | GeoJSON — sites in viewport, RLS-scoped, k-suppressed counts |
| `GET /gis/clusters?zoom=&bbox=` | GeoJSON with cluster counts — server-side clustering below zoom 8 |
| `GET /gis/aggregates?level=state\|district` | JSON for the choropleth, k-suppressed |
| `GET /gis/sites/{id}/detail` | Per-site aggregates. **Requires `gis:drilldown` + scope** |

**Clustering happens on the server.** Below zoom 8 you request `/gis/clusters` and render what comes back. Do **not** add `leaflet.markercluster` and pull every site down to collapse it in the browser — a national dataset should not cross the network just to become twelve dots.

Refetch on `moveend`, debounced 300 ms, with the bbox rounded to reduce cache misses. Cancel in-flight requests on a new move.

### 12.3 The privacy rules — read these before writing map code

**The map may show:** institution names and locations · site locations and status · trial counts per site/state/district · aggregate enrolment · aggregate compliance percentages · aggregate safety counts · phase and therapeutic area.

**The map must never show:** participant names · participant ids or subject codes · phone numbers or emails · addresses · individual medical information · individual observations · individual AE narratives · anything from participant identities.

There is no drill-down level that exposes an individual participant. It is not a permission that exists.

### 12.4 Suppressed cells — render them honestly

Aggregates over small cohorts are suppressed **in SQL**, before the data ever reaches the API. A suppressed value arrives shaped like this:

```json
{ "value": null, "suppressed": true, "label": "<5" }
```

**Render `<5`. Never `0`, never blank, never a dash.** A blank invites the reader to assume zero, and reporting "0 adverse events" for a two-person site discloses exactly as much as reporting "1". The tooltip explains: *"Suppressed: fewer than 5 participants in this group."*

In charts, a suppressed point is a **gap with a hatched marker**, not a zero on the axis. A zero would be a lie plotted on a chart, and someone will screenshot it.

Structural counts — how many sites or trials exist in a state — are **not** suppressed. That is organisational information, not participant information.

### 12.5 Drill-down

| Level | Shows | Gate |
|---|---|---|
| 0 · Base | Institutions, sites, national counts | `gis:read` — every role |
| 1 · Aggregate | Per-state/district counts, enrolment, compliance %, safety counts | `gis:read` + k-suppression |
| 2 · Site detail | One site's enrolment, trials, compliance, AE counts | `gis:drilldown` **and** RLS scope covers it |
| 3 · Trial detail | Site enrolment curve, compliance breakdown, safety summary | `gis:drilldown` + trial assignment |

A marker the user cannot drill into is still shown — it just has no "Open site" action in its popup. Do not hide the marker; the map is geographic context for everyone.

### 12.6 Map craft

- Base tiles muted (CartoDB Positron / dark matter) so data reads on top. **Attribute OpenStreetMap** — it is a licence condition, not a courtesy.
- Marker size encodes magnitude, colour encodes status or compliance. Never both encoding the same variable.
- Legend always visible, with the suppression rule stated on it.
- Keyboard: `Tab` cycles markers, `Enter` opens the popup, `Esc` closes. A map that only works with a mouse fails accessibility outright.
- Provide a **"View as table"** toggle for the whole map. Same data, sortable, screen-reader navigable, and the thing a regulator will export.
- Reasonable initial view: India bounds, zoom 5.

---

## 13 · Dashboards 🟡 (B8)

Every dashboard answers one question: **what needs my attention right now?** Not "here is everything we could count". Every card links directly into the work it describes; a number that is not clickable is a number nobody acts on.

### 13.1 The endpoint

`GET /api/v1/analytics/dashboard` returns **one payload shaped by the caller's role** — a discriminated union on `dashboard_type`. One request per dashboard, never one per card.

```ts
type DashboardPayload =
  | { dashboard_type: 'ADMIN'; … }
  | { dashboard_type: 'INVESTIGATOR'; … }
  | { dashboard_type: 'COORDINATOR'; … }
  | { dashboard_type: 'RESEARCH'; … }
  | { dashboard_type: 'ETHICS'; … }
  | { dashboard_type: 'SAFETY'; … }
  | { dashboard_type: 'REGULATOR'; … };
```

Until B8, compose each dashboard client-side from the live list endpoints and put every derivation in `features/analytics/derive/`. When the endpoint lands, those files are deleted and the components keep their props. **Do not scatter dashboard arithmetic through card components.**

### 13.2 Per-role widgets

**`/admin` — platform health.** User summary (total, active, locked, new this week) · institutions by type · active trials by status and phase · sites (total, activated, enrolling) · system activity sparkline · **security alerts** (failed logins, lockouts, token reuse, denied access) · background job queue depth and failures · storage (document count, total bytes).

> **No clinical metrics on this dashboard.** No enrolment, no safety counts. Deliberate.

**`/investigator` — trial management.** Enrolment progress vs target per trial with a projection line · enrolment by site (bar — identifies under-recruiting sites) · participant status breakdown · site activation status · **safety summary** flagged when serious events are outstanding · ethics status with approvals expiring within 60 days · compliance percentage with overdue items named · recent activity (last 20 audit events across their trials).

**`/coordinator` — operational, today-first.** **Today's visits** at their sites with subject codes · upcoming 7 days · **missed visits** — the escalation queue · out-of-window visits (deviations) · data entry queue (completed visits with missing observations) · participant counts · documents pending or expiring · AEs awaiting review · overdue compliance.

**`/research` — daily clinical work.** The narrowest and most focused. **Today's visits** ordered by time · assigned participants · data entry queue · their last 10 observations for quick correction · **"Report adverse event"** as a prominent action, not a metric · current protocol and consent form versions.

**`/ethics` — review workflow.** **Pending submissions** oldest first · in-progress reviews · recent committee decisions (30 days) · approvals expiring within 90 days · submissions by type · this member's review history.

> No participant or clinical widgets. Ethical review operates on the protocol.

**`/safety` — safety monitoring.** **Pending review**, serious first · **open serious events across every trial** · **expedited overdue** — expedited-reportable events with no authority submission recorded, the highest-priority item on the platform · AE rate over time by trial with a comparison line · counts by trial, ranked · by site, k-suppressed · severity distribution · causality distribution.

**`/regulator` — national oversight.** **National overview** (trials, institutions, sites, aggregate enrolment) · trials by phase and status · **compliance overview** with non-compliant trials named · overdue compliance by trial · aggregate safety, k-suppressed · **ethics coverage** — trials with a current approval vs without, a hard compliance gate · **embedded national map**, compliance-coloured · regulatory queue.

### 13.3 Dashboard craft

- **A single number needs context.** "42 participants" means nothing; "42 of 120 target · 35%" with a progress bar means something.
- **Order by urgency, not by symmetry.** The overdue thing goes at the top left even if it makes the grid uneven.
- **Zero states are good news** — *"No serious events awaiting review."* with a check, not an empty card.
- Refresh on window focus if data is older than 60 s. Show "Updated 2 minutes ago" with a manual refresh.
- No auto-refresh timer that moves content under a reader's cursor.

---

## 14 · API contract reference

Base path `/api/v1`, proxied through the frontend origin (§4.2). JSON in, JSON out. `credentials: 'include'` on everything.

### 14.1 What exists today, exactly

**Conventions that are real now:** ETag/If-Match on trials, ethics and compliance · `Idempotency-Key` on enrolment · 302 signed redirect on document download.

**Conventions specified but NOT implemented yet — do not code against them:**
- ❌ **No pagination.** Every list returns a plain JSON array. Assume `limit`/`cursor` later.
- ❌ **No sorting or filtering params** beyond the required ones listed below.
- ❌ Errors from non-auth endpoints are Spring defaults, not the `{error:{code,message,request_id}}` envelope. **Normalise every response through one client function** so the shape change later is contained.

| Method | Path | Required params | Permission | Notes |
|---|---|---|---|---|
| POST | `/auth/login` | body `{email, password}` | public | sets cookies; returns `{userId, email, role, mfaRequired}` |
| POST | `/auth/refresh` | refresh cookie | — | rotates both cookies |
| POST | `/auth/logout` | — | authenticated | 204, clears cookies |
| GET | `/auth/me` | — | authenticated | `{userId, email, role, permissions[]}` |
| GET | `/users` | — | `user:read` | `{id, email, fullName, roleId}[]` |
| GET | `/roles` | — | `role:read` | `{id, name, displayName}[]` |
| GET | `/permissions` | — | `role:read` | `{id, name, resource, action}[]` |
| GET | `/institutions` | — | `institution:read` | |
| GET | `/institutions/{id}` | — | `institution:read` | |
| POST | `/institutions` | `{name, institutionType, city, state, latitude?, longitude?}` | `institution:create` | |
| PATCH | `/institutions/{id}` | `{name?, city?, state?, addressLine?, postalCode?, latitude?, longitude?}` | `institution:update` | |
| GET | `/trials` | — | `trial:read` | RLS-scoped |
| GET | `/trials/{id}` | — | `trial:read` | **returns `ETag`** |
| POST | `/trials` | `{protocolNumber, title, sponsorInstitutionId, phase, targetEnrollment?}` | `trial:create` | |
| PATCH | `/trials/{id}` | `{title?, shortTitle?, therapeuticArea?}` + **`If-Match`** | `trial:update` | 428 / 409 |
| POST | `/trials/{id}/status` | `{status}` + **`If-Match`** | `trial:update` | 409 on illegal transition |
| GET | `/sites` | **`?trialId=`** | `site:read` | |
| POST | `/sites` | `{trialId, institutionId, siteCode, targetEnrollment?}` | `site:create` | |
| GET | `/trial-staff` | **`?trialId=`** | `trial_staff:read` | active assignments only |
| POST | `/trial-staff` | `{trialId, trialSiteId?, userId, staffRole}` | `trial_staff:create` | |
| DELETE | `/trial-staff/{id}` | — | `trial_staff:delete` | ends the assignment |
| GET | `/participants` | **`?trialId=`** | `participant:read` | |
| GET | `/participants/{id}` | — | `participant:read` | |
| POST | `/participants` | `EnrollmentRequest` + **`Idempotency-Key`** | `participant:create` | see below |
| POST | `/participants/{id}/withdrawal` | `{reason}` | `participant:withdraw` | |
| GET | `/consents` | **`?participantId=`** | `consent:read` | |
| POST | `/consents/{id}/withdrawal` | `{reason}` | `consent:withdraw` | |
| GET | `/visits` | **`?participantId=`** | `visit:read` | |
| POST | `/visits` | `{participantId, visitName, visitNumber, scheduledDate}` | `visit:create` | |
| GET | `/observations` | **`?visitId=`** | `observation:read` | |
| POST | `/observations` | `{visitId, observationCode, observationName, category, valueNumeric?\|valueText?\|valueBoolean?, unit?}` | `observation:create` | idempotent on `(visitId, observationCode)` |
| GET | `/medications` | **`?participantId=`** | `medication:read` | |
| POST | `/medications` | `{participantId, medicationName, medicationType, dose?, route?, startDate}` | `medication:create` | |
| GET | `/adverse-events` | **`?participantId=` or `?trialId=`** (one required) | `adverse_event:read` | 400 if neither |
| POST | `/adverse-events` | `{participantId, visitId?, eventTerm, description, onsetDate, severity, seriousness?, seriousCriteria?}` | `adverse_event:create` | |
| GET | `/safety/reviews` | **`?adverseEventId=`** | `safety_report:read` | |
| POST | `/safety/reviews` | `{adverseEventId, assessedSeverity, assessedCausality, isExpected, requiresExpeditedReporting?, comments?, decision}` | `adverse_event:review` | |
| GET | `/ethics/submissions` | **`?trialId=` or `?institutionId=`** (+ optional `status`) | `ethics:read` | 400 if neither |
| GET | `/ethics/submissions/{id}` | — | `ethics:read` | |
| POST | `/ethics/submissions` | `{trialId, institutionId, submissionNumber, submissionType, summary, protocolDocumentId?}` | `ethics:submit` | |
| POST | `/ethics/submissions/{id}/decision` | `{status, conditions?, approvalValidUntil?}` + `If-Match` | `ethics:decide` | |
| POST | `/ethics/submissions/{id}/withdraw` | — + `If-Match` | `ethics:submit` | |
| GET | `/ethics/reviews` | **`?submissionId=`** | `ethics:review` | |
| POST | `/ethics/reviews` | `{ethicsSubmissionId, recommendation, comments}` | `ethics:review` | |
| GET | `/compliance/requirements` | — | `compliance:read` | |
| GET | `/compliance/requirements/{id}` | — | `compliance:read` | |
| POST | `/compliance/requirements` | `{code, title, description, category, authority?, appliesToPhase?, isMandatory?, evidenceRequired?}` | `compliance:define` | |
| GET | `/compliance/trials/{trialId}` | — | `compliance:read` | |
| GET | `/compliance/trials/{trialId}/summary` | — | `compliance:read` | `{trialId, total, byStatus, mandatoryOutstanding, compliant}` |
| GET | `/compliance/trials/{trialId}/{id}` | — | `compliance:read` | |
| POST | `/compliance/trials/{trialId}/requirements` | `{complianceRequirementId, trialSiteId?, dueDate?}` | `compliance:update` | attach |
| POST | `/compliance/trials/{trialId}/{id}/status` | `{status, evidenceDocumentId?, notes?}` + `If-Match` | `compliance:update` | |
| POST | `/documents` | **multipart**: `file` + query `documentType`, `title`, one of `trialId`/`institutionId`, `trialSiteId?`, `effectiveDate?`, `expiryDate?` | `document:upload` | |
| GET | `/documents` | **`?trialId=`** | `document:read` | |
| GET | `/documents/{id}` | — | `document:read` | poll this for scan status |
| POST | `/documents/{id}/versions` | multipart `file` + `title?` | `document:upload` | adds v(n+1) as DRAFT |
| GET | `/documents/{id}/versions` | — | `document:read` | the whole family chain |
| POST | `/documents/{id}/publish` | — | `document:supersede` | makes it CURRENT |
| GET | `/documents/{id}/download` | — | `document:read` | **302 → signed URL, 5 min** |

**`EnrollmentRequest` body:**
```json
{
  "trialId": "uuid", "trialSiteId": "uuid", "subjectCode": "CT-2026-014-DEL-0042",
  "dateOfBirthYear": 1987, "sex": "FEMALE",
  "identity": { "fullName": "…", "dateOfBirth": "1987-04-12", "phone": "…" },
  "consent":  { "consentVersion": "ICF v3.0", "consentMethod": "WRITTEN" }
}
```

### 14.2 Planned — mock these behind `lib/mocks/`

| Endpoint | Phase | Powers |
|---|---|---|
| `GET /analytics/dashboard` | B8 | all seven dashboards |
| `GET /analytics/trials/{id}/enrollment` \| `/safety` \| `/compliance` | B8 | `/investigator/analytics` |
| `GET /gis/*` (five endpoints, §12.2) | B7 | `/gis`, `/regulator/gis` |
| `GET /audit?entity_type=&entity_id=&user_id=&from=&to=` | B8 | `/admin/audit` |
| `POST /auth/mfa/verify`, `/password-reset-request`, `/password-reset` | B9 | auth pages |
| `GET /auth/sessions`, `DELETE /auth/sessions/{id}`, `POST /auth/logout-all` | B9 | `/profile` sessions |
| `GET /participants/{id}/identity` | — | the reveal panel (§9.4.4) |
| `GET /participants/{id}/timeline` | — | the overview tab; **derive client-side for now** |
| `GET /trials/{id}/summary` | — | trial workspace header |
| `PATCH` on participants, visits, observations, medications, adverse events | — | **all editing after creation.** Build the forms; wire the mutations behind a flag |
| `GET /safety/pending`, `/safety/trends`, `POST /safety/reports` | — | `/safety/monitoring`, `/safety/reports` |

> **The `PATCH` gap is the one to plan around.** Several entities can be created but not yet updated through the API. Build the edit UI, keep the mutation in the feature's hook, and have the hook throw a clear "not yet available" in dev. Do not ship an edit button that silently does nothing.

### 14.3 The API client — build this first

One module, `lib/api/client.ts`, and nothing else calls `fetch` for API data.

Responsibilities:
1. Prefix `/api/v1`, always `credentials: 'include'`.
2. Attach `X-CSRF-Token` when the cookie exists (§4.6).
3. Capture `ETag` on GET, replay as `If-Match` on write (§9.3).
4. Single-flight 401 → refresh → retry (§4.4).
5. Normalise every error into `ApiError { status, code, message, requestId, fieldErrors }`.
6. Generate and attach `Idempotency-Key` where the caller asks for it.

Types live in `lib/api/types.ts`, hand-written today. **When the backend publishes OpenAPI, generate them** — a backend field rename should become a frontend build error, not a runtime `undefined` in a clinical form. Ask for the OpenAPI document (§16).

---

## 15 · Quality bar

### 15.1 Accessibility — WCAG 2.1 AA, non-negotiable

This is government-adjacent healthcare software; accessibility is a requirement, not a stretch goal.

- **Keyboard reachable, everything.** Especially the data-entry grid and the map.
- **Visible focus** on every interactive element — 2px `--primary` ring, never `outline: none` without a replacement.
- **Contrast** 4.5:1 body, 3:1 large text and UI boundaries. Check the amber and the muted greys; those are where it usually fails.
- **Forms:** every input has a real `<label>`. Errors are tied with `aria-describedby` and announced via `role="alert"`. Never placeholder-as-label.
- **Tables:** real `<th scope>`, a `<caption>` (visually hidden is fine), `aria-sort` on sortable headers.
- **Live regions:** autosave status and toasts announce through `aria-live="polite"`; a serious-AE alert through `role="alert"`.
- **Dialogs:** focus moves in on open, is trapped, returns to the trigger on close. `Esc` closes.
- **Colour is never the only channel** (§8.1).
- **Charts and maps** each have a table equivalent.
- **Skip-to-content** link as the first focusable element.

### 15.2 Responsive

| Breakpoint | Behaviour |
|---|---|
| < 768 | Sidebar → sheet. Tables → stacked cards showing the 3–4 key fields. **Data-entry grids get a simplified one-field-at-a-time flow.** Map full-bleed with a bottom sheet for details |
| 768–1279 | Sidebar collapses to icons. Tables scroll horizontally inside their own container |
| ≥ 1280 | Full layout |

The realistic device story: coordinators and research staff on tablets at the bedside, everyone else on a desktop. **A tablet must be able to complete a visit's data entry.** The page body must never scroll horizontally — wide content scrolls inside its own `overflow-x: auto` container.

### 15.3 Performance budgets

| Metric | Target |
|---|---|
| LCP on dashboard | < 2.0 s |
| INP | < 200 ms |
| Route JS (excl. shared) | < 150 KB gzipped |
| Map bundle | lazy-loaded, only on `/gis` |
| Table of 500 rows | virtualise beyond 100 |

Server Components for anything the server can render. Leaflet, Recharts and the PDF viewer are all `dynamic()` imports.

### 15.4 Testing

| Layer | What |
|---|---|
| Unit | Permission helpers, status maps, derivations, the enrolment zod schema |
| Component | DataTable, ConflictDialog, the reveal panel, the AE seriousness interaction |
| Integration (MSW) | 401→refresh→retry · 409 conflict dialog · idempotent double-submit · quarantine flow |
| E2E (Playwright) | One journey per role, plus **"no participant name appears on any regulator or admin screen"** as an explicit assertion |
| a11y | `axe` in CI on every route |

The privacy assertion in E2E is worth writing carefully. It is the property the whole architecture exists to protect, and it is cheap to test: crawl each role's routes with seeded data and fail if a known participant name string appears in the DOM.

---

## 16 · Open questions for the backend team

Raise these early; several block real work.

1. **Is there an OpenAPI document?** No springdoc dependency is present. Adding it turns our hand-written types into generated ones and a rename into a build error. Highest-value ask on this list.
2. **`/auth/me` needs `fullName`, `institutionId`, and the caller's `trial_staff` assignments.** Without assignments, "my site" defaults have nothing to key on.
3. **`PATCH` endpoints** for participants, visits, observations, medications and adverse events. Creation exists; correction does not. Amendment with a reason is a GCP requirement and currently unreachable through the API.
4. **List endpoints need broader queries.** `GET /visits` requires `participantId`, so "today's visits at my site" — the single most important coordinator query — cannot be asked. We need `?siteId=&trialId=&date=&status=`. Same for `/participants?siteId=` and `/documents?trialId=&type=&status=`.
5. **Pagination.** Confirm cursor-based `?cursor=&limit=` as specified, and when.
6. **The error envelope.** Auth endpoints return `{error:{code,message}}`; the rest return Spring defaults. Confirm the `{error:{code,message,request_id}}` shape lands everywhere, and that `request_id` is surfaced.
7. **`GET /participants/{id}/identity`** — specified, audited, not built. It is the entire point of the identity/clinical split.
8. **CSRF token delivery.** Which cookie name, which header, and from which endpoint.
9. **`nationalIdHash`** — does the frontend hash it, or do we not collect it at all in the MVP? The UI must never transmit a raw national id.
10. **Validation error shape.** Do 422 responses carry per-field detail we can map onto form fields, or only a message?

---

## 17 · Build order

Nine steps. Each ends with something demonstrable.

| # | Deliverable |
|---|---|
| **F1** | **Foundation.** Next.js + TS + Tailwind + shadcn. Design tokens, dark mode, `StatusBadge` with all 15 status maps, `DataTable`, `PageHeader`, `EmptyState`, `ConflictDialog`. The API client of §14.3 with ETag, refresh and error normalisation. `lib/mocks/` scaffolding. **Nothing after this is allowed to call `fetch` directly.** |
| **F2** | **Auth + shell.** Login, middleware, layout guards, `/auth/me`, `usePermissions`, `<Can>`, sidebar/topbar, `/profile` (partial). Deploy to Vercel with the rewrite proxy and prove cookies survive it. |
| **F3** | **Structure.** Institutions (with the map coordinate picker), trials (list, detail, create, the state machine, ETag conflict flow), sites, staff assignment. This exercises every hard pattern — start here, not with dashboards. |
| **F4** | **Participants.** Enrolment wizard with idempotency, participant record with tabs, timeline (derived), consent, withdrawal, the identity reveal panel. |
| **F5** | **Clinical.** Visits list + calendar, the data-entry grid with autosave, medications, amendment flow. The most UX-sensitive work in the project — budget for it. |
| **F6** | **Safety, ethics, compliance, documents.** Four parallel tracks. AE reporting + adjudication workspace; ethics submission + review workspace; compliance checklist; document upload with the scan lifecycle and the version chain. |
| **F7** | **Dashboards** — all seven, composed client-side from live endpoints, structured so the B8 payload drops straight in. |
| **F8** | **GIS** — the map, layers, clustering, drill-down, suppression rendering, the table equivalent. Mocked until B7. |
| **F9** | **Polish.** Accessibility audit, responsive pass, empty and error states everywhere, performance budgets, notifications page, public marketing pages, E2E per role. |

**F1 and F2 are not parallelisable and everything depends on them.** F3 proves the hard patterns work before four people start copying them. F6 splits cleanly across the team.

---

## Appendix A · Copy guidelines

Clinical software earns trust through precision. Write like a careful colleague, not like a marketing site.

| Do | Don't |
|---|---|
| "Visit marked completed" | "Success! 🎉" |
| "This visit is outside its protocol window (12–18 May). It will be recorded as a deviation." | "Warning: date issue" |
| "No participants match these filters." | "Nothing here!" |
| "This file was found to contain malware and has been removed. Nothing was stored." | "Upload failed" |
| "Fewer than 5 participants — value suppressed to protect privacy." | "N/A" |
| "Your session ended. Please sign in again." | "Oops! Something went wrong" |

- Sentence case for everything: buttons, headings, labels.
- Buttons are verbs: "Enrol participant", "Record review", "Publish version".
- Use the domain's words: *participant* not user, *subject code* not ID, *adverse event* not incident, *site* not location, *withdraw* not delete.
- Dates: `12 Mar 2026`. Times: `14:32 IST`. Never `03/12/26` — the ambiguity is real and this is clinical data.
- No emoji in the product UI.

## Appendix B · Definition of done, per page

- [ ] Loading, empty, error and success states all implemented
- [ ] Permission-gated per §5, with no role-name checks anywhere
- [ ] Keyboard navigable end to end; `axe` clean
- [ ] Responsive at 375 / 768 / 1280
- [ ] Correct in dark mode
- [ ] Every mutation goes through the API client, with `If-Match` where the resource is versioned
- [ ] 409 conflict handled with the dialog, not a silent retry
- [ ] No participant identifying field rendered outside the reveal panel
- [ ] Filters serialise into the URL
- [ ] Copy follows Appendix A
