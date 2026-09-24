# JanNet AI — Phase 22: EC2 app host (runs docker compose: backend +
# ai-service, plus nginx as a TLS-terminating reverse proxy).
#
# Single instance, single AZ — matches the SRS's pilot-budget constraint
# (Section 30) and this project's Section 7 "Explicit Non-Goals"
# (no Kubernetes/additional infra beyond what's demonstrated-necessary).
# An Auto Scaling Group / ALB / multi-instance design is a real, valid
# future upgrade path (documented in ../README.md's "Scaling beyond the
# pilot" section) but is deliberately NOT built this phase — it is not
# needed to satisfy this phase's brief, and building it now would be the
# same kind of unrequested overengineering Section 7 already warns
# against for the rest of this project's architecture.

data "aws_ssm_parameter" "al2023_ami" {
  # Amazon's own published, auto-updating SSM parameter path for the
  # latest Amazon Linux 2023 x86_64 AMI — not a hardcoded/guessed AMI ID,
  # which would silently go stale and eventually fail to launch.
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_eip" "app_host" {
  domain = "vpc"
  tags   = { Name = "${var.project_name}-${var.environment}-eip" }
}

resource "aws_instance" "app_host" {
  ami                    = data.aws_ssm_parameter.al2023_ami.value
  instance_type          = var.ec2_instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.app_host.id]
  iam_instance_profile   = aws_iam_instance_profile.app_host.name
  key_name               = var.ec2_key_name

  root_block_device {
    volume_size = var.ec2_root_volume_gb
    volume_type = "gp3"
    encrypted   = true
    # Gap-backlog Patch 21: selected by the DLM snapshot policy in app_monitoring.tf.
    tags = {
      Snapshot = "${var.project_name}-${var.environment}-daily"
    }
  }

  # Renders scripts/ec2-user-data.sh.tpl with this environment's real
  # values — see that file's own header comment for exactly what it
  # does on first boot (Docker install, SSM parameter fetch, .env
  # generation, GHCR image pull, docker compose up, nginx+certbot setup).
  user_data = templatefile("${path.module}/../scripts/ec2-user-data.sh.tpl", {
    project_name     = var.project_name
    environment      = var.environment
    aws_region       = var.aws_region
    ghcr_image_owner = var.ghcr_image_owner
    app_version_tag  = var.app_version_tag
    db_endpoint      = aws_db_instance.mysql.address
    db_port          = aws_db_instance.mysql.port
    db_name          = var.db_name
    db_secret_arn    = aws_db_instance.mysql.master_user_secret[0].secret_arn
    domain_name      = var.domain_name
  })

  # A code change alone (new user_data content, e.g. a new app_version_tag
  # or a script fix) intentionally does NOT retrigger a fresh EC2 launch
  # — see scripts/deploy.sh for the correct way to roll out a new image
  # tag to an already-running instance (SSM Run Command, not
  # terraform apply). user_data only runs the very first time an instance
  # boots.
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${var.project_name}-${var.environment}-app-host" }

  depends_on = [aws_db_instance.mysql]
}

resource "aws_eip_association" "app_host" {
  instance_id   = aws_instance.app_host.id
  allocation_id = aws_eip.app_host.id
}
