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

runner_label=agent-relay-public-ci
runner_image="${PUBLIC_RUNNER_IMAGE:-agent-relay-public-runner:2.337.0}"
runner_memory="${PUBLIC_RUNNER_MEMORY:-12g}"
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

repository_url="$(gh repo view "$PUBLIC_RUNNER_REPOSITORY" --json url --jq .url)"
environment_file=""
active_container=""

cleanup() {
  if [[ -n "$environment_file" ]]; then
    rm -f "$environment_file"
  fi
  if [[ -n "$active_container" ]]; then
    "${docker_command[@]}" stop --time=30 "$active_container" > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

cleanup_offline_registrations() {
  local runner_id
  while IFS= read -r runner_id; do
    [[ "$runner_id" =~ ^[0-9]+$ ]] || continue
    gh api --method DELETE \
      "repos/$PUBLIC_RUNNER_REPOSITORY/actions/runners/$runner_id"
  done < <(
    gh api "repos/$PUBLIC_RUNNER_REPOSITORY/actions/runners" --paginate |
      jq --raw-output --arg label "$runner_label" ".runners[]
        | select(.status == \"offline\")
        | select(any(.labels[]; .name == \$label))
        | .id"
  )
}

while true; do
  cleanup_offline_registrations
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
    --init \
    --name "$runner_name" \
    --hostname public-ci \
    --read-only \
    --cap-drop=ALL \
    --security-opt=no-new-privileges \
    --pids-limit=4096 \
    --memory="$runner_memory" \
    --memory-swap="$runner_memory" \
    --cpus="$runner_cpus" \
    --network=bridge \
    --tmpfs="/runner:rw,exec,nosuid,nodev,size=$runner_tmpfs_size,uid=10001,gid=10001,mode=0700" \
    --tmpfs=/tmp:rw,exec,nosuid,nodev,size=2g,uid=10001,gid=10001,mode=0700 \
    --env=HOME=/runner/home \
    --env=RUNNER_TOOL_CACHE=/runner/toolcache \
    --env-file "$environment_file" \
    "$runner_image" > /dev/null
  rm -f "$environment_file"
  environment_file=""

  "${docker_command[@]}" logs --follow "$active_container" &
  logs_pid=$!
  runner_exit="$("${docker_command[@]}" wait "$active_container")"
  wait "$logs_pid" || true
  active_container=""
  [[ "$runner_exit" =~ ^[0-9]+$ ]] || runner_exit=1
  if ((runner_exit != 0)); then
    printf "Disposable public runner exited with status %d; retrying.\n" "$runner_exit" >&2
  fi
  sleep "$restart_delay"
done
