package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PresentationModelsTest {
    private val manifest = PresentationExtensionManifest(
        id = ExtensionId("calm.presentation"),
        displayName = "Calm presentation",
        apiVersion = ExtensionApiVersion(1, 1),
        schemaVersion = 2,
        kinds = setOf(PresentationExtensionKind.THEME, PresentationExtensionKind.HAPTIC),
        privacyClass = ExtensionPrivacyClass.PUBLIC,
        supportsHighContrast = true,
        supportsLargeFont = true,
        supportsReducedMotion = true,
        maxBatteryCostMilliampHoursPerHour = 4,
        revision = 7,
    )

    @Test
    fun `compatible presentation is applied for accessible context`() {
        val result = resolver().resolve(
            manifest,
            PresentationExtensionKind.THEME,
            PresentationContext(fontScale = 1.5f, highContrast = true, reducedMotion = true),
            ExtensionApiVersion(1, 2),
            2,
            currentRevision = 7,
            allowPrivateContent = false,
        )
        assertEquals(
            PresentationResolution.Applied(manifest.id, manifest.kinds),
            result,
        )
    }

    @Test
    fun `missing and unsuitable presentations fail to safe fallback`() {
        val resolver = resolver()
        assertEquals(
            PresentationFallbackReason.MISSING_EXTENSION,
            (
                resolver.resolve(null, PresentationExtensionKind.THEME, PresentationContext(), api(), 2, 7, false)
                    as PresentationResolution.Fallback
                ).reason,
        )
        assertEquals(
            PresentationFallbackReason.REDUCED_MOTION_UNSUPPORTED,
            (
                resolver.resolve(
                    manifest.copy(supportsReducedMotion = false),
                    PresentationExtensionKind.THEME,
                    PresentationContext(reducedMotion = true),
                    api(),
                    2,
                    7,
                    false,
                ) as PresentationResolution.Fallback
                ).reason,
        )
        assertEquals(
            PresentationFallbackReason.LOW_POWER,
            (
                resolver.resolve(
                    manifest.copy(maxBatteryCostMilliampHoursPerHour = 11),
                    PresentationExtensionKind.THEME,
                    PresentationContext(lowPower = true),
                    api(),
                    2,
                    7,
                    false,
                ) as PresentationResolution.Fallback
                ).reason,
        )
    }

    @Test
    fun `revision privacy kind and hearing constraints fail closed`() {
        val resolver = resolver()
        assertEquals(
            PresentationFallbackReason.STALE_REVISION,
            (
                resolver.resolve(manifest, PresentationExtensionKind.THEME, PresentationContext(), api(), 2, 8, false)
                    as PresentationResolution.Fallback
                ).reason,
        )
        assertEquals(
            PresentationFallbackReason.KIND_UNSUPPORTED,
            (
                resolver.resolve(manifest, PresentationExtensionKind.SOUND, PresentationContext(), api(), 2, 7, false)
                    as PresentationResolution.Fallback
                ).reason,
        )
        assertEquals(
            PresentationFallbackReason.HEARING_MODE_UNSUPPORTED,
            (
                resolver.resolve(
                    manifest.copy(kinds = setOf(PresentationExtensionKind.SOUND)),
                    PresentationExtensionKind.SOUND,
                    PresentationContext(hearingMode = PresentationHearingMode.VISUAL_ONLY),
                    api(),
                    2,
                    7,
                    false,
                )
                    as PresentationResolution.Fallback
                ).reason,
        )
        assertEquals(
            PresentationFallbackReason.PRIVACY_RESTRICTED,
            (
                resolver.resolve(
                    manifest.copy(
                        privacyClass = ExtensionPrivacyClass.SENSITIVE,
                        kinds = setOf(PresentationExtensionKind.THEME),
                    ),
                    PresentationExtensionKind.THEME,
                    PresentationContext(),
                    api(),
                    2,
                    7,
                    false,
                ) as PresentationResolution.Fallback
                ).reason,
        )
    }

    @Test
    fun `manifest and context bounds reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { PresentationContext(Float.NaN) }
        assertFailsWith<IllegalArgumentException> { PresentationContext(3.1f) }
        assertFailsWith<IllegalArgumentException> {
            manifest.copy(kinds = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            manifest.copy(privacyClass = ExtensionPrivacyClass.SENSITIVE, kinds = setOf(PresentationExtensionKind.SOUND))
        }
    }

    private fun resolver() = PresentationExtensionResolver()
    private fun api() = ExtensionApiVersion(1, 1)
}
