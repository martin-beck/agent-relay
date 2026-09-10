#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -Eeuo pipefail
IFS="$(printf "\n_\t")"
IFS="${IFS/_/}"

if [[ "${1:-}" == run ]]; then
  exec ./run.sh
fi

: "${RUNNER_REPOSITORY_URL:?RUNNER_REPOSITORY_URL is required}"
: "${RUNNER_REGISTRATION_TOKEN:?RUNNER_REGISTRATION_TOKEN is required}"
: "${RUNNER_NAME:?RUNNER_NAME is required}"
cp -a /opt/runner-dist/. /runner/
mkdir --mode=0700 /runner/home /runner/toolcache
./config.sh \
  --unattended \
  --ephemeral \
  --disableupdate \
  --url "$RUNNER_REPOSITORY_URL" \
  --token "$RUNNER_REGISTRATION_TOKEN" \
  --name "$RUNNER_NAME" \
  --labels agent-relay-public-ci \
  --no-default-labels \
  --work _work
unset RUNNER_REGISTRATION_TOKEN

exec env -i \
  HOME=/runner/home \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
  RUNNER_TOOL_CACHE=/runner/toolcache \
  /usr/local/bin/public-runner-entrypoint run
