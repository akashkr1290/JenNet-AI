# JanNet AI — Phase 22: managed MySQL database (RDS).
#
# The master password is intentionally NOT set here as a plain Terraform
# variable/tfvars value, to avoid it being written into terraform.tfstate
# in cleartext (a well-known Terraform footgun, not specific to this
# project) or accidentally committed inside terraform.tfvars. Instead:
# `manage_master_user_password = true` delegates password generation and
# rotation entirely to AWS Secrets Manager (a real, current RDS feature —
# no application code needs to know the password directly, matching how
# .env.example already treats DB_PASSWORD as "no safe default, must be
# supplied at deploy time"). See ssm/PARAMETERS.md for how the EC2
# bootstrap script retrieves the resulting Secrets Manager secret value
# at boot (IAM-permission-gated, never copy-pasted).

resource "aws_db_instance" "mysql" {
  identifier     = "${var.project_name}-${var.environment}"
  engine         = "mysql"
  engine_version = var.db_engine_version

  instance_class        = var.db_instance_class
  allocated_storage     = var.db_allocated_storage_gb
  storage_type          = "gp3"
  storage_encrypted     = true

  db_name  = var.db_name
  username = "jannet_admin"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db.id]

  multi_az                = var.db_multi_az
  backup_retention_period  = var.db_backup_retention_days
  # Match docker-compose's local dev backup window as closely as RDS
  # allows — see database/scripts/backup.sh's own retention comment
  # (Phase 2) for the equivalent local-dev convention this mirrors.
  backup_window            = "17:00-17:30" # UTC == 22:30-23:00 IST, low-traffic window
  maintenance_window       = "sun:18:00-sun:19:00" # UTC == Sun 23:30-00:30 IST

  # Application code never generates DDL (application.yml's
  # ddl-auto: validate, Phase 3) — Flyway migrations (database/migrations/)
  # are the schema's sole source of truth. skip_final_snapshot is false
  # so a `terraform destroy` cannot silently discard production data
  # without an explicit snapshot identifier.
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project_name}-${var.environment}-final-snapshot"
  deletion_protection       = true

  # Encrypted in transit — see backend/src/main/resources/application.yml's
  # PROJECT_INTEGRATION.md Section 6 note on the JDBC useSSL flag (updated
  # this phase; see that file for the full rationale) — the backend now
  # requires TLS to this RDS endpoint's default self-signed CA bundle
  # rather than the local docker-compose useSSL=false convenience default.
  parameter_group_name = aws_db_parameter_group.mysql_require_ssl.name

  apply_immediately = false

  tags = { Name = "${var.project_name}-${var.environment}-mysql" }
}

resource "aws_db_parameter_group" "mysql_require_ssl" {
  name   = "${var.project_name}-${var.environment}-mysql-require-ssl"
  family = "mysql8.0"

  # SRS 27.2: "All data in transit is encrypted via TLS 1.2+ ... internal
  # service-to-service communication" — enforced at the database layer,
  # not left to the client's connection-string opinion alone.
  parameter {
    name  = "require_secure_transport"
    value = "ON"
  }

  tags = { Name = "${var.project_name}-${var.environment}-mysql-require-ssl" }
}
