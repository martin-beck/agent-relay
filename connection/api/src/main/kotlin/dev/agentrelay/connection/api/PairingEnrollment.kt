/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

/** Encrypted profile material produced by the same enrollment path as an in-app scan. */
data class PairingEnrollmentProfile(
    val daemonIdentity: StableEndpointIdentity,
    val grantReference: String,
    val credentialReference: String,
    val routeReference: String,
    val expiresAtMillis: Long,
)

fun interface PairingEnrollmentProfileFactory {
    fun create(link: PairingAppLink): PairingEnrollmentProfile
}

fun interface PairingEnrollmentProbe {
    fun verify(profile: PairingEnrollmentProfile): Boolean
}

interface PairingEnrollmentTransaction {
    fun commit()
    fun rollback()
}

fun interface PairingEnrollmentStore {
    fun begin(profile: PairingEnrollmentProfile): PairingEnrollmentTransaction
}

/** Shared, atomic handoff used by camera App Links and in-app QR readers. */
class PairingEnrollmentCoordinator(
    private val store: PairingEnrollmentStore,
    private val profileFactory: PairingEnrollmentProfileFactory,
    private val probe: PairingEnrollmentProbe,
) {
    private val consumedNonces = mutableSetOf<String>()

    @Synchronized
    fun enroll(link: PairingAppLink): Boolean {
        if (link.nonce in consumedNonces) return false
        val profile = profileFactory.create(link)
        val transaction = store.begin(profile)
        return try {
            if (!probe.verify(profile)) {
                transaction.rollback()
                false
            } else {
                transaction.commit()
                consumedNonces += link.nonce
                true
            }
        } catch (failure: Throwable) {
            transaction.rollback()
            throw failure
        }
    }
}
