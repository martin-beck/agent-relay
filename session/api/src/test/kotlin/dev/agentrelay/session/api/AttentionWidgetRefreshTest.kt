package dev.agentrelay.session.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttentionWidgetRefreshTest {
    private val policy = AttentionWidgetRefreshPolicy(minimumIntervalMillis = 10, pollingIntervalMillis = 30)

    @Test
    fun eventBurstsCoalesceUntilTheBatteryWindowExpires() {
        val scheduler = AttentionWidgetRefreshScheduler(policy)
        assertEquals(AttentionWidgetRefreshDecision.EMIT, scheduler.request(1, 100, AttentionWidgetRefreshReason.WIDGET_BOUND))
        assertEquals(AttentionWidgetRefreshDecision.THROTTLED, scheduler.request(2, 105, AttentionWidgetRefreshReason.ATTENTION_CHANGED))
        assertEquals(2L, scheduler.state().pendingRevision)
        assertEquals(AttentionWidgetRefreshDecision.EMIT, scheduler.poll(2, 110))
        assertEquals(2L, scheduler.state().lastPublishedRevision)
    }

    @Test
    fun backwardsClockDoesNotCauseAnUpdateStorm() {
        val scheduler = AttentionWidgetRefreshScheduler(policy)
        scheduler.request(1, 100, AttentionWidgetRefreshReason.WIDGET_BOUND)
        assertEquals(AttentionWidgetRefreshDecision.THROTTLED, scheduler.request(2, 50, AttentionWidgetRefreshReason.CLOCK_CHANGED))
        assertEquals(AttentionWidgetRefreshDecision.EMIT, scheduler.poll(2, 110))
    }

    @Test
    fun offlineEventsRemainPendingUntilALaterOnlineRequest() {
        val scheduler = AttentionWidgetRefreshScheduler(policy)
        scheduler.setOffline(true)
        assertEquals(AttentionWidgetRefreshDecision.OFFLINE, scheduler.request(4, 100, AttentionWidgetRefreshReason.ATTENTION_CHANGED))
        scheduler.setOffline(false)
        assertEquals(AttentionWidgetRefreshDecision.EMIT, scheduler.poll(4, 100))
    }

    @Test
    fun persistedStateBoundsProcessRestartAndAllowsNewRevision() {
        val first = AttentionWidgetRefreshScheduler(policy)
        first.request(3, 100, AttentionWidgetRefreshReason.ATTENTION_CHANGED)
        val restarted = AttentionWidgetRefreshScheduler(policy, first.state())
        assertEquals(AttentionWidgetRefreshDecision.THROTTLED, restarted.request(3, 101, AttentionWidgetRefreshReason.PROCESS_RESTART))
        assertEquals(AttentionWidgetRefreshDecision.EMIT, restarted.request(4, 110, AttentionWidgetRefreshReason.ATTENTION_CHANGED))
    }

    @Test
    fun widgetInstancesHaveIndependentThrottleState() {
        val coordinator = AttentionWidgetRefreshCoordinator(policy)
        assertEquals(AttentionWidgetRefreshDecision.EMIT, coordinator.request(1, 1, 100, AttentionWidgetRefreshReason.WIDGET_BOUND))
        assertEquals(AttentionWidgetRefreshDecision.EMIT, coordinator.request(2, 1, 100, AttentionWidgetRefreshReason.WIDGET_BOUND))
        coordinator.remove(1)
        assertFalse(coordinator.state(2).pendingRevision != null)
        assertTrue(coordinator.state(1).lastPublishedRevision == -1L)
    }
}
