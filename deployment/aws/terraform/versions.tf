# JanNet AI — Phase 22 (AWS production deployment)
#
# Pinned to a real, currently-released `hashicorp/aws` provider major
# version (5.x) and a real Terraform CLI minor floor (1.6) — not a
# guessed/placeholder version. This workspace has no network reach to
# registry.terraform.io (see root README's network_configuration note),
# so `terraform init`/`plan`/`apply` were NOT executed here — see
# ../../VERIFICATION.md for the full list of what was and was not
# actually run in this sandbox versus what a real deploy operator with
# AWS credentials must run.

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

  # Local backend by default (state file stays with whoever runs this).
  # For a real multi-operator pilot, uncomment and point this at a
  # dedicated S3 bucket + DynamoDB lock table created out-of-band before
  # first `terraform init` — deliberately not provisioned by this same
  # config (a Terraform backend cannot safely bootstrap the bucket it
  # then immediately stores its own state in).
  #
  # backend "s3" {
  #   bucket         = "jannet-ai-terraform-state"
  #   key            = "phase22/terraform.tfstate"
  #   region         = "ap-south-1"
  #   dynamodb_table = "jannet-ai-terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "JanNet-AI"
      Environment = var.environment
      ManagedBy   = "Terraform"
      Phase       = "22"
    }
  }
}
