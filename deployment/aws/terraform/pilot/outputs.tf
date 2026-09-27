output "app_host_public_ip" {
  description = "Auto-assigned public IPv4 of the pilot EC2 host — changes if the instance stops/restarts (no Elastic IP, to avoid the $0.005/hr public-IPv4 charge)"
  value       = aws_instance.app_host.public_ip
}

output "app_host_instance_id" {
  description = "EC2 instance ID, for SSM Session Manager / SSH / CloudWatch"
  value       = aws_instance.app_host.id
}

output "ssh_command" {
  description = "SSH command to reach the host (uses the key pair named in ec2_key_name)"
  value       = "ssh -i ~/.ssh/jannet-ai/${var.ec2_key_name}.pem ec2-user@${aws_instance.app_host.public_ip}"
}
