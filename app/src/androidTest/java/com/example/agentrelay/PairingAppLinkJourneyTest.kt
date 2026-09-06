package com.example.agentrelay

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.agentrelay.connection.api.PairingAppLink
import dev.agentrelay.connection.api.PairingAppLinkCodec
import dev.agentrelay.connection.api.StableEndpointIdentity
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real Activity boundary used by a camera-launched pairing App Link. */
@RunWith(AndroidJUnit4::class)
class PairingAppLinkJourneyTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var keyPair: KeyPair
    private lateinit var enrollment: AndroidPairingAppLinkEnrollment
    private var nowMillis = 1_700_000_000_000L

    @Before
    fun seedGrant() {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        enrollment = AndroidPairingAppLinkEnrollment(composeTestRule.activity)
        runBlocking { enrollment.write(grantRecord()) }
    }

    @Test
    fun cameraAppLinkShowsScopedConfirmationAndEnrollment() {
        val link = validLink()
        launchAppLink(link)

        composeTestRule.onNodeWithText("Review secure pairing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Daemon: $DAEMON_IDENTITY").assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Requested scope: secure enrollment and the advertised host route.")
            .assertIsDisplayed()
        captureIfRequested("camera-link-confirmation.png")

        composeTestRule.onNodeWithText("Approve pairing").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Review secure pairing").assertDoesNotExist()
        captureIfRequested("enrollment-complete.png")
    }

    @Test
    fun expiredCameraAppLinkShowsExplicitRejection() {
        nowMillis = 1_700_000_060_000L
        launchAppLink(validLink(expiresAtMillis = nowMillis - 1_000L))

        composeTestRule.onNodeWithText("Pairing link rejected").assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "The link was expired, replayed, or not trusted. Return to Agent Relay and scan a new code.",
            )
            .assertIsDisplayed()
        captureIfRequested("expired-link-rejection.png")
    }

    private fun launchAppLink(raw: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(raw)).setPackage(
            composeTestRule.activity.packageName,
        )
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.setIntent(intent)
        }
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
    }

    private fun validLink(expiresAtMillis: Long = nowMillis + 60_000L): String {
        val unsigned = PairingAppLink(
            grantReference = GRANT_REFERENCE,
            daemonIdentity = StableEndpointIdentity(DAEMON_IDENTITY),
            nonce = NONCE,
            expiresAtMillis = expiresAtMillis,
            signature = "AA",
        )
        val signature = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(unsigned.signingPayload())
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        }
        return "https://${PairingAppLinkCodec.HOST}${PairingAppLinkCodec.PATH}" +
            "?a=${PairingAppLinkCodec.AUDIENCE}&d=$DAEMON_IDENTITY&e=$expiresAtMillis" +
            "&g=$GRANT_REFERENCE&n=$NONCE&s=$signature"
    }

    private fun grantRecord() = PairingLinkGrantRecord(
        grantReference = GRANT_REFERENCE,
        daemonIdentity = DAEMON_IDENTITY,
        encodedPublicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(keyPair.public.encoded),
        credentialReference = "credential-qr-fixture",
        routeReference = "route-qr-fixture",
        expiresAtMillis = nowMillis + 60_000L,
    )

    private fun captureIfRequested(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("captureQrEvidence") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir("qr-workflow") ?: error("external files unavailable")
        check(directory.mkdirs() || directory.isDirectory)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("could not capture QR workflow screenshot")
        java.io.File(directory, name).outputStream().use { stream ->
            check(
                screenshot.compress(
                    android.graphics.Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                ),
            )
        }
        screenshot.recycle()
    }

    private companion object {
        const val DAEMON_IDENTITY = "ari_v1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        const val GRANT_REFERENCE = "grant-qr-12345678"
        const val NONCE = "nonce-qr-12345678"
    }
}
