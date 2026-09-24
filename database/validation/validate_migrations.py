#!/usr/bin/env python3
"""
Phase 20: static Flyway migration validation. No live MySQL/Flyway runtime
is available in this workspace (see PROJECT_PROGRESS.md's Phase 20 TESTS
section), so this script performs the checks that ARE possible without one
- pure static analysis of the .sql files and cross-referencing against the
JPA entity source. It does NOT replace actually running
`mvn flyway:migrate` (or letting Spring Boot's Flyway auto-run do it)
against a real MySQL instance, which remains NOT VERIFIED here.

Checks performed:
  1. Version sequence: V1..N with no gaps or duplicate version numbers.
  2. Dependency ordering: every FK's REFERENCES target table already
     exists (created in an earlier migration, or in the same CREATE TABLE
     for self-references) by the time it's referenced.
  3. SQL syntax sanity: balanced parentheses (comments stripped), every
     file ends with a semicolon-terminated statement.
  4. Entity/schema drift: every @Column(name=...)/@JoinColumn(name=...) in
     each JPA entity has a matching column somewhere in that entity's
     @Table's migration history (best-effort regex; column-type keywords
     not in TYPE_KEYWORDS below will under-match and should be reviewed
     manually rather than treated as false confirmations - see the
     CHAR(36)/family_id case this script's first run turned up, a false
     positive from an incomplete type list, not a real drift).

Run: python3 database/validation/validate_migrations.py
"""
import glob
import re
import sys

TYPE_KEYWORDS = (
    "BIGINT", "INT", "VARCHAR", "TEXT", "TIMESTAMP", "DATE", "DECIMAL",
    "BOOLEAN", "JSON", "ENUM", "TINYINT", "DATETIME", "LONGTEXT", "CHAR",
    "FLOAT", "DOUBLE", "MEDIUMTEXT",
)
TYPE_PATTERN = "|".join(TYPE_KEYWORDS)


def check_version_sequence(files):
    versions = [int(re.match(r"V(\d+)__", f.split("/")[-1]).group(1)) for f in files]
    expected = set(range(1, max(versions) + 1))
    missing = expected - set(versions)
    dupes = {v for v in versions if versions.count(v) > 1}
    problems = []
    if missing:
        problems.append(f"Missing version number(s): {sorted(missing)}")
    if dupes:
        problems.append(f"Duplicate version number(s): {sorted(dupes)}")
    return problems


def check_dependency_ordering(files):
    known_tables = set()
    problems = []
    for f in files:
        sql = open(f, encoding="utf-8").read()
        this_file_new = {t.lower() for t in re.findall(
            r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?", sql, re.IGNORECASE)}
        for m in re.finditer(r"REFERENCES\s+`?(\w+)`?\s*\(", sql, re.IGNORECASE):
            ref = m.group(1).lower()
            if ref not in known_tables and ref not in this_file_new:
                problems.append(f"{f}: REFERENCES `{ref}` before that table exists "
                                 f"(known so far: {sorted(known_tables)})")
        known_tables |= this_file_new
    return problems


def check_sql_syntax(files):
    problems = []
    for f in files:
        sql = open(f, encoding="utf-8").read()
        no_comments = re.sub(r"--.*", "", sql)
        bal = no_comments.count("(") - no_comments.count(")")
        if bal != 0:
            problems.append(f"{f}: paren imbalance {bal}")
        stripped = no_comments.strip()
        if stripped and not stripped.rstrip().endswith(";"):
            problems.append(f"{f}: does not end with a semicolon-terminated statement")
    return problems


def extract_migration_columns(files):
    mig_columns = {}
    for f in files:
        sql = open(f, encoding="utf-8").read()
        for cm in re.finditer(r"CREATE TABLE\s+(?:IF NOT EXISTS\s+)?`?(\w+)`?\s*\((.*?)\n\)",
                               sql, re.IGNORECASE | re.DOTALL):
            table = cm.group(1).lower()
            cols = set()
            for line in cm.group(2).split("\n"):
                line = line.strip().rstrip(",")
                m = re.match(rf"`?(\w+)`?\s+(?:{TYPE_PATTERN})", line, re.IGNORECASE)
                if m:
                    cols.add(m.group(1).lower())
            mig_columns.setdefault(table, set()).update(cols)
        for am in re.finditer(r"ALTER TABLE\s+`?(\w+)`?\s+ADD COLUMN\s+`?(\w+)`?", sql, re.IGNORECASE):
            mig_columns.setdefault(am.group(1).lower(), set()).add(am.group(2).lower())
    return mig_columns


def check_entity_drift(mig_columns, entity_files):
    problems = []
    for ef in entity_files:
        src = open(ef, encoding="utf-8").read()
        tm = re.search(r'@Table\(name\s*=\s*"(\w+)"', src)
        if not tm:
            continue
        table = tm.group(1).lower()
        cols = {c.lower() for c in re.findall(r'@Column\(name\s*=\s*"(\w+)"', src)}
        cols |= {c.lower() for c in re.findall(r'@JoinColumn\(name\s*=\s*"(\w+)"', src)}
        if table not in mig_columns:
            problems.append(f"{ef}: @Table('{table}') has no matching CREATE TABLE in migrations")
            continue
        missing = cols - mig_columns[table]
        if missing:
            problems.append(f"{ef} (table={table}): possible drift, review manually: {sorted(missing)}")
    return problems


def main():
    files = sorted(glob.glob("database/migrations/V*.sql"),
                    key=lambda f: int(re.match(r".*V(\d+)__", f).group(1)))
    if not files:
        print("No migration files found - run from the repo root.")
        sys.exit(1)

    all_problems = []
    all_problems += check_version_sequence(files)
    all_problems += check_dependency_ordering(files)
    all_problems += check_sql_syntax(files)

    mig_columns = extract_migration_columns(files)
    entity_files = glob.glob("backend/src/main/java/com/jannetai/backend/entity/*.java")
    drift = check_entity_drift(mig_columns, entity_files)

    print(f"Checked {len(files)} migrations, {len(entity_files)} entities.\n")
    if all_problems:
        print("STRUCTURAL PROBLEMS:")
        for p in all_problems:
            print(" -", p)
    else:
        print("No structural problems (version sequence, dependency ordering, SQL syntax).")

    print()
    if drift:
        print("POSSIBLE ENTITY/SCHEMA DRIFT (review manually - regex-based, may include false "
              "positives from column types not in TYPE_KEYWORDS):")
        for d in drift:
            print(" -", d)
    else:
        print("No entity/schema column-name drift detected.")

    sys.exit(1 if all_problems else 0)


if __name__ == "__main__":
    main()
