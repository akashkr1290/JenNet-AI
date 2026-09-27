# Single security group for the EC2 host. MySQL (3306) is deliberately
# NOT opened here — it's self-hosted in Docker on this same host and
# reached only via the Docker network / localhost, never from outside.

resource "aws_security_group" "app_host" {
  name        = "${var.project_name}-pilot-app-sg"
  description = "JanNet AI pilot EC2 host - SSH from admin IP only, HTTP/HTTPS public"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "SSH from admin IP only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.admin_cidr]
  }

  ingress {
    description = "HTTP (nginx entrypoint)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS (nginx entrypoint, if TLS terminated here)"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${var.project_name}-pilot-app-sg"
  }
}
