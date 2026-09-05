#!/usr/bin/env bash
set -euo pipefail
: "${ANDROID_HOME:=}"
: "${RUNNER_TEMP:=}"
: "${EMULATOR_AVD_NAME:=agent-relay-ci}"
: "${EMULATOR_PORT:=5582}"
: "${EMULATOR_API_LEVEL:=36}"
: "${EMULATOR_TARGET:=default}"
: "${EMULATOR_ARCH:=x86_64}"
: "${EMULATOR_PROFILE:=pixel_7_pro}"
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-${HOME:-$RUNNER_TEMP}/.android/avd}"
echo "Android SDK: ${ANDROID_HOME:-<unset>}"
echo "AVD home: $ANDROID_AVD_HOME"
echo "Runner temp: ${RUNNER_TEMP:-<unset>}"
echo "Emulator port: $EMULATOR_PORT"
test -n "$ANDROID_HOME" -a -n "$ANDROID_AVD_HOME" -a -n "$RUNNER_TEMP"
XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-$RUNNER_TEMP/android-runtime}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 0700 "$XDG_RUNTIME_DIR"
export XDG_RUNTIME_DIR
export ANDROID_EMULATOR_DISCOVERY_DIR="${ANDROID_EMULATOR_DISCOVERY_DIR:-$XDG_RUNTIME_DIR}"
export ANDROID_EMULATOR_LAUNCHER_DIR="${ANDROID_EMULATOR_LAUNCHER_DIR:-$ANDROID_HOME/emulator}"
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-$EMULATOR_PORT}"
if [[ "${1:-}" == -- ]]; then shift; fi
adb_bin="$ANDROID_HOME/platform-tools/adb"
emulator_bin="$ANDROID_HOME/emulator/emulator"
avdmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
test -x "$adb_bin" -a -x "$emulator_bin" -a -x "$avdmanager_bin"
android_config_dir="${HOME:-$RUNNER_TEMP}/.android"
adb_private_key="$android_config_dir/adbkey"
adb_public_key="$adb_private_key.pub"
ensure_adb_keypair() {
  # QEMU's ADB-auth bridge assumes both files exist. A stale cache containing
  # only adbkey can crash the native bridge before the emulator exposes ADB.
  if [[ -s "$adb_private_key" && -s "$adb_public_key" ]]; then return 0; fi
  mkdir -p "$android_config_dir"
  chmod 0700 "$android_config_dir"
  key_tmp_dir="$(mktemp -d "$RUNNER_TEMP/agent-relay-adbkey.XXXXXX")"
  umask 077
  if ! "$adb_bin" keygen "$key_tmp_dir/adbkey" > /dev/null 2>&1; then
    rm -rf "$key_tmp_dir"
    echo "Unable to generate the Android ADB keypair" >&2
    return 1
  fi
  test -s "$key_tmp_dir/adbkey" -a -s "$key_tmp_dir/adbkey.pub"
  mv -f "$key_tmp_dir/adbkey" "$adb_private_key"
  mv -f "$key_tmp_dir/adbkey.pub" "$adb_public_key"
  rmdir "$key_tmp_dir"
  chmod 0600 "$adb_private_key"
  chmod 0644 "$adb_public_key"
  echo "Initialized complete Android ADB keypair for emulator startup"
}
ensure_adb_keypair
"$adb_bin" kill-server > /dev/null 2>&1 || true
export ADB_VENDOR_KEYS="$adb_private_key"
"$adb_bin" start-server > /dev/null 2>&1
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$avdmanager_bin" create avd --force --name "$EMULATOR_AVD_NAME" --path "$ANDROID_AVD_HOME/$EMULATOR_AVD_NAME.avd" --package "system-images;android-$EMULATOR_API_LEVEL;$EMULATOR_TARGET;$EMULATOR_ARCH" --device "$EMULATOR_PROFILE"
avd_dir="$ANDROID_AVD_HOME/$EMULATOR_AVD_NAME.avd"
if [[ ! -d "$avd_dir" ]]; then
  echo "avdmanager did not create expected AVD directory: $avd_dir" >&2
  find "$ANDROID_AVD_HOME" -maxdepth 2 -type f -name config.ini -print >&2 || true
  exit 1
fi
if [[ -f "$avd_dir/config.ini" ]]; then
  echo 'hw.cpu.ncore=2' >> "$avd_dir/config.ini"
else
  echo "avdmanager created no config.ini; writing minimal x86_64 configuration" >&2
  cat > "$avd_dir/config.ini" << EOF
