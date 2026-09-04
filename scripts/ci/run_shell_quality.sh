#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)"
bin_dir="$($repo_root/scripts/ci/install_shell_quality_tools.sh)"

mapfile -t shell_files < <(
  {
    git -C "$repo_root" ls-files '*.bash' '*.sh'
    printf '%s\n' scripts/ci/install_shell_quality_tools.sh scripts/ci/run_shell_quality.sh
  } |
    LC_ALL=C sort -u |
    while IFS= read -r path; do
      [[ -f "$repo_root/$path" ]] && printf '%s\n' "$path"
    done |
    LC_ALL=C sort
)
[[ ${#shell_files[@]} -gt 0 ]] || {
  printf 'No tracked first-party shell scripts found.\n' >&2
  exit 1
}
for index in "${!shell_files[@]}"; do
  shell_files[$index]="$repo_root/${shell_files[$index]}"
done

case "${1:-}" in
  shellcheck)
    "$bin_dir/shellcheck" --external-sources --severity=warning "${shell_files[@]}"
    ;;
  shfmt)
    "$bin_dir/shfmt" --diff --language-dialect bash --indent 2 --case-indent --space-redirects \
      "${shell_files[@]}"
    ;;
  test)
    "$bin_dir/bats" "$repo_root/tests/shell"
    ;;
  list)
    printf '%s\n' "${shell_files[@]}"
    ;;
  all)
    "$0" shellcheck
    "$0" shfmt
    "$0" test
    ;;
  *)
    printf 'Usage: %s {shellcheck|shfmt|test|list|all}\n' "$0" >&2
    exit 2
    ;;
esac
