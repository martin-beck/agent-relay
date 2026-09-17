/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.api

import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedSpeechModelCatalogTest {
    @Test
    fun acceptsAnEntrySignedByTheTrustedEd25519Key() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val entry = signedEntry(keyPair.private)

        assertEquals(
            listOf(entry.descriptor),
            SignedSpeechModelCatalog(listOf(entry), mapOf("release" to keyPair.public.encoded)).models,
        )
    }

    @Test
    fun rejectsTamperingUnknownKeysAndDuplicateIds() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val entry = signedEntry(keyPair.private)
        assertFailsWith<IllegalArgumentException> {
            SignedSpeechModelCatalog(
                listOf(entry.copy(descriptor = entry.descriptor.copy(version = "changed"))),
                mapOf("release" to keyPair.public.encoded),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedSpeechModelCatalog(listOf(entry.copy(signingKeyId = "other")), mapOf("release" to keyPair.public.encoded))
        }
        assertFailsWith<IllegalArgumentException> {
            SignedSpeechModelCatalog(listOf(entry, entry), mapOf("release" to keyPair.public.encoded))
        }
    }

    private fun signedEntry(privateKey: java.security.PrivateKey): SignedSpeechModelCatalogEntry {
        val descriptor = SpeechModelDescriptor(
            id = SpeechModelId("synthetic-asr"),
            displayName = "Synthetic English",
            version = "1",
            languageTags = setOf("en"),
            capabilities = setOf(SpeechModelCapability.TRANSCRIPTION),
            license = SpeechModelLicense("Synthetic", "MIT", "https://example.com/license"),
            modelPackage = SpeechModelPackage(
                "https://models.example.com/synthetic.tar.bz2",
                "0".repeat(64),
                10,
                20,
            ),
        )
        val signer = Signature.getInstance("Ed25519").apply {
            initSign(privateKey)
            update(SignedSpeechModelCatalog.canonicalPayload(descriptor))
        }
        return SignedSpeechModelCatalogEntry(
            descriptor,
            "release",
            Base64.getEncoder().encodeToString(signer.sign()),
        )
    }
}
