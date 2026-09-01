package dev.agentrelay.session.api

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PersistentSessionHubRepositoryTest {
    @Test
    fun locatorIncludesConnectionProviderAndProfile() {
        val ssh = locator("ssh.secure-shell", "host-one", "same-session")
        val local = locator("local.device", "local", "same-session")
        val secondHost = locator("ssh.secure-shell", "host-two", "same-session")

        assertNotEquals(ssh, local)
        assertNotEquals(ssh, secondHost)
        assertNotEquals(ssh.stableKey, local.stableKey)
        assertNotEquals(ssh.stableKey, secondHost.stableKey)
    }

    @Test
    fun draftsInboxPreferencesAndTranscriptSurviveRepositoryReopen() = runTest {
        val store = InMemorySessionHubStore()
        val first = PersistentSessionHubRepository.open(store)
        val session = locator("ssh.secure-shell", "workstation", "codex-thread")
        first.upsertSession(observation(session, updatedAt = 10L))
        first.setPreferences(
            session,
            SessionPreferences(
                pinned = true,
                notificationPriority = SessionNotificationPriority.FINAL_OUTPUT_ONLY,
            ),
        )
        first.updateDraft(
            session,
            SessionDraft(
                text = "continue from the last checkpoint",
                selectionStart = 9,
                selectionEnd = 9,
                updatedAtEpochMillis = 20L,
            ),
        )
        first.recordActivity(
            activity(
                id = "approval-1",
                locator = session,
                type = SessionActivityType.APPROVAL_REQUIRED,
                at = 30L,
            ),
        )
        first.cacheTranscript(
            session,
            listOf(
                CachedTranscriptEntry(
                    id = "message-1",
                    turnId = "turn-1",
                    role = AgentTranscriptRole.AGENT,
                    channel = AgentMessageChannel.FINAL,
                    text = "Durable result",
                    createdAtEpochMillis = 25L,
                ),
            ),
        )

        val reopened = PersistentSessionHubRepository.open(store)
        val restored = reopened.snapshot.value

        assertTrue(restored.session(session)?.preferences?.pinned == true)
        assertEquals(
            SessionNotificationPriority.FINAL_OUTPUT_ONLY,
            restored.session(session)?.preferences?.notificationPriority,
        )
        assertEquals("continue from the last checkpoint", restored.drafts[session]?.text)
        assertEquals(1, restored.session(session)?.unreadCount)
        assertEquals("approval-1", restored.inbox().single().id)
        assertEquals("Durable result", restored.transcripts[session]?.single()?.text)
    }

    @Test
    fun duplicateActivityIsIdempotentAndReadStateIsTransactional() = runTest {
        val store = RecordingStore()
        val repository = PersistentSessionHubRepository.open(store)
        val session = locator("local.device", "local", "local-thread")

        @Test
        fun activityIdentityAndResolutionAreScopedToTheFullSessionLocator() = runTest {
            val repository = PersistentSessionHubRepository.open(InMemorySessionHubStore())
            val ssh = locator("ssh.secure-shell", "workstation", "shared-event-test")
            val local = locator("local.device", "local", "shared-event-test")
            repository.upsertSession(observation(ssh, updatedAt = 1L))
            repository.upsertSession(observation(local, updatedAt = 1L))

            repository.recordActivity(
                activity("approval", ssh, SessionActivityType.APPROVAL_REQUIRED, 2L),
            )
            repository.recordActivity(
                activity("approval", local, SessionActivityType.APPROVAL_REQUIRED, 2L),
            )
            repository.resolveActivity(local, "approval")

            assertEquals(2, repository.snapshot.value.activities.size)
            assertFalse(repository.snapshot.value.activities.single { it.locator == ssh }.isResolved)
            assertTrue(repository.snapshot.value.activities.single { it.locator == local }.isResolved)
        }

        repository.upsertSession(observation(session, updatedAt = 1L))
        val activity = activity(
            id = "output-1",
            locator = session,
            type = SessionActivityType.NEW_OUTPUT,
            at = 2L,
        )

        repository.recordActivity(activity)
        val savesAfterFirstEvent = store.saveCount
        repository.recordActivity(activity)

        assertEquals(savesAfterFirstEvent, store.saveCount)
        assertEquals(1, repository.snapshot.value.session(session)?.unreadCount)
        repository.markSessionRead(session, throughEpochMillis = 2L)
        assertEquals(0, repository.snapshot.value.session(session)?.unreadCount)
        assertTrue(repository.snapshot.value.activities.single().isRead)
    }

    @Test
    fun failedPersistenceNeverPublishesPartialState() = runTest {
        val session = locator("local.device", "local", "failure-test")
        val initial = SessionHubSnapshot(
            sessions = listOf(SessionRecord(observation(session, updatedAt = 1L))),
        )
        val store = FailingStore(initial)
        val repository = PersistentSessionHubRepository.open(store)
        store.failWrites = true

        assertFailsWith<IllegalStateException> {
            repository.updateDraft(
                session,
                SessionDraft("unsaved", 7, 7, 2L),
            )
        }

        assertFalse(session in repository.snapshot.value.drafts)
        assertEquals(initial, repository.snapshot.value)
    }

    @Test
    fun retentionIsBoundedAndKeepsPinnedAndActionableStateFirst() = runTest {
        val policy = SessionRetentionPolicy(
            maximumSessions = 2,
            maximumActivities = 2,
            maximumTranscriptEntriesPerSession = 2,
        )
        val repository = PersistentSessionHubRepository.open(InMemorySessionHubStore(), policy)
        val pinned = locator("ssh.secure-shell", "pinned", "one")
        val recent = locator("ssh.secure-shell", "recent", "two")
        val dropped = locator("ssh.secure-shell", "dropped", "three")
        repository.upsertSession(observation(pinned, updatedAt = 1L))
        repository.setPreferences(pinned, SessionPreferences(pinned = true))
        repository.upsertSession(observation(dropped, updatedAt = 2L))
        repository.upsertSession(observation(recent, updatedAt = 3L))

        assertTrue(repository.snapshot.value.session(pinned) != null)
        assertTrue(repository.snapshot.value.session(recent) != null)
        assertTrue(repository.snapshot.value.session(dropped) == null)

        repository.recordActivity(
            activity("approval", pinned, SessionActivityType.APPROVAL_REQUIRED, 1L),
        )
        repository.recordActivity(activity("old-output", pinned, SessionActivityType.NEW_OUTPUT, 2L))
        repository.recordActivity(activity("new-output", pinned, SessionActivityType.NEW_OUTPUT, 3L))
        repository.cacheTranscript(
            pinned,
            listOf(
                transcript("one", 1L),
                transcript("two", 2L),
                transcript("three", 3L),
            ),
        )

        assertEquals(setOf("approval", "new-output"), repository.snapshot.value.activities.map { it.id }.toSet())
        assertEquals(listOf("two", "three"), repository.snapshot.value.transcripts[pinned]?.map { it.id })
        assertEquals(2, repository.snapshot.value.totalUnread)
    }

    private fun locator(
        connectionProvider: String,
        connectionProfile: String,
        session: String,
    ) = SessionLocator(
        connectionProviderId = ConnectionProviderId(connectionProvider),
        connectionProfileId = ConnectionProfileId(connectionProfile),
        agentProviderId = AgentProviderId("codex"),
        agentSessionId = AgentSessionId(session),
    )

    private fun observation(
        locator: SessionLocator,
        updatedAt: Long,
    ) = SessionObservation(
        locator = locator,
        connectionLabel = locator.connectionProfileId.value,
        connectionTarget = "Target",
        projectPath = "/workspace/project",
        agentProviderLabel = "Codex",
        title = "Session " + locator.agentSessionId.value,
        preview = "Latest output",
        agentState = AgentSessionState.IDLE,
        createdAtEpochMillis = 0L,
        updatedAtEpochMillis = updatedAt,
    )

    private fun activity(
        id: String,
        locator: SessionLocator,
        type: SessionActivityType,
        at: Long,
    ) = SessionActivity(
        id = id,
        locator = locator,
        type = type,
        summary = id,
        eventAnchorId = "event-$id",
        occurredAtEpochMillis = at,
    )

    private fun transcript(id: String, at: Long) = CachedTranscriptEntry(
        id = id,
        turnId = null,
        role = AgentTranscriptRole.AGENT,
        channel = AgentMessageChannel.COMMENTARY,
        text = id,
        createdAtEpochMillis = at,
    )

    private class RecordingStore : SessionHubStore {
        private var value = SessionHubSnapshot()
        var saveCount = 0

        override suspend fun load(): SessionHubSnapshot = value

        override suspend fun save(snapshot: SessionHubSnapshot) {
            saveCount += 1
            value = snapshot
        }
    }

    private class FailingStore(
        private val value: SessionHubSnapshot,
    ) : SessionHubStore {
        var failWrites = false

        override suspend fun load(): SessionHubSnapshot = value

        override suspend fun save(snapshot: SessionHubSnapshot) {
            check(!failWrites) { "Injected persistence failure" }
        }
    }
}
