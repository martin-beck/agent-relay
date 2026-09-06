package dev.agentrelay.companion.api

/**
 * Transport boundary for a phone-to-Wear message adapter.
 *
 * An Android Data Layer implementation belongs outside this contract. This API keeps
 * addresses, node ids, credentials, and transport-specific handles out of durable state.
 */
fun interface WearMessageTransport {
    fun send(message: CompanionMessageEnvelope): CompanionDeliveryOutcome
}

data class WearDispatchResult(
    val deviceId: CompanionDeviceId,
    val attemptedRevisions: List<Long>,
    val deliveredRevisions: List<Long>,
    val outcomes: Map<Long, CompanionDeliveryOutcome>,
) {
    init {
        require(attemptedRevisions == attemptedRevisions.sorted()) { "Attempted revisions must be ordered" }
        require(deliveredRevisions.all { it in attemptedRevisions }) {
            "Delivered revisions must be attempted"
        }
    }
}

/** Phone-owned dispatcher that retains messages until the transport acknowledges them. */
class WearProjectionDispatcher(
    private val coordinator: CompanionPhoneCoordinator,
    private val transport: WearMessageTransport,
) {
    fun dispatch(deviceId: CompanionDeviceId, nowEpochMillis: Long): WearDispatchResult {
        val pending = coordinator.pending(deviceId, nowEpochMillis)
        val outcomes = linkedMapOf<Long, CompanionDeliveryOutcome>()
        val delivered = mutableListOf<Long>()
        pending.forEach { projection ->
            val message = CompanionMessageEnvelope(
                deviceId = projection.deviceId,
                enrollmentGeneration = coordinator.enrollmentGeneration(deviceId),
                revision = projection.projectionRevision,
                messageId = projection.idempotencyKey,
                issuedAtEpochMillis = projection.issuedAtEpochMillis,
                expiresAtEpochMillis = projection.expiresAtEpochMillis,
                body = projection.payload,
                signature = projection.authenticationTag,
            )
            val outcome = transport.send(message)
            outcomes[projection.projectionRevision] = outcome
            if (outcome == CompanionDeliveryOutcome.ACCEPTED ||
                outcome == CompanionDeliveryOutcome.DUPLICATE
            ) {
                delivered += projection.projectionRevision
            }
        }
        delivered.maxOrNull()?.let { coordinator.acknowledge(deviceId, it) }
        return WearDispatchResult(
            deviceId = deviceId,
            attemptedRevisions = pending.map { it.projectionRevision },
            deliveredRevisions = delivered,
            outcomes = outcomes,
        )
    }
}
