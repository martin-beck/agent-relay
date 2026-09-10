------------------------ MODULE CrossDeviceContinuity ------------------------
\* Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
\* SPDX-License-Identifier: MIT

EXTENDS Naturals

CONSTANTS Devices, Revoked
VARIABLES authority, sequence, acknowledged, state

Init ==
  /\ authority = 1
  /\ sequence = 0
  /\ acknowledged = [d \in Devices |-> 0]
  /\ state = [d \in Devices |-> "TRUSTED"]

Publish == sequence' = sequence + 1 /\ UNCHANGED <<authority, acknowledged, state>>

AcceptAcknowledgement(d, cursor) ==
  /\ state[d] # "REVOKED"
  /\ cursor <= sequence
  /\ cursor >= acknowledged[d]
  /\ acknowledged' = [acknowledged EXCEPT ![d] = cursor]
  /\ UNCHANGED <<authority, sequence, state>>

Revoke(d) ==
  /\ state' = [state EXCEPT ![d] = "REVOKED"]
  /\ UNCHANGED <<authority, sequence, acknowledged>>

CursorMonotonic == \A d \in Devices : acknowledged'[d] >= acknowledged[d]
RevocationSafety == \A d \in Revoked : state[d] = "REVOKED"

=============================================================================
