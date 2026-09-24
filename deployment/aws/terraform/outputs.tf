output "app_host_public_ip" {
  description = "Elastic IP of the EC2 app host. Point DNS here manually if domain_name/route53_zone_id were left empty."
  value       = aws_eip.app_host.public_ip
}

output "api_url" {
  description = "The API's public base URL, matching the shape of API_BASE_URL in .env.example."
  value       = var.domain_name != "" ? "https://${var.domain_name}/api/v1" : "http://${aws_eip.app_host.public_ip}/api/v1 (no domain configured — see deployment/nginx/README.md; HTTPS requires a real domain for Let's Encrypt)"
}

output "rds_endpoint" {
  description = "RDS MySQL endpoint (host:port) — matches DB_HOST/DB_PORT in .env.example."
  value       = aws_db_instance.mysql.endpoint
}

output "rds_master_secret_arn" {
  description = "Secrets Manager ARN holding the RDS master credentials (managed by AWS, never printed here in plaintext). Fetch with: aws secretsmanager get-secret-value --secret-id <this-arn>"
  value       = aws_db_instance.mysql.master_user_secret[0].secret_arn
}

output "ssh_command" {
  description = "SSH command to reach the app host, once ec2_key_name's matching private key is available locally."
  value       = "ssh -i /path/to/${var.ec2_key_name}.pem ec2-user@${aws_eip.app_host.public_ip}"
}

output "sns_alerts_topic_arn" {
  description = "SNS topic ARN that CloudWatch alarms publish to."
  value       = aws_sns_topic.alerts.arn
}

output "complaint_media_bucket" {
  description = "S3 bucket name for complaint media - set as STORAGE_S3_BUCKET/STORAGE_PROVIDER=s3 in the app host's environment (see .env.example)."
  value       = aws_s3_bucket.complaint_media.bucket
}
