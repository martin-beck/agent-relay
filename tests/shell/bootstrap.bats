#!/usr/bin/env bats
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT


setup() {
  REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd -P)"
  BOOTSTRAP="$REPO_ROOT/scripts/daemon/bootstrap.sh"
  TEST_ROOT="$(mktemp -d "${BATS_TEST_TMPDIR}/bootstrap.XXXXXX")"
  export AGENT_RELAY_HOME="$TEST_ROOT/home"
  mkdir -p "$TEST_ROOT/bundle-root"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$TEST_ROOT/bundle-root/agent-relay-daemon"
  chmod +x "$TEST_ROOT/bundle-root/agent-relay-daemon"
  tar -czf "$TEST_ROOT/daemon.tar.gz" -C "$TEST_ROOT/bundle-root" agent-relay-daemon
  sha256=$(sha256sum "$TEST_ROOT/daemon.tar.gz" | cut -d' ' -f1)
  printf 'version=1.2.3\narch=%s\narchive=daemon.tar.gz\nsha256=%s\nsignature=%s\n' \
    "$(uname -m)" "$sha256" "$TEST_ROOT/manifest.sig" > "$TEST_ROOT/manifest"
  : > "$TEST_ROOT/manifest.sig"
}

@test "preview exposes bounded consent details without installing" {
  run "$BOOTSTRAP" preview --manifest "$TEST_ROOT/manifest"
  [ "$status" -eq 0 ]
  [[ "$output" == *"explicit"* || "$output" == *"Target:"* ]]
  [ ! -e "$AGENT_RELAY_HOME" ]
}

@test "preview rejects a missing manifest before reading bundle state" {
  run "$BOOTSTRAP" preview --manifest "$TEST_ROOT/missing-manifest"
  [ "$status" -ne 0 ]
  [[ "$output" == *"manifest"* ]]
  [ ! -e "$AGENT_RELAY_HOME" ]
}

@test "install requires explicit consent and pinned signature key" {
  run "$BOOTSTRAP" install --manifest "$TEST_ROOT/manifest" --bundle "$TEST_ROOT/daemon.tar.gz"
  [ "$status" -ne 0 ]
  [[ "$output" == *"--yes consent"* ]]
}

@test "hostile traversal archive is rejected before installation" {
  mkdir -p "$TEST_ROOT/evil"
  printf 'bad\n' > "$TEST_ROOT/evil/file"
  tar -czf "$TEST_ROOT/evil.tar.gz" -C "$TEST_ROOT/evil" file
  sed -i "s#daemon.tar.gz#evil.tar.gz#" "$TEST_ROOT/manifest"
  evil_sha=$(sha256sum "$TEST_ROOT/evil.tar.gz" | cut -d' ' -f1)
  sed -i "s#sha256=.*#sha256=$evil_sha#" "$TEST_ROOT/manifest"
  run env AGENT_RELAY_TRUSTED_KEY="$TEST_ROOT/no-key" "$BOOTSTRAP" install --manifest "$TEST_ROOT/manifest" --bundle "$TEST_ROOT/evil.tar.gz" --yes
  [ "$status" -ne 0 ]
  [ ! -e "$AGENT_RELAY_HOME/current" ]
}
