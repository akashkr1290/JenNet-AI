# JANNet AI — Spring Boot Backend

Owned by Team Member 1 (see root `README.md`). Java 21 + Spring Boot 3.3.4,
Maven build. This module owns the JPA entities/repositories mapped onto the
schema `database/` (Team Member 4) already migrated — entities are written
to match an already-migrated schema, never the reverse
(`PROJECT_INTEGRATION.md` Section 4).

## Phase 3 scope (this phase)

- Maven project skeleton (`pom.xml`, Spring Boot 3.3.4, Java 21).
- Flyway wired to the Phase 2 schema: `database/migrations/*.sql` is copied
  into the build's classpath at `db/migration` by a `maven-resources-plugin`
  execution in `pom.xml` (see that file's comment) so Spring Boot's Flyway
  auto-configuration finds it at its conventional default location
  (`classpath:db/migration`) and the packaged jar is self-contained. No
  independent copy of the migrations is committed under `backend/` —
  `database/migrations/` remains the single authored source.
- 13 JPA entities, one per table in the Phase 2 schema, plus 12 Java enums
  matching every `CHECK`-constrained column exactly
  (`entity/`, `entity/enums/`).
- 13 Spring Data JPA repositories, plain `JpaRepository<T, Long>` — no
  business-specific query methods yet (those belong to the phase that owns
  each module).
- `spring.jpa.hibernate.ddl-auto=validate` — Hibernate never generates or
  alters schema; it only validates entity mappings against the real,
  Flyway-migrated tables at startup.
- Standard error response shape (`exception/ErrorResponse.java`) finalized
  per `PROJECT_INTEGRATION.md` Section 2, wired via
  `exception/GlobalExceptionHandler.java` so every controller added by
  later phases inherits consistent error handling automatically.
- OpenAPI/Swagger setup (`config/OpenApiConfig.java`) — UI at
  `/swagger-ui.html`, spec at `/v3/api-docs`, once the app is running.
- Actuator `health`/`info` endpoints exposed.
- `application.yml` + `application-dev.yml` / `application-prod.yml`,
  reading the same environment variable names as root `.env.example`
  (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`,
  `SERVER_PORT`, `SPRING_PROFILES_ACTIVE`).

**Explicitly out of scope this phase** (see `ARCHITECTURE.md` Section 8):
no Spring Security/JWT/RBAC (Phase 4), no business REST controllers or
workflow logic (Phase 5+), no S3 integration (Phase 6), no AI-service
integration (Phase 8).

## Running locally

Requires a real MySQL 8 instance reachable via the `DB_*` env vars in
`.env.example`, and Maven 3.9+ (a Maven Wrapper was **not** included — see
`.mvn/wrapper/maven-wrapper.properties` for why and how to regenerate it).

```bash
cd backend
mvn spring-boot:run
```

On startup, Flyway applies `database/migrations/V1`–`V15` (if not already
applied) and Hibernate validates every entity against the resulting schema.
Swagger UI: `http://localhost:8080/swagger-ui.html`. Health check:
`http://localhost:8080/actuator/health`.

## Known limitation carried into Phase 4+

**NOT VERIFIED.** This workspace has no outbound network access (Maven
Central is unreachable) and no local Maven installation, so `mvn compile` /
`mvn test` could not actually be run here — consistent with Phase 2's same
limitation for the schema itself (see `database/README.md`). Validation
performed instead via: manual review of every entity's field list against
its migration's `CREATE TABLE` statement (column-for-column, all 13 tables
confirmed to match exactly — see `PHASE_HANDOFF.md`), and an automated
brace/parenthesis-balance check across all 44 Java files (all balanced).
This is not equivalent to an actual compile or a real Flyway
migrate + Hibernate `ddl-auto=validate` run against MySQL 8.

**Run `mvn -pl backend clean verify` against a real MySQL 8 instance
(with `database/migrations/V1–V15` applying cleanly first — still itself
unverified from Phase 2) as the first action of Phase 4**, before adding
Spring Security/JWT/RBAC on top of this skeleton.
