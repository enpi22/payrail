output "url" {
  description = "Public HTTPS URL (give the server a few minutes after apply to build and start)"
  value       = "https://${local.domain}"
}

output "public_ip" {
  value = aws_eip.payrail.public_ip
}

output "ssh" {
  value = "ssh ubuntu@${aws_eip.payrail.public_ip}"
}