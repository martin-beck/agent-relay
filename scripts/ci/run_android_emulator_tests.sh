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
ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$RUNNER_TEMP/agent-relay-avd}"
echo "Android SDK: ${ANDROID_HOME:-<unset>}"
echo "AVD home: $ANDROID_AVD_HOME"
echo "Runner temp: ${RUNNER_TEMP:-<unset>}"
echo "Emulator port: $EMULATOR_PORT"
test -n "$ANDROID_HOME" -a -n "$ANDROID_AVD_HOME" -a -n "$RUNNER_TEMP"
if [[ "${1:-}" == -- ]]; then shift; fi
adb_bin="$ANDROID_HOME/platform-tools/adb"
emulator_bin="$ANDROID_HOME/emulator/emulator"
avdmanager_bin="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
test -x "$adb_bin" -a -x "$emulator_bin" -a -x "$avdmanager_bin"
mkdir -p "$ANDROID_AVD_HOME"
echo no | "$avdmanager_bin" create avd --sdk_root "$ANDROID_HOME" --force --name "$EMULATOR_AVD_NAME" --package "system-images;android-$EMULATOR_API_LEVEL;$EMULATOR_TARGET;$EMULATOR_ARCH" --device "$EMULATOR_PROFILE"
avd_dir="$ANDROID_AVD_HOME/$EMULATOR_AVD_NAME.avd"
if [[ ! -d "$avd_dir" ]]; then
  echo "avdmanager did not create expected AVD directory: $avd_dir" >&2
  find "$ANDROID_AVD_HOME" -maxdepth 2 -type f -name config.ini -print >&2 || true
  exit 1
fi
if [[ -f "$avd_dir/config.ini" ]]; then
  echo 'hw.cpu.ncore=2' >> "$avd_dir/config.ini"
else
  echo "avdmanager created no config.ini; using default AVD configuration" >&2
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
setsid "$emulator_bin" -port "$EMULATOR_PORT" -avd "$EMULATOR_AVD_NAME" -no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim > "$log_file" 2>&1 < /dev/null &
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
"$@"
