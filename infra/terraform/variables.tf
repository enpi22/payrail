variable "region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "ap-south-1" # Mumbai
}

variable "instance_type" {
  description = "Needs ~4 GB RAM for PostgreSQL, Kafka and the app"
  type        = string
  default     = "t3.medium"
}

variable "repo_url" {
  description = "Public Git URL of your Payrail repository, cloned onto the server"
  type        = string
}

variable "my_ip_cidr" {
  description = "Your public IP in CIDR form, e.g. 203.0.113.7/32. Only this address can SSH in."
  type        = string
}

variable "public_key_path" {
  description = "SSH public key used to log in to the server"
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}