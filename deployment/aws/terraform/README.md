# Terraform — AWS infrastructure (Phase 22)

Provisions everything in `../ARCHITECTURE_DIAGRAM.md`: VPC, one public
subnet pair + one DB subnet pair, an EC2 app host (docker compose:
backend + ai-service + nginx), an RDS MySQL instance, IAM roles (EC2
instance profile + a GitHub Actions OIDC deploy role), CloudWatch alarms
+ SNS topic, and (optionally) a Route53 record.

**NOT VERIFIED** — this sandbox has no network reach to
`registry.terraform.io` or a real AWS account (see
`../../VERIFICATION.md`). Every resource/argument name below was
hand-cross-checked against the `hashicorp/aws` provider's current (5.x)
documented schema from training-data knowledge, not executed — a real
operator running this for the first time should treat the very first
`terraform plan` as this design's actual first test, the same way this
project has treated every "written but not run" phase before it
(PHASE_HANDOFF.md's own established convention).

## Sizing note (`ec2_instance_type`)

`t3.micro` (1 GiB RAM) was evaluated and rejected: this single host runs
a JVM (Spring Boot, `-Xmx` unconfigured = defaults to a fraction of
available RAM) AND a Python process that imports OpenCV/optionally
torch/ultralytics (`ai-service/requirements.txt`) side by side, plus
nginx. `t3.small` (2 GiB) is the smallest size with a realistic chance of
not OOM-killing one of the two application containers under any real
load — still a guess pending this deployment's first real memory-usage
data, not a benchmarked number.

## First-time setup

```bash
cd deployment/aws/terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars with real values (see that file's own comments)
terraform init
terraform plan   # review carefully before the next step
terraform apply
```

Then, **before** the EC2 instance's user-data script can succeed:
1. Create every parameter listed in `../../ssm/PARAMETERS.md`.
2. Note the `app_host_public_ip` and `rds_endpoint` outputs.
3. If `domain_name`/`route53_zone_id` were left empty, manually point
   your own DNS provider's A record at `app_host_public_ip`.

Then set two repo-level GitHub Actions **variables** (not secrets —
neither value is sensitive) so `.github/workflows/deploy-aws.yml` can
find its target:
- `EC2_INSTANCE_ID` = `terraform output -raw` the instance ID (add an
  `instance_id` output if you need it printed; not included above by
  default to keep the output list focused on what a human deploying
  manually actually needs).
- `AWS_DEPLOY_ROLE_ARN` = the `github_deploy_role_arn` output.
- `API_BASE_URL` = the `api_url` output (used only by `deploy-aws.yml`'s
  optional post-deploy health check step).

## Destroying

```bash
terraform destroy
```

`deletion_protection = true` on the RDS instance (`rds.tf`) means this
will fail until you separately `terraform apply` with that flag flipped
to `false` (or delete the resource by hand with an explicit final
snapshot) — a deliberate guard against an accidental `destroy` silently
discarding production complaint data, not a bug in this config.
