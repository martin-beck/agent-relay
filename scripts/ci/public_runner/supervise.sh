#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -Eeuo pipefail
IFS="$(printf "\n_\t")"
IFS="${IFS/_/}"

: "${PUBLIC_RUNNER_REPOSITORY:?PUBLIC_RUNNER_REPOSITORY must be owner/repository}"
[[ "$PUBLIC_RUNNER_REPOSITORY" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || {
  printf "PUBLIC_RUNNER_REPOSITORY must be owner/repository.\n" >&2
  exit 2
}
: "${XDG_RUNTIME_DIR:?XDG_RUNTIME_DIR is required}"
exec 9> "$XDG_RUNTIME_DIR/agent-relay-public-runner.lock"
flock --nonblock 9 || {
  printf "Another disposable public runner supervisor is active.\n" >&2
  exit 1
}

runner_label=agent-relay-public-ci
runner_network=agent-relay-public-ci
runner_image="${PUBLIC_RUNNER_IMAGE:-agent-relay-public-runner:2.337.0}"
: "${PUBLIC_RUNNER_IMAGE_ID:?PUBLIC_RUNNER_IMAGE_ID must be an exact sha256 image ID}"
[[ "$PUBLIC_RUNNER_IMAGE_ID" =~ ^sha256:[0-9a-f]{64}$ ]] || {
  printf "PUBLIC_RUNNER_IMAGE_ID must be an exact sha256 image ID.\n" >&2
  exit 2
}
runner_memory="${PUBLIC_RUNNER_MEMORY:-24g}"
runner_cpus="${PUBLIC_RUNNER_CPUS:-8}"
runner_tmpfs_size="${PUBLIC_RUNNER_TMPFS_SIZE:-20g}"
restart_delay="${PUBLIC_RUNNER_RESTART_DELAY_SECONDS:-5}"
case "${PUBLIC_RUNNER_DOCKER_COMMAND:-docker}" in
  docker) docker_command=(docker) ;;
  "sudo -n docker") docker_command=(sudo -n docker) ;;
  *)
    printf "PUBLIC_RUNNER_DOCKER_COMMAND must be docker or sudo -n docker.\n" >&2
    exit 2
    ;;
esac

resolved_image_id="$(
  "${docker_command[@]}" image inspect --format '{{.Id}}' "$runner_image"
)"
if [[ "$resolved_image_id" != "$PUBLIC_RUNNER_IMAGE_ID" ]]; then
  printf "Disposable runner image does not match PUBLIC_RUNNER_IMAGE_ID.\n" >&2
  exit 1
fi
runner_image="$resolved_image_id"

network_guard="${PUBLIC_RUNNER_NETWORK_GUARD:-/usr/local/libexec/agent-relay-public-network-guard}"
[[ "$network_guard" == /usr/local/libexec/agent-relay-public-network-guard ]] || {
  printf "PUBLIC_RUNNER_NETWORK_GUARD must name the root-owned installed guard.\n" >&2
  exit 2
}
sudo -n "$network_guard" verify

repository_url="$(gh repo view "$PUBLIC_RUNNER_REPOSITORY" --json url --jq .url)"
environment_file=""
active_container=""
guard_monitor_pid=""
guard_failure_file=""

cleanup() {
  if [[ -n "$environment_file" ]]; then
    rm -f "$environment_file"
  fi
  if [[ -n "$active_container" ]]; then
    "${docker_command[@]}" stop --time=30 "$active_container" > /dev/null 2>&1 || true
  fi
  if [[ -n "$guard_monitor_pid" ]]; then
    kill "$guard_monitor_pid" > /dev/null 2>&1 || true
  fi
  if [[ -n "$guard_failure_file" ]]; then
    rm -f "$guard_failure_file"
  fi
}
trap cleanup EXIT

