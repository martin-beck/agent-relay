#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat << 'EOF'
Usage: scripts/docs/capture_qr_workflow.sh

Capture the real Activity-boundary QR App-Link states for review. This command
does not create reviewed baselines: host QR and camera-preview evidence must be
provided by a real host/camera capture before the QR scenario can be verified.
EOF
}

if [[ $# -ne 0 ]]; then
  usage >&2
  exit 2
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$repo_root"

sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$sdk_root" ]]; then
  echo "Set ANDROID_SDK_ROOT or ANDROID_HOME." >&2
  exit 2
fi
adb_bin="$sdk_root/platform-tools/adb"
if [[ ! -x "$adb_bin" ]]; then
  echo "adb is not executable at $adb_bin." >&2
  exit 2
fi

device_count="$($adb_bin devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$device_count" != "1" ]]; then
  echo "Connect exactly one booted Android emulator; found $device_count." >&2
  exit 2
fi
if [[ "$($adb_bin shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "QR workflow evidence must be recorded on an emulator, not a physical device." >&2
  exit 2
fi
if [[ "$($adb_bin shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo "The emulator has not completed booting." >&2
  exit 2
fi

capture_root="$repo_root/build/qr-workflow/captured"
remote_capture="/sdcard/Android/data/com.example.agentrelay/files/qr-workflow"
case "$capture_root" in
  "$repo_root"/build/qr-workflow/*) ;;
  *)
    echo "Unsafe capture path: $capture_root" >&2
    exit 2
    ;;
esac

"$adb_bin" shell rm -rf "$remote_capture"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.agentrelay.PairingAppLinkJourneyTest \
  -Pandroid.testInstrumentationRunnerArguments.captureQrEvidence=true \
  --stacktrace

rm -rf "$capture_root"
mkdir -p "$capture_root"
"$adb_bin" pull "$remote_capture/." "$capture_root/"

count="$(find "$capture_root" -type f -name '*.png' | wc -l)"
if [[ "$count" != "3" ]]; then
  echo "Expected three Activity-boundary QR captures, found $count." >&2
  exit 1
fi
echo "Captured $count QR App-Link states for review under $capture_root."
echo "Host QR and camera-preview captures remain required before verification."
