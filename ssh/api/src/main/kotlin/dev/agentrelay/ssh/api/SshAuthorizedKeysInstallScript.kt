/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

internal object SshAuthorizedKeysInstallScript {
    fun build(authorizedKey: String): String {
        require(authorizedKey.matches(NORMALIZED_KEY)) {
            "The authorized key must contain only a normalized algorithm and blob"
        }
        val (algorithm, blob) = authorizedKey.split(' ')
        return SCRIPT_TEMPLATE
            .replace(ALGORITHM_TOKEN, algorithm)
            .replace(BLOB_TOKEN, blob)
    }

    private val SCRIPT_TEMPLATE = """
            set -eu
            if ! command -v awk >/dev/null 2>&1; then
              exit 69
            fi
            if ! awk 'BEGIN { exit 0 }' </dev/null >/dev/null 2>&1; then
              exit 70
            fi
            umask 077
            ssh_dir=${'$'}HOME/.ssh
            authorized_keys=${'$'}ssh_dir/authorized_keys
            if [ -L "${'$'}ssh_dir" ] ||
              { [ -e "${'$'}ssh_dir" ] && [ ! -d "${'$'}ssh_dir" ]; }; then
              exit 73
            fi
            mkdir -p "${'$'}ssh_dir"
            if [ -L "${'$'}ssh_dir" ] || [ ! -d "${'$'}ssh_dir" ]; then
              exit 73
            fi
            chmod 700 "${'$'}ssh_dir"
            lock_dir=${'$'}ssh_dir/.agent-relay-authorized-keys.lock
            lock_owner_file=${'$'}lock_dir/owner
            lock_recovery_file=${'$'}lock_dir/recovery
            lock_recovery_dir=${'$'}ssh_dir/.agent-relay-authorized-keys.recovery.${'$'}${'$'}
            lock_observation_file=${'$'}ssh_dir/.agent-relay-authorized-keys.observation.${'$'}${'$'}
            lock_owner=
            lock_owned=0
            recovery_owned=0
            recovery_quarantined=0
            recovery_delete_allowed=0
            observation_owned=0
            observation_inode=
            uncertain_lock_state=
            uncertain_lock_observations=0
            # A hardlink pins the foreign marker inode across generations; ls -i remains
            # available on POSIX targets whose test utility does not implement -ef.
            read_regular_file_inode() {
              file_inode=
              if [ -L "${'$'}1" ] || [ ! -f "${'$'}1" ]; then
                return 1
              fi
              file_inode=${'$'}(
                LC_ALL=C ls -id "${'$'}1" 2>/dev/null | awk '{ print ${'$'}1; exit }'
              ) || return 1
              case "${'$'}file_inode" in
                ""|*[!0-9]*)
                  return 1
                  ;;
              esac
            }
            cleanup_observation() {
              if [ "${'$'}observation_owned" -eq 1 ]; then
                if read_regular_file_inode "${'$'}lock_observation_file" &&
                  [ "${'$'}file_inode" = "${'$'}observation_inode" ]; then
                  rm -f "${'$'}lock_observation_file" || return 1
                fi
              fi
              observation_owned=0
              observation_inode=
              return 0
            }
            read_lock_owner() {
              lock_owner=
              if [ -L "${'$'}lock_owner_file" ] || [ ! -f "${'$'}lock_owner_file" ]; then
                return 1
              fi
              IFS= read -r lock_owner < "${'$'}lock_owner_file" || return 1
              case "${'$'}lock_owner" in
                ""|*[!0-9]*)
                  return 1
                  ;;
              esac
            }
            owner_publication_failure_is_retryable() {
              if [ ! -e "${'$'}lock_dir" ] && [ ! -L "${'$'}lock_dir" ]; then
                return 0
              fi
              if [ -L "${'$'}lock_dir" ] || [ ! -d "${'$'}lock_dir" ]; then
                return 1
              fi
              if read_lock_owner; then
                if kill -0 "${'$'}lock_owner" 2>/dev/null; then
                  return 0
                fi
                return 1
              fi
              if [ -e "${'$'}lock_owner_file" ] || [ -L "${'$'}lock_owner_file" ]; then
                return 1
              fi
              if [ -L "${'$'}lock_recovery_file" ] || [ ! -f "${'$'}lock_recovery_file" ]; then
                return 1
              fi
              recovery_owner=
              IFS= read -r recovery_owner < "${'$'}lock_recovery_file" || return 1
              case "${'$'}recovery_owner" in
                ""|*[!0-9]*)
                  return 1
                  ;;
              esac
              if kill -0 "${'$'}recovery_owner" 2>/dev/null; then
                return 0
              fi
              return 1
            }
            recover_stale_lock() {
              confirmation_required=0
              if [ -L "${'$'}lock_dir" ] || [ ! -d "${'$'}lock_dir" ]; then
                cleanup_observation || :
                uncertain_lock_state=
                uncertain_lock_observations=0
                return 1
              fi
              if [ -L "${'$'}lock_owner_file" ] ||
                { [ -e "${'$'}lock_owner_file" ] && [ ! -f "${'$'}lock_owner_file" ]; }; then
                cleanup_observation || :
                uncertain_lock_state=
                uncertain_lock_observations=0
                return 1
              fi
              if read_lock_owner; then
                if kill -0 "${'$'}lock_owner" 2>/dev/null; then
                  cleanup_observation || :
                  uncertain_lock_state=
                  uncertain_lock_observations=0
                  return 1
                fi
                current_lock_state=owner:${'$'}lock_owner
              else
                confirmation_required=1
                if [ -e "${'$'}lock_owner_file" ]; then
                  current_lock_state=malformed:${'$'}lock_owner
                else
                  current_lock_state=ownerless
                fi
              fi
              if [ -e "${'$'}lock_recovery_dir" ] || [ -L "${'$'}lock_recovery_dir" ]; then
                cleanup_observation || :
                return 1
              fi
              recovery_marker_created=0
              observation_marker_created=0
              recovery_marker_foreign=0
              recovery_owner=
              if [ -e "${'$'}lock_recovery_file" ] || [ -L "${'$'}lock_recovery_file" ]; then
                if [ -L "${'$'}lock_recovery_file" ] || [ ! -f "${'$'}lock_recovery_file" ]; then
                  cleanup_observation || :
                  return 1
                fi
                IFS= read -r recovery_owner < "${'$'}lock_recovery_file" || :
                if [ "${'$'}recovery_owned" -ne 1 ] || [ "${'$'}recovery_owner" != "${'$'}${'$'}" ]; then
                  case "${'$'}recovery_owner" in
                    ""|*[!0-9]*)
                      ;;
                    *)
                      if kill -0 "${'$'}recovery_owner" 2>/dev/null; then
                        cleanup_observation || :
                        return 1
                      fi
                      ;;
                  esac
                  recovery_marker_foreign=1
                fi
              fi
              if [ ! -e "${'$'}lock_recovery_file" ] && [ ! -L "${'$'}lock_recovery_file" ]; then
                cleanup_observation || :
                recovery_owned=1
                if ! (set -C; printf '%s\n' "${'$'}${'$'}" > "${'$'}lock_recovery_file") 2>/dev/null; then
                  recovery_owned=0
                  return 1
                fi
                recovery_owner=${'$'}${'$'}
                recovery_marker_created=1
              fi
              if [ "${'$'}recovery_marker_foreign" -eq 1 ]; then
                if [ "${'$'}observation_owned" -eq 1 ]; then
                  observed_inode=${'$'}observation_inode
                  if ! read_regular_file_inode "${'$'}lock_observation_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_inode" ] ||
                    ! read_regular_file_inode "${'$'}lock_recovery_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_inode" ]; then
                    cleanup_observation || :
                    uncertain_lock_state=
                    uncertain_lock_observations=0
                  fi
                fi
                if [ "${'$'}observation_owned" -eq 0 ]; then
                  if [ -e "${'$'}lock_observation_file" ] ||
                    [ -L "${'$'}lock_observation_file" ] ||
                    ! read_regular_file_inode "${'$'}lock_recovery_file"; then
                    return 1
                  fi
                  if ! ln "${'$'}lock_recovery_file" "${'$'}lock_observation_file" 2>/dev/null; then
                    return 1
                  fi
                  if ! read_regular_file_inode "${'$'}lock_observation_file"; then
                    return 1
                  fi
                  observed_inode=${'$'}file_inode
                  if ! read_regular_file_inode "${'$'}lock_recovery_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_inode" ]; then
                    return 1
                  fi
                  observation_inode=${'$'}observed_inode
                  observation_owned=1
                  observation_marker_created=1
                fi
              else
                cleanup_observation || :
              fi
              observation_state=${'$'}current_lock_state:recovery:${'$'}recovery_owner
              if [ "${'$'}observation_owned" -eq 1 ]; then
                observation_state=${'$'}observation_state:inode:${'$'}observation_inode
              fi
              if [ "${'$'}confirmation_required" -eq 1 ]; then
                if [ "${'$'}recovery_marker_created" -eq 0 ] &&
                  [ "${'$'}observation_marker_created" -eq 0 ] &&
                  [ "${'$'}uncertain_lock_state" = "${'$'}observation_state" ]; then
                  uncertain_lock_observations=${'$'}((uncertain_lock_observations + 1))
                else
                  uncertain_lock_state=${'$'}observation_state
                  uncertain_lock_observations=1
                fi
                if [ "${'$'}uncertain_lock_observations" -lt 2 ]; then
                  return 1
                fi
              fi
              observed_lock_state=${'$'}current_lock_state
              observed_recovery_owner=${'$'}recovery_owner
              observed_recovery_inode=${'$'}observation_inode
              if read_lock_owner; then
                current_lock_state=owner:${'$'}lock_owner
              elif [ -e "${'$'}lock_owner_file" ]; then
                current_lock_state=malformed:${'$'}lock_owner
              else
                current_lock_state=ownerless
              fi
              recovery_owner=
              recovery_marker_invalid=0
              if [ -L "${'$'}lock_recovery_file" ] || [ ! -f "${'$'}lock_recovery_file" ]; then
                recovery_marker_invalid=1
              else
                IFS= read -r recovery_owner < "${'$'}lock_recovery_file" || :
              fi
              recovery_quarantined=1
              if [ "${'$'}current_lock_state" != "${'$'}observed_lock_state" ] ||
                [ "${'$'}recovery_marker_invalid" -ne 0 ] ||
                [ "${'$'}recovery_owner" != "${'$'}observed_recovery_owner" ] ||
                { [ "${'$'}observation_owned" -eq 1 ] &&
                  { ! read_regular_file_inode "${'$'}lock_observation_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_recovery_inode" ] ||
                    ! read_regular_file_inode "${'$'}lock_recovery_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_recovery_inode" ]; }; } ||
                ! mv "${'$'}lock_dir" "${'$'}lock_recovery_dir" 2>/dev/null; then
                if [ "${'$'}recovery_owned" -eq 1 ] &&
                  IFS= read -r recovery_owner < "${'$'}lock_recovery_file" &&
                  [ "${'$'}recovery_owner" = "${'$'}${'$'}" ]; then
                  rm -f "${'$'}lock_recovery_file" || :
                fi
                recovery_owned=0
                recovery_quarantined=0
                cleanup_observation || :
                return 1
              fi
              lock_owner_file=${'$'}lock_recovery_dir/owner
              if read_lock_owner; then
                moved_lock_state=owner:${'$'}lock_owner
              elif [ -e "${'$'}lock_owner_file" ]; then
                moved_lock_state=malformed:${'$'}lock_owner
              else
                moved_lock_state=ownerless
              fi
              lock_owner_file=${'$'}lock_dir/owner
              recovery_owner=
              recovery_marker_invalid=0
              if [ -L "${'$'}lock_recovery_dir/recovery" ] ||
                [ ! -f "${'$'}lock_recovery_dir/recovery" ]; then
                recovery_marker_invalid=1
              else
                IFS= read -r recovery_owner < "${'$'}lock_recovery_dir/recovery" || :
              fi
              if [ "${'$'}moved_lock_state" != "${'$'}observed_lock_state" ] ||
                [ "${'$'}recovery_marker_invalid" -ne 0 ] ||
                [ "${'$'}recovery_owner" != "${'$'}observed_recovery_owner" ] ||
                { [ "${'$'}observation_owned" -eq 1 ] &&
                  { ! read_regular_file_inode "${'$'}lock_observation_file" ||
                    [ "${'$'}file_inode" != "${'$'}observed_recovery_inode" ] ||
                    ! read_regular_file_inode "${'$'}lock_recovery_dir/recovery" ||
                    [ "${'$'}file_inode" != "${'$'}observed_recovery_inode" ]; }; }; then
                if [ "${'$'}recovery_owned" -eq 1 ] &&
                  [ "${'$'}recovery_owner" = "${'$'}${'$'}" ]; then
                  rm -f "${'$'}lock_recovery_dir/recovery" || :
                fi
                recovery_owned=0
                cleanup_observation || :
                return 1
              fi
              recovery_delete_allowed=1
              rm -f "${'$'}lock_recovery_dir/owner" || return 1
              rm -f "${'$'}lock_recovery_dir/recovery" || return 1
              cleanup_observation || return 1
              rmdir "${'$'}lock_recovery_dir" 2>/dev/null || return 1
              recovery_owned=0
              recovery_quarantined=0
              recovery_delete_allowed=0
              uncertain_lock_state=
              uncertain_lock_observations=0
              return 0
            }
            cleanup_recovery() {
              if [ "${'$'}recovery_owned" -eq 1 ]; then
                for recovery_path in "${'$'}lock_dir" "${'$'}lock_recovery_dir"; do
                  recovery_marker=${'$'}recovery_path/recovery
                  recovery_owner=
                  if [ ! -L "${'$'}recovery_marker" ] && [ -f "${'$'}recovery_marker" ]; then
                    IFS= read -r recovery_owner < "${'$'}recovery_marker" || :
                  fi
                  if [ "${'$'}recovery_owner" = "${'$'}${'$'}" ]; then
                    if [ "${'$'}recovery_path" = "${'$'}lock_recovery_dir" ] &&
                      [ "${'$'}recovery_delete_allowed" -eq 1 ]; then
                      rm -f "${'$'}recovery_path/owner" || :
                    fi
                    rm -f "${'$'}recovery_marker" || :
                    if [ "${'$'}recovery_path" = "${'$'}lock_recovery_dir" ] &&
                      [ "${'$'}recovery_delete_allowed" -eq 1 ]; then
                      rmdir "${'$'}recovery_path" 2>/dev/null || :
                    fi
                  fi
                done
              fi
            }
            cleanup_lock() {
              cleanup_recovery
              cleanup_observation || :
              if [ "${'$'}lock_owned" -eq 1 ]; then
                if read_lock_owner && [ "${'$'}lock_owner" = "${'$'}${'$'}" ]; then
                  rm -f "${'$'}lock_owner_file" || :
                  rmdir "${'$'}lock_dir" 2>/dev/null || :
                fi
              fi
            }
            trap cleanup_lock 0
            trap 'exit 74' 1 2 15
            lock_attempt=0
            # Two generation observations and one owner/recovery collision can consume five waits.
            lock_attempt_limit=8
            while :; do
              lock_recovered=0
              if mkdir "${'$'}lock_dir" 2>/dev/null; then
                lock_owned=1
                if ! (set -C; printf '%s\n' "${'$'}${'$'}" > "${'$'}lock_owner_file") 2>/dev/null; then
                  lock_owned=0
                  if ! owner_publication_failure_is_retryable; then
                    exit 75
                  fi
                elif ! read_lock_owner || [ "${'$'}lock_owner" != "${'$'}${'$'}" ]; then
                  lock_owned=0
                  if ! owner_publication_failure_is_retryable; then
                    exit 75
                  fi
                elif [ ! -e "${'$'}lock_recovery_file" ] && [ ! -L "${'$'}lock_recovery_file" ]; then
                  break
                else
                  if ! read_lock_owner || [ "${'$'}lock_owner" != "${'$'}${'$'}" ]; then
                    exit 75
                  fi
                  rm -f "${'$'}lock_owner_file" || exit 75
                  lock_owned=0
                fi
              elif recover_stale_lock; then
                lock_recovered=1
              fi
              lock_attempt=${'$'}((lock_attempt + 1))
              if [ "${'$'}lock_attempt" -ge "${'$'}lock_attempt_limit" ]; then
                exit 75
              fi
              if [ "${'$'}lock_recovered" -eq 1 ]; then
                continue
              fi
              sleep 1
            done
            if [ -L "${'$'}authorized_keys" ] ||
              { [ -e "${'$'}authorized_keys" ] && [ ! -f "${'$'}authorized_keys" ]; }; then
              exit 73
            fi
            if [ ! -e "${'$'}authorized_keys" ]; then
              (set -C; : > "${'$'}authorized_keys") 2>/dev/null || exit 73
            fi
            if [ -L "${'$'}authorized_keys" ] || [ ! -f "${'$'}authorized_keys" ]; then
              exit 73
            fi
            chmod 600 "${'$'}authorized_keys"
            if ! match=${'$'}(
              awk -v target_algorithm='__AGENT_RELAY_ALGORITHM__' -v target_blob='__AGENT_RELAY_BLOB__' '
                function valid_option(option, equals_at, name, value, normalized_name) {
                  normalized_name = tolower(option)
                  if (normalized_name == "cert-authority") {
                    return 2
                  }
                  if (normalized_name == "agent-forwarding" ||
                      normalized_name == "no-agent-forwarding" ||
                      normalized_name == "no-port-forwarding" ||
                      normalized_name == "no-pty" ||
                      normalized_name == "no-touch-required" ||
                      normalized_name == "no-user-rc" ||
                      normalized_name == "no-x11-forwarding" ||
                      normalized_name == "port-forwarding" ||
                      normalized_name == "pty" ||
                      normalized_name == "restrict" ||
                      normalized_name == "user-rc" ||
                      normalized_name == "verify-required" ||
                      normalized_name == "x11-forwarding") {
                    return 1
                  }
                  equals_at = index(option, "=")
                  if (equals_at == 0) {
                    return 0
                  }
                  name = tolower(substr(option, 1, equals_at - 1))
                  value = substr(option, equals_at + 1)
                  if (!(name == "command" ||
                        name == "environment" ||
                        name == "expiry-time" ||
                        name == "from" ||
                        name == "permitlisten" ||
                        name == "permitopen" ||
                        name == "principals" ||
                        name == "tunnel")) {
                    return 0
                  }
                  if (length(value) < 2 ||
                      substr(value, 1, 1) != "\"" ||
                      substr(value, length(value), 1) != "\"") {
                    return 0
                  }
                  return name == "principals" ? 3 : 1
                }
                function valid_options(options, position, character, quoted, escaped, option, status, has_ca, has_principals) {
                  quoted = 0
                  escaped = 0
                  option = ""
                  has_ca = 0
                  has_principals = 0
                  for (position = 1; position <= length(options); position++) {
                    character = substr(options, position, 1)
                    if (escaped) {
                      option = option character
                      escaped = 0
                    } else if (quoted && character == "\\") {
                      option = option character
                      escaped = 1
                    } else if (character == "\"") {
                      option = option character
                      quoted = !quoted
                    } else if (!quoted && character == ",") {
                      status = valid_option(option)
                      if (status == 0) {
                        return 0
                      }
                      has_ca = has_ca || status == 2
                      has_principals = has_principals || status == 3
                      option = ""
                    } else {
                      option = option character
                    }
                  }
                  if (quoted || escaped || length(option) == 0) {
                    return 0
                  }
                  status = valid_option(option)
                  if (status == 0) {
                    return 0
                  }
                  has_ca = has_ca || status == 2
                  has_principals = has_principals || status == 3
                  if (has_ca) {
                    return 2
                  }
                  return has_principals ? 0 : 1
                }
                function contains_target(line, fields, field_count, position, character, quoted, escaped, field) {
                  field_count = 0
                  field = ""
                  quoted = 0
                  escaped = 0
                  for (position = 1; position <= length(line); position++) {
                    character = substr(line, position, 1)
                    if (escaped) {
                      field = field character
                      escaped = 0
                    } else if (quoted && character == "\\") {
                      field = field character
                      escaped = 1
                    } else if (character == "\"") {
                      field = field character
                      quoted = !quoted
                    } else if (!quoted && (character == " " || character == "\t" || character == "\r")) {
                      if (length(field) > 0) {
                        fields[++field_count] = field
                        field = ""
                      }
                    } else {
                      field = field character
                    }
                  }
                  if (length(field) > 0) {
                    fields[++field_count] = field
                  }
                  if (field_count == 0 || substr(fields[1], 1, 1) == "#") {
                    return 0
                  }
                  if (field_count >= 2 &&
                      fields[1] == target_algorithm && fields[2] == target_blob) {
                    return 1
                  }
                  if (field_count >= 3 &&
                      fields[2] == target_algorithm && fields[3] == target_blob) {
                    option_status = valid_options(fields[1])
                    if (option_status == 1) {
                      return 1
                    }
                    if (option_status == 0) {
                      ambiguous = 1
                    }
                  }
                  return 0
                }
                contains_target(${'$'}0) { found = 1; exit }
                END { print found ? "found" : (ambiguous ? "ambiguous" : "absent") }
              ' "${'$'}authorized_keys"
            ); then
              exit 70
            fi
            case "${'$'}match" in
              found)
                ;;
              absent)
                if [ -L "${'$'}authorized_keys" ] || [ ! -f "${'$'}authorized_keys" ]; then
                  exit 73
                fi
                printf '\n%s %s\n' '__AGENT_RELAY_ALGORITHM__' '__AGENT_RELAY_BLOB__' >> "${'$'}authorized_keys"
                ;;
              *)
                exit 70
                ;;
            esac
    """.trimIndent()

    private val NORMALIZED_KEY = Regex("[A-Za-z0-9@._+-]{1,128} [A-Za-z0-9+/]+={0,2}")
    private const val ALGORITHM_TOKEN = "__AGENT_RELAY_ALGORITHM__"
    private const val BLOB_TOKEN = "__AGENT_RELAY_BLOB__"
}
