#!/usr/bin/env bash

set -euo pipefail

readonly cmake_version="3.28.3"
readonly cmake_archive_sha256="804d231460ab3c8b556a42d2660af4ac7a0e21c98a7f8ee3318a74b4a9a187a6"
readonly ndk_version="28.2.13676358"
readonly ninja_version="1.11.1"
readonly ninja_archive_sha256="b901ba96e486dce377f9a070ed4ef3f79deb45f4ffe2938f8e7ddc69cfb3df77"

: "${RUNNER_TEMP:?RUNNER_TEMP must identify the hosted runner temporary directory}"
: "${GITHUB_PATH:?GITHUB_PATH must identify the hosted runner path file}"

android_sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$android_sdk_root" ]] || {
  printf 'Android SDK root was not configured\n' >&2
  exit 1
}

native_root="$RUNNER_TEMP/agent-relay-native-tools"
cmake_root="$native_root/cmake"
ninja_root="$native_root/ninja"
cmake_asset="cmake-$cmake_version-linux-x86_64.tar.gz"
cmake_archive="$native_root/$cmake_asset"
cmake_url="https://github.com/Kitware/CMake/releases/download/v$cmake_version/$cmake_asset"
ninja_archive="$native_root/ninja-linux.zip"
ninja_url="https://github.com/ninja-build/ninja/releases/download/v$ninja_version/ninja-linux.zip"

mkdir -p "$cmake_root" "$ninja_root"
curl --fail --location --retry 3 --proto '=https' \
  --output "$cmake_archive" "$cmake_url"
curl --fail --location --retry 3 --proto '=https' \
  --output "$ninja_archive" "$ninja_url"
printf '%s  %s\n' "$cmake_archive_sha256" "$cmake_archive" |
  sha256sum --check --strict
printf '%s  %s\n' "$ninja_archive_sha256" "$ninja_archive" |
  sha256sum --check --strict
tar -xzf "$cmake_archive" --strip-components=1 -C "$cmake_root"
unzip -oq "$ninja_archive" -d "$ninja_root"
chmod 0755 "$ninja_root/ninja"

cmake_actual="$("$cmake_root/bin/cmake" --version | sed -n '1s/^cmake version //p')"
[[ "$cmake_actual" == "$cmake_version" ]] || {
  printf 'CMake version differed from the pinned version\n' >&2
  exit 1
}
[[ "$("$ninja_root/ninja" --version)" == "$ninja_version" ]] || {
  printf 'Ninja version differed from the pinned version\n' >&2
  exit 1
}

sdkmanager_path="$android_sdk_root/cmdline-tools/latest/bin/sdkmanager"
[[ -x "$sdkmanager_path" ]] || {
  printf 'Android sdkmanager was not found at the canonical runner path\n' >&2
  exit 1
}
ndk_properties="$android_sdk_root/ndk/$ndk_version/source.properties"
if [[ ! -f "$ndk_properties" ]]; then
  "$sdkmanager_path" --install "ndk;$ndk_version"
fi
[[ -f "$ndk_properties" ]]
ndk_actual="$(sed -n 's/^Pkg.Revision = //p' "$ndk_properties")"
[[ "$ndk_actual" == "$ndk_version" ]]

printf '%s\n' "$cmake_root/bin" "$ninja_root" >> "$GITHUB_PATH"
