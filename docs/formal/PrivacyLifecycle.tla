--------------------------- MODULE PrivacyLifecycle ---------------------------
EXTENDS Naturals

CONSTANTS Records
VARIABLES state, retention, credentialVersion, audit

Init ==
  /\ state = [r \in Records |-> "LIVE"]
  /\ retention = [r \in Records |-> 0]
  /\ credentialVersion = [r \in Records |-> 0]
  /\ audit = << >>

Expire(r, now) ==
  /\ now >= retention[r]
  /\ state' = [state EXCEPT ![r] = "DELETED"]
  /\ audit' = Append(audit, [record |-> r, action |-> "expire"])
  /\ UNCHANGED <<retention, credentialVersion>>

Rotate(r, next) ==
  /\ next > credentialVersion[r]
  /\ credentialVersion' = [credentialVersion EXCEPT ![r] = next]
  /\ audit' = Append(audit, [record |-> r, action |-> "rotate"])
  /\ UNCHANGED <<state, retention>>

DeletionTerminal == \A r \in Records : state[r] = "DELETED" => state'[r] = "DELETED"
CredentialMonotonic == \A r \in Records : credentialVersion'[r] >= credentialVersion[r]
AuditMonotonic == Len(audit') >= Len(audit)

=============================================================================
