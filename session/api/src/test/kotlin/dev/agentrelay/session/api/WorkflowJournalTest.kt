package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class WorkflowJournalTest {
    private val draft = WorkflowFactDraft(
        id = "fact-1",
        schemaVersion = 1,
        entityType = WorkflowEntityType.WORKFLOW,
        entityId = "workflow-1",
        kind = "created",
        idempotencyKey = "command-1",
        causationId = "request-1",
        values = mapOf("status" to "queued", "name" to "build"),
    )

    @Test
    fun appendAssignsMonotonicSequencesAndDeduplicatesBeforeCommit() {
        val journal = InMemoryWorkflowJournal()
        val first = journal.append("stream-1", draft)
        val duplicate = journal.append("stream-1", draft.copy(id = "fact-retry"))
        val second = journal.append("stream-1", draft.copy(id = "fact-2", idempotencyKey = "command-2"))

        assertEquals(1, (first as AppendResult.Appended).fact.sequence)
        assertEquals(first.fact, (duplicate as AppendResult.Duplicate).fact)
        assertEquals(2, (second as AppendResult.Appended).fact.sequence)
        assertEquals(listOf("fact-1", "fact-2"), journal.facts("stream-1").map(WorkflowFact::id))
    }

    @Test
    fun crashBeforeCommitLeavesNoTailAndRetryIsSafe() {
        var fail = true
        val journal = InMemoryWorkflowJournal { if (fail) error("injected commit failure") }
        assertFailsWith<IllegalStateException> { journal.append("stream-1", draft) }
        assertTrue(journal.facts("stream-1").isEmpty())
        fail = false
        assertEquals(1, (journal.append("stream-1", draft) as AppendResult.Appended).fact.sequence)
    }

    @Test
    fun projectionMigratesFactsAndRebuildDoesNotAddFacts() {
        val journal = InMemoryWorkflowJournal()
        journal.append("stream-1", draft)
        journal.append(
            "stream-1",
            draft.copy(
                id = "fact-2",
                schemaVersion = 2,
                idempotencyKey = "command-2",
                values = mapOf("state" to "running"),
            ),
        )
        val before = journal.projection("stream-1")
        val rebuilt = journal.rebuildProjection("stream-1")

        assertEquals("running", rebuilt.rows[ProjectionKey(WorkflowEntityType.WORKFLOW, "workflow-1")]?.values?.get("state"))
        assertEquals(before, rebuilt)
        assertEquals(2, journal.facts("stream-1").size)
    }

    @Test
    fun retentionReportsRequestedRemovalWithoutDeletingAuthoritativeFacts() {
        val journal = InMemoryWorkflowJournal()
        journal.append("stream-1", draft)
        val report = journal.requestRetention("stream-1", keepAtLeast = 0)

        assertEquals(1, report.requestedRemoval)
        assertEquals(1, report.retainedFacts)
        assertTrue(report.authoritativeFactsPreserved)
        assertEquals(1, journal.facts("stream-1").size)
    }

    @Test
    fun concurrentWritersKeepOneContiguousStreamAndRejectNoFacts() {
        val journal = InMemoryWorkflowJournal()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val writes = (1..64).map { number ->
                pool.submit(
                    Callable {
                        journal.append(
                            "stream-1",
                            draft.copy(id = "fact-$number", idempotencyKey = "command-$number"),
                        )
                    },
                )
            }
            writes.forEach { assertTrue(it.get() is AppendResult.Appended) }
        } finally {
            pool.shutdownNow()
        }
        val facts = journal.facts("stream-1")
        assertEquals((1L..64L).toList(), facts.map(WorkflowFact::sequence))
        assertEquals(64, facts.map(WorkflowFact::idempotencyKey).toSet().size)
    }
}
