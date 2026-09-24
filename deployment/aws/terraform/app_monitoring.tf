# Gap-backlog Patches 19/20/21 (Sep 2026 strict recheck).
#
# dns_and_monitoring.tf already alarms on INFRASTRUCTURE (EC2 status check,
# RDS CPU, RDS free storage). Patch 20's point is that this is not enough -
# this file adds APPLICATION-level alarms. Container logs reach CloudWatch
# Logs via Docker's awslogs driver (docker-compose.prod-override.yml); the
# metric filters below match log lines the application really emits (the
# exact strings are cited next to each filter), and every alarm notifies the
# existing SNS topic.
#
# Honest scope: HTTP 5xx is counted from the backend's own
# "UNHANDLED_EXCEPTION" log line (GlobalExceptionHandler), not from nginx
# access logs - nginx runs on the host and no CloudWatch agent is installed.
# AI inference LATENCY is exposed as a Micrometer timer on /actuator/prometheus
# (Patch 17) but is not alarmed here: that needs a metrics pipeline
# (CloudWatch agent with Prometheus scraping) this stack does not run yet.
#
# NOT VERIFIED against a live AWS account (none reachable from this sandbox);
# HCL syntax was machine-parsed, not `terraform validate`d (no terraform
# binary available here).

locals {
  log_group_prefix = "/${var.project_name}/${var.environment}"
  metric_namespace = "JanNetAI/${var.environment}"
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "${local.log_group_prefix}/backend"
  retention_in_days = 30
}

resource "aws_cloudwatch_log_group" "ai_service" {
  name              = "${local.log_group_prefix}/ai-service"
  retention_in_days = 30
}

resource "aws_iam_role_policy" "app_host_logs" {
  name = "${var.project_name}-app-logs"
  role = aws_iam_role.app_host.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
      Resource = ["${aws_cloudwatch_log_group.backend.arn}:*", "${aws_cloudwatch_log_group.ai_service.arn}:*"]
    }]
  })
}

# ---- metric filters (patterns = literal strings logged by the code) ----

# GlobalExceptionHandler.handleGeneric: "UNHANDLED_EXCEPTION <method> <uri>: ..."
resource "aws_cloudwatch_log_metric_filter" "backend_5xx" {
  name           = "backend-unhandled-5xx"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "\"UNHANDLED_EXCEPTION\""
  metric_transformation {
    name          = "Backend5xx"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

# AiServiceClient: "ai-service unreachable at <url>: ..." (connect/read failure)
resource "aws_cloudwatch_log_metric_filter" "ai_unreachable" {
  name           = "ai-service-unreachable"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "\"ai-service unreachable\""
  metric_transformation {
    name          = "AiServiceFailures"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

# NotificationService.deliver: "Notification delivery attempt N/M failed ..."
resource "aws_cloudwatch_log_metric_filter" "notification_failures" {
  name           = "notification-delivery-failures"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "\"Notification delivery attempt\""
  metric_transformation {
    name          = "NotificationDeliveryFailures"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

# Spring/Hibernate/Hikari database exceptions (class names appear in the log line)
resource "aws_cloudwatch_log_metric_filter" "database_errors" {
  name           = "database-errors"
  log_group_name = aws_cloudwatch_log_group.backend.name
  pattern        = "?SQLException ?JDBCConnectionException ?CannotCreateTransactionException ?\"Connection is not available\""
  metric_transformation {
    name          = "DatabaseErrors"
    namespace     = local.metric_namespace
    value         = "1"
    default_value = "0"
  }
}

# ---- alarms -> existing SNS topic (thresholds are documented placeholders) ----

resource "aws_cloudwatch_metric_alarm" "backend_5xx_high" {
  alarm_name          = "${var.project_name}-${var.environment}-backend-5xx-high"
  alarm_description   = "More than 5 unhandled server errors in 5 minutes"
  namespace           = local.metric_namespace
  metric_name         = "Backend5xx"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "ai_failures_high" {
  alarm_name          = "${var.project_name}-${var.environment}-ai-failures-high"
  alarm_description   = "ai-service unreachable more than 3 times in 5 minutes (complaints fall back to manual review)"
  namespace           = local.metric_namespace
  metric_name         = "AiServiceFailures"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 3
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "notification_failures_high" {
  alarm_name          = "${var.project_name}-${var.environment}-notification-failures-high"
  alarm_description   = "More than 20 failed notification delivery attempts in 15 minutes"
  namespace           = local.metric_namespace
  metric_name         = "NotificationDeliveryFailures"
  statistic           = "Sum"
  period              = 900
  evaluation_periods  = 1
  threshold           = 20
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "database_errors_high" {
  alarm_name          = "${var.project_name}-${var.environment}-database-errors"
  alarm_description   = "Database errors logged by the backend"
  namespace           = local.metric_namespace
  metric_name         = "DatabaseErrors"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
}

# ---- Patch 21: daily EBS snapshots of the app host's root volume ----
# Still relevant: app.storage.provider defaults to "local", so until a
# deployment sets STORAGE_PROVIDER=s3, complaint photos live on this volume.

resource "aws_iam_role" "dlm_lifecycle" {
  name = "${var.project_name}-${var.environment}-dlm"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "dlm.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "dlm_lifecycle" {
  role       = aws_iam_role.dlm_lifecycle.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSDataLifecycleManagerServiceRole"
}

resource "aws_dlm_lifecycle_policy" "app_host_daily" {
  description        = "Daily snapshots of the ${var.project_name} ${var.environment} app host volume, 7 kept"
  execution_role_arn = aws_iam_role.dlm_lifecycle.arn
  state              = "ENABLED"

  policy_details {
    resource_types = ["VOLUME"]

    schedule {
      name = "daily-keep-7"
      create_rule {
        interval      = 24
        interval_unit = "HOURS"
        times         = ["20:30"] # UTC = 02:00 IST
      }
      retain_rule {
        count = 7
      }
      copy_tags = true
    }

    target_tags = {
      Snapshot = "${var.project_name}-${var.environment}-daily"
    }
  }
}
