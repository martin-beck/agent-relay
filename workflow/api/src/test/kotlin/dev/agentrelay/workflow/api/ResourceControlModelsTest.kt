package dev.agentrelay.workflow.api

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceControlModelsTest {
    private val limits = ResourceKind.entries.associateWith { 100L }
    private val policy = ResourceControlPolicy(limits, UnknownPricePolicy.REQUIRE_APPROVAL)
    private val estimate = ResourceEstimate(ResourceKind.entries.associateWith { 60L })

    @Test
    fun rejectsUnknownPriceUntilExplicitApproval() {
        val ledger = AtomicResourceLedger(policy)
        val unknown = estimate.copy(hasUnknownPrice = true)
        assertEquals(
            ReservationOutcome.Rejected(ReservationRejection.UNKNOWN_PRICE),
            ledger.reserve("unknown", unknown),
        )
        assertEquals(ReservationOutcome.Reserved("unknown"), ledger.reserve("unknown", unknown, approved = true))
    }

    @Test
    fun reservationsAreAtomicAndPreventBudgetOversubscription() {
        val ledger = AtomicResourceLedger(policy)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val results = (0 until 8).map { index ->
            pool.submit<ReservationOutcome> {
                start.await()
                ledger.reserve("op-$index", estimate)
            }
        }
        start.countDown()
        val reserved = results.count { it.get() is ReservationOutcome.Reserved }
        pool.shutdownNow()
        assertEquals(1, reserved)
    }

    @Test
    fun reconciliationIsMonotonicAndHardStopsAfterOverrun() {
        val ledger = AtomicResourceLedger(policy)
        assertTrue(ledger.reserve("op", estimate) is ReservationOutcome.Reserved)
        assertEquals(ReconciliationOutcome.Reconciled, ledger.reconcile("op", MeterReport(limits.mapValues { 40L })))
        assertEquals(ReconciliationOutcome.UnknownOperation, ledger.reconcile("op", MeterReport(limits.mapValues { 20L })))
        assertEquals(40L, ledger.snapshot().getValue(ResourceKind.TIME_MILLIS))
        assertTrue(ledger.reserve("overrun", estimate) is ReservationOutcome.Reserved)
        assertTrue(ledger.reconcile("overrun", MeterReport(limits.mapValues { 101L })) is ReconciliationOutcome.HardStopped)
        assertTrue(ledger.isHardStopped())
        assertFalse(ledger.reserve("after-stop", ResourceEstimate(limits)).let { it is ReservationOutcome.Reserved })
    }

    @Test
    fun malformedDimensionsAndNegativeValuesAreRejected() {
        assertFailsForMissingDimension { ResourceEstimate(mapOf(ResourceKind.TIME_MILLIS to 1L)) }
        assertFailsForNegative { MeterReport(ResourceKind.entries.associateWith { -1L }) }
    }

    private fun assertFailsForMissingDimension(block: () -> Unit) {
        try {
            block()
            error("Expected malformed dimensions to fail")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }

    private fun assertFailsForNegative(block: () -> Unit) {
        try {
            block()
            error("Expected negative value to fail")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }
}
