# WorkBuddy Local Assistant provider

Agent Relay includes an implemented WorkBuddy Local Assistant adapter for the
documented Open API v2 boundary. The module is not registered in the Android
application graph, so this publication does not claim an app workflow or generic
WorkBuddy agent control. The [versioned contract](contracts/workbuddy-provider-v1.json)
and capability registry, `config/capability-status-registry.json`, are the
machine authorities for the generated status below.

<!-- generated:workbuddy-capabilities:start -->
Registry status: **implemented**, **not exposed in the Android app**.

| Capability | Published status | Relay mapping | Failure boundary |
| --- | --- | --- | --- |
| online-status | supported | SESSION_DISCOVERY | Authentication, authorization, offline, and unavailable outcomes remain explicit and fail closed. |
| text-submission | supported | SESSION_START | Non-text, permission_response, repeated unknown-delivery, working-directory, model, and provider session options are unsupported. |
| history-polling | supported | SESSION_HISTORY | Unbounded, malformed, duplicate, attachment-bearing, or non-text history fails closed. |
| session-identity | unsupported | SESSION_RESUME | UNSUPPORTED; do not derive identity from content or timestamps. |
| live-streaming | unsupported | LIVE_STREAMING | UNSUPPORTED. |
| actions-and-approvals | unsupported | APPROVALS | UNSUPPORTED; effects fail closed. |
| cancellation | unsupported | TURN_INTERRUPT | UNKNOWN_OUTCOME; never replay automatically. |
| file-changes | unsupported | FILE_CHANGES | UNSUPPORTED; never infer changes from message text. |

Registry limitations:

- The Android application graph does not expose this provider.
- Only synthetic Open API v2 JVM-adapter behavior is verified; no live WorkBuddy account, credential, message, network request, alternate API version, or other device/platform combination was exercised.
<!-- generated:workbuddy-capabilities:end -->

## Evidence boundary

The official WorkBuddy Open Platform documentation was retrieved on 2026-09-10.
The English Open API reference hash is
`sha256:94b236a4465e91d0a9c43117dd9582acbaf780a29543c9fd7896c32dd947d52e`, and
the third-party-app guide hash is
`sha256:70cb159236628cd43550c4ad872e02dfbcb0836f1834fbf87d0f61d010265a9b`.
Both document API version `/openapi/v2`, OAuth 2.1, and least-privilege scopes.

The send-scope spelling is `user.localassistant.invokable`, exactly as recorded
in the [reviewed source excerpt](contracts/evidence/workbuddy-openapi-send-scope-2026-09-10.json).
The original AR-2212 contract used that spelling before a terminology-only
follow-up changed it to `invocable`; the official page still uses `invokable`,
so this correction is a transcription repair rather than a capability change.

The implementation and conformance suites verify synthetic online status, text
message submission, and bounded history polling. They also cover authentication,
authorization, rate limits, offline transitions, duplicates and gaps, malformed
and oversized replies, hostile inputs, uncertain delivery, cancellation races,
cleanup, and privacy. They do not establish live service interoperability.

## Setup, authentication, and consent

The selected remote runtime needs Python 3 and outbound HTTPS access to the
fixed WorkBuddy Local Assistant endpoint. It must inject the approved token
through `AGENT_RELAY_WORKBUDDY_ACCESS_TOKEN`, the exact least-privilege scope
set through `AGENT_RELAY_WORKBUDDY_SCOPES`, and the literal
`AGENT_RELAY_WORKBUDDY_CONSENT=enabled` only after the user grants consent.
The token and prompt travel outside process arguments. Never place token values
in provider options, logs, durable events, fixtures, shell history, or support
output.

Read-only status and history need `user.localassistant.readable`. Text
submission additionally needs `user.localassistant.invokable`. Missing consent
or credentials reports authentication required; a missing approved scope reports
authorization denied. Do not broaden the scope set to make a failed probe pass.

The Android app cannot select this provider yet. Setup currently applies only to
an explicitly composed JVM runtime that includes `:provider:workbuddy`.

## Recovery behavior

- When WorkBuddy reports offline or cannot be reached, keep input disabled,
  repair the selected connection or service state, and refresh discovery.
- After authentication or authorization failure, renew the approved credential
  or consent outside Agent Relay, reconnect, and probe again. Never log the
  credential while diagnosing the failure.
- After a rate-limit response, wait for the provider limit to recover and refresh
  history before the user deliberately submits again.
- A timeout or cancellation after submission is `UNKNOWN_OUTCOME`. Refresh
  history before deciding whether to send a new message; Agent Relay never
  replays the original submission automatically.

## Unsupported operations

The generated matrix is authoritative. In particular, WorkBuddy does not expose
a stable external session identity, token streaming, tool or approval exchange,
provider-side turn cancellation, or a revisioned changed-file manifest. Start
options for a working directory, model, or provider session are rejected.
Attachments, non-text input, `permission_response`, active-turn steering,
forking, and inferred file changes also fail closed.

## Integration rules

- Agent Relay owns session identity, policy, approvals, persistence, leases,
  retries, cancellation, and completion.
- OAuth tokens remain approved-runtime secrets and never enter options,
  logs, events, fixtures, process arguments, or test output. Request only the
  local-assistant scopes needed by the operation:
  `user.localassistant.readable` for reads and
  `user.localassistant.invokable` for message submission.
- Only `msg_type=text` is documented. Non-text and `permission_response`
  messages fail closed before network I/O; tool approval remains with the
  local user until a compatible handshake is evidenced.
- History reads one bounded `limit`/`offset` page per relay request. Incremental
  message-ID queries and automatic multi-page traversal are unsupported. A
  post-submit timeout is `UNKNOWN_OUTCOME` and is never automatically replayed.
- Fixtures are synthetic and credential-free.

## Verification limit

AR-2213 implemented the adapter and AR-2214 validated it with deterministic
fixtures. No live WorkBuddy account, credential, protected message, network
request, alternate API version, or other device/platform combination was
exercised. The provider therefore remains not app-exposed, and this guide must
not be used as evidence of live interoperability or generic agent control.
