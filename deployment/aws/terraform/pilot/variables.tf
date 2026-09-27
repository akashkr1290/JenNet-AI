variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment tag distinguishing this pilot from the full deployment config"
  type        = string
  default     = "pilot-free"
}

variable "project_name" {
  description = "Short project name used in resource naming"
  type        = string
  default     = "jannet-ai"
}

variable "vpc_cidr" {
  description = "CIDR block for the pilot VPC"
  type        = string
  default     = "10.20.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets (EC2 host only — no DB subnets needed, MySQL is self-hosted in Docker)"
  type        = list(string)
  default     = ["10.20.1.0/24", "10.20.2.0/24"]
}

variable "availability_zones" {
  description = "Availability zones to spread the public subnets across"
  type        = list(string)
  default     = ["ap-south-1a", "ap-south-1b"]
}

variable "admin_cidr" {
  description = "CIDR allowed to SSH into the EC2 host (your own IP/32 — never 0.0.0.0/0)"
  type        = string
}

variable "ec2_instance_type" {
  description = "EC2 instance type — confirmed free-tier-eligible on this account via aws ec2 describe-instance-types"
  type        = string
  default     = "t3.small"
}

variable "ec2_root_volume_gb" {
  description = "Root EBS volume size in GB (gp3 free tier covers up to 30GB-month)"
  type        = number
  default     = 30
}

variable "ec2_key_name" {
  description = "Name of the existing EC2 key pair to attach for SSH access"
  type        = string
}

variable "ghcr_image_owner" {
  description = "GHCR image owner/repo prefix to pull backend/ai-service images from"
  type        = string
}

variable "app_version_tag" {
  description = "Image tag to deploy"
  type        = string
  default     = "latest"
}
