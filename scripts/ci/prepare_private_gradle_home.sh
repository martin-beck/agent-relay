#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -euo pipefail

if (($# != 2)); then
  echo "Usage: $0 SHARED_GRADLE_HOME PRIVATE_GRADLE_HOME" >&2
  exit 2
fi

readonly shared_gradle_home="$1"
readonly private_gradle_home="$2"
: "${RUNNER_TEMP:=}"

if [[ -z "$RUNNER_TEMP" || "$private_gradle_home" != "$RUNNER_TEMP"/* ]]; then
  echo "Private Gradle home must be a child of RUNNER_TEMP" >&2
  exit 2
fi
if [[ "$private_gradle_home" == "$shared_gradle_home" ]]; then
  echo "Private Gradle home must differ from the shared Gradle home" >&2
  exit 2
fi
if [[ -e "$private_gradle_home/daemon" ]]; then
  echo "Private Gradle daemon registry already exists: $private_gradle_home/daemon" >&2
  exit 1
fi

mkdir -p "$private_gradle_home"
for reusable_directory in caches wrapper; do
  shared_path="$shared_gradle_home/$reusable_directory"
  private_path="$private_gradle_home/$reusable_directory"
  if [[ ! -d "$shared_path" ]]; then continue; fi
  if [[ -L "$private_path" && "$(readlink -f "$private_path")" == "$(readlink -f "$shared_path")" ]]; then
    continue
  fi
  if [[ -e "$private_path" || -L "$private_path" ]]; then
    echo "Refusing to replace existing Gradle path: $private_path" >&2
    exit 1
  fi
  ln -s "$shared_path" "$private_path"
done

printf '%s\n' "$private_gradle_home"
