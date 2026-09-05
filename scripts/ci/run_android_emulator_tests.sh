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
export ANDROID_AVD_HOME
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
if [[ "${1:-}" == -- ]]; then shift; fi
adb_bin="$ANDROID_HOME/platform-tools/adb"
qemu_bin="$ANDROID_HOME/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"
emulator_library_path="$ANDROID_HOME/emulator/lib64:$ANDROID_HOME/emulator/lib64/qt/lib"
avdmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
test -x "$adb_bin" -a -x "$qemu_bin" -a -x "$avdmanager_bin"
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
adb_port=$((EMULATOR_PORT + 1))
echo "QEMU binary: $qemu_bin"
echo "QEMU version: $(LD_LIBRARY_PATH="$emulator_library_path" "$qemu_bin" -version | head -1)"
controller_status_file="$RUNNER_TEMP/agent-relay-emulator-$EMULATOR_PORT.status"
run_controller() {
  qemu_pid=""
  for _ in $(seq 1 20); do
    qemu_pid="$(pgrep -u "$(id -u)" -f "$qemu_bin.*-ports $EMULATOR_PORT,$adb_port" | head -1 || true)"
    [[ -n "$qemu_pid" ]] && break
    sleep 1
  done
  if [[ -z "$qemu_pid" ]]; then
    echo 1 > "$controller_status_file"
    return 0
  fi
  echo "$qemu_pid" > "$pid_file"
  echo "Started emulator pid=$qemu_pid port=$EMULATOR_PORT log=$log_file"
  for _ in $(seq 1 300); do
    if ! kill -0 "$qemu_pid" 2> /dev/null; then
      cat "$log_file" >&2
      echo 1 > "$controller_status_file"
      return 0
    fi
    if [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" get-state 2> /dev/null || true)" == device ]] && [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" shell getprop sys.boot_completed 2> /dev/null || true)" == 1 ]]; then break; fi
    sleep 2
  done
  if [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" get-state 2> /dev/null || true)" != device ]] || [[ "$("$adb_bin" -s "emulator-$EMULATOR_PORT" shell getprop sys.boot_completed 2> /dev/null || true)" != 1 ]]; then
    cat "$log_file" >&2
    echo 1 > "$controller_status_file"
    kill "$qemu_pid" 2> /dev/null || true
    return 0
  fi
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell wm size 1080x2400
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell wm density 420
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put system font_scale 1.0
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell cmd alarm set-timezone UTC
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell cmd uimode night no
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global window_animation_scale 0
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global transition_animation_scale 0
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell settings put global animator_duration_scale 0
  "$adb_bin" -s "emulator-$EMULATOR_PORT" shell rm -rf /sdcard/Download/agent-relay-usage-guide
  set +e
  "$@"
  test_status=$?
  set -e
  echo "$test_status" > "$controller_status_file"
  "$adb_bin" -s "emulator-$EMULATOR_PORT" emu kill > /dev/null 2>&1 || true
  for _ in $(seq 1 10); do
    kill -0 "$qemu_pid" 2> /dev/null || return 0
    sleep 1
  done
  kill "$qemu_pid" 2> /dev/null || true
}
run_controller "$@" &
controller_pid=$!
set +e
env "LD_LIBRARY_PATH=$emulator_library_path" "$qemu_bin" -ports "$EMULATOR_PORT,$adb_port" -avd "$EMULATOR_AVD_NAME" -no-window -gpu swiftshader_indirect -no-snapshot -no-audio -no-boot-anim > "$log_file" 2>&1
qemu_status=$?
set -e
if [[ "$qemu_status" -ne 0 && "$qemu_status" -ne 143 ]]; then
  kill "$controller_pid" 2> /dev/null || true
  wait "$controller_pid" || true
  cat "$log_file" >&2
  exit 1
fi
wait "$controller_pid" || true
test_status=1
[[ -s "$controller_status_file" ]] && test_status="$(< "$controller_status_file")"
if [[ "$test_status" -eq 0 ]]; then
  if [[ "$qemu_status" -ne 0 && "$qemu_status" -ne 143 ]]; then
    echo "QEMU exited unexpectedly with status $qemu_status" >&2
    exit 1
  fi
  exit 0
fi
cat "$log_file" >&2
exit "$test_status"
