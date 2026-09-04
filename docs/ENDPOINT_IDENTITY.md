# Endpoint identity contract

Agent Relay identifies a daemon or client by the SHA-256 digest of its Ed25519
public signing key. The wire form is `ari_v1_` followed by 43 unpadded
base64url characters. It is opaque and deliberately excludes hostnames,
addresses, paths, account names, credentials, labels, process IDs and transport
details. A path change therefore does not create a new endpoint identity.

The durable record contains the endpoint role, public key, key version,
creation time and key state. Private key material is owned by the platform key
store and is never represented in a connection contract, log, event, QR payload
or diagnostic projection. The identity must be recomputed from the presented
public key before it is trusted.

## Rotation and recovery

Planned or expiring-key rotation advances the key version and requires a signed
continuity statement binding the previous identity, replacement identity,
version and authenticated transcript. The client keeps the previous pin until
the replacement is durably committed; a changed identity is never accepted
implicitly. A compromise recovery intentionally cannot use the old key's
continuity proof: it retires the compromised record, creates a fresh key and
requires explicit re-pairing or an independent recovery authority. Grants and
revocations are evaluated separately by the pairing policy.

If either side loses the replacement before durable commit, it retains the old
pin and retries the rotation ceremony. If the old key is unavailable or the
rotation proof is malformed, the connection remains blocked and asks for a new
pairing. Transport failover and process restart preserve the stable identity;
they do not reset trust or advance a key version.

## Threat analysis

| Threat | Contract response |
| --- | --- |
| Address, host or path tracking through identifiers | Identity is a fixed-length digest of public key bytes only. |
| Stolen or replayed discovery/transport metadata | Discovery is candidate data; every path must prove the pinned key. |
| Silent man-in-the-middle key substitution | Digest mismatch requires an explicit trust decision; planned changes require continuity proof. |
| Replay of an old rotation | Key versions must advance and the authenticated transcript binds the replacement; stale records are rejected by the pairing layer. |
| Compromised old signing key | Recovery cannot carry an old-key proof and must use explicit re-pairing or independent authority. |
| Malformed, oversized or algorithm-downgrade key | Only canonical Ed25519 public keys and bounded version/signature fields are admitted. |
| Leakage through logs or diagnostics | Only the opaque identity and explicitly redacted public metadata may cross the API boundary. |
| Process restart or transport failover | Identity is durable endpoint state, independent of implementation process and route. |

This contract does not itself authorize a device or grant access. Pairing,
scope, expiry and revocation remain the policy authority's responsibility.
