/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

/** Supported emulator profiles for the phone-mediated development installer. */
data class WearDeviceProfile(
    val apiLevel: Int,
    val model: String,
    val abi: String,
    val debugAuthorized: Boolean,
) {
    init {
        require(apiLevel in MIN_WEAR_API..MAX_WEAR_API) { "Wear API level is unsupported" }
        requireBoundedValue(model, "Wear model", MAX_MODEL_CHARS)
        require(abi in SUPPORTED_ABIS) { "Wear ABI is unsupported" }
    }
}

enum class WearArtifactVariant { DEBUG, INTERNAL }

/** Provenance is explicit; paths, private keys and Play Store metadata never cross this API. */
data class WearDevelopmentArtifact(
    val packageName: String,
    val versionCode: Long,
    val schemaVersion: Int,
    val variant: WearArtifactVariant,
    val sha256: String,
    val sizeBytes: Long,
    val signingFingerprint: String,
) {
    init {
        require(packageName.matches(PACKAGE_PATTERN)) { "Wear package name is invalid" }
        require(versionCode > 0) { "Wear version code is invalid" }
        require(schemaVersion in 1..MAX_SCHEMA_VERSION) { "Wear schema version is invalid" }
        require(sha256.matches(SHA256_PATTERN)) { "Wear artifact digest is invalid" }
        require(sizeBytes in 1..MAX_ARTIFACT_BYTES) { "Wear artifact size is invalid" }
        require(signingFingerprint.matches(FINGERPRINT_PATTERN)) {
            "Wear signing fingerprint is invalid"
        }
        require(variant != WearArtifactVariant.INTERNAL || !signingFingerprint.equals(RELEASE_FINGERPRINT, true)) {
            "Development artifacts cannot use the release fingerprint"
        }
    }
}

enum class WearInstallationFailure {
    DEBUG_AUTHORIZATION_REQUIRED,
    API_INCOMPATIBLE,
    ABI_INCOMPATIBLE,
    PACKAGE_MISMATCH,
    SCHEMA_INCOMPATIBLE,
    RELEASE_SIGNING_KEY,
}

data class WearInstallationRequest(
    val profile: WearDeviceProfile,
    val artifact: WearDevelopmentArtifact,
    val expectedPackageName: String,
    val expectedSchemaVersion: Int,
    val allowUpgrade: Boolean,
) {
    init {
        require(expectedPackageName.matches(PACKAGE_PATTERN)) { "Expected package name is invalid" }
        require(expectedSchemaVersion in 1..MAX_SCHEMA_VERSION) { "Expected schema is invalid" }
    }

    fun failure(): WearInstallationFailure? = when {
        !profile.debugAuthorized -> WearInstallationFailure.DEBUG_AUTHORIZATION_REQUIRED
        profile.apiLevel < MIN_WEAR_API -> WearInstallationFailure.API_INCOMPATIBLE
        profile.abi !in SUPPORTED_ABIS -> WearInstallationFailure.ABI_INCOMPATIBLE
        artifact.packageName != expectedPackageName -> WearInstallationFailure.PACKAGE_MISMATCH
        artifact.schemaVersion != expectedSchemaVersion -> WearInstallationFailure.SCHEMA_INCOMPATIBLE
        artifact.signingFingerprint.equals(RELEASE_FINGERPRINT, true) -> WearInstallationFailure.RELEASE_SIGNING_KEY
        else -> null
    }

    fun isInstallable(): Boolean = failure() == null
}

private const val MIN_WEAR_API = 30
private const val MAX_WEAR_API = 36
private const val MAX_MODEL_CHARS = 64
private const val MAX_SCHEMA_VERSION = 1_000_000
private const val MAX_ARTIFACT_BYTES = 100L * 1024 * 1024
private const val RELEASE_FINGERPRINT = "RELEASE_ONLY"
private val SUPPORTED_ABIS = setOf("arm64-v8a", "x86_64")
private val PACKAGE_PATTERN = Regex("dev\\.agentrelay\\.wear\\.[a-z][a-z0-9_]{1,31}")
private val SHA256_PATTERN = Regex("[a-fA-F0-9]{64}")
private val FINGERPRINT_PATTERN = Regex("[A-Fa-f0-9:]{32,95}")

private fun requireBoundedValue(value: String, field: String, max: Int) {
    require(value.isNotBlank() && value.length <= max) { "$field is invalid or too long" }
    require(!value.contains('\u0000')) { "$field contains a NUL" }
}
