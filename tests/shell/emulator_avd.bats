#!/usr/bin/env bats
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

setup() {
  REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd -P)"
  EMULATOR_RUNNER="$REPO_ROOT/scripts/ci/run_android_emulator_tests.sh"
  TEST_ROOT="$(mktemp -d "${BATS_TEST_TMPDIR}/emulator-avd.XXXXXX")"
  SDK_ROOT="$TEST_ROOT/sdk"
  RUNNER_TEMP="$TEST_ROOT/runner-temp"
  ANDROID_AVD_HOME="$TEST_ROOT/avd"
  HOME="$TEST_ROOT/home"
  mkdir -p "$SDK_ROOT/platform-tools" "$SDK_ROOT/emulator" "$RUNNER_TEMP" "$HOME"

  cat > "$SDK_ROOT/platform-tools/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
if [[ "${1:-}" == keygen ]]; then
  printf 'private-key\n' > "$2"
  printf 'public-key\n' > "$2.pub"
  exit 0
fi
case " ${*:-} " in
  *" shell getprop sys.boot_completed "*) printf '1\n' ;;
  *" shell "*) exit 0 ;;
  *" devices "*) printf 'List of devices attached\nemulator-5582\tdevice\n' ;;
  *" get-state "*) printf 'device\n' ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$SDK_ROOT/platform-tools/adb"

  cat > "$SDK_ROOT/emulator/emulator" <<'EOF'
#!/usr/bin/env bash
trap 'exit 0' TERM INT
while :; do sleep 1; done
EOF
  chmod +x "$SDK_ROOT/emulator/emulator"

  cat > "$TEST_ROOT/avdmanager" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
avd_name=''
avd_path=''
while (($#)); do
  case "$1" in
    --name) avd_name="$2"; shift 2 ;;
    --path) avd_path="$2"; shift 2 ;;
    *) shift ;;
  esac
done
mkdir -p "$avd_path"
printf 'disk.dataPartition.size=8G\n' > "$avd_path/config.ini"
printf '%s\n' "$avd_name" > "$avd_path/created-name"
EOF
  chmod +x "$TEST_ROOT/avdmanager"
}

run_emulator_runner() {
  env ANDROID_HOME="$SDK_ROOT" \
    ANDROID_AVD_HOME="$ANDROID_AVD_HOME" \
    RUNNER_TEMP="$RUNNER_TEMP" \
    HOME="$HOME" \
    AVDMANAGER_BIN="$TEST_ROOT/avdmanager" \
    EMULATOR_AVD_NAME='test-avd' \
    EMULATOR_PORT=5582 \
    GRADLE_USER_HOME="$TEST_ROOT/shared-gradle" \
    "$EMULATOR_RUNNER" -- bash -c 'test -f "$ANDROID_AVD_HOME/test-avd.ini"'
}

@test "registers an AVD descriptor when avdmanager creates only the directory" {
  run run_emulator_runner

  [ "$status" -eq 0 ]
  [ -f "$ANDROID_AVD_HOME/test-avd.ini" ]
  grep -Fx "path=$ANDROID_AVD_HOME/test-avd.avd" "$ANDROID_AVD_HOME/test-avd.ini"
  grep -Fx 'target=android-36' "$ANDROID_AVD_HOME/test-avd.ini"
  grep -Fx 'disk.dataPartition.size=6G' "$ANDROID_AVD_HOME/test-avd.avd/config.ini"
}

@test "preserves a correct existing AVD descriptor and still runs the command" {
  mkdir -p "$ANDROID_AVD_HOME"
  cat > "$ANDROID_AVD_HOME/test-avd.ini" <<EOF
avd.ini.encoding=UTF-8
path=$ANDROID_AVD_HOME/test-avd.avd
target=android-36
existing=preserve-me
EOF

  run run_emulator_runner

  [ "$status" -eq 0 ]
  grep -Fx 'existing=preserve-me' "$ANDROID_AVD_HOME/test-avd.ini"
  [ "$(cat "$ANDROID_AVD_HOME/test-avd.avd/created-name")" = test-avd ]
}
