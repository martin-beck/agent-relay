package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConnectivityFaultScenariosTest {
    @Test
    fun replayIsDeterministicAndBoundedForEveryNamedScenario() {
        ConnectivityFaultScenarioName.entries.forEach { name ->
            val scenario = ConnectivityFaultScenario(name, seed = 42, steps = 16)
            assertEquals(scenario.replay(), scenario.replay())
            assertEquals(16, scenario.replay().size)
            assertTrue(scenario.replay().all { it.sequence in 1..16 })
        }
    }

    @Test
    fun seedsProduceIndependentPlansWithoutProtectedContent() {
        val first = ConnectivityFaultScenario(ConnectivityFaultScenarioName.DELAY, 1).replay()
        val second = ConnectivityFaultScenario(ConnectivityFaultScenarioName.DELAY, 2).replay()
        assertNotEquals(first, second)
        assertTrue(first.all { it.value in 1..500 })
        assertTrue(second.all { it.value in 1..500 })
    }

    @Test
    fun resetAndPartitionPlansPreserveReconnectAndUncertainDeliveryContracts() {
        val reset = ConnectivityFaultScenario(ConnectivityFaultScenarioName.RESET, 7, 1).replay().single()
        val partition = ConnectivityFaultScenario(ConnectivityFaultScenarioName.PARTITION, 7, 1).replay().single()
        assertEquals(ConnectivityFaultAction.RESET, reset.action)
        assertEquals(ConnectivityFaultAction.PARTITION, partition.action)

        val ledger = CommandLedger()
        val command = CommandId("sync-command")
        assertEquals(CommandDisposition.NEW, ledger.begin(command).disposition)
        assertTrue(ledger.markUnknown(command))
        assertEquals(CommandDisposition.UNKNOWN_DELIVERY, ledger.disposition(command))
        assertEquals(CommandDisposition.UNKNOWN_DELIVERY, ledger.begin(command).disposition)
    }
}
