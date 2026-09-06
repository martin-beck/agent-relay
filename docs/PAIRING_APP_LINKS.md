# Verified pairing App Links

Camera QR readers may recognize a pairing code as an HTTPS link. Agent Relay
accepts only the verified `https://pair.agentrelay.dev/v1/pair` App Link. The
link contains a short-lived grant reference, daemon identity, nonce, audience,
expiry and daemon signature; it never contains credentials, private keys,
workflow content, or raw transport credentials.

The Android intent filter uses `android:autoVerify="true"`. The pairing domain
must publish an Android Digital Asset Links statement for the exact release
certificate before a production build is distributed. Verification is still
performed by the application: wrong hosts, non-HTTPS schemes, malformed or
duplicate fields, expired or replayed grants, identity mismatches and invalid
signatures fail closed before confirmation or persistence.

After signature verification, the grant is resolved from the encrypted
application-private store. The existing enrollment path performs the identity,
capability and connectivity probes, commits the encrypted profile atomically,
and consumes the nonce only after all probes pass. A failed probe rolls back
the transaction and permits a deterministic retry. Browser fallback may explain
how to return to the app, but it never enrolls a connection.

Never log the URI, grant reference, signature, route, credentials or protected
payload. Emulator evidence must use synthetic grants and record only the
accepted/rejected outcome and redacted daemon identity.
