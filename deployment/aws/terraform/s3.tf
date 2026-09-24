# Gap-backlog Patches 5/7/21 (Sep 2026 audit): complaint media storage,
# replacing the local-disk stub (StorageService's Javadoc has the full
# swap-in contract this fulfills). Bucket name includes the AWS account
# ID's region+project+environment (not just project+environment) because
# S3 bucket names are globally unique across ALL AWS accounts, not just
# within this one.
#
# NOT VERIFIED: no live AWS account reachable from this sandbox to apply
# this against - validated by manual review only, consistent with every
# other file in this directory (see ec2.tf/rds.tf's own NOT VERIFIED
# notes).

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "complaint_media" {
  bucket = "${var.project_name}-${var.environment}-media-${data.aws_caller_identity.current.account_id}"

  tags = {
    Project     = var.project_name
    Environment = var.environment
  }
}

# "Images should not be publicly accessible" (gap-backlog Patch 6) - block
# every public-access avenue S3 offers, belt-and-suspenders alongside the
# bucket simply never having a public-read bucket policy in the first
# place. All access is via the backend's own presigned GET URLs
# (S3StorageService.presignedUrl), never a direct public bucket URL.
resource "aws_s3_bucket_public_access_block" "complaint_media" {
  bucket = aws_s3_bucket.complaint_media.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "complaint_media" {
  bucket = aws_s3_bucket.complaint_media.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Gap-backlog Patch 21 ("Automated EBS Backup"): once media moves here
# from local disk, S3 versioning is this project's actual answer to that
# patch - EBS snapshots of an EC2 host that no longer holds complaint
# media as its source of truth would be backing up the wrong thing. See
# PROJECT_INTEGRATION.md Section 6 for the decision record this closes.
resource "aws_s3_bucket_versioning" "complaint_media" {
  bucket = aws_s3_bucket.complaint_media.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Complements versioning: once a "current" version is 90 days old (i.e.
# something newer has superseded it), move the old version to cheaper
# storage rather than paying standard rates for it forever. Nothing is
# ever deleted by this rule - only current, non-versioned copies would
# need an expiration rule, and this bucket has none.
resource "aws_s3_bucket_lifecycle_configuration" "complaint_media" {
  bucket = aws_s3_bucket.complaint_media.id

  rule {
    id     = "noncurrent-version-transition"
    status = "Enabled"
    noncurrent_version_transition {
      noncurrent_days = 90
      storage_class   = "STANDARD_IA"
    }
  }
}

# Scoped strictly to this one bucket - same least-privilege convention as
# iam.tf's ssm_read policy (no wildcard Resource).
resource "aws_iam_role_policy" "s3_media_access" {
  name = "${var.project_name}-s3-media-access"
  role = aws_iam_role.app_host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:GetObject"]
        Resource = "${aws_s3_bucket.complaint_media.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.complaint_media.arn
      }
    ]
  })
}
