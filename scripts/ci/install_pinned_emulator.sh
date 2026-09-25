#!/usr/bin/env bash
# Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
# SPDX-License-Identifier: MIT

set -euo pipefail

: "${ANDROID_HOME:?ANDROID_HOME must identify the Android SDK}"
: "${RUNNER_TEMP:?RUNNER_TEMP must identify runner temporary storage}"

readonly version="36.6.11"
readonly archive_name="emulator-linux_x64-15507667.zip"
readonly archive_sha256="1eade4cf2df6ea8eeead4902c635897ba12aaa32aac4389eaae0fdb498a5b830"
readonly url="https://dl.google.com/android/repository/$archive_name"
archive="$RUNNER_TEMP/$archive_name"

curl --fail --location --retry 3 --proto '=https' --output "$archive" "$url"
printf '%s  %s\n' "$archive_sha256" "$archive" | sha256sum --check --strict
rm -rf "$ANDROID_HOME/emulator"
mkdir -p "$ANDROID_HOME/emulator"
command -v unzip > /dev/null 2>&1 || {
  printf 'unzip is required to install the pinned Android emulator\n' >&2
  exit 127
}
unzip -oq "$archive" -d "$ANDROID_HOME"
emulator_bin="$ANDROID_HOME/emulator/emulator"
test -x "$emulator_bin"
cat > "$ANDROID_HOME/emulator/package.xml" << EOF
<?xml version="1.0" encoding="utf-8"?>
<ns2:repository xmlns:ns2="http://schemas.android.com/repository/android/common/02" xmlns:ns5="http://schemas.android.com/repository/android/generic/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<localPackage path="emulator" obsolete="false">
  <type-details xsi:type="ns5:genericDetailsType"/>
  <revision><major>36</major><minor>6</minor><micro>11</micro></revision>
  <display-name>Android Emulator</display-name>
  <uses-license ref="android-sdk-license"/>
</localPackage>
</ns2:repository>
EOF
test -s "$ANDROID_HOME/emulator/package.xml"
source_properties="$ANDROID_HOME/emulator/source.properties"
test -s "$source_properties"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*36\.6\.11$' "$source_properties"
if version_raw="$($emulator_bin -version 2>&1)"; then
  :
else
  status=$?
  printf 'Pinned Android emulator failed to start (exit %s):\n%s\n' "$status" "$version_raw" >&2
  if command -v ldd > /dev/null 2>&1; then
    ldd "$emulator_bin" >&2 || true
  fi
  exit "$status"
fi
printf '%s\n' "$version_raw"
grep -q "^Android emulator version $version\." <<< "$version_raw"
