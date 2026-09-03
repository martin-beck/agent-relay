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
            lock_owner=
            lock_owned=0
            recovery_owned=0
            recovery_quarantined=0
            recovery_delete_allowed=0
            uncertain_lock_state=
            uncertain_lock_observations=0
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
            recover_stale_lock() {
              if [ -L "${'$'}lock_dir" ] || [ ! -d "${'$'}lock_dir" ]; then
                uncertain_lock_state=
                uncertain_lock_observations=0
                return 1
              fi
              if [ -L "${'$'}lock_owner_file" ] ||
                { [ -e "${'$'}lock_owner_file" ] && [ ! -f "${'$'}lock_owner_file" ]; }; then
                uncertain_lock_state=
                uncertain_lock_observations=0
                return 1
              fi
              if read_lock_owner; then
                if kill -0 "${'$'}lock_owner" 2>/dev/null; then
                  uncertain_lock_state=
                  uncertain_lock_observations=0
                  return 1
                fi
                current_lock_state=owner:${'$'}lock_owner
              else
                if [ -e "${'$'}lock_owner_file" ]; then
                  current_lock_state=malformed:${'$'}lock_owner
                else
                  current_lock_state=ownerless
                fi
                if [ "${'$'}uncertain_lock_state" = "${'$'}current_lock_state" ]; then
                  uncertain_lock_observations=${'$'}((uncertain_lock_observations + 1))
                else
                  uncertain_lock_state=${'$'}current_lock_state
                  uncertain_lock_observations=1
                fi
                if [ "${'$'}uncertain_lock_observations" -lt 2 ]; then
                  return 1
                fi
              fi
              if [ -e "${'$'}lock_recovery_dir" ] || [ -L "${'$'}lock_recovery_dir" ]; then
                return 1
              fi
              if [ -e "${'$'}lock_recovery_file" ] || [ -L "${'$'}lock_recovery_file" ]; then
                recovery_owner=
                if [ -L "${'$'}lock_recovery_file" ] || [ ! -f "${'$'}lock_recovery_file" ]; then
                  return 1
                fi
                IFS= read -r recovery_owner < "${'$'}lock_recovery_file" || :
                case "${'$'}recovery_owner" in
                  ""|*[!0-9]*)
                    ;;
                  *)
                    if kill -0 "${'$'}recovery_owner" 2>/dev/null; then
                      return 1
                    fi
                    ;;
                esac
                rm -f "${'$'}lock_recovery_file" || return 1
              fi
              recovery_owned=1
              if ! (set -C; printf '%s\n' "${'$'}${'$'}" > "${'$'}lock_recovery_file") 2>/dev/null; then
                recovery_owned=0
                return 1
              fi
              observed_lock_state=${'$'}current_lock_state
              if read_lock_owner; then
                current_lock_state=owner:${'$'}lock_owner
              elif [ -e "${'$'}lock_owner_file" ]; then
                current_lock_state=malformed:${'$'}lock_owner
              else
                current_lock_state=ownerless
              fi
              recovery_owner=
              recovery_quarantined=1
              if [ "${'$'}current_lock_state" != "${'$'}observed_lock_state" ] ||
                ! IFS= read -r recovery_owner < "${'$'}lock_recovery_file" ||
                [ "${'$'}recovery_owner" != "${'$'}${'$'}" ] ||
                ! mv "${'$'}lock_dir" "${'$'}lock_recovery_dir" 2>/dev/null; then
                if IFS= read -r recovery_owner < "${'$'}lock_recovery_file" &&
                  [ "${'$'}recovery_owner" = "${'$'}${'$'}" ]; then
                  rm -f "${'$'}lock_recovery_file" || :
                fi
                recovery_owned=0
                recovery_quarantined=0
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
              IFS= read -r recovery_owner < "${'$'}lock_recovery_dir/recovery" || :
              if [ "${'$'}moved_lock_state" != "${'$'}observed_lock_state" ] ||
                [ "${'$'}recovery_owner" != "${'$'}${'$'}" ]; then
                if [ "${'$'}recovery_owner" = "${'$'}${'$'}" ]; then
                  rm -f "${'$'}lock_recovery_dir/recovery" || :
                fi
                recovery_owned=0
                return 1
              fi
              recovery_delete_allowed=1
              rm -f "${'$'}lock_recovery_dir/owner" || return 1
              rm -f "${'$'}lock_recovery_dir/recovery" || return 1
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
            until mkdir "${'$'}lock_dir" 2>/dev/null; do
              if recover_stale_lock; then
                continue
              fi
              lock_attempt=${'$'}((lock_attempt + 1))
              if [ "${'$'}lock_attempt" -ge 5 ]; then
                exit 75
              fi
              sleep 1
            done
            lock_owned=1
            if ! (set -C; printf '%s\n' "${'$'}${'$'}" > "${'$'}lock_owner_file") 2>/dev/null; then
              exit 75
            fi
            if ! read_lock_owner || [ "${'$'}lock_owner" != "${'$'}${'$'}" ] ||
              [ -e "${'$'}lock_recovery_file" ] || [ -L "${'$'}lock_recovery_file" ]; then
              exit 75
            fi
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
