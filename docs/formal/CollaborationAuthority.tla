------------------------ MODULE CollaborationAuthority ------------------------
EXTENDS Naturals

CONSTANTS Actors, Capabilities
VARIABLES revision, leases, audit

Init ==
  /\ revision = 0
  /\ leases = [a \in Actors |-> "ACTIVE"]
  /\ audit = << >>

Apply(expected) ==
  /\ expected = revision
  /\ revision' = revision + 1
  /\ audit' = Append(audit, [revision |-> revision + 1])
  /\ UNCHANGED leases

Revoke(actor) ==
  /\ leases' = [leases EXCEPT ![actor] = "REVOKED"]
  /\ UNCHANGED <<revision, audit>>

CompareAndSet == revision' >= revision
AuditMonotonic == Len(audit') >= Len(audit)
RevocationFailClosed == \A a \in Actors : leases[a] = "REVOKED" => leases'[a] = "REVOKED"

=============================================================================
