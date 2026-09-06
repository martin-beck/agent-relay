package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WearInstallationModelsTest {
    @Test
    fun acceptsAuthorizedCompatibleDevelopmentArtifact() {
        assertTrue(request().isInstallable())
    }

    @Test
    fun reportsActionableCompatibilityFailures() {
        assertFalse(request(profile = profile(debugAuthorized = false)).isInstallable())
        assertFalse(request(expectedPackageName = "dev.agentrelay.wear.other").isInstallable())
        assertFalse(request(expectedSchemaVersion = 2).isInstallable())
    }

    @Test
    fun rejectsHostileOrReleaseArtifactProvenance() {
        assertFailsWith<IllegalArgumentException> {
            artifact(packageName = "../../private")
        }
        assertFailsWith<IllegalArgumentException> {
            artifact(sizeBytes = 100L * 1024 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            artifact(signingFingerprint = "RELEASE_ONLY")
        }
    }

    private fun request(
        profile: WearDeviceProfile = profile(),
        expectedPackageName: String = "dev.agentrelay.wear.debug",
        expectedSchemaVersion: Int = 1,
    ) = WearInstallationRequest(profile, artifact(), expectedPackageName, expectedSchemaVersion, allowUpgrade = true)

    private fun profile(debugAuthorized: Boolean = true) = WearDeviceProfile(34, "wear-emu", "x86_64", debugAuthorized)

    private fun artifact(
        packageName: String = "dev.agentrelay.wear.debug",
        sizeBytes: Long = 1_024,
        signingFingerprint: String = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
    ) = WearDevelopmentArtifact(
        packageName,
        1,
        1,
        WearArtifactVariant.DEBUG,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        sizeBytes,
        signingFingerprint,
    )
}
