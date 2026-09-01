# CTMS Backend

Java 26 · Spring Boot 4.1 · PostgreSQL + PostGIS · Gradle multi-module.

Design: [../docs/superpowers/specs/2026-09-01-spring-boot-backend-design.md](../docs/superpowers/specs/2026-09-01-spring-boot-backend-design.md)
Phases: [../BACKEND_PHASES.md](../BACKEND_PHASES.md)
Domain rules: [../PROJECT_ARCHITECTURE.md](../PROJECT_ARCHITECTURE.md) §5–§23

## Run it

```bash
docker compose up -d postgres redis        # from the repo root
cd backend
./gradlew :ctms-app:bootRun
```

- App: <http://localhost:8080>
- Liveness: <http://localhost:8080/actuator/health/liveness>
- Readiness: <http://localhost:8080/actuator/health/readiness>
- Metrics: <http://localhost:8080/actuator/prometheus>

```bash
./gradlew build                 # compile + tests (Testcontainers needs Docker running)
./gradlew :ctms-app:bootJar        # the deployable fat jar
```

## Modules

| Module | Owns |
|---|---|
| `ctms-common` | Errors, request-id, base entities, audit writer |
| `ctms-security` | JWT, Spring Security, RBAC, the RLS `TransactionSynchronization` |
| `ctms-persistence` | Datasource routing, Flyway, base repositories |
| `ctms-trials` | Institutions, trials, sites, `trial_staff` |
| `ctms-clinical` | Participants, identities, consent, visits, observations, medications |
| `ctms-safety` | Adverse events, safety reviews |
| `ctms-ethics` | Ethics submissions, reviews, compliance |
| `ctms-documents` | Cloudinary, ClamAV, version chain |
| `ctms-gis` | PostGIS aggregation, k-anonymity |
| `ctms-analytics` | Dashboards, rollups |
| `ctms-app` | Boot entrypoint and composition — the only module aware of all others |

Only `ctms-app` applies the Spring Boot plugin. The others are libraries, which is what
prevents a domain module from quietly acquiring a dependency on the web layer.

## Conventions

- **Flyway owns the schema.** Hibernate is `ddl-auto: validate`. RLS policies and grants are
  migrations too, and an applied migration is never edited.
- **`open-in-view: false`.** An open session in the view layer hides N+1 queries and blurs
  the transaction boundary — and the transaction boundary is where RLS identity is set (B3).
- **Tests use Testcontainers, not an embedded database.** From B3 the things worth testing
  (RLS policies, partitions, spatial types) do not exist outside PostgreSQL.
- **`-parameters` is on.** Spring needs it for constructor binding on records.
- **No GraalVM native image.** GraalVM has no Java 26 build, and its benefits (heap
  footprint, cold start) do not apply to a 24 GB always-on VM. The deployable is the fat jar.
