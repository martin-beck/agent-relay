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
readonly PRE_KILL_STABILITY_ATTEMPTS=40
readonly POST_RESTART_STABILITY_ATTEMPTS=20

adb_shell() {
  "$ADB_BIN" shell -n "$@"
}

service_dump() {
  adb_shell dumpsys activity services "$SERVICE_COMPONENT"
}

service_exists() {
  service_dump | grep -F "ServiceRecord" > /dev/null
}

app_pid() {
  adb_shell pidof -s "$PACKAGE_NAME" | tr -d '\r' || true
}

service_is_foreground() {
  local dump
  dump="$(service_dump)"
  grep -Fq "ServiceRecord" <<< "$dump" &&
    grep -Fq "isForeground=true" <<< "$dump" &&
    grep -Fq "startCommandResult=1" <<< "$dump"
}

report_service_failure() {
  local reason="$1"
  printf '%s\n' "$reason" >&2
  printf 'Application PID: %s\n' "$(app_pid)" >&2
  service_dump >&2 || true
  return 1
}

wait_for_service() {
  for _ in $(seq 1 "$POLL_ATTEMPTS"); do
    if [[ -n "$(app_pid)" ]] && service_is_foreground; then
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Background service did not become active." >&2
  return 1
}

wait_for_no_service() {
  for _ in $(seq 1 "$POLL_ATTEMPTS"); do
    if ! service_exists; then
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Background service did not stop." >&2
  return 1
}

wait_for_stable_service() {
  local expected_pid="$1"
  local attempts="$2"
  local reason="$3"
  local current_pid
  for _ in $(seq 1 "$attempts"); do
    current_pid="$(app_pid)"
    if [[ -z "$current_pid" || "$current_pid" != "$expected_pid" ]] ||
      ! service_is_foreground; then
      report_service_failure "$reason"
      return 1
    fi
    sleep "$POLL_SECONDS"
  done
}

wait_for_restarted_process() {
  local previous_pid="$1"
  local current_pid
  for _ in $(seq 1 "$POLL_ATTEMPTS"); do
    current_pid="$(app_pid)"
    if [[ -n "$current_pid" && "$current_pid" != "$previous_pid" ]] &&
      service_is_foreground; then
      printf '%s\n' "$current_pid"
      return 0
    fi
    sleep "$POLL_SECONDS"
  done
  echo "Android did not recreate the sticky background service." >&2
  return 1
}

launch_and_start_service() {
  adb_shell am start -W -n "$PACKAGE_NAME/.MainActivity" > /dev/null
  adb_shell run-as "$PACKAGE_NAME" am start-foreground-service --user 0 \
    -a "$START_ACTION" -n "$SERVICE_COMPONENT" > /dev/null
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
  adb_shell am start -W -n "$PACKAGE_NAME/.MainActivity" > /dev/null
  sleep 3
  if service_exists; then
    echo "$reason restarted background mode without a new explicit action." >&2
    return 1
  fi
}

cleanup() {
  adb_shell am force-stop "$PACKAGE_NAME" > /dev/null 2>&1 || true
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
if ! "$ADB_BIN" install -r "$APK_PATH" > /dev/null; then
  echo "Application install failed before background recovery verification." >&2
  exit 1
fi

SDK_LEVEL="$(adb_shell getprop ro.build.version.sdk | tr -d '\r')"
readonly SDK_LEVEL
if ((SDK_LEVEL >= 33)); then
  adb_shell pm grant "$PACKAGE_NAME" android.permission.POST_NOTIFICATIONS
fi

launch_and_start_service
BEFORE_PID="$(app_pid)"
readonly BEFORE_PID
if [[ -z "$BEFORE_PID" ]]; then
  report_service_failure "Background service started without an application process."
fi
# On a fresh API 36 install, a synthetic process kill after only two seconds
# reproducibly races foreground-service restart eligibility. Exercise recovery
# only after the service has remained fully foreground for ten seconds.
wait_for_stable_service "$BEFORE_PID" "$PRE_KILL_STABILITY_ATTEMPTS" \
  "Background service did not remain stable before process-death verification."
adb_shell run-as "$PACKAGE_NAME" kill -9 "$BEFORE_PID" > /dev/null 2>&1 || true
AFTER_PID="$(wait_for_restarted_process "$BEFORE_PID")"
readonly AFTER_PID
wait_for_stable_service "$AFTER_PID" "$POST_RESTART_STABILITY_ATTEMPTS" \
  "Recreated background service did not remain stable."
restart_dump="$(service_dump)"
if ! grep -Fq "restartCount=1" <<< "$restart_dump"; then
  report_service_failure "Background service was not recorded as a sticky restart."
fi
if ! grep -Fq "startCommandResult=1" <<< "$restart_dump"; then
  report_service_failure "Background service did not retain START_STICKY."
fi

adb_shell run-as "$PACKAGE_NAME" am startservice --user 0 \
  -a "$STOP_ACTION" -n "$SERVICE_COMPONENT" > /dev/null
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
