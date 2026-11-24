#!/usr/bin/env bash
set -euo pipefail

# Linux KVM kernel tuning for single-host loopback load testing (requires root)
sysctl -w net.core.somaxconn=65535
sysctl -w net.core.netdev_max_backlog=65535
sysctl -w net.ipv4.tcp_max_syn_backlog=65535
sysctl -w net.ipv4.ip_local_port_range="1024 65535"
sysctl -w net.ipv4.tcp_tw_reuse=1
sysctl -w net.ipv4.tcp_fin_timeout=15
sysctl -w fs.file-max=1048576

echo "Applied benchmark sysctl tuning."
