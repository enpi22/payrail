data "aws_vpc" "default" {
  default = true
}

data "aws_ami" "ubuntu" {
  most_recent = true
  owners      = ["099720109477"] # Canonical

  filter {
    name   = "name"
    values = ["ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*"]
  }
}
resource "aws_key_pair" "deployer" {
  key_name   = "payrail-deployer"
  public_key = file(pathexpand(var.public_key_path))
}

resource "aws_security_group" "payrail" {
  name        = "payrail-server"
  description = "HTTP/HTTPS from anywhere, SSH only from my IP"
  vpc_id      = data.aws_vpc.default.id

  ingress {
    description = "SSH from my IP only"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.my_ip_cidr]
  }

  ingress {
    description = "HTTP (Caddy redirects to HTTPS and answers certificate challenges)"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound (package installs, Docker images, merchant webhooks)"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
resource "aws_eip" "payrail" {
  domain = "vpc"
}

locals {
  domain = "${replace(aws_eip.payrail.public_ip, ".", "-")}.sslip.io"
}
resource "aws_instance" "payrail" {
  ami                    = data.aws_ami.ubuntu.id
  instance_type          = var.instance_type
  key_name               = aws_key_pair.deployer.key_name
  vpc_security_group_ids = [aws_security_group.payrail.id]

  root_block_device {
    volume_size = 20
    volume_type = "gp3"
    encrypted   = true
  }

  metadata_options {
    http_tokens = "required" # IMDSv2 only: blunts SSRF attacks against the metadata service
  }

  user_data = templatefile("${path.module}/user_data.sh.tftpl", {
    repo_url = var.repo_url
    domain   = local.domain
  })

  tags = {
    Name = "payrail"
  }
}

resource "aws_eip_association" "payrail" {
  instance_id   = aws_instance.payrail.id
  allocation_id = aws_eip.payrail.id
}