package dev.agentrelay.companion.api

/** The phone's durable decision about offering the companion package to one device. */
enum class WearInstallDecisionState { PENDING_OFFER, INSTALLED, DECLINED, UNAVAILABLE }

/** Package identity scopes a decline so a new companion version can be offered explicitly. */
data class WearInstallPolicy(
    val packageName: String,
    val versionCode: Long,
) {
    init {
        require(packageName.matches(PACKAGE_PATTERN)) { "Wear package name is invalid" }
        require(versionCode > 0) { "Wear package version is invalid" }
    }

    internal fun encoded(): String = "$packageName,$versionCode"
}

data class WearInstallDecision(
    val deviceId: CompanionDeviceId,
    val policy: WearInstallPolicy,
    val state: WearInstallDecisionState,
    val changedAtEpochMillis: Long,
) {
    init {
        require(changedAtEpochMillis >= 0) { "Decision timestamp is invalid" }
    }
}

/** Versioned durable document. It contains stable identities only, never transport metadata. */
data class WearInstallDecisionDocument(
    val schemaVersion: Int,
    val decisions: List<WearInstallDecision>,
) {
    init {
        require(schemaVersion == CURRENT_DECISION_SCHEMA) { "Unsupported Wear decision schema" }
        require(decisions.zipWithNext().all { (left, right) -> left.deviceId.value < right.deviceId.value }) {
            "Wear decisions must be sorted by stable identity"
        }
        require(decisions.map { it.deviceId }.toSet().size == decisions.size) {
            "Wear decisions must have unique device identities"
        }
    }

    fun encode(): String = buildString {
        append(schemaVersion)
        decisions.forEach { decision ->
            append('|').append(decision.deviceId.value)
                .append(',').append(decision.policy.packageName)
                .append(',').append(decision.policy.versionCode)
                .append(',').append(decision.state.name)
                .append(',').append(decision.changedAtEpochMillis)
        }
    }

    companion object {
        fun empty(): WearInstallDecisionDocument = WearInstallDecisionDocument(CURRENT_DECISION_SCHEMA, emptyList())

        /** Reads schema 1 and writes it back as the current schema without changing decisions. */
        fun decode(encoded: String): WearInstallDecisionDocument {
            val fields = encoded.split('|')
            require(fields.isNotEmpty()) { "Wear decision document is empty" }
            val schema = fields.first().toIntOrNull() ?: error("Wear decision schema is invalid")
            require(schema in LEGACY_DECISION_SCHEMA..CURRENT_DECISION_SCHEMA) {
                "Unsupported Wear decision schema"
            }
            val decisions = fields.drop(1).map { record ->
                val values = record.split(',')
                require(values.size == RECORD_FIELD_COUNT) { "Wear decision record is invalid" }
                WearInstallDecision(
                    deviceId = CompanionDeviceId(values[0]),
                    policy = WearInstallPolicy(values[1], values[2].toLongOrNull() ?: error("Wear version is invalid")),
                    state = runCatching { WearInstallDecisionState.valueOf(values[3]) }
                        .getOrElse { error("Wear decision state is invalid") },
                    changedAtEpochMillis = values[4].toLongOrNull() ?: error("Wear decision time is invalid"),
                )
            }.sortedBy { it.deviceId.value }
            return WearInstallDecisionDocument(CURRENT_DECISION_SCHEMA, decisions)
        }
    }
}

/** Narrow durable boundary; Android adapters can back it with encrypted app storage. */
interface WearInstallDecisionPersistence {
    fun read(): String?

    fun write(document: String)
}

class WearInstallDecisionStore(
    private val persistence: WearInstallDecisionPersistence,
) {
    fun decision(deviceId: CompanionDeviceId): WearInstallDecision? = load().decisions.firstOrNull { it.deviceId == deviceId }

    fun observe(
        device: WearDeviceDiscoveryRecord,
        policy: WearInstallPolicy,
        observedAtEpochMillis: Long,
    ): WearInstallDecision {
        require(observedAtEpochMillis >= 0) { "Observation time is invalid" }
        val previous = decision(device.deviceId)
        val nextState = when {
            device.support != WearDeviceSupport.SUPPORTED -> WearInstallDecisionState.UNAVAILABLE
            device.packageState == WearCompanionPackageState.INSTALLED -> WearInstallDecisionState.INSTALLED
            previous?.policy == policy && previous.state == WearInstallDecisionState.DECLINED ->
                WearInstallDecisionState.DECLINED
            else -> WearInstallDecisionState.PENDING_OFFER
        }
        return save(WearInstallDecision(device.deviceId, policy, nextState, observedAtEpochMillis))
    }

    fun recordDeclined(deviceId: CompanionDeviceId, policy: WearInstallPolicy, changedAtEpochMillis: Long): WearInstallDecision =
        save(WearInstallDecision(deviceId, policy, WearInstallDecisionState.DECLINED, changedAtEpochMillis))

    fun recordInstalled(deviceId: CompanionDeviceId, policy: WearInstallPolicy, changedAtEpochMillis: Long): WearInstallDecision =
        save(WearInstallDecision(deviceId, policy, WearInstallDecisionState.INSTALLED, changedAtEpochMillis))

    /** Explicit reset is the only way to ask again for the same device and policy. */
    fun reset(deviceId: CompanionDeviceId): Boolean {
        val document = load()
        if (document.decisions.none { it.deviceId == deviceId }) return false
        persist(WearInstallDecisionDocument(CURRENT_DECISION_SCHEMA, document.decisions.filterNot { it.deviceId == deviceId }))
        return true
    }

    private fun save(decision: WearInstallDecision): WearInstallDecision {
        require(decision.changedAtEpochMillis >= 0) { "Decision time is invalid" }
        val next = load().decisions.filterNot { it.deviceId == decision.deviceId } + decision
        val document = WearInstallDecisionDocument(CURRENT_DECISION_SCHEMA, next.sortedBy { it.deviceId.value })
        persist(document)
        return decision
    }

    private fun load(): WearInstallDecisionDocument = persistence.read()?.let(WearInstallDecisionDocument::decode)
        ?: WearInstallDecisionDocument.empty()

    private fun persist(document: WearInstallDecisionDocument) {
        persistence.write(document.encode())
    }
}

private const val LEGACY_DECISION_SCHEMA = 1
private const val CURRENT_DECISION_SCHEMA = 2
private const val RECORD_FIELD_COUNT = 5
private val PACKAGE_PATTERN = Regex("dev\\.agentrelay\\.wear\\.[a-z][a-z0-9_]{1,31}")
