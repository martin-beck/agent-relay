#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

readonly SHELLCHECK_VERSION="0.11.0"
readonly SHFMT_VERSION="3.14.0"
readonly BATS_VERSION="1.14.0"
readonly BATS_REVISION="eb7f42f8d608ac693d7a4b67474f6714ea68cfc5"
readonly BATS_ARCHIVE_SHA256="845574549f4c9777bf02fcdf307f1bf347d40c66920fb6b47dcc8fdfa065ac39"
readonly BATS_REGULAR_FILE_PAYLOAD_SHA256="6add870c431c73b580c798bc4bdea644e615bbf08368e9aef6dccfbf11f7f95e"

fail() {
  printf 'shell quality tools: %s\n' "$*" >&2
  exit 1
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
tool_root="${SHELL_QUALITY_TOOL_ROOT:-$repo_root/build/tools/shell-quality}"
[[ "$tool_root" == /* ]] || fail "tool directory must be absolute"
resolved_runner_temp="$(realpath -m -- "${RUNNER_TEMP:-/nonexistent}")"
resolved_tool_root="$(realpath -m -- "$tool_root")"
case "$resolved_tool_root" in
  "$repo_root"/build/tools/shell-quality | /tmp/* | "$resolved_runner_temp"/*) ;;
  *) fail "tool directory must be the repository cache, /tmp, or RUNNER_TEMP" ;;
esac
mkdir -p -- "$tool_root"
tool_root="$(realpath -e -- "$tool_root")"
case "$tool_root" in
  "$repo_root"/build/tools/shell-quality | /tmp/* | "$resolved_runner_temp"/*) ;;
  *) fail "tool directory must be the repository cache, /tmp, or RUNNER_TEMP" ;;
esac
cache_dir="$tool_root/cache"
bin_dir="$tool_root/bin"
mkdir -p -- "$cache_dir" "$bin_dir"

staging_paths=()
cleanup() {
  local path
  for path in "${staging_paths[@]}"; do
    rm -rf -- "$path"
  done
}
trap cleanup EXIT

case "$(uname -s):$(uname -m)" in
  Linux:x86_64)
    shellcheck_platform="linux.x86_64"
    shellcheck_sha256="8c3be12b05d5c177a04c29e3c78ce89ac86f1595681cab149b65b97c4e227198"
    shellcheck_payload_sha256="4da528ddb3a4d1b7b24a59d4e16eb2f5fd960f4bd9a3708a15baddbdf1d5a55b"
    shfmt_platform="linux_amd64"
    shfmt_sha256="fe42021c7272ef2d67ea36cbc3031683c625d0badec733ef3a57b567246a0b66"
    ;;
  Linux:aarch64 | Linux:arm64)
    shellcheck_platform="linux.aarch64"
    shellcheck_sha256="12b331c1d2db6b9eb13cfca64306b1b157a86eb69db83023e261eaa7e7c14588"
    shellcheck_payload_sha256="127f13925eadd52c341bca0ebaf9ab0dbd78c6468f30a8f262a528bf8de47546"
    shfmt_platform="linux_arm64"
    shfmt_sha256="8029959a945b5c6f2bc92ce53fca5cf0384c811cc0884b25b196a093a005657a"
    ;;
  *) fail "unsupported host: $(uname -s) $(uname -m)" ;;
esac

download() {
  local url=$1
  local destination=$2
  if [[ -f "$destination" ]]; then
    return
  fi
  [[ "${SHELL_QUALITY_OFFLINE:-0}" != 1 ]] || fail "offline archive is missing: $(basename "$destination")"
  download_temp="$destination.tmp.$$"
  staging_paths+=("$download_temp")
  curl --fail --location --retry 3 --proto '=https' --output "$download_temp" "$url"
  mv -- "$download_temp" "$destination"
}

verify() {
  local expected=$1
  local file=$2
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --strict > /dev/null
}

regular_file_tree_sha256() {
  (
    cd "$1"
    find . -type f -print0 |
      LC_ALL=C sort -z |
      xargs -0 sha256sum |
      sha256sum |
      awk '{print $1}'
  )
}

shellcheck_archive="$cache_dir/shellcheck-v$SHELLCHECK_VERSION.$shellcheck_platform.tar.xz"
download \
  "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/$(basename "$shellcheck_archive")" \
  "$shellcheck_archive"
verify "$shellcheck_sha256" "$shellcheck_archive"
shellcheck_extract="$tool_root/shellcheck-v$SHELLCHECK_VERSION"
if [[ ! -x "$shellcheck_extract/shellcheck" ]] ||
  [[ "$(sha256sum "$shellcheck_extract/shellcheck" | awk '{print $1}')" != "$shellcheck_payload_sha256" ]]; then
  shellcheck_staging="$(mktemp -d "$tool_root/.shellcheck.XXXXXX")"
  staging_paths+=("$shellcheck_staging")
  tar --extract --xz --file "$shellcheck_archive" --directory "$shellcheck_staging"
  verify "$shellcheck_payload_sha256" "$shellcheck_staging/shellcheck-v$SHELLCHECK_VERSION/shellcheck"
  rm -rf -- "$shellcheck_extract"
  mv -- "$shellcheck_staging/shellcheck-v$SHELLCHECK_VERSION" "$shellcheck_extract"
fi
verify "$shellcheck_payload_sha256" "$shellcheck_extract/shellcheck"
staging_paths+=("$bin_dir/.shellcheck.$$")
install -m 0755 -- "$shellcheck_extract/shellcheck" "$bin_dir/.shellcheck.$$"
mv --force --no-target-directory "$bin_dir/.shellcheck.$$" "$bin_dir/shellcheck"

shfmt_asset="shfmt_v${SHFMT_VERSION}_${shfmt_platform}"
shfmt_archive="$cache_dir/$shfmt_asset"
download "https://github.com/mvdan/sh/releases/download/v$SHFMT_VERSION/$shfmt_asset" "$shfmt_archive"
verify "$shfmt_sha256" "$shfmt_archive"
staging_paths+=("$bin_dir/.shfmt.$$")
install -m 0755 -- "$shfmt_archive" "$bin_dir/.shfmt.$$"
mv --force --no-target-directory "$bin_dir/.shfmt.$$" "$bin_dir/shfmt"

bats_archive="$cache_dir/bats-core-$BATS_REVISION.tar.gz"
download "https://github.com/bats-core/bats-core/archive/$BATS_REVISION.tar.gz" "$bats_archive"
verify "$BATS_ARCHIVE_SHA256" "$bats_archive"
bats_extract="$tool_root/bats-core-$BATS_REVISION"
if [[ ! -x "$bats_extract/bin/bats" ]] ||
  [[ "$(regular_file_tree_sha256 "$bats_extract")" != "$BATS_REGULAR_FILE_PAYLOAD_SHA256" ]]; then
  bats_staging="$(mktemp -d "$tool_root/.bats.XXXXXX")"
  staging_paths+=("$bats_staging")
  tar --extract --gzip --file "$bats_archive" --directory "$bats_staging"
  [[ "$(regular_file_tree_sha256 "$bats_staging/bats-core-$BATS_REVISION")" == "$BATS_REGULAR_FILE_PAYLOAD_SHA256" ]] ||
    fail "Bats extracted payload differed from the pin"
  rm -rf -- "$bats_extract"
  mv -- "$bats_staging/bats-core-$BATS_REVISION" "$bats_extract"
fi
[[ "$(regular_file_tree_sha256 "$bats_extract")" == "$BATS_REGULAR_FILE_PAYLOAD_SHA256" ]] ||
  fail "Bats installed payload differed from the pin"
staging_paths+=("$bin_dir/.bats.$$")
ln -s -- "$bats_extract/bin/bats" "$bin_dir/.bats.$$"
mv --force --no-target-directory "$bin_dir/.bats.$$" "$bin_dir/bats"

[[ "$("$bin_dir/shellcheck" --version | sed -n 's/^version: //p')" == "$SHELLCHECK_VERSION" ]] ||
  fail "ShellCheck version differed from the pin"
[[ "$("$bin_dir/shfmt" --version)" == "v$SHFMT_VERSION" ]] ||
  fail "shfmt version differed from the pin"
[[ "$("$bin_dir/bats" --version)" == "Bats $BATS_VERSION" ]] ||
  fail "Bats version differed from the pin"

if [[ -n "${GITHUB_PATH:-}" ]]; then
  printf '%s\n' "$bin_dir" >> "$GITHUB_PATH"
fi
printf '%s\n' "$bin_dir"
