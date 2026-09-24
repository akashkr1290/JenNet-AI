# JanNet AI — Phase 22 Terraform variables.
#
# SRS Section 30 "Constraints": "the initial deployment is designed to
# run on low-cost/open-source infrastructure ... rather than enterprise
# managed services." Every default below follows that constraint —
# single-AZ, smallest practical instance sizes, no NAT Gateway (see
# vpc.tf's comment for why), no Multi-AZ RDS. Each is still a variable,
# not hardcoded, so a real municipal pilot with a bigger budget can
# raise them without editing any .tf file.

variable "aws_region" {
  description = "AWS region to deploy into. ap-south-1 (Mumbai) is closest to this project's SRS-stated target users (Indian municipal bodies, SRS Section 3)."
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Deployment environment name, used in resource names/tags."
  type        = string
  default     = "pilot"
}

variable "project_name" {
  description = "Short project slug used as a prefix for resource names."
  type        = string
  default     = "jannet-ai"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "Public subnet CIDRs (one per AZ) — hosts the EC2 application host. No NAT Gateway exists in this design (see vpc.tf), so nothing lives in a private-with-egress subnet."
  type        = list(string)
  default     = ["10.20.1.0/24", "10.20.2.0/24"]
}

variable "db_subnet_cidrs" {
  description = "Private (no route to the internet) subnet CIDRs for the RDS instance — two required by AWS for a DB subnet group even in single-AZ mode."
  type        = list(string)
  default     = ["10.20.11.0/24", "10.20.12.0/24"]
}

variable "availability_zones" {
  description = "AZs to spread the public/db subnets across."
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b"]
}

variable "admin_cidr" {
  description = "CIDR allowed to SSH (port 22) into the EC2 host. MUST be narrowed from 0.0.0.0/0 before apply — see terraform.tfvars.example. No safe default is provided on purpose."
  type        = string
  # Intentionally invalid-looking placeholder, not 0.0.0.0/0, so an
  # operator who forgets to override this gets an obvious apply-time
  # CIDR-parse error rather than a silently world-open SSH port.
  default = "203.0.113.0/32"
}

variable "domain_name" {
  description = "Public domain/subdomain the API will be served on (e.g. api.jannetai.example.org). Used only for the nginx/certbot config and Route53 record — leave empty to skip Route53 record creation and use the EC2 public IP directly (HTTP-only, no TLS certificate possible without a domain)."
  type        = string
  default     = ""
}

variable "route53_zone_id" {
  description = "Existing Route53 hosted zone ID to create the API record in. Leave empty if domain_name is empty or DNS is managed outside Route53."
  type        = string
  default     = ""
}

variable "ec2_instance_type" {
  description = "EC2 instance type running docker-compose (backend + ai-service containers). t3.small is the smallest type comfortably running both a JVM (Spring Boot) and a Python/OpenCV process side by side; t3.micro was evaluated and rejected — see ../README.md's sizing note."
  type        = string
  default     = "t3.small"
}

variable "ec2_root_volume_gb" {
  description = "EC2 root EBS volume size. Also backs the backend_storage Docker named volume (complaint photo uploads) — see ../README.md's 'Object storage' section for why S3 migration is an explicit open item, not done this phase."
  type        = number
  default     = 30
}

variable "ec2_key_name" {
  description = "Name of an EXISTING EC2 key pair (created out-of-band via `aws ec2 create-key-pair` or the console) to allow SSH access. Never generate/commit a private key in this repo."
  type        = string
}

variable "db_instance_class" {
  description = "RDS instance class. db.t3.micro is AWS's smallest MySQL-compatible burstable class, matching the SRS's low-cost-infrastructure constraint."
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage_gb" {
  description = "RDS allocated storage (GB). gp3, smallest practical size for a pilot's complaint-record volume."
  type        = number
  default     = 20
}

variable "db_engine_version" {
  description = "RDS MySQL engine version. Must satisfy backend/pom.xml's mysql-connector-j compatibility and match the mysql:8.0 image used in local docker-compose.yml (Phase 18) so Flyway migrations behave identically in both environments."
  type        = string
  default     = "8.0.35"
}

variable "db_name" {
  description = "Database name — MUST match DB_NAME in .env.example / the DB_NAME SSM parameter (see ../ssm/PARAMETERS.md)."
  type        = string
  default     = "jannet_ai"
}

variable "db_multi_az" {
  description = "Whether RDS runs Multi-AZ (higher cost, automatic failover). Defaults to false per the SRS's pilot-budget constraint; flip to true for a real production launch beyond the academic pilot."
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  description = "RDS automated backup retention window in days."
  type        = number
  default     = 7
}

variable "ghcr_image_owner" {
  description = "GitHub org/user + repo path GHCR images are published under by .github/workflows/release-image-publish.yml (Phase 21), e.g. \"your-org/jannet-ai\". Used only to compose image references in the EC2 bootstrap script — never a credential."
  type        = string
}

variable "alert_email" {
  description = "Email address subscribed to the SNS alerts topic for infrastructure alarms (EC2 status check, RDS CPU/storage). Leave empty to create the topic without a subscription (alarms still fire, but no one is notified until a subscription is added)."
  type        = string
  default     = ""
}

variable "app_version_tag" {
  description = "Image tag to deploy — MUST correspond to a real `v*.*.*` tag already built and pushed by release-image-publish.yml (Phase 21). Terraform does not build or publish images itself (see deployment/README.md's Phase 21/22 boundary note)."
  type        = string
}
