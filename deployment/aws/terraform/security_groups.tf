# JanNet AI — Phase 22: security groups.
#
# SRS 27.3 (Rate Limiting) and 27.1 (RBAC) are application-layer controls
# already implemented in backend/ (Phases 4/11) — these security groups
# are the network-layer complement: nothing reaches the app or the
# database except the specific ports/sources listed below. ai-service's
# port 8001 is deliberately NOT opened here at all — SRS/architecture
# treat it as an internal-only microservice the backend calls
# server-to-server (ARCHITECTURE.md Section 3), never a citizen-facing
# endpoint, so it has no business being reachable from outside the EC2
# host's own docker network (see docker/docker-compose.yml — no public
# port mapping change made or needed here either).

resource "aws_security_group" "app_host" {
  name        = "${var.project_name}-app-host-sg"
  description = "JanNet AI EC2 app host (backend + ai-service via docker compose, nginx reverse proxy)"
  vpc_id      = aws_vpc.main.id

  # HTTPS — public, citizen/officer/admin/government-dashboard traffic
  # via the Flutter app and any browser-based dashboard use.
  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # HTTP — kept open only for the ACME HTTP-01 challenge (Let's
  # Encrypt/certbot renewal, see nginx/README.md) and to redirect
  # plain-HTTP requests to HTTPS; nginx.conf never proxies plaintext
  # HTTP through to the backend.
  ingress {
    description = "HTTP (ACME challenge + HTTPS redirect only)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  # SSH — restricted to var.admin_cidr, never 0.0.0.0/0 (see variables.tf's
  # deliberately-invalid default forcing an explicit override).
  ingress {
    description = "SSH (admin access only)"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  egress {
    description = "All outbound (package installs, GHCR image pulls, Gemini API, SMTP/SMS gateways)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-app-host-sg" }
}

resource "aws_security_group" "db" {
  name        = "${var.project_name}-db-sg"
  description = "JanNet AI RDS MySQL — reachable only from the app host, on 3306, never from the internet"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL from the app host only"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.app_host.id]
  }

  egress {
    description = "All outbound (RDS-managed; no application-level need for this, kept open per AWS default practice)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.project_name}-db-sg" }
}
