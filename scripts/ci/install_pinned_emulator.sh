#!/usr/bin/env bash

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
unzip -oq "$archive" -d "$ANDROID_HOME"
emulator_bin="$ANDROID_HOME/emulator/emulator"
test -x "$emulator_bin"
source_properties="$ANDROID_HOME/emulator/source.properties"
test -s "$source_properties"
grep -Eq '^Pkg.Revision[[:space:]]*=[[:space:]]*36\.6\.11$' "$source_properties"
version_raw="$($emulator_bin -version 2>&1)"
printf '%s\n' "$version_raw"
grep -q "^Android emulator version $version\." <<< "$version_raw"
