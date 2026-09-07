#!/usr/bin/env bats

setup() {
  REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd -P)"
  INSTALLER="$REPO_ROOT/scripts/ci/install_shell_quality_tools.sh"
  RUNNER="$REPO_ROOT/scripts/ci/run_shell_quality.sh"
  EMULATOR_RUNNER="$REPO_ROOT/scripts/ci/run_android_emulator_tests.sh"
  SOURCE_TOOL_ROOT="${SHELL_QUALITY_TOOL_ROOT:-$REPO_ROOT/build/tools/shell-quality}"
  TEST_ROOT="$(mktemp -d "${BATS_TEST_TMPDIR}/tooling.XXXXXX")"
  TOOL_ROOT="$TEST_ROOT/tools"
  OUTSIDE_ROOT=""
  case "$(uname -m)" in
    x86_64) SHELLCHECK_PLATFORM=linux.x86_64 ;;
    aarch64 | arm64) SHELLCHECK_PLATFORM=linux.aarch64 ;;
    *) SHELLCHECK_PLATFORM=unsupported ;;
  esac
}

@test "emulator runner validates an explicit avdmanager before touching adb" {
  mkdir -p "$TEST_ROOT/sdk/platform-tools" "$TEST_ROOT/sdk/emulator"
  printf '#!/usr/bin/env bash\nexit 99\n' > "$TEST_ROOT/sdk/platform-tools/adb"
  printf '#!/usr/bin/env bash\nexit 99\n' > "$TEST_ROOT/sdk/emulator/emulator"
  chmod +x "$TEST_ROOT/sdk/platform-tools/adb" "$TEST_ROOT/sdk/emulator/emulator"

  run env \
    ANDROID_HOME="$TEST_ROOT/sdk" \
    ANDROID_AVD_HOME="$TEST_ROOT/avd" \
    RUNNER_TEMP="$TEST_ROOT/runner-temp" \
    AVDMANAGER_BIN="$TEST_ROOT/missing-avdmanager" \
    "$EMULATOR_RUNNER" true

  [ "$status" -eq 2 ]
  [[ "$output" == *"avdmanager is not executable at $TEST_ROOT/missing-avdmanager"* ]]
  [ -d "$TEST_ROOT/runner-temp" ]
}

teardown() {
  if [[ "$OUTSIDE_ROOT" == /var/tmp/shell-quality-outside.* ]]; then
    rm -rf -- "$OUTSIDE_ROOT"
  fi
}

seed_archives() {
  mkdir -p "$TOOL_ROOT/cache"
  cp -a "$SOURCE_TOOL_ROOT/cache/." "$TOOL_ROOT/cache/"
}

install_offline() {
  env SHELL_QUALITY_TOOL_ROOT="$TOOL_ROOT" SHELL_QUALITY_OFFLINE=1 "$INSTALLER"
}

@test "offline install fails closed when an archive is missing" {
  run install_offline
  [ "$status" -eq 1 ]
  [[ "$output" == *"offline archive is missing"* ]]
  [ ! -e "$TOOL_ROOT/bin/shellcheck" ]
}

@test "offline install rejects a corrupt cached archive" {
  seed_archives
  printf 'corruption\n' >> "$TOOL_ROOT/cache/shellcheck-v0.11.0.$SHELLCHECK_PLATFORM.tar.xz"
  run install_offline
  [ "$status" -ne 0 ]
  [ ! -e "$TOOL_ROOT/bin/shellcheck" ]
}

