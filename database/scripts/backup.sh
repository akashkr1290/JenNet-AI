#!/usr/bin/env bash
# backup.sh — dump the JANNet AI MySQL database to a timestamped file.
#
# Usage:
#   ./backup.sh [output_dir]
#
# Reads connection details from environment variables (matching
# .env.example at the repo root): DB_HOST, DB_PORT, DB_NAME, DB_USERNAME,
# DB_PASSWORD. NOT VERIFIED — not executed in this environment (no live
# MySQL instance available here); reviewed for correctness only.

set -euo pipefail

OUTPUT_DIR="${1:-./backups}"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
FILE="${OUTPUT_DIR}/jannet_ai_${TIMESTAMP}.sql.gz"

: "${DB_HOST:?DB_HOST is not set}"
: "${DB_PORT:?DB_PORT is not set}"
: "${DB_NAME:?DB_NAME is not set}"
: "${DB_USERNAME:?DB_USERNAME is not set}"
: "${DB_PASSWORD:?DB_PASSWORD is not set}"

mkdir -p "${OUTPUT_DIR}"

MYSQL_PWD="${DB_PASSWORD}" mysqldump \
    --host="${DB_HOST}" \
    --port="${DB_PORT}" \
    --user="${DB_USERNAME}" \
    --single-transaction \
    --routines \
    --triggers \
    --hex-blob \
    "${DB_NAME}" | gzip > "${FILE}"

echo "Backup written to ${FILE}"
