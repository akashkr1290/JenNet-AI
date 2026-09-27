# JanNet AI — AWS Free-Plan Pilot
#
# Minimal, self-hosted-MySQL, no-RDS/S3/CloudWatch-logs variant of the
# full deployment/aws/terraform/ config, kept in its own state/directory
# so the original full config is never touched. Same provider pins as
# the top-level versions.tf for compatibility.

terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }

  # Local backend — state file stays with whoever runs this.
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "JanNet-AI"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Phase       = "pilot-free"
    }
  }
}
