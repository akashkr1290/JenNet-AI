# JANNet AI — Database Module

Owned by Team Member 4 (see root `README.md`). MySQL 8.0+, schema-versioned
with Flyway. This module owns raw SQL only — Java JPA entities/repositories
that map onto this schema are owned separately in `backend/` (Team Member 1)
and must be written to match an already-migrated schema, never the reverse
(`PROJECT_INTEGRATION.md` Section 4).

## Layout

```
database/
├── migrations/   # Flyway-versioned schema migrations, V1 .. V15 (this phase)
├── seed/         # Dev/test-only data — NOT applied via Flyway, see seed/README.md
├── docs/         # Data dictionary and ER diagram, kept in sync with migrations/
├── scripts/      # backup.sh / restore.sh
└── README.md     # this file
```

## Naming convention

`V<version>__<description>.sql`, strictly sequential, never renumbered or
edited after being applied to any shared environment (Flyway checksums each
applied migration). To change something already migrated, add a new
migration — don't edit an old one.

## Applying migrations

Flyway configuration (connection details, migration path) is wired into the
Spring Boot backend starting Phase 3, per `PROJECT_INTEGRATION.md`. Until
then, migrations can be applied directly for local development:

```bash
flyway -url=jdbc:mysql://localhost:3306/jannet_ai \
       -user=jannet_user -password=$DB_PASSWORD \
       -locations=filesystem:database/migrations \
       migrate
```

or, without the Flyway CLI, by applying the files in `migrations/` in order
with any MySQL client:

```bash
for f in database/migrations/V*.sql; do
  mysql -u jannet_user -p jannet_ai < "$f"
done
```

## What's in this schema (Phase 2)

15 migrations covering: `wards`, `departments`, `users`, `locations`,
`complaints`, `images`, `predictions`, `budget`, `status_history`,
`notifications`, `audit_logs`, `settings`, `routing_rules`, plus one
reference-data seed (wards + departments only — see
`docs/data-dictionary.md` decision note 9 for why `routing_rules`/`settings`
aren't seeded here).

Full field-by-field documentation: `docs/data-dictionary.md`.
Entity relationships: `docs/er-diagram.md`.

**Every deviation from the supplied SRS/BRD/FRS document — corrections,
additions, and the MySQL-vs-PostgreSQL tech-stack conflict — is recorded in
`docs/data-dictionary.md`'s "Decision notes" section.** Read that before
building JPA entities against this schema in Phase 3, and before Phase 6
implements the real complaint status transition table.

## Constraints and indexing philosophy

- Every FK is explicit, with an `ON DELETE` behavior chosen per relationship
  (`CASCADE` for owned child records like images/status history; `SET NULL`
  for optional references like assigned officer; `RESTRICT` where deleting
  the parent should be blocked, e.g. a citizen with existing complaints).
- Enum-like columns (`status`, `role`, `category`, `severity`, etc.) use
  `VARCHAR` + `CHECK` rather than native MySQL `ENUM`, so adding a new valid
  value is a new migration rather than an ALTER that rewrites the column
  type.
- Indexes are added for every FK plus the specific filter/sort patterns
  named in the SRS (status/severity/department filtering, SLA-ordered
  officer queues, time-range report queries) — not speculatively for every
  column.

## Backup / restore

`scripts/backup.sh` and `scripts/restore.sh` wrap `mysqldump`/`mysql` using
the same environment variable names as the root `.env.example`
(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`). **NOT
VERIFIED** — no live MySQL instance was available in this environment to
execute them against; they were reviewed for correctness but not run.

## Known limitation carried into Phase 3+

No live MySQL server was available in this workspace, so these migrations
were validated by careful manual review (dependency order, FK direction,
CHECK constraint syntax for MySQL 8) rather than by actually running
`flyway migrate` against a real database. Run them against a real MySQL 8
instance as the first step of Phase 3, before writing JPA entities against
this schema, and report back any failure.
