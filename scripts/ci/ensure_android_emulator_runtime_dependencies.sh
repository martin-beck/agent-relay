#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -euo pipefail

packages=(
  libasound2t64
  libdbus-1-3
  libfontconfig1
  libgl1
  libpulse0
  libx11-6
  libxcb1
  libxcomposite1
  libxcursor1
  libxi6
  libxrandr2
  libxtst6
  unzip
)

if [[ "${AGENT_RELAY_UNPRIVILEGED_RUNNER:-0}" != "1" ]] && sudo -n true > /dev/null 2>&1; then
  sudo -n apt-get update
  sudo -n env DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends --yes "${packages[@]}"
  exit 0
fi

missing=()
for package in "${packages[@]}"; do
  if ! dpkg-query -W -f='${Status}' "$package" 2> /dev/null | grep -q 'install ok installed'; then
    missing+=("$package")
  fi
done

if ((${#missing[@]} > 0)); then
  printf 'Unprivileged runner is missing required emulator packages: %s\n' \
    "${missing[*]}" >&2
  printf 'Install these packages in the runner image; this workflow will not elevate privileges.\n' >&2
  exit 1
fi

echo 'Android emulator runtime dependencies are preinstalled on this unprivileged runner.'
