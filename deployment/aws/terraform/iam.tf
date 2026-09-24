# JanNet AI — Phase 22: EC2 instance role.
#
# No AWS access keys are ever placed on the instance or in this repo —
# the EC2 host authenticates to SSM/Secrets Manager/CloudWatch purely via
# this instance role (the standard, credential-free AWS pattern), exactly
# matching this project's existing "no secret values live in source
# control" convention (ARCHITECTURE.md Section 6, carried over from
# Phase 4's JWT/.env handling).

resource "aws_iam_role" "app_host" {
  name = "${var.project_name}-${var.environment}-app-host-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# Scoped strictly to this project's own parameter path — never
# ssm:GetParameter on "*". See ../ssm/PARAMETERS.md for the exact
# parameter names this path must contain before the bootstrap script
# runs.
resource "aws_iam_role_policy" "ssm_read" {
  name = "${var.project_name}-ssm-read"
  role = aws_iam_role.app_host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ssm:GetParameter",
          "ssm:GetParametersByPath"
        ]
        Resource = "arn:aws:ssm:${var.aws_region}:*:parameter/${var.project_name}/${var.environment}/*"
      },
      {
        # Read-only access to exactly the one RDS-managed master
        # password secret this instance needs (rds.tf's
        # manage_master_user_password = true) — not a wildcard grant
        # across all Secrets Manager secrets in the account.
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = aws_db_instance.mysql.master_user_secret[0].secret_arn
      },
      {
        # The RDS-managed secret above is encrypted with the AWS-managed
        # aws/secretsmanager KMS key by default — Decrypt is required to
        # actually read the secret value, not just its metadata.
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "kms:ViaService" = "secretsmanager.${var.aws_region}.amazonaws.com"
          }
        }
      }
    ]
  })
}

# SRS 28 (Monitoring): "infrastructure and application metrics ...
# collected and visualized on an operations dashboard." The managed
# CloudWatchAgentServerPolicy is AWS's own standard, least-privilege-for-
# its-purpose policy for exactly this — not a hand-rolled equivalent.
resource "aws_iam_role_policy_attachment" "cloudwatch_agent" {
  role       = aws_iam_role.app_host.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_instance_profile" "app_host" {
  name = "${var.project_name}-${var.environment}-app-host-profile"
  role = aws_iam_role.app_host.name
}
