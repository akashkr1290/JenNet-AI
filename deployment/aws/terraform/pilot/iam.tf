# Pilot EC2 instance role — SSM Parameter Store read access only.
# No RDS secret (self-hosted MySQL, no Secrets Manager involved) and no
# CloudWatchAgentServerPolicy attachment (pilot relies on EC2's automatic
# free basic monitoring, not the CloudWatch agent / custom log groups,
# to avoid any CloudWatch Logs ingestion/storage charges).

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
      }
    ]
  })
}

resource "aws_iam_instance_profile" "app_host" {
  name = "${var.project_name}-${var.environment}-app-host-profile"
  role = aws_iam_role.app_host.name
}
