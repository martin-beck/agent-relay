#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -Eeuo pipefail

readonly network_name=agent-relay-public-ci
readonly network_subnet=172.30.0.0/24
readonly network_gateway=172.30.0.1
readonly firewall_chain=AGENT_RELAY_PUBLIC
readonly blocked_destinations=(
  0.0.0.0/8 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16
  172.16.0.0/12 192.0.0.0/24 192.0.2.0/24 192.168.0.0/16
  198.18.0.0/15 198.51.100.0/24 203.0.113.0/24 224.0.0.0/4 240.0.0.0/4
)

usage() {
  printf 'Usage: %s install|verify\n' "$0" >&2
  exit 2
}

[[ ${EUID:-$(id -u)} -eq 0 ]] || {
  printf 'The public runner network guard must run as root.\n' >&2
  exit 1
}
[[ $# -eq 1 ]] || usage

verify_network() {
  [[ "$(docker network inspect --format '{{.Driver}}' "$network_name")" == bridge ]]
  [[ "$(docker network inspect --format '{{.Internal}}' "$network_name")" == false ]]
  [[ "$(docker network inspect --format '{{.EnableIPv6}}' "$network_name")" == false ]]
  [[ "$(docker network inspect --format '{{(index .IPAM.Config 0).Subnet}}' "$network_name")" == "$network_subnet" ]]
  [[ "$(docker network inspect --format '{{(index .IPAM.Config 0).Gateway}}' "$network_name")" == "$network_gateway" ]]
}

verify_firewall() {
  iptables -C DOCKER-USER -s "$network_subnet" -j "$firewall_chain"
  iptables -C DOCKER-USER -s "$network_subnet" -j DROP
  iptables -C "$firewall_chain" -s "$network_subnet" \
    -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  iptables -C "$firewall_chain" -s "$network_subnet" \
    -m addrtype --dst-type LOCAL -j REJECT
  local destination
  for destination in "${blocked_destinations[@]}"; do
    iptables -C "$firewall_chain" -s "$network_subnet" -d "$destination" -j REJECT
  done
  iptables -C "$firewall_chain" -s "$network_subnet" -j ACCEPT
}

install_guard() {
  if ! docker network inspect "$network_name" > /dev/null 2>&1; then
    docker network create --driver bridge --ipv6=false \
      --subnet "$network_subnet" --gateway "$network_gateway" "$network_name" > /dev/null
  fi
  verify_network

  iptables -N "$firewall_chain" 2> /dev/null || true
  iptables -F "$firewall_chain"
  iptables -A "$firewall_chain" -s "$network_subnet" \
    -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
  iptables -A "$firewall_chain" -s "$network_subnet" \
    -m addrtype --dst-type LOCAL -j REJECT
  local destination
  for destination in "${blocked_destinations[@]}"; do
    iptables -A "$firewall_chain" -s "$network_subnet" -d "$destination" -j REJECT
  done
  iptables -A "$firewall_chain" -s "$network_subnet" -j ACCEPT
  while iptables -C DOCKER-USER -s "$network_subnet" -j "$firewall_chain" 2> /dev/null; do
    iptables -D DOCKER-USER -s "$network_subnet" -j "$firewall_chain"
  done
  while iptables -C DOCKER-USER -s "$network_subnet" -j DROP 2> /dev/null; do
    iptables -D DOCKER-USER -s "$network_subnet" -j DROP
  done
  iptables -I DOCKER-USER 1 -s "$network_subnet" -j DROP
  iptables -I DOCKER-USER 1 -s "$network_subnet" -j "$firewall_chain"
  verify_firewall
}

case "$1" in
  install) install_guard ;;
  verify)
    verify_network
    verify_firewall
    ;;
  *) usage ;;
esac
