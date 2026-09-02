#!/usr/bin/env bash
set -euo pipefail

readonly PACKAGE_NAME="com.example.agentrelay"
readonly SERVICE_COMPONENT="$PACKAGE_NAME/.background.RemoteSessionForegroundService"
readonly START_ACTION="$PACKAGE_NAME.background.START"
readonly STOP_ACTION="$PACKAGE_NAME.background.STOP"
readonly APK_PATH="${1:-app/build/outputs/apk/debug/app-debug.apk}"
readonly ADB_BIN="${ADB_BIN:-adb}"
readonly POLL_ATTEMPTS=80
readonly POLL_SECONDS=0.25
readonly STABILITY_SECONDS=5

adb_shell() {
  "$ADB_BIN" shell -n "$@"
}

service_dump() {
  adb_shell dumpsys activity services "$SERVICE_COMPONENT"
}

service_exists() {
  service_dump | grep -F "ServiceRecord" >/dev/null
}

app_pid() {
  adb_shell pidof -s "$PACKAGE_NAME" | tr -d '\r' || true
}

wait_for_service() {
  local attempt
  for attempt in $(seq 1 "$POLL_ATTEMPTS"); do
    if [[ -n "$(app_pid)" ]] && service_exists; then
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Background service did not become active." >&2
  return 1
}

wait_for_no_service() {
  local attempt
  for attempt in $(seq 1 "$POLL_ATTEMPTS"); do
    if ! service_exists; then
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Background service did not stop." >&2
  return 1
}

wait_for_restarted_process() {
  local previous_pid="$1"
  local attempt
  local current_pid
  for attempt in $(seq 1 "$POLL_ATTEMPTS"); do
    current_pid="$(app_pid)"
    if [[ -n "$current_pid" && "$current_pid" != "$previous_pid" ]] &&
      service_exists; then
      printf '%s\n' "$current_pid"
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Android did not recreate the sticky background service." >&2
  return 1
}

launch_and_start_service() {
  adb_shell am start -W -n "$PACKAGE_NAME/.MainActivity" >/dev/null
  adb_shell run-as "$PACKAGE_NAME" am start-foreground-service --user 0 \
    -a "$START_ACTION" -n "$SERVICE_COMPONENT" >/dev/null
  adb_shell input keyevent KEYCODE_HOME
  wait_for_service
}

assert_stays_stopped_after_reopen() {
  local reason="$1"
  sleep "$STABILITY_SECONDS"
  if [[ -n "$(app_pid)" ]] || service_exists; then
    echo "$reason did not stop the complete application." >&2
    return 1
  fi
  adb_shell am start -W -n "$PACKAGE_NAME/.MainActivity" >/dev/null
  sleep 3
  if service_exists; then
    echo "$reason restarted background mode without a new explicit action." >&2
    return 1
  fi
}

cleanup() {
  adb_shell am force-stop "$PACKAGE_NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

"$ADB_BIN" wait-for-device
if [[ "$(adb_shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo "Android device did not report a completed boot." >&2
  exit 1
fi
if [[ ! -f "$APK_PATH" ]]; then
  echo "Debug APK is missing before background recovery verification." >&2
  exit 1
fi
if ! "$ADB_BIN" install -r "$APK_PATH" >/dev/null; then
  echo "Application install failed before background recovery verification." >&2
  exit 1
fi

readonly SDK_LEVEL="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
if ((SDK_LEVEL >= 33)); then
  adb_shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS
fi

launch_and_start_service
readonly BEFORE_PID="$(app_pid)"
[[ -n "$BEFORE_PID" ]]
sleep 2
adb_shell run-as "$PACKAGE_NAME" kill -9 "$BEFORE_PID" >/dev/null 2>&1 || true
readonly AFTER_PID="$(wait_for_restarted_process "$BEFORE_PID")"
sleep "$STABILITY_SECONDS"
[[ "$(app_pid)" == "$AFTER_PID" ]]
restart_dump="$(service_dump)"
grep -Fq "restartCount=1" <<<"$restart_dump"
grep -Fq "startCommandResult=1" <<<"$restart_dump"

adb_shell run-as "$PACKAGE_NAME" am startservice --user 0 \
  -a "$STOP_ACTION" -n "$SERVICE_COMPONENT" >/dev/null
wait_for_no_service

user_stop_result="not applicable below API 33"
if ((SDK_LEVEL >= 33)); then
  launch_and_start_service
  adb_shell cmd activity stop-app "$PACKAGE_NAME"
  assert_stays_stopped_after_reopen "Android user Stop"
  user_stop_result="passed"
fi

launch_and_start_service
adb_shell am force-stop "$PACKAGE_NAME"
assert_stays_stopped_after_reopen "Force-stop"

printf '%s\n' \
  "Background recovery passed: sticky restart, explicit Stop, and force-stop;" \
  "user Stop: $user_stop_result."
