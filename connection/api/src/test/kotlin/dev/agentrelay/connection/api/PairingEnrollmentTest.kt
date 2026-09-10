/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingEnrollmentTest {
    private val identity = StableEndpointIdentity("ari_v1_" + "A".repeat(43))
    private val link = PairingAppLink("grant_12345", identity, "nonce_12345", 10_000, "QQ")

    @Test
    fun commitConsumesNonceAndDuplicateTapDoesNotCreateProfile() {
        var begins = 0
        var commits = 0
        val coordinator = PairingEnrollmentCoordinator(
            store = PairingEnrollmentStore {
                begins++
                object : PairingEnrollmentTransaction {
                    override fun commit() {
                        commits++
                    }
                    override fun rollback() = Unit
                }
            },
            profileFactory = PairingEnrollmentProfileFactory {
                PairingEnrollmentProfile(it.daemonIdentity, it.grantReference, "credential", "route", it.expiresAtMillis)
            },
            probe = PairingEnrollmentProbe { true },
        )
        assertTrue(coordinator.enroll(link))
        assertFalse(coordinator.enroll(link))
        assertTrue(begins == 1 && commits == 1)
    }

    @Test
    fun failedProbeRollsBackAndAllowsDeterministicRetry() {
        var probeCalls = 0
        var rollbacks = 0
        val coordinator = PairingEnrollmentCoordinator(
            store = PairingEnrollmentStore {
                object : PairingEnrollmentTransaction {
                    override fun commit() = Unit
                    override fun rollback() {
                        rollbacks++
                    }
                }
            },
            profileFactory = PairingEnrollmentProfileFactory {
                PairingEnrollmentProfile(it.daemonIdentity, it.grantReference, "credential", "route", it.expiresAtMillis)
            },
            probe = PairingEnrollmentProbe { ++probeCalls > 1 },
        )
        assertFalse(coordinator.enroll(link))
        assertTrue(coordinator.enroll(link))
        assertTrue(probeCalls == 2 && rollbacks == 1)
    }
}
