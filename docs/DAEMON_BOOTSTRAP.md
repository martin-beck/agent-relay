# User-space daemon bootstrap

The supported host entry point is `scripts/daemon/bootstrap.sh`. It is designed
for a user-owned Linux installation and never invokes `sudo` or a distribution
package manager.

1. Preview the signed release manifest:

   ```sh
   scripts/daemon/bootstrap.sh preview --manifest release.manifest
   ```

2. After reviewing the destination, version, architecture, service mode and
   rollback policy, install with an explicit consent flag:

   ```sh
   AGENT_RELAY_TRUSTED_KEY=release-public.pem \
     scripts/daemon/bootstrap.sh install --manifest release.manifest \
     --bundle daemon.tar.gz --yes
   ```

The manifest signature is verified with the pinned public key, then the archive
size, SHA-256, architecture, traversal and link contents are checked before
anything is installed. Versions live below `${XDG_DATA_HOME:-~/.local/share}/agent-relay`.
Installation switches the `current` link atomically and retains `previous` as
the last-known-good version. `start`, `stop`, and `status` use a systemd user
service when available and otherwise a bounded user-owned foreground process.

`rollback` switches back to `previous`; `uninstall` stops the process and removes
only the user-owned Agent Relay directory. `pair` consumes a daemon-produced
short-lived code once and prints only a short authentication phrase; it does not
log the QR payload, route, credentials, or private material.

The host slice is transport- and distro-capability based. A release must publish
separate signed manifests for Debian/Ubuntu, Fedora/RHEL-compatible, Arch-based,
and openEuler targets on x86_64 and arm64/aarch64. Android profile persistence,
reciprocal QR confirmation, and connected-device evidence are separate adapters;
they must validate identity, capability, health and expiry before publishing a
profile to the session hub.
