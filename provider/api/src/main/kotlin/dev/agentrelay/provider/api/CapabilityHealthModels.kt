package dev.agentrelay.provider.api

@JvmInline
value class CapabilityRecordId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) {
            "Capability record id must be a stable lowercase identifier"
        }
    }
}

enum class CapabilitySubjectKind {
    HOST,
    CONNECTION,
    EXECUTION_TARGET,
    AGENT,
    MODEL,
    PROVIDER,
}

enum class CapabilityHealthState {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    UNKNOWN,
}

data class CapabilityHealthObservation(
    val state: CapabilityHealthState,
    val observedAtEpochSeconds: Long,
    val reasonCode: String? = null,
    val consecutiveFailures: Int = 0,
) {
    init {
        require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
        require(consecutiveFailures >= 0) { "Failure count must not be negative" }
        require(reasonCode == null || reasonCode.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
            "Reason code must be a bounded lowercase identifier"
        }
    }
}

data class CapabilityHealthRecord(
    val id: CapabilityRecordId,
    val subjectKind: CapabilitySubjectKind,
    val capabilityNames: Set<String>,
    val health: CapabilityHealthObservation,
    val dataLocationClass: String? = null,
) {
    init {
        require(capabilityNames.isNotEmpty()) { "At least one capability is required" }
        require(capabilityNames.size <= MAX_CAPABILITIES) { "Capability set is too large" }
        require(capabilityNames.all { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) }) {
            "Capability names must be bounded lowercase identifiers"
        }
        require(dataLocationClass == null || dataLocationClass.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
            "Data location class must be a bounded lowercase identifier"
        }
    }
}

data class CapabilityHealthSnapshot(
    val records: List<CapabilityHealthRecord>,
    val generatedAtEpochSeconds: Long,
) {
    init {
        require(records.size <= MAX_RECORDS) { "Snapshot contains too many records" }
        require(generatedAtEpochSeconds >= 0) { "Snapshot time must not be negative" }
        require(records.map { it.id }.distinct().size == records.size) {
            "Snapshot record identifiers must be unique"
        }
    }
}

const val MAX_CAPABILITIES = 64
const val MAX_RECORDS = 256
