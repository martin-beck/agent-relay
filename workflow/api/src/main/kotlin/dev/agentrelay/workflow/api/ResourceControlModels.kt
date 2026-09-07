package dev.agentrelay.workflow.api

/** Dimensions that an admitted operation must account for before it starts. */
enum class ResourceKind {
    TIME_MILLIS,
    MONEY_MICROS,
    COMPUTE_MILLIS,
    STORAGE_BYTES,
    NETWORK_BYTES,
}

enum class UnknownPricePolicy {
    REJECT,
    REQUIRE_APPROVAL,
}

data class ResourceControlPolicy(
    val limits: Map<ResourceKind, Long>,
    val unknownPricePolicy: UnknownPricePolicy = UnknownPricePolicy.REJECT,
) {
    init {
        require(limits.isNotEmpty()) { "At least one resource limit is required" }
        require(limits.keys == ResourceKind.entries.toSet()) { "All resource limits must be declared" }
        limits.values.forEach { require(it > 0) { "Resource limits must be positive" } }
    }
}

data class ResourceEstimate(
    val units: Map<ResourceKind, Long>,
    val hasUnknownPrice: Boolean = false,
) {
    init {
        require(units.keys == ResourceKind.entries.toSet()) { "All resource estimates must be declared" }
        units.values.forEach { require(it >= 0) { "Resource estimates cannot be negative" } }
    }
}

data class MeterReport(val units: Map<ResourceKind, Long>) {
    init {
        require(units.keys == ResourceKind.entries.toSet()) { "All meter dimensions must be declared" }
        units.values.forEach { require(it >= 0) { "Meter readings cannot be negative" } }
    }
}

enum class ReservationRejection {
    DUPLICATE_OPERATION,
    UNKNOWN_PRICE,
    BUDGET_EXHAUSTED,
}

sealed interface ReservationOutcome {
    data class Reserved(val operationId: String) : ReservationOutcome

    data class Rejected(val reason: ReservationRejection) : ReservationOutcome
}

sealed interface ReconciliationOutcome {
    data object Reconciled : ReconciliationOutcome

    data class HardStopped(val exceeded: Set<ResourceKind>) : ReconciliationOutcome

    data object UnknownOperation : ReconciliationOutcome
}

/**
 * A serialized, conservative ledger for admitted work.
 *
 * Reservations are counted before an operation starts, so concurrent callers
 * cannot collectively exceed a limit. Reconciliation never decreases usage;
 * a provider report that exceeds a limit permanently hard-stops new work.
 */
class AtomicResourceLedger(private val policy: ResourceControlPolicy) {
    private val usage = ResourceKind.entries.associateWith { 0L }.toMutableMap()
    private val reservations = linkedMapOf<String, ResourceEstimate>()
    private var hardStopped = false

    @Synchronized
    fun reserve(operationId: String, estimate: ResourceEstimate, approved: Boolean = false): ReservationOutcome {
        require(operationId.isNotBlank()) { "Operation id is required" }
        if (operationId in reservations) return ReservationOutcome.Rejected(ReservationRejection.DUPLICATE_OPERATION)
        if (estimate.hasUnknownPrice && !approved && policy.unknownPricePolicy == UnknownPricePolicy.REQUIRE_APPROVAL) {
            return ReservationOutcome.Rejected(ReservationRejection.UNKNOWN_PRICE)
        }
        if (estimate.hasUnknownPrice && policy.unknownPricePolicy == UnknownPricePolicy.REJECT) {
            return ReservationOutcome.Rejected(ReservationRejection.UNKNOWN_PRICE)
        }
        if (hardStopped || exceeds(estimate.units)) {
            return ReservationOutcome.Rejected(ReservationRejection.BUDGET_EXHAUSTED)
        }
        reservations[operationId] = estimate
        return ReservationOutcome.Reserved(operationId)
    }

    @Synchronized
    fun reconcile(operationId: String, report: MeterReport): ReconciliationOutcome {
        if (operationId !in reservations) return ReconciliationOutcome.UnknownOperation
        reservations.remove(operationId)
        ResourceKind.entries.forEach { kind ->
            usage[kind] = maxOf(usage.getValue(kind), report.units.getValue(kind))
        }
        val exceeded = ResourceKind.entries.filterTo(linkedSetOf()) { usage.getValue(it) > policy.limits.getValue(it) }
        if (exceeded.isNotEmpty()) hardStopped = true
        return if (exceeded.isEmpty()) ReconciliationOutcome.Reconciled else ReconciliationOutcome.HardStopped(exceeded)
    }

    @Synchronized
    fun snapshot(): Map<ResourceKind, Long> = usage.toMap()

    @Synchronized
    fun isHardStopped(): Boolean = hardStopped

    private fun exceeds(candidate: Map<ResourceKind, Long>): Boolean =
        ResourceKind.entries.any { kind ->
            val reserved = reservations.values.sumOf { it.units.getValue(kind) }
            usage.getValue(kind) + reserved + candidate.getValue(kind) > policy.limits.getValue(kind)
        }
}
