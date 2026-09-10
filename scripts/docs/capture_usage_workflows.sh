#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -euo pipefail

usage() {
  cat << 'EOF'
Usage: scripts/docs/capture_usage_workflows.sh [--record]

Capture the verified Android usage journeys on the only connected emulator.
With --record, replace the reviewed baselines after manual visual review.
EOF
}

mode="${1:-}"
if [[ -n "$mode" && "$mode" != "--record" ]]; then
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

device_count="$("$adb_bin" devices | awk 'NR > 1 && $2 == "device" { count++ } END { print count + 0 }')"
if [[ "$device_count" != "1" ]]; then
  echo "Connect exactly one booted Android emulator; found $device_count." >&2
  exit 2
fi
if [[ "$("$adb_bin" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Workflow evidence must be recorded on an emulator, not a physical device." >&2
  exit 2
fi
if [[ "$("$adb_bin" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; then
  echo "The emulator has not completed booting." >&2
  exit 2
fi

capture_root="$repo_root/build/usage-guide/captured"
baseline_root="$repo_root/docs/assets/workflows"
remote_capture="/sdcard/Download/agent-relay-usage-guide"
case "$capture_root" in
  "$repo_root"/build/usage-guide/*) ;;
  *)
    echo "Unsafe capture path: $capture_root" >&2
    exit 2
    ;;
esac
case "$baseline_root" in
  "$repo_root"/docs/assets/workflows) ;;
  *)
    echo "Unsafe baseline path: $baseline_root" >&2
    exit 2
    ;;
esac

"$adb_bin" shell wm size 1080x2400
"$adb_bin" shell wm density 420
"$adb_bin" shell settings put system font_scale 1.0
"$adb_bin" shell cmd alarm set-timezone UTC
"$adb_bin" shell cmd uimode night no
"$adb_bin" shell settings put global window_animation_scale 0
"$adb_bin" shell settings put global transition_animation_scale 0
"$adb_bin" shell settings put global animator_duration_scale 0
"$adb_bin" shell rm -rf "$remote_capture"

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.agentrelay.ui.main.UsageJourneyTest \
  --stacktrace

rm -rf "$capture_root"
mkdir -p "$capture_root"
"$adb_bin" pull "$remote_capture/." "$capture_root/"

uv run --only-group docs python scripts/docs/render_workflows.py --check
if [[ "$mode" == "--record" ]]; then
  mkdir -p "$baseline_root"
  # A capture test owns only the workflow directories that it produced. Other
  # tests (for example, the QR journey) retain their independently reviewed
  # evidence when this recorder refreshes UsageJourneyTest baselines.
  while IFS= read -r -d "" captured_workflow; do
    workflow_name="${captured_workflow##*/}"
    baseline_workflow="$baseline_root/$workflow_name"
    rm -rf "$baseline_workflow"
    cp -a "$captured_workflow" "$baseline_workflow"
  done < <(find "$capture_root" -mindepth 1 -maxdepth 1 -type d -print0)
fi
uv run --only-group docs python scripts/docs/verify_workflows.py \
  --captured "$capture_root"
uv run --only-group docs mkdocs build --strict

if [[ "$mode" == "--record" ]]; then
  echo "Recorded reviewed baselines in docs/assets/workflows."
else
  echo "Current captures match the reviewed baselines."
fi
echo "Captured images: build/usage-guide/captured"
echo "Browsable site: build/site/index.html"
