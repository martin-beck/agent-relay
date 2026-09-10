# WorkBuddy provider contract

Agent Relay records WorkBuddy as a contract-only provider until an adapter and
conformance evidence exist. The [versioned contract](contracts/workbuddy-provider-v1.json)
is the authoritative capability matrix.

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

The Local Assistant API documents online status, text message submission, and
bounded paginated or incremental history polling. It does not establish a
stable session identifier, streaming transport, cancellation endpoint,
approval handshake, or changed-file manifest. The adapter must not infer those
capabilities from product descriptions.

## Integration rules

- Agent Relay owns session identity, policy, approvals, persistence, leases,
  retries, cancellation, and completion.
- OAuth tokens are opaque approved-store references and never enter options,
  logs, events, fixtures, process arguments, or test output. Request only the
  local-assistant scopes needed by the operation:
  `user.localassistant.readable` for reads and
  `user.localassistant.invokable` for message submission.
- Only `msg_type=text` is documented. Non-text and `permission_response`
  messages fail closed before network I/O; tool approval remains with the
  local user until a compatible handshake is evidenced.
- History uses bounded pages or an incremental cursor. A post-submit timeout is
  `UNKNOWN_OUTCOME` and is never automatically replayed.
- Fixtures are synthetic and credential-free; live credentials and network
  calls are out of scope for this milestone.

AR-2213 may implement only verified capabilities. AR-2214 owns conformance
validation and AR-2215 owns publication.
