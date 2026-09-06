package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttentionWidgetActionModelsTest {
    private var current = snapshot(revision = 7)
    private val executed = mutableListOf<AttentionWidgetActionRequest>()
    private var persisted = AttentionWidgetActionAuthoritySnapshot(2, false)

    @Test
    fun authenticatedCurrentSafeActionExecutesExactlyOnce() {
        val processor = processor()
        val request = request(action = AttentionWidgetAction.ACKNOWLEDGE)

        assertEquals(AttentionWidgetActionOutcome.EXECUTED, processor.process(request, NOW))
        assertEquals(AttentionWidgetActionOutcome.REJECT_DUPLICATE, processor.process(request, NOW))
        assertEquals(listOf(request), executed)
    }

    @Test
    fun currentStateIsReloadedAndBothStaleRevisionAndMissingItemFailClosed() {
        val processor = processor()
        current = snapshot(revision = 8)

        assertEquals(
            AttentionWidgetActionOutcome.REJECT_STALE,
            processor.process(request(revision = 7), NOW),
        )
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_STALE,
            processor.process(request(revision = 8, itemId = "removed-item"), NOW),
        )
        assertEquals(emptyList(), executed)
    }

    @Test
    fun authenticationExpiryAndUnavailableActionsNeverReachExecutor() {
        val processor = processor(authenticated = false)

        assertEquals(
            AttentionWidgetActionOutcome.REJECT_UNAUTHORIZED,
            processor.process(request(), NOW),
        )
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_UNAUTHORIZED,
            processor.process(request(expiresAt = NOW), NOW),
        )

        val authenticatedProcessor = processor()
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_EXPIRED,
            authenticatedProcessor.process(request(expiresAt = NOW), NOW),
        )
        current = snapshot(revision = 7, canDefer = false)
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_NOT_ALLOWED,
            authenticatedProcessor.process(request(action = AttentionWidgetAction.DEFER), NOW),
        )
        assertEquals(emptyList(), executed)
    }

    @Test
    fun revocationAndGenerationRotationInvalidateEveryOldPendingIntent() {
        val processor = processor()
        val oldRequest = request(generation = 2)

        val revoked = processor.revoke()
        assertEquals(3, revoked.generation)
        assertEquals(true, revoked.revoked)
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_REVOKED,
            processor.process(oldRequest, NOW),
        )
        val processRestartedWhileRevoked = processor(initial = persisted)
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_REVOKED,
            processRestartedWhileRevoked.process(oldRequest, NOW),
        )

        processor.activate(4)
        assertEquals(
            AttentionWidgetActionOutcome.REJECT_REVOKED,
            processor.process(oldRequest, NOW),
        )
        assertEquals(
            AttentionWidgetActionOutcome.EXECUTED,
            processor.process(request(generation = 4), NOW),
        )
    }

    @Test
    fun muteIsConsumedButAlwaysRequiresInAppConfirmation() {
        val processor = processor()
        val mute = request(action = AttentionWidgetAction.MUTE)

        assertEquals(AttentionWidgetActionOutcome.CONFIRMATION_REQUIRED, processor.process(mute, NOW))
        assertEquals(AttentionWidgetActionOutcome.REJECT_DUPLICATE, processor.process(mute, NOW))
        assertEquals(emptyList(), executed)
    }

    @Test
    fun persistedAuthorityRejectsDuplicateAfterProcessDeath() {
        val first = processor()
        val action = request()
        assertEquals(AttentionWidgetActionOutcome.EXECUTED, first.process(action, NOW))

        val restored = processor(initial = persisted)
        assertEquals(AttentionWidgetActionOutcome.REJECT_DUPLICATE, restored.process(action, NOW))
        assertEquals(1, executed.size)
    }

    @Test
    fun persistenceFailurePreventsTheEffectAndDoesNotClaimExecution() {
        val processor = processor(persist = false)
        val action = request()

        assertEquals(AttentionWidgetActionOutcome.REJECT_PERSISTENCE, processor.process(action, NOW))
        assertEquals(emptyList(), executed)
        assertEquals(emptyList(), processor.snapshot().consumedRequests)
    }

    @Test
    fun failedRevocationPersistenceDoesNotClaimTheAuthorityTransition() {
        val processor = processor(persist = false)

        assertFailsWith<IllegalStateException> { processor.revoke() }
        assertEquals(AttentionWidgetActionOutcome.REJECT_PERSISTENCE, processor.process(request(), NOW))
        assertEquals(2, processor.snapshot().generation)
        assertEquals(false, processor.snapshot().revoked)
        assertEquals(emptyList(), executed)
    }

    @Test
    fun finalCompareAndSetFailureOrThrowIsUncertainAndCannotReplay() {
        val stale = processor(execution = AttentionWidgetActionExecutionResult.STALE_STATE)
        val staleRequest = request()
        assertEquals(AttentionWidgetActionOutcome.REJECT_STALE, stale.process(staleRequest, NOW))
        assertEquals(AttentionWidgetActionOutcome.REJECT_DUPLICATE, stale.process(staleRequest, NOW))

        val failed = AttentionWidgetActionProcessor(
            AttentionWidgetActionAuthoritySnapshot(2, revoked = false),
            AttentionWidgetActionAuthenticator { true },
            AttentionWidgetActionStateSource { current },
            AttentionWidgetActionExecutor { error("effect result unavailable") },
            AttentionWidgetActionAuthorityStore {
                persisted = it
                true
            },
        )
        val failedRequest = request(requestId = "widget_action_v1_failure000001")
        assertEquals(AttentionWidgetActionOutcome.EXECUTION_UNCERTAIN, failed.process(failedRequest, NOW))
        assertEquals(AttentionWidgetActionOutcome.REJECT_DUPLICATE, failed.process(failedRequest, NOW))
    }

    @Test
    fun replayLedgerIsBoundedWithoutEvictingFreshAuthority() {
        val processor = processor()
        repeat(128) { index ->
            val id = "widget_action_v1_bounded${index.toString().padStart(6, '0')}"
            assertEquals(
                AttentionWidgetActionOutcome.EXECUTED,
                processor.process(request(requestId = id), NOW),
            )
        }
        val overflow = request(requestId = "widget_action_v1_overflow000001")
        assertEquals(AttentionWidgetActionOutcome.REJECT_CAPACITY, processor.process(overflow, NOW))
        assertEquals(128, processor.snapshot().consumedRequests.size)
    }

    @Test
    fun authenticationPayloadIsDeterministicAndLengthPrefixed() {
        val request = request()

        assertContentEquals(request.authenticationPayload(), request.copy().authenticationPayload())
        assertFailsWith<IllegalArgumentException> {
            request.copy(expiresAtEpochMillis = request.issuedAtEpochMillis + 3_600_001)
        }
    }

    private fun processor(
        initial: AttentionWidgetActionAuthoritySnapshot = AttentionWidgetActionAuthoritySnapshot(2, false),
        authenticated: Boolean = true,
        execution: AttentionWidgetActionExecutionResult = AttentionWidgetActionExecutionResult.APPLIED,
        persist: Boolean = true,
    ) = AttentionWidgetActionProcessor(
        initial,
        AttentionWidgetActionAuthenticator { authenticated },
        AttentionWidgetActionStateSource { current },
        AttentionWidgetActionExecutor {
            if (execution == AttentionWidgetActionExecutionResult.APPLIED) executed += it
            execution
        },
        AttentionWidgetActionAuthorityStore {
            if (persist) persisted = it
            persist
        },
    )

    private fun request(
        requestId: String = "widget_action_v1_request000001",
        itemId: String = ITEM_ID,
        revision: Long = 7,
        generation: Long = 2,
        action: AttentionWidgetAction = AttentionWidgetAction.ACKNOWLEDGE,
        expiresAt: Long = 20_000,
    ) = AttentionWidgetActionRequest(
        requestId = requestId,
        itemId = itemId,
        snapshotRevision = revision,
        authorityGeneration = generation,
        action = action,
        issuedAtEpochMillis = 9_000,
        expiresAtEpochMillis = expiresAt,
        authenticationTag = "authenticated_tag_1234",
    )

    private fun snapshot(
        revision: Long,
        canDefer: Boolean = true,
    ) = AttentionWidgetSnapshot(
        revision = revision,
        generatedAtEpochMillis = 9_000,
        items = listOf(
            AttentionWidgetItem(
                id = ITEM_ID,
                title = "Review current request",
                summary = "Safe context",
                urgency = AttentionUrgency.HIGH,
                state = AttentionState.OPEN,
                createdAtEpochMillis = 8_000,
                expiresAtEpochMillis = null,
                canOpen = true,
                canAcknowledge = true,
                canDefer = canDefer,
                canMute = true,
            ),
        ),
    )

    private companion object {
        const val ITEM_ID = "attention-item-1"
        const val NOW = 10_000L
    }
}
