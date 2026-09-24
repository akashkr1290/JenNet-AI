# JanNet AI — Phase 22: DNS record + baseline monitoring/alerting.
#
# Both blocks below are conditional on values the operator may not have
# (a domain, a notification email) — this config runs cleanly to an
# IP-only, alert-free deployment if var.domain_name is left empty,
# matching this project's established "safe defaults, nothing required
# that isn't truly required" convention (.env.example's own header).

resource "aws_route53_record" "api" {
  count   = var.domain_name != "" && var.route53_zone_id != "" ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"
  ttl     = 300
  records = [aws_eip.app_host.public_ip]
}

# SRS 28 (Monitoring): "Automated alerts are configured for: API error
# rate exceeding 5% ... AI classification latency exceeding 15s (P95)
# ... scheduled analytics job failures ... SLA-breach rate exceeding a
# configured threshold." Those four are APPLICATION-level metrics that
# would require the backend/ai-service to emit custom CloudWatch metrics
# — no such metric-emission code exists anywhere in backend/ or
# ai-service/ as of Phase 21, and adding it would be backend application
# source work outside this phase's "deployment/" scope (ARCHITECTURE.md
# Section 8). What IS in scope and built here: INFRASTRUCTURE-level
# health/availability alerting (EC2 status checks), which needs no
# application code change at all. The four SRS-listed application-metric
# alerts remain an explicitly open item — see ../README.md's "Known gaps"
# section, not silently claimed as done.

resource "aws_sns_topic" "alerts" {
  name = "${var.project_name}-${var.environment}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

resource "aws_cloudwatch_metric_alarm" "ec2_status_check" {
  alarm_name          = "${var.project_name}-${var.environment}-ec2-status-check-failed"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "StatusCheckFailed"
  namespace           = "AWS/EC2"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  alarm_description   = "App host EC2 instance/system status check failed twice in a row — see ../ROLLBACK.md for the recovery procedure."
  dimensions          = { InstanceId = aws_instance.app_host.id }
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name          = "${var.project_name}-${var.environment}-rds-cpu-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "CPUUtilization"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  threshold           = 80
  alarm_description   = "RDS CPU above 80% for 15 minutes."
  dimensions          = { DBInstanceIdentifier = aws_db_instance.mysql.identifier }
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name          = "${var.project_name}-${var.environment}-rds-storage-low"
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = 1
  metric_name         = "FreeStorageSpace"
  namespace           = "AWS/RDS"
  period              = 300
  statistic           = "Average"
  # 10% of db_allocated_storage_gb, in bytes.
  threshold           = var.db_allocated_storage_gb * 1024 * 1024 * 1024 * 0.1
  alarm_description   = "RDS free storage below 10% of allocated capacity."
  dimensions          = { DBInstanceIdentifier = aws_db_instance.mysql.identifier }
  alarm_actions       = [aws_sns_topic.alerts.arn]
}
