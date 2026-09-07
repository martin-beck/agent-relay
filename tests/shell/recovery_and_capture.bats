#!/usr/bin/env bats

setup() {
  REPO_ROOT="$(cd "$BATS_TEST_DIRNAME/../.." && pwd -P)"
  RECOVERY="$REPO_ROOT/scripts/ci/verify_background_process_recovery.sh"
  CAPTURE="$REPO_ROOT/scripts/docs/capture_usage_workflows.sh"
  TEST_ROOT="$(mktemp -d "${BATS_TEST_TMPDIR}/recovery.XXXXXX")"
  APK="$TEST_ROOT/app.apk"
  touch "$APK"
}

make_adb() {
  local boot=${1:-1}
  local qemu=${2:-1}
  local devices=${3:-1}
  local install_status=${4:-0}
  mkdir -p "$TEST_ROOT/sdk/platform-tools"
  cat > "$TEST_ROOT/sdk/platform-tools/adb" <<EOF
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$TEST_ROOT/adb.calls"
case "\$*" in
  devices)
    printf 'List of devices attached\\n'
    for ((i = 0; i < $devices; i++)); do printf 'emulator-%s\\tdevice\\n' "\$((5554 + i))"; done
    ;;
  *"getprop ro.kernel.qemu"*) printf '%s\\n' '$qemu' ;;
  *"getprop sys.boot_completed"*) printf '%s\\n' '$boot' ;;
  "install -r "*) exit $install_status ;;
  *) exit 0 ;;
esac
EOF
  chmod +x "$TEST_ROOT/sdk/platform-tools/adb"
}

@test "recovery rejects an incomplete Android boot and still cleans up" {
  make_adb 0
  run env ADB_BIN="$TEST_ROOT/sdk/platform-tools/adb" "$RECOVERY" "$APK"
  [ "$status" -eq 1 ]
  [[ "$output" == *"did not report a completed boot"* ]]
}

@test "recovery rejects a missing APK before install" {
  make_adb
  run env ADB_BIN="$TEST_ROOT/sdk/platform-tools/adb" "$RECOVERY" "$TEST_ROOT/missing.apk"
  [ "$status" -eq 1 ]
  [[ "$output" == *"Debug APK is missing"* ]]
}

@test "recovery reports an adb install failure" {
  make_adb 1 1 1 23
  run env ADB_BIN="$TEST_ROOT/sdk/platform-tools/adb" "$RECOVERY" "$APK"
  [ "$status" -eq 1 ]
  [[ "$output" == *"Application install failed"* ]]
}

@test "recovery force-stops the app after an install failure" {
  make_adb 1 1 1 23
  run env ADB_BIN="$TEST_ROOT/sdk/platform-tools/adb" "$RECOVERY" "$APK"
  [ "$status" -eq 1 ]
  run grep -F "shell -n am force-stop com.example.agentrelay" "$TEST_ROOT/adb.calls"
  [ "$status" -eq 0 ]
}

@test "capture rejects hostile command-line input before device access" {
  run "$CAPTURE" --delete-baselines
  [ "$status" -eq 2 ]
  [[ "$output" == *"Usage:"* ]]
}

@test "capture requires an explicit Android SDK" {
  run env -u ANDROID_HOME -u ANDROID_SDK_ROOT "$CAPTURE"
  [ "$status" -eq 2 ]
  [[ "$output" == *"Set ANDROID_SDK_ROOT or ANDROID_HOME"* ]]
}

@test "capture rejects an SDK without an executable adb" {
  mkdir -p "$TEST_ROOT/sdk/platform-tools"
  run env ANDROID_SDK_ROOT="$TEST_ROOT/sdk" ANDROID_HOME= "$CAPTURE"
  [ "$status" -eq 2 ]
  [[ "$output" == *"adb is not executable"* ]]
}

@test "capture rejects multiple attached devices" {
  make_adb 1 1 2
  run env ANDROID_SDK_ROOT="$TEST_ROOT/sdk" ANDROID_HOME= "$CAPTURE"
  [ "$status" -eq 2 ]
  [[ "$output" == *"found 2"* ]]
}

@test "capture rejects physical-device evidence" {
  make_adb 1 0 1
  run env ANDROID_SDK_ROOT="$TEST_ROOT/sdk" ANDROID_HOME= "$CAPTURE"
  [ "$status" -eq 2 ]
  [[ "$output" == *"not a physical device"* ]]
}

@test "capture rejects an emulator that has not finished booting" {
  make_adb 0 1 1
  run env ANDROID_SDK_ROOT="$TEST_ROOT/sdk" ANDROID_HOME= "$CAPTURE"
  [ "$status" -eq 2 ]
  [[ "$output" == *"has not completed booting"* ]]
}
