----------------------------- MODULE ResourceBudget -----------------------------
EXTENDS Naturals, FiniteSets

CONSTANTS Resources, Limits
VARIABLES usage, reservations, stopped

Init ==
  /\ usage = [r \in Resources |-> 0]
  /\ reservations = {}
  /\ stopped = FALSE

ReservationFits(estimate) ==
  ~stopped /\ \A r \in Resources : usage[r] + estimate[r] <= Limits[r]

Reconcile(report) ==
  usage' = [r \in Resources |-> Max(usage[r], report[r])]
  /\ reservations' = {}
  /\ stopped' = stopped \/ \E r \in Resources : report[r] > Limits[r]

UsageMonotonic == \A r \in Resources : usage'[r] >= usage[r]
HardStopIsTerminal == stopped => stopped'

=============================================================================
