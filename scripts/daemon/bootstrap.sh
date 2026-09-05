#!/usr/bin/env bash
set -Eeuo pipefail
IFS=$'\n\t'

script_name="$(basename "$0")"
readonly script_name
readonly max_bundle_bytes=$((512 * 1024 * 1024))
data_root="${AGENT_RELAY_HOME:-${XDG_DATA_HOME:-$HOME/.local/share}/agent-relay}"
state_root="$data_root/state"
versions_root="$data_root/versions"
current_link="$data_root/current"
previous_link="$data_root/previous"
lock_dir="$data_root/.install.lock"

die() {
  printf '%s: %s\n' "$script_name" "$*" >&2
  exit 1
}
usage() { printf 'Usage: %s {preview|install|start|stop|status|rollback|uninstall|pair} [options]\n' "$script_name"; }

parse_args() {
  manifest=''
  bundle=''
  consent=0
  version=''
  while (($#)); do
    case "$1" in
      --manifest)
        (($# >= 2)) || die '--manifest needs a path'
        manifest=$2
        shift 2
        ;;
      --bundle)
        (($# >= 2)) || die '--bundle needs a path'
        bundle=$2
        shift 2
        ;;
      --version)
        (($# >= 2)) || die '--version needs a value'
        version=$2
        shift 2
        ;;
      --yes)
        consent=1
        shift
        ;;
      -h | --help)
        usage
        exit 0
        ;;
      *) die "unknown option: $1" ;;
    esac
  done
}

load_manifest() {
  [[ -r "$manifest" ]] || die 'manifest is required and must be readable'
  unset manifest_version manifest_arch manifest_archive manifest_sha256 manifest_signature
  while IFS='=' read -r key value; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    case "$key" in
      version) manifest_version=$value ;;
      arch) manifest_arch=$value ;;
      archive) manifest_archive=$value ;;
      sha256) manifest_sha256=$value ;;
      signature) manifest_signature=$value ;;
      *) die "unknown manifest field: $key" ;;
    esac
  done < "$manifest"
  : "${manifest_version:?manifest version is missing}"
  : "${manifest_arch:?manifest architecture is missing}"
  : "${manifest_archive:?manifest archive is missing}"
  : "${manifest_sha256:?manifest checksum is missing}"
  [[ $manifest_version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die 'manifest version is invalid'
  [[ $manifest_arch == "$(uname -m)" ]] || die 'manifest architecture does not match this host'
  [[ $manifest_sha256 =~ ^[0-9a-f]{64}$ ]] || die 'manifest checksum is invalid'
  [[ -n ${manifest_signature:-} ]] || die 'manifest signature is missing'
}

validate_archive() {
  [[ -r "$bundle" ]] || die 'bundle is required and must be readable'
  local size name listing
  size=$(wc -c < "$bundle")
  ((size > 0 && size <= max_bundle_bytes)) || die 'bundle size is outside the safety bound'
  listing=$(tar -tzf "$bundle") || die 'bundle is not a readable gzip tar archive'
  while IFS= read -r name; do
    [[ -n "$name" ]] || continue
    [[ "$name" != /* && "$name" != ../* && "$name" != */../* && "$name" != *$'\n'* ]] || die 'bundle contains traversal'
  done <<< "$listing"
  tar -tvzf "$bundle" | awk '$1 ~ /^l|^h/ { exit 1 }' || die 'bundle contains a link'
  printf '%s  %s\n' "$manifest_sha256" "$bundle" | sha256sum --check --status - || die 'bundle checksum mismatch'
}

verify_manifest_signature() {
  local trusted_key="${AGENT_RELAY_TRUSTED_KEY:-}"
  [[ -r "$trusted_key" ]] || die 'AGENT_RELAY_TRUSTED_KEY must name the pinned release key'
  [[ -r "$manifest_signature" ]] || die 'manifest signature is not readable'
  command -v openssl > /dev/null 2>&1 || die 'openssl is required for manifest verification'
  openssl dgst -sha256 -verify "$trusted_key" -signature "$manifest_signature" "$manifest" > /dev/null ||
    die 'manifest signature verification failed'
}

acquire_lock() {
  mkdir -p "$data_root"
  mkdir "$lock_dir" 2> /dev/null || die 'another bootstrap operation is active'
  trap 'rmdir "$lock_dir" 2>/dev/null || true' EXIT
}
preview() {
  load_manifest
  printf 'Target: user-space %s (%s)\nFiles: %s\nService: systemd-user or supervised foreground\nNetwork: only the supplied signed bundle\nRollback: one last-known-good version\n' "$manifest_version" "$manifest_arch" "$data_root"
}

install_bundle() {
  ((consent == 1)) || die 'explicit --yes consent is required after preview'
  load_manifest
  verify_manifest_signature
  validate_archive
  acquire_lock
  version=${version:-$manifest_version}
  [[ $version == "$manifest_version" ]] || die 'requested version differs from signed manifest'
  local staging="$versions_root/.${version}.tmp" target="$versions_root/$version"
  mkdir -p "$versions_root" "$state_root"
  rm -rf -- "$staging"
  mkdir "$staging"
  tar -xzf "$bundle" -C "$staging" --no-same-owner --no-same-permissions
  [[ -x "$staging/agent-relay-daemon" ]] || die 'bundle lacks executable daemon'
  rm -rf -- "$target"
  mv -- "$staging" "$target"
  [[ -L "$current_link" ]] && ln -sfn "$(readlink "$current_link")" "$previous_link"
  ln -sfn "versions/$version" "$current_link"
  printf '%s\n' "$version" > "$state_root/installed-version"
}

service_action() {
  local action=$1
  if command -v systemctl > /dev/null 2>&1 && systemctl --user cat agent-relay.service > /dev/null 2>&1; then
    systemctl --user "$action" agent-relay.service || die "systemd user service $action failed"
    return
  fi
  local pid_file="$state_root/daemon.pid"
  case "$action" in
    start)
      [[ -x "$current_link/agent-relay-daemon" ]] || die 'no installed daemon'
      [[ -f "$pid_file" ]] && kill -0 "$(< "$pid_file")" 2> /dev/null && die 'daemon is already running'
      nohup "$current_link/agent-relay-daemon" > "$state_root/daemon.log" 2>&1 &
      printf '%s\n' "$!" > "$pid_file"
      ;;
    stop)
      [[ -f "$pid_file" ]] || return 0
      kill "$(< "$pid_file")" 2> /dev/null || true
      rm -f "$pid_file"
      ;;
    status) [[ -f "$pid_file" ]] && kill -0 "$(< "$pid_file")" 2> /dev/null && printf 'running\n' || printf 'stopped\n' ;;
  esac
}

rollback() {
  acquire_lock
  [[ -L "$previous_link" ]] || die 'no last-known-good version exists'
  local old
  old=$(readlink "$previous_link")
  ln -sfn "$old" "$current_link"
  printf '%s\n' "${old##*/}" > "$state_root/installed-version"
}

pair() {
  [[ -L "$current_link" ]] || die 'install the daemon before pairing'
  [[ -r "$state_root/pairing-code" ]] || die 'daemon has not produced a short-lived pairing code'
  local code
  code=$(< "$state_root/pairing-code")
  [[ $code =~ ^[A-Za-z0-9_-]{16,128}$ ]] || die 'pairing code is malformed'
  rm -f "$state_root/pairing-code"
  printf 'Pairing is ready; scan the short-lived code now. Authentication phrase: %.8s\n' "$code"
}

main() {
  (($# >= 1)) || {
    usage >&2
    exit 2
  }
  local action=$1
  shift
  parse_args "$@"
  case "$action" in
    preview)
      load_manifest
      preview
      ;;
    install) install_bundle ;;
    start | stop | status) service_action "$action" ;;
    rollback) rollback ;;
    pair) pair ;;
    uninstall)
      acquire_lock
      service_action stop
      rm -rf -- "$data_root"
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}
main "$@"
