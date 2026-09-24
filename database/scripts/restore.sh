#!/usr/bin/env bash
# restore.sh — restore the JANNet AI MySQL database from a backup produced
# by backup.sh.
#
# Usage:
#   ./restore.sh path/to/jannet_ai_TIMESTAMP.sql.gz
#
# Reads connection details from environment variables (matching
# .env.example at the repo root): DB_HOST, DB_PORT, DB_NAME, DB_USERNAME,
# DB_PASSWORD. NOT VERIFIED — not executed in this environment (no live
# MySQL instance available here); reviewed for correctness only.
#
# WARNING: this overwrites the target database's contents. Confirm the
# target DB_NAME before running against anything other than a local/dev
# environment.

set -euo pipefail

BACKUP_FILE="${1:?Usage: ./restore.sh path/to/backup.sql.gz}"

: "${DB_HOST:?DB_HOST is not set}"
: "${DB_PORT:?DB_PORT is not set}"
: "${DB_NAME:?DB_NAME is not set}"
: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"

if [[ ! -f "${BACKUP_FILE}" ]]; then
    echo "Backup file not found: ${BACKUP_FILE}" >&2
    exit 1
fi

read -r -p "This will overwrite database '${DB_NAME}' on ${DB_HOST}. Continue? [y/N] " CONFIRM
if [[ "${CONFIRM}" != "y" && "${CONFIRM}" != "Y" ]]; then
    echo "Aborted."
    exit 1
fi

gunzip -c "${BACKUP_FILE}" | MYSQL_PWD="${DB_PASSWORD}" mysql \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --user="${DB_USERNAME}" \
    "${DB_NAME}"

echo "Restore complete from ${BACKUP_FILE}"
