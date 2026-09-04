package dev.agentrelay.session.api

/** A fact supplied to the journal before its stream sequence is assigned. */
data class WorkflowFactDraft(
    val id: String,
    val schemaVersion: Int,
    val entityType: WorkflowEntityType,
    val entityId: String,
    val kind: String,
    val idempotencyKey: String,
    val causationId: String,
    val values: Map<String, String>,
) {
    init {
        requireJournalId(id, "Fact id")
        require(schemaVersion in 1..MAX_JOURNAL_SCHEMA) { "Fact schema version is invalid" }
        requireJournalId(entityId, "Fact entity id")
        requireJournalId(kind, "Fact kind")
        requireJournalId(idempotencyKey, "Fact idempotency key")
        requireJournalId(causationId, "Fact causation id")
        require(values.size <= MAX_JOURNAL_FIELDS) { "Fact values are too large" }
        values.forEach { (key, value) ->
            requireJournalId(key, "Fact value key")
            require(value.length <= MAX_JOURNAL_VALUE_CHARS) { "Fact value is too large" }
        }
    }
}

data class WorkflowFact(
    val streamId: String,
    val sequence: Long,
    val draft: WorkflowFactDraft,
) {
    init {
        requireJournalId(streamId, "Fact stream id")
        require(sequence > 0) { "Fact sequence must be positive" }
    }

    val id: String get() = draft.id
    val idempotencyKey: String get() = draft.idempotencyKey
}

enum class WorkflowEntityType { WORKFLOW, RUN, STEP }

sealed interface AppendResult {
    val fact: WorkflowFact

    data class Appended(override val fact: WorkflowFact) : AppendResult

    data class Duplicate(override val fact: WorkflowFact) : AppendResult
}

data class ProjectionKey(val entityType: WorkflowEntityType, val entityId: String)

data class WorkflowProjectionRow(
    val key: ProjectionKey,
    val values: Map<String, String>,
    val schemaVersion: Int,
    val lastSequence: Long,
)

data class WorkflowProjectionSnapshot(
    val rows: Map<ProjectionKey, WorkflowProjectionRow>,
    val lastSequence: Long,
)

data class RetentionReport(
    val requestedRemoval: Int,
    val retainedFacts: Int,
    val authoritativeFactsPreserved: Boolean,
)

/**
 * A small, deterministic journal boundary for the future host daemon storage adapter.
 * Implementations must commit a fact and its idempotency key atomically.
 */
interface WorkflowJournal {
    fun append(streamId: String, draft: WorkflowFactDraft): AppendResult
    fun facts(streamId: String): List<WorkflowFact>
    fun rebuildProjection(streamId: String): WorkflowProjectionSnapshot
    fun projection(streamId: String): WorkflowProjectionSnapshot
    fun requestRetention(streamId: String, keepAtLeast: Int): RetentionReport
}

/**
 * In-memory reference implementation used by contract tests. A daemon's relational adapter
 * can use the same ordering, migration, and idempotency rules without exposing storage details.
 */
class InMemoryWorkflowJournal(
    private val beforeCommit: (() -> Unit)? = null,
) : WorkflowJournal {
    private val streams = mutableMapOf<String, MutableList<WorkflowFact>>()
    private val projections = mutableMapOf<String, WorkflowProjectionSnapshot>()

    @Synchronized
    override fun append(streamId: String, draft: WorkflowFactDraft): AppendResult {
        requireJournalId(streamId, "Fact stream id")
        val facts = streams.getOrPut(streamId) { mutableListOf() }
        facts.firstOrNull { it.idempotencyKey == draft.idempotencyKey }?.let {
            return AppendResult.Duplicate(it)
        }
        val fact = WorkflowFact(streamId, facts.size.toLong() + 1, draft)
        beforeCommit?.invoke()
        facts += fact
        projections.remove(streamId)
        return AppendResult.Appended(fact)
    }

    @Synchronized
    override fun facts(streamId: String): List<WorkflowFact> = streams[streamId].orEmpty().toList()

    @Synchronized
    override fun rebuildProjection(streamId: String): WorkflowProjectionSnapshot {
        val rebuilt = project(facts(streamId))
        projections[streamId] = rebuilt
        return rebuilt
    }

    @Synchronized
    override fun projection(streamId: String): WorkflowProjectionSnapshot =
        projections[streamId] ?: rebuildProjection(streamId)

    @Synchronized
    override fun requestRetention(streamId: String, keepAtLeast: Int): RetentionReport {
        require(keepAtLeast >= 0) { "Retention lower bound must not be negative" }
        val count = streams[streamId].orEmpty().size
        return RetentionReport(
            requestedRemoval = (count - keepAtLeast).coerceAtLeast(0),
            retainedFacts = count,
            authoritativeFactsPreserved = true,
        )
    }

    private fun project(facts: List<WorkflowFact>): WorkflowProjectionSnapshot {
        val rows = mutableMapOf<ProjectionKey, WorkflowProjectionRow>()
        facts.forEach { fact ->
            val migrated = migrate(fact)
            val key = ProjectionKey(migrated.draft.entityType, migrated.draft.entityId)
            val prior = rows[key]
            rows[key] = WorkflowProjectionRow(
                key = key,
                values = prior.orEmptyValues() + migrated.draft.values,
                schemaVersion = CURRENT_PROJECTION_SCHEMA,
                lastSequence = migrated.sequence,
            )
        }
        return WorkflowProjectionSnapshot(rows.toMap(), facts.lastOrNull()?.sequence ?: 0)
    }

    private fun migrate(fact: WorkflowFact): WorkflowFact {
        if (fact.draft.schemaVersion == CURRENT_PROJECTION_SCHEMA) return fact
        val values = fact.draft.values.toMutableMap()
        if (fact.draft.schemaVersion == 1 && "status" in values && "state" !in values) {
            values["state"] = values.remove("status")!!
        }
        return fact.copy(draft = fact.draft.copy(schemaVersion = CURRENT_PROJECTION_SCHEMA, values = values))
    }

    private fun WorkflowProjectionRow?.orEmptyValues(): Map<String, String> = this?.values.orEmpty()
}

private fun requireJournalId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_JOURNAL_ID_CHARS) { "$label is invalid" }
}

private const val CURRENT_PROJECTION_SCHEMA = 2
private const val MAX_JOURNAL_SCHEMA = 2
private const val MAX_JOURNAL_ID_CHARS = 512
private const val MAX_JOURNAL_FIELDS = 64
private const val MAX_JOURNAL_VALUE_CHARS = 4_096
