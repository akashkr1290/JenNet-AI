# Pilot EC2 app host — no RDS/S3/CloudWatch dependency. Runs
# docker/docker-compose.yml + deployment/docker-compose.pilot-override.yml
# (backend + ai-service + self-hosted mysql), fronted by plain-HTTP nginx.
#
# No Elastic IP: since Feb 2024 AWS bills $0.005/hr for every public IPv4
# address, EIP or not, and that charge is not reliably covered by this
# account's Free Plan. The subnet already sets
# map_public_ip_on_launch = true, so the instance gets a free
# auto-assigned public IPv4 on its own — the trade-off is that this
# address changes if the instance stops/restarts (fine for a pilot that
# isn't restarted often; re-point DNS/clients after any restart).

data "aws_ssm_parameter" "al2023_ami" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

resource "aws_instance" "app_host" {
  ami                         = data.aws_ssm_parameter.al2023_ami.value
  instance_type               = var.ec2_instance_type
  subnet_id                   = aws_subnet.public[0].id
  vpc_security_group_ids      = [aws_security_group.app_host.id]
  iam_instance_profile        = aws_iam_instance_profile.app_host.name
  key_name                    = var.ec2_key_name
  associate_public_ip_address = true

  # Standard (not the T3 default "unlimited") CPU credits: when credits run
  # out the instance is throttled to baseline instead of billed for surplus
  # CPU - required for the zero-cost pilot.
  credit_specification {
    cpu_credits = "standard"
  }

  root_block_device {
    volume_size = var.ec2_root_volume_gb
    volume_type = "gp3"
    encrypted   = true
  }

  user_data = templatefile("${path.module}/../../../scripts/ec2-user-data-pilot.sh.tpl", {
    project_name     = var.project_name
    environment      = var.environment
    aws_region       = var.aws_region
    ghcr_image_owner = var.ghcr_image_owner
    app_version_tag  = var.app_version_tag
  })

  # A user_data code change alone doesn't retrigger a fresh EC2 launch —
  # same convention as the full deployment config.
  lifecycle {
    ignore_changes = [ami, user_data]
  }

  tags = { Name = "${var.project_name}-${var.environment}-app-host" }
}
