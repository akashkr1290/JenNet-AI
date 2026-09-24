# JanNet AI — Phase 22: networking.
#
# Deliberately NO NAT Gateway (~$32/mo + data-processing charges just to
# let a private subnet reach the internet) — the SRS's own
# low-cost-infrastructure constraint (Section 30) rules that out for a
# 4-person academic pilot. Instead: the EC2 app host lives in a PUBLIC
# subnet directly (locked down by security_groups.tf's tight ingress
# rules, not by network topology), and RDS lives in a private subnet
# with no route to the internet at all — it never needs one, since the
# only thing that talks to it is the EC2 host over the internal VPC
# network, and RDS has no OS to patch that requires outbound access.

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.project_name}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${var.project_name}-igw" }
}

resource "aws_subnet" "public" {
  count                   = length(var.public_subnet_cidrs)
  vpc_id                  = aws_vpc.main.id
  cidr_block              = var.public_subnet_cidrs[count.index]
  availability_zone       = var.availability_zones[count.index]
  map_public_ip_on_launch = true

  tags = { Name = "${var.project_name}-public-${count.index}" }
}

resource "aws_subnet" "db" {
  count             = length(var.db_subnet_cidrs)
  vpc_id            = aws_vpc.main.id
  cidr_block        = var.db_subnet_cidrs[count.index]
  availability_zone = var.availability_zones[count.index]

  tags = { Name = "${var.project_name}-db-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.project_name}-public-rt" }
}

resource "aws_route_table_association" "public" {
  count          = length(aws_subnet.public)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# No route table entry/association needed for the db subnets: they use
# the VPC's implicit "local" route only (RDS <-> EC2 within the VPC),
# which every subnet gets automatically — intentionally no association
# with aws_route_table.public, so they stay unreachable from the
# internet even if a future change loosens a security group by mistake.

resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-db-subnet-group"
  subnet_ids = aws_subnet.db[*].id

  tags = { Name = "${var.project_name}-db-subnet-group" }
}
