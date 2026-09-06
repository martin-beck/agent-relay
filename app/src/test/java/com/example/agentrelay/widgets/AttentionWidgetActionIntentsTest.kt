package com.example.agentrelay.widgets

import android.app.Application
import android.content.Intent
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetActionRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPendingIntent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttentionWidgetActionIntentsTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPendingIntents() {
        ShadowPendingIntent.reset()
        AttentionWidgetActionRuntimeHolder.runtime = null
    }

    @After
    fun clearRuntime() {
        AttentionWidgetActionRuntimeHolder.runtime = null
    }

    @Test
    fun pendingIntentIsExplicitImmutableAndRoundTripsAuthenticatedRequest() {
        val request = request()
        val factory = AttentionWidgetPendingIntentFactory(context) { itemId, revision, action ->
            assertEquals(request.itemId, itemId)
            assertEquals(request.snapshotRevision, revision)
            assertEquals(request.action, action)
            request
        }

        val pendingIntent = factory.create(request.itemId, request.snapshotRevision, request.action)
        val shadow = shadowOf(pendingIntent)
        val saved = shadow.savedIntent

        assertTrue(shadow.isImmutable)
        assertEquals(AttentionWidgetActionReceiver::class.java.name, saved.component?.className)
        assertEquals(context.packageName, saved.`package`)
        assertEquals(request, saved.toAttentionWidgetActionRequest(context.packageName))
    }

    @Test
    fun parserRejectsWrongActionPackageIdentityOrMalformedFields() {
        val valid = request().toIntent(context)

        assertNull(Intent(valid).setAction("hostile").toAttentionWidgetActionRequest(context.packageName))
        assertNull(Intent(valid).setPackage("hostile.package").toAttentionWidgetActionRequest(context.packageName))
        assertNull(Intent(valid).setData(null).toAttentionWidgetActionRequest(context.packageName))
        assertNull(
            Intent(valid)
                .putExtra("com.example.agentrelay.widget.ACTION", "APPROVE_AGENT_ACTION")
                .toAttentionWidgetActionRequest(context.packageName),
        )
        assertNull(
            Intent(valid)
                .putExtra("com.example.agentrelay.widget.AUTHENTICATION_TAG", "short")
                .toAttentionWidgetActionRequest(context.packageName),
        )
    }

    @Test
    fun receiverFailsClosedAfterProcessDeathAndDispatchesOnlyStrictlyParsedRequest() {
        val request = request()
        val receiver = AttentionWidgetActionReceiver()
        val observed = mutableListOf<AttentionWidgetActionRequest>()

        receiver.onReceive(context, request.toIntent(context))
        assertTrue(observed.isEmpty())

        AttentionWidgetActionRuntimeHolder.runtime = AttentionWidgetActionRuntime(observed::add)
        receiver.onReceive(context, request.toIntent(context))
        receiver.onReceive(context, Intent(request.toIntent(context)).setAction("hostile"))
        assertEquals(listOf(request), observed)
    }

    @Test(expected = IllegalStateException::class)
    fun pendingIntentFactoryRejectsIssuerSubstitution() {
        AttentionWidgetPendingIntentFactory(context) { _, _, _ ->
            request().copy(itemId = "different-item")
        }.create("attention-item-1", 7, AttentionWidgetAction.ACKNOWLEDGE)
    }

    private fun request() = AttentionWidgetActionRequest(
        requestId = "widget_action_v1_request000001",
        itemId = "attention-item-1",
        snapshotRevision = 7,
        authorityGeneration = 2,
        action = AttentionWidgetAction.ACKNOWLEDGE,
        issuedAtEpochMillis = 9_000,
        expiresAtEpochMillis = 20_000,
        authenticationTag = "authenticated_tag_1234",
    )
}
