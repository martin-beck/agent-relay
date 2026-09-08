/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class OfflineSyncModelsTest {
    @Test
    fun cursorRoundTripsAndLegacyMigrationIsExplicit() {
        val cursor = SessionSyncCursor("daemon", 7)
        assertEquals(cursor, SessionSyncCursor.decode(cursor.encode()))
        assertEquals(SessionSyncCursor("daemon", 3), SessionSyncCursor.migrateLegacy("daemon", 3))
    }

    @Test
    fun coordinatorRejectsReplayGapsWithoutAdvancingCachedProjection() = runTest {
        val store = InMemorySessionHubStore()
        val repository = PersistentSessionHubRepository.open(store)
        val locator = testLocator("offline-sync")
        val observation = testObservation(locator)
        val initial = OfflineSessionProjection(
            snapshot = repository.snapshot.value,
            cursor = SessionSyncCursor("daemon", 0),
        )
        val coordinator = OfflineSessionSyncCoordinator(repository, initial)
        val gap = coordinator.apply(
            SessionSyncBatch(
                streamId = "daemon",
                base = initial.cursor,
                events = listOf(
                    SessionSyncEvent(
                        SessionSyncCursor("daemon", 2),
                        SessionEventUpdate(locator, observation = observation),
                    ),
                ),
            ),
        )
        assertIs<SessionSyncResult.Gap>(gap)
        assertEquals(0L, coordinator.projection().cursor.sequence)
        assertTrue(repository.snapshot.value.sessions.isEmpty())
    }

    @Test
    fun coordinatorPersistsCursorAndDeduplicatesEmptyReplay() = runTest {
        val store = InMemorySessionHubStore()
        val repository = PersistentSessionHubRepository.open(store)
        val locator = testLocator("offline-sync-apply")
        val initial = OfflineSessionProjection(repository.snapshot.value, SessionSyncCursor("daemon", 0))
        val coordinator = OfflineSessionSyncCoordinator(repository, initial)
        val result = coordinator.apply(
            SessionSyncBatch(
                "daemon",
                initial.cursor,
                listOf(SessionSyncEvent(SessionSyncCursor("daemon", 1), SessionEventUpdate(locator, testObservation(locator)))),
            ),
        )
        assertIs<SessionSyncResult.Applied>(result)
        assertEquals("1|daemon|1", repository.snapshot.value.recovery[locator]?.eventCursor)
        assertIs<SessionSyncResult.Duplicate>(coordinator.apply(SessionSyncBatch("daemon", coordinator.projection().cursor, emptyList())))
    }
}

private fun testLocator(session: String): SessionLocator = SessionLocator(
    connectionProviderId = ConnectionProviderId("local.device"),
    connectionProfileId = ConnectionProfileId("local"),
    agentProviderId = AgentProviderId("codex"),
    agentSessionId = AgentSessionId(session),
)

private fun testObservation(locator: SessionLocator): SessionObservation = SessionObservation(
    locator = locator,
    connectionLabel = "Local",
    connectionTarget = "device",
    projectPath = "/workspace/project",
    agentProviderLabel = "Codex",
    title = "Offline session",
    preview = "Cached preview",
    agentState = AgentSessionState.IDLE,
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
)
