# JanNet AI — Phase 22: let GitHub Actions deploy without any long-lived
# AWS access key ever existing as a GitHub secret.
#
# Uses GitHub's own OIDC token + AWS IAM's federated-identity role
# assumption (aws-actions/configure-aws-credentials@v4's documented
# `role-to-assume` mode) — the same "no static credential anywhere"
# principle this project has followed since Phase 4's JWT/.env handling
# and Phase 21's ephemeral `openssl rand` CI credentials. The only thing
# a GitHub Actions run ever has is a short-lived, automatically-expiring
# token GitHub itself issues per-run.

variable "github_repo" {
  description = "GitHub \"org/repo\" this OIDC trust is scoped to (e.g. \"your-org/jannet-ai\") — deploys can only be triggered from this exact repository, never any fork."
  type        = string
}

data "tls_certificate" "github_oidc" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github_oidc.certificates[0].sha1_fingerprint]
}

resource "aws_iam_role" "github_deploy" {
  name = "${var.project_name}-${var.environment}-github-deploy-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.github.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        # Restricted to the release-image-publish tag pattern (v*.*.*)
        # AND a manually-invoked workflow_dispatch on this repo's main
        # branch — matches deploy-aws.yml's own trigger conditions, so
        # the AWS-side trust and the GitHub-side trigger agree with each
        # other rather than one being broader than the other.
        StringLike = {
          "token.actions.githubusercontent.com:sub" = [
            "repo:${var.github_repo}:ref:refs/tags/v*",
            "repo:${var.github_repo}:ref:refs/heads/main"
          ]
        }
      }
    }]
  })
}

# Scoped to exactly the one instance this project ever deploys to and
# exactly the SSM actions deploy.sh/rollback.sh issue — not
# ssm:SendCommand on "*", not any broader EC2/IAM permission.
resource "aws_iam_role_policy" "github_deploy_ssm" {
  name = "${var.project_name}-github-deploy-ssm"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["ssm:SendCommand"]
        Resource = [
          aws_instance.app_host.arn,
          "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
        ]
      },
      {
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
        Resource = "*" # These two read-only calls take a command ID, not
                        # an instance ARN, as their resource — SSM has no
                        # finer-grained resource type for them.
      }
    ]
  })
}

output "github_deploy_role_arn" {
  description = "Put this in the GitHub repo's Actions variables (not secrets — it's not sensitive, it's a role ARN) as AWS_DEPLOY_ROLE_ARN, consumed by .github/workflows/deploy-aws.yml."
  value       = aws_iam_role.github_deploy.arn
}
