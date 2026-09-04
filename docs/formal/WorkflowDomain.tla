---- MODULE WorkflowDomain ----
EXTENDS Naturals, Sequences

(***************************************************************************
 * Opaque finite domain model. Constants are supplied by the model checker.
 * Safety properties are intentionally independent of implementation names.
 ***************************************************************************)
CONSTANTS Tasks, Runs, Steps, Events, AttentionItems, Projections, Actors
TaskStates, RunStates, StepStates, AttentionStates

ASSUME Cardinality(Tasks) <= 2 /\ Cardinality(Runs) <= 2 /\
       Cardinality(Steps) <= 2 /\ Cardinality(Events) <= 4 /\
       Cardinality(AttentionItems) <= 2 /\ Cardinality(Projections) <= 2

VARIABLES taskState, runState, stepState, lease, leaseAttempt,
          eventSequence, eventCursor, attentionState, projectionSequence,
          taskRevision, runRevision

vars == <<taskState, runState, stepState, lease, leaseAttempt,
           eventSequence, eventCursor, attentionState, projectionSequence,
           taskRevision, runRevision>>

TypeOK ==
  /\ taskState \in [Tasks -> TaskStates]
  /\ runState \in [Runs -> RunStates]
  /\ stepState \in [Steps -> StepStates]
  /\ lease \in [Steps -> (BOOLEAN)]
  /\ leaseAttempt \in [Steps -> Nat]
  /\ eventSequence \in [Events -> Nat]
  /\ eventCursor \in [Events -> Nat]
  /\ attentionState \in [AttentionItems -> AttentionStates]
  /\ projectionSequence \in [Projections -> Nat]
  /\ taskRevision \in [Tasks -> Nat]
  /\ runRevision \in [Runs -> Nat]

(* Every active lease has exactly one positive attempt and no terminal step
   may retain a lease. *)
LeaseSafety == \A s \in Steps : lease[s] => leaseAttempt[s] > 0 /\
  stepState[s] \notin {"SUCCEEDED", "FAILED", "CANCELLED"}

(* A cursor never moves backwards and a projection never observes a future
   event. *)
OrderingSafety == \A e \in Events : eventCursor[e] <= eventSequence[e]

ProjectionSafety == \A p \in Projections : projectionSequence[p] <=
  Max({eventSequence[e] : e \in Events})

(* Revisions are monotonic and therefore stale writers cannot be accepted by
   a serialized implementation. *)
RevisionSafety == (\A t \in Tasks : taskRevision[t] >= 0) /\
                  (\A r \in Runs : runRevision[r] >= 0)

AttentionSafety == \A a \in AttentionItems :
  attentionState[a] \in {"OPEN", "SNOOZED", "ESCALATED", "RESOLVED",
                          "INVALIDATED", "EXPIRED"}

Invariant == TypeOK /\ LeaseSafety /\ OrderingSafety /\ ProjectionSafety /\
             RevisionSafety /\ AttentionSafety

Init == Invariant

Next == \/ UNCHANGED vars

====
