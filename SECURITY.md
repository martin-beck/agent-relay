# Security policy

## Supported versions

Agent Relay has no supported release. The current development APK is incomplete
and must not be used as a trusted production control surface.

| Version | Supported |
| --- | --- |
| Development branch | Security fixes are considered |
| APK artifacts | No support guarantee |
| Public releases | None |

## Report a vulnerability

Do not open a public issue or include sensitive details in a pull request. An
invited collaborator should use a
[private security advisory](https://github.com/martin-beck/agent-relay/security/advisories/new)
for the repository. Include the affected commit, impact, reproduction steps, and
a minimal redacted proof of concept.

Do not include real credentials, private keys, tokens, host details, transcripts,
or production data. If a secret may have been exposed, rotate it outside this
repository before continuing the report.

## Security model

Agent Relay treats these as distinct trust boundaries:

- the Android application sandbox and Android Keystore;
- each connection provider and connection profile;
- SSH server identity, remote account, and remote workspace;
- local app-controlled working roots;
- each agent provider's protocol and advertised capabilities; and
- user approval decisions.

The project requires explicit SSH host-key trust, encrypted namespaced
persistence, redacted diagnostics, bounded streams and protocol lines, workspace
path confinement, and no secret-bearing process arguments.

## Known development gaps

Before a supported release, the project still needs:

- integration of the security-reviewed modules into the Android application;
- real-device Android Keystore and lifecycle evidence;
- a stable application ID and final backup/data-extraction policy review;
- production signing and secret handling outside the repository;
- adaptive UI review for identity, approval, and error presentation; and
- end-to-end threat modeling and release privacy review.

Android backup is disabled fail-closed while encrypted connection and session
state are under development. Re-enabling any backup or device-transfer surface
requires a threat-model and data-classification review.
