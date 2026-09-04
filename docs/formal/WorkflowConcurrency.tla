---- MODULE WorkflowConcurrency ----
EXTENDS Naturals, Sequences

(***************************************************************************
 * Bounded concurrency/recovery companion model.  Worker and session names
 * are opaque; the Python verifier executes the same finite fault relation.
 ***************************************************************************)
CONSTANTS Steps, Workers, Attempts, JournalPositions
VARIABLES runState, leaseOwner, attemptNumber, effectState, journalSequence,
          checkpointSequence, projectionSequence

vars == <<runState, leaseOwner, attemptNumber, effectState, journalSequence,
           checkpointSequence, projectionSequence>>

TypeOK == /\ runState \in {"QUEUED", "RUNNING", "UNCERTAIN"}
          /\ leaseOwner \in [Steps -> (Workers \cup {"NONE"})]
          /\ attemptNumber \in [Steps -> Attempts]
          /\ effectState \in [Steps -> {"NONE", "COMPLETED", "UNCERTAIN"}]
          /\ journalSequence \in JournalPositions
          /\ checkpointSequence \in JournalPositions
          /\ projectionSequence \in JournalPositions

ConcurrencySafety == Cardinality({s \in Steps : leaseOwner[s] # "NONE"}) <= Cardinality(Workers)
LeaseFencing == \A s \in Steps : effectState[s] = "UNCERTAIN" => leaseOwner[s] = "NONE"
RecoverySafety == \A s \in Steps : attemptNumber[s] <= 2
OrderingSafety == checkpointSequence <= journalSequence /\ projectionSequence <= journalSequence
UncertainSafety == runState = "UNCERTAIN" => \A s \in Steps : effectState[s] # "COMPLETED"
Invariant == TypeOK /\ ConcurrencySafety /\ LeaseFencing /\ RecoverySafety /\ OrderingSafety /\ UncertainSafety
Init == Invariant
Next == \/ UNCHANGED vars
====
