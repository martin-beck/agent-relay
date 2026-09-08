/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

enum class PresentationExtensionKind {
    THEME,
    SOUND,
    HAPTIC,
    NOTIFICATION,
}

enum class PresentationHearingMode {
    AUDIBLE,
    VISUAL_ONLY,
}

data class PresentationContext(
    val fontScale: Float = 1f,
    val highContrast: Boolean = false,
    val reducedMotion: Boolean = false,
    val hearingMode: PresentationHearingMode = PresentationHearingMode.AUDIBLE,
    val lowPower: Boolean = false,
) {
    init {
        require(fontScale.isFinite() && fontScale in 1f..3f) {
            "Font scale must be finite and between 1 and 3"
        }
    }
}

data class PresentationExtensionManifest(
    val id: ExtensionId,
    val displayName: String,
    val apiVersion: ExtensionApiVersion,
    val schemaVersion: Int,
    val kinds: Set<PresentationExtensionKind>,
    val privacyClass: ExtensionPrivacyClass,
    val supportsHighContrast: Boolean,
    val supportsLargeFont: Boolean,
    val supportsReducedMotion: Boolean,
    val maxBatteryCostMilliampHoursPerHour: Int,
    val revision: Long = 0,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 80) {
            "Presentation display name is invalid"
        }
        require(schemaVersion >= 1) { "Presentation schema version must be positive" }
        require(kinds.isNotEmpty()) { "Presentation extension must declare a kind" }
        require(maxBatteryCostMilliampHoursPerHour in 0..100) {
            "Presentation battery cost must be between 0 and 100 mAh per hour"
        }
        require(revision >= 0) { "Presentation revision must not be negative" }
        require(privacyClass != ExtensionPrivacyClass.SENSITIVE || kinds == setOf(PresentationExtensionKind.THEME)) {
            "Sensitive presentation extensions may only provide themes"
        }
    }

    fun compatibleWith(hostApi: ExtensionApiVersion, hostSchema: Int): Boolean =
        apiVersion.major == hostApi.major && apiVersion.minor <= hostApi.minor && schemaVersion == hostSchema
}

enum class PresentationFallbackReason {
    MISSING_EXTENSION,
    INCOMPATIBLE_VERSION,
    STALE_REVISION,
    HIGH_CONTRAST_UNSUPPORTED,
    LARGE_FONT_UNSUPPORTED,
    REDUCED_MOTION_UNSUPPORTED,
    LOW_POWER,
    HEARING_MODE_UNSUPPORTED,
    PRIVACY_RESTRICTED,
    KIND_UNSUPPORTED,
}

sealed interface PresentationResolution {
    data class Applied(
        val extensionId: ExtensionId,
        val kinds: Set<PresentationExtensionKind>,
    ) : PresentationResolution

    data class Fallback(val reason: PresentationFallbackReason) : PresentationResolution
}

class PresentationExtensionResolver {
    fun resolve(
        manifest: PresentationExtensionManifest?,
        requestedKind: PresentationExtensionKind,
        context: PresentationContext,
        hostApi: ExtensionApiVersion,
        hostSchema: Int,
        currentRevision: Long,
        allowPrivateContent: Boolean,
    ): PresentationResolution {
        if (manifest == null) return PresentationResolution.Fallback(PresentationFallbackReason.MISSING_EXTENSION)
        if (!manifest.compatibleWith(hostApi, hostSchema)) {
            return PresentationResolution.Fallback(PresentationFallbackReason.INCOMPATIBLE_VERSION)
        }
        if (manifest.revision != currentRevision) {
            return PresentationResolution.Fallback(PresentationFallbackReason.STALE_REVISION)
        }
        if (requestedKind !in manifest.kinds) {
            return PresentationResolution.Fallback(PresentationFallbackReason.KIND_UNSUPPORTED)
        }
        if (manifest.privacyClass == ExtensionPrivacyClass.SENSITIVE && !allowPrivateContent) {
            return PresentationResolution.Fallback(PresentationFallbackReason.PRIVACY_RESTRICTED)
        }
        if (context.highContrast && !manifest.supportsHighContrast) {
            return PresentationResolution.Fallback(PresentationFallbackReason.HIGH_CONTRAST_UNSUPPORTED)
        }
        if (context.fontScale > 1.3f && !manifest.supportsLargeFont) {
            return PresentationResolution.Fallback(PresentationFallbackReason.LARGE_FONT_UNSUPPORTED)
        }
        if (context.reducedMotion && !manifest.supportsReducedMotion) {
            return PresentationResolution.Fallback(PresentationFallbackReason.REDUCED_MOTION_UNSUPPORTED)
        }
        if (context.lowPower && manifest.maxBatteryCostMilliampHoursPerHour > 10) {
            return PresentationResolution.Fallback(PresentationFallbackReason.LOW_POWER)
        }
        if (context.hearingMode == PresentationHearingMode.VISUAL_ONLY &&
            requestedKind == PresentationExtensionKind.SOUND
        ) {
            return PresentationResolution.Fallback(PresentationFallbackReason.HEARING_MODE_UNSUPPORTED)
        }
        return PresentationResolution.Applied(manifest.id, manifest.kinds)
    }
}