cleanup_stale_registrations() {
  local runner_id response_file delete_status
  while IFS= read -r runner_id; do
    [[ "$runner_id" =~ ^[0-9]+$ ]] || continue
    response_file="$(mktemp)"
    if gh api --method DELETE \
      "repos/$PUBLIC_RUNNER_REPOSITORY/actions/runners/$runner_id" \
      > "$response_file" 2>&1; then
      rm -f "$response_file"
      continue
    fi
    delete_status=$?
    if jq -e '
      (.status == 422 or .status == "422") and
      .message == "Cannot delete a runner that is currently running a job"
    ' < "$response_file" > /dev/null 2>&1 ||
      grep -Fqx \
        "gh: Cannot delete a runner that is currently running a job (HTTP 422)" \
        "$response_file"; then
      rm -f "$response_file"
      return 75
    fi
    rm -f "$response_file"
    printf "Failed to remove a stale public runner registration.\n" >&2
    return "$delete_status"
  done < <(
    gh api "repos/$PUBLIC_RUNNER_REPOSITORY/actions/runners" --paginate |
      jq --raw-output --arg label "$runner_label" ".runners[]
        | select(any(.labels[]; .name == \$label))
        | .id"
  )
}

monitor_network_guard() {
  local container_name=$1
  while sleep 1; do
    if ! sudo -n "$network_guard" verify > /dev/null 2>&1; then
      : > "$guard_failure_file"
      "${docker_command[@]}" stop --time=1 "$container_name" > /dev/null 2>&1 || true
      return
    fi
  done
}

while true; do
  sudo -n "$network_guard" verify
  if cleanup_stale_registrations; then
    :
  else
    cleanup_status=$?
    if ((cleanup_status == 75)); then
      printf "A stale public runner is still busy; waiting before retrying cleanup.\n" >&2
      sleep "$restart_delay"
      continue
    fi
    exit "$cleanup_status"
  fi
  runner_name="public-ci-$(openssl rand -hex 8)"
  registration_token="$(
    gh api --method POST \
      "repos/$PUBLIC_RUNNER_REPOSITORY/actions/runners/registration-token" \
      --jq .token
  )"
  environment_file="$(mktemp)"
  chmod 0600 "$environment_file"
  {
    printf "RUNNER_REPOSITORY_URL=%s\n" "$repository_url"
    printf "RUNNER_REGISTRATION_TOKEN=%s\n" "$registration_token"
    printf "RUNNER_NAME=%s\n" "$runner_name"
  } > "$environment_file"
  unset registration_token

  active_container="$runner_name"
  "${docker_command[@]}" run \
    --detach \
    --rm \
    --pull=never \
    --name "$runner_name" \
    --hostname public-ci \
    --read-only \
    --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=4096 \
    --memory="$runner_memory" \
    --memory-swap="$runner_memory" \
    --cpus="$runner_cpus" \
    --network="$runner_network" \
    --sysctl=net.ipv6.conf.all.disable_ipv6=1 \
    --tmpfs="/runner:rw,exec,nosuid,nodev,size=$runner_tmpfs_size,uid=10001,gid=10001,mode=0700" \
    --tmpfs=/tmp:rw,exec,nosuid,nodev,size=2g,uid=10001,gid=10001,mode=0700 \
    --env=HOME=/runner/home \
    --env=RUNNER_TOOL_CACHE=/runner/toolcache \
    --env-file "$environment_file" \
    "$runner_image" > /dev/null
  rm -f "$environment_file"
  environment_file=""

  guard_failure_file="$(mktemp)"
  rm -f "$guard_failure_file"
  monitor_network_guard "$active_container" &
  guard_monitor_pid=$!
  "${docker_command[@]}" logs --follow "$active_container" &
  logs_pid=$!
  runner_exit="$("${docker_command[@]}" wait "$active_container")"
  kill "$guard_monitor_pid" > /dev/null 2>&1 || true
  wait "$guard_monitor_pid" 2> /dev/null || true
  guard_monitor_pid=""
  wait "$logs_pid" || true
  active_container=""
  if [[ -e "$guard_failure_file" ]]; then
    printf "Public runner network guard failed; supervisor is stopping.\n" >&2
    exit 1
  fi
  rm -f "$guard_failure_file"
  guard_failure_file=""
  [[ "$runner_exit" =~ ^[0-9]+$ ]] || runner_exit=1
  if ((runner_exit != 0)); then
    printf "Disposable public runner exited with status %d; retrying.\n" "$runner_exit" >&2
  fi
  sleep "$restart_delay"
done