@test "failed downloads remove partial archives" {
  mkdir -p "$TEST_ROOT/fake-bin"
  cat > "$TEST_ROOT/fake-bin/curl" <<'EOF'
#!/usr/bin/env bash
for ((index = 1; index <= $#; index++)); do
  if [[ "${!index}" == --output ]]; then
    next=$((index + 1))
    printf 'partial\n' > "${!next}"
    break
  fi
done
exit 22
EOF
  chmod +x "$TEST_ROOT/fake-bin/curl"
  run env PATH="$TEST_ROOT/fake-bin:$PATH" SHELL_QUALITY_TOOL_ROOT="$TOOL_ROOT" "$INSTALLER"
  [ "$status" -ne 0 ]
  run find "$TOOL_ROOT" -name '*.tmp.*' -print
  [ "$status" -eq 0 ]
  [ -z "$output" ]
}

@test "unsupported architectures fail before downloading" {
  mkdir -p "$TEST_ROOT/fake-bin"
  cat > "$TEST_ROOT/fake-bin/uname" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  -s) printf 'Linux\n' ;;
  -m) printf 'ppc64le\n' ;;
esac
EOF
  chmod +x "$TEST_ROOT/fake-bin/uname"
  run env PATH="$TEST_ROOT/fake-bin:$PATH" SHELL_QUALITY_TOOL_ROOT="$TOOL_ROOT" "$INSTALLER"
  [ "$status" -eq 1 ]
  [[ "$output" == *"unsupported host: Linux ppc64le"* ]]
}

@test "symlinked tool roots cannot create outside the allowed cache" {
  OUTSIDE_ROOT="$(mktemp -d /var/tmp/shell-quality-outside.XXXXXX)"
  ln -s "$OUTSIDE_ROOT" "$TEST_ROOT/tool-link"
  run env SHELL_QUALITY_TOOL_ROOT="$TEST_ROOT/tool-link/child" "$INSTALLER"
  [ "$status" -eq 1 ]
  [ ! -e "$OUTSIDE_ROOT/child" ]
}

@test "tampered ShellCheck payload cannot bypass analysis" {
  seed_archives
  run install_offline
  [ "$status" -eq 0 ]
  cat > "$TOOL_ROOT/shellcheck-v0.11.0/shellcheck" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == --version ]]; then
  printf 'ShellCheck - shell script analysis tool\nversion: 0.11.0\n'
fi
exit 0
EOF
  chmod +x "$TOOL_ROOT/shellcheck-v0.11.0/shellcheck"
  printf '#!/usr/bin/env bash\nif then\n' > "$TEST_ROOT/invalid.sh"
  run install_offline
  [ "$status" -eq 0 ]
  run "$TOOL_ROOT/bin/shellcheck" "$TEST_ROOT/invalid.sh"
  [ "$status" -ne 0 ]
  [[ "$output" == *"syntax error"* || "$output" == *"Couldn't parse"* ]]
}

@test "tampered Bats payload cannot bypass a failing suite" {
  seed_archives
  run install_offline
  [ "$status" -eq 0 ]
  cat > "$TOOL_ROOT/bats-core-eb7f42f8d608ac693d7a4b67474f6714ea68cfc5/bin/bats" <<'EOF'
#!/usr/bin/env bash
if [[ "${1:-}" == --version ]]; then printf 'Bats 1.14.0\n'; fi
exit 0
EOF
  chmod +x "$TOOL_ROOT/bats-core-eb7f42f8d608ac693d7a4b67474f6714ea68cfc5/bin/bats"
  printf '@test "must fail" { false; }\n' > "$TEST_ROOT/failing.bats"
  run install_offline
  [ "$status" -eq 0 ]
  run "$TOOL_ROOT/bin/bats" "$TEST_ROOT/failing.bats"
  [ "$status" -eq 1 ]
  [[ "$output" == *"not ok 1 must fail"* ]]
}

@test "runner discovers first-party scripts and excludes generated gradlew" {
  run "$RUNNER" list
  [ "$status" -eq 0 ]
  [[ "$output" == *"scripts/ci/install_android_native_toolchain.sh"* ]]
  [[ "$output" == *"scripts/ci/install_shell_quality_tools.sh"* ]]
  [[ "$output" == *"speech/sherpa/native/build-android-runtime.sh"* ]]
  [[ "$output" != *"/gradlew"* ]]
}