AvdId=$EMULATOR_AVD_NAME
abi.type=x86_64
avd.ini.displayname=Agent Relay CI
disk.dataPartition.size=6G
fastboot.forceColdBoot=yes
hw.accelerometer=yes
hw.audioInput=yes
hw.battery=yes
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.device.name=$EMULATOR_PROFILE
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.ramSize=2048
image.sysdir.1=system-images/android-$EMULATOR_API_LEVEL/$EMULATOR_TARGET/$EMULATOR_ARCH/
target=android-$EMULATOR_API_LEVEL
tag.display=default
EOF
fi
log_file="$RUNNER_TEMP/agent-relay-emulator-$EMULATOR_PORT.log"
pid_file="$RUNNER_TEMP/agent-relay-emulator-$EMULATOR_PORT.pid"
cleanup() {
  mkdir -p build/emulator
  [[ ! -f "$log_file" ]] || cp "$log_file" "build/emulator/emulator-$EMULATOR_PORT.log"
  [[ ! -f "$pid_file" ]] || cp "$pid_file" "build/emulator/emulator-$EMULATOR_PORT.pid"
  "$adb_bin" -s "emulator-$EMULATOR_PORT" emu kill > /dev/null 2>&1 || true
  if [[ -s "$pid_file" ]]; then
    pid="$(< "$pid_file")"
    kill -- "-$pid" > /dev/null 2>&1 || kill "$pid" > /dev/null 2>&1 || true
    for _ in $(seq 1 20); do
      kill -0 "$pid" > /dev/null 2>&1 || break
      sleep 1
    done
    kill -KILL -- "-$pid" > /dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
env "$emulator_bin" -port "$EMULATOR_PORT" -avd "$EMULATOR_AVD_NAME" -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim > "$log_file" 2>&1 &
emulator_pid=$!
echo "$emulator_pid" > "$pid_file"
echo "Started emulator pid=$emulator_pid port=$EMULATOR_PORT log=$log_file"
for _ in $(seq 1 300); do
  if ! kill -0 "$emulator_pid" 2> /dev/null; then
    cat "$log_file" >&2
    exit 1
  fi
  if [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" get-state 2> /dev/null || true)" == device ]] && [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" shell getprop sys.boot_completed 2> /dev/null || true)" == 1 ]]; then break; fi
  sleep 2
done
test "$("$adb_bin" -s "emulator-$EMULATOR_PORT" get-state 2> /dev/null)" = device || {
  cat "$log_file" >&2
  exit 1
}
test "$("$adb_bin" -s "emulator-$EMULATOR_PORT" shell getprop sys.boot_completed)" = 1 || {
  cat "$log_file" >&2
  exit 1
}
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell wm size 1080x2400
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell wm density 420
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put system font_scale 1.0
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell cmd alarm set-timezone UTC
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell cmd uimode night no
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global window_animation_scale 0
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global transition_animation_scale 0
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global animator_duration_scale 0
"$adb_bin" -s "emulator-$EMULATOR_PORT" shell rm -rf /sdcard/Download/agent-relay-usage-guide
verify_device_stable() {
  serial="emulator-$EMULATOR_PORT"
  if "$adb_bin" devices | awk '$1 == "emulator-5554" && $2 == "unauthorized" { found = 1 } END { exit !found }'; then
    # Remove only the stale unauthorized transport; do not stop its emulator.
    "$adb_bin" disconnect localhost:5554 > /dev/null 2>&1 || true
  fi
  timeout 5 "$adb_bin" -s "$serial" wait-for-device > /dev/null 2>&1 || true
  for _ in $(seq 1 5); do
    if [[ -s "$pid_file" ]] && kill -0 "$(< "$pid_file")" 2> /dev/null &&
      "$adb_bin" devices -l | awk -v serial="$serial" '$1 == serial && $2 == "device" { found = 1 } END { exit !found }' &&
      [[ "$("$adb_bin" -s "$serial" get-state 2> /dev/null || true)" == device ]]; then
      return 0
    fi
    sleep 1
  done
  echo "Target emulator $serial was not stable immediately before tests" >&2
  "$adb_bin" devices -l >&2 || true
  "$adb_bin" server-status >&2 || true
  return 1
}
verify_device_stable
"$adb_bin" devices -l
"$adb_bin" -s "emulator-$EMULATOR_PORT" get-state
./gradlew --stop > /dev/null 2>&1 || true
if "$adb_bin" devices | awk '$1 == "emulator-5554" && $2 == "unauthorized" { found = 1 } END { exit !found }'; then
  "$adb_bin" disconnect localhost:5554 > /dev/null 2>&1 || true
fi
"$@"
