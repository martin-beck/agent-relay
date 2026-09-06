package dev.agentrelay.companion.api

/** Phone-owned, serialized companion state; transports only receive opaque projections. */
class CompanionPhoneCoordinator(
    private val maxQueuedProjections: Int = 32,
) {
    init {
        require(maxQueuedProjections > 0) { "Queue budget must be positive" }
    }

    private val enrollments = linkedMapOf<CompanionDeviceId, CompanionEnrollment>()
    private val queues = linkedMapOf<CompanionDeviceId, ArrayDeque<CompanionProjection>>()
    private val preferences = linkedMapOf<CompanionDeviceId, CompanionDevicePreferences>()
    private val acceptedMessageIds = linkedMapOf<CompanionDeviceId, MutableSet<String>>()

    fun enroll(enrollment: CompanionEnrollment): CompanionEnrollment {
        val current = enrollments[enrollment.device.id]
        require(current == null || enrollment.generation > current.generation) {
            "Enrollment generation must advance"
        }
        enrollments[enrollment.device.id] = enrollment
        queues.getOrPut(enrollment.device.id) { ArrayDeque() }
        acceptedMessageIds.getOrPut(enrollment.device.id) { linkedSetOf() }
        return enrollment
    }

    fun revoke(deviceId: CompanionDeviceId, changedAtEpochMillis: Long): CompanionEnrollment {
        val current = enrollments[deviceId] ?: error("Unknown companion device")
        return CompanionEnrollment(
            current.device,
            CompanionEnrollmentState.REVOKED,
            current.generation + 1,
            changedAtEpochMillis,
        ).also {
            enrollments[deviceId] = it
            queues[deviceId]?.clear()
        }
    }

    fun setPreferences(deviceId: CompanionDeviceId, value: CompanionDevicePreferences) {
        require(enrollments[deviceId]?.state == CompanionEnrollmentState.ENROLLED) {
            "Only enrolled devices may receive preferences"
        }
        preferences[deviceId] = value
    }

    fun preferences(deviceId: CompanionDeviceId): CompanionDevicePreferences? = preferences[deviceId]

    fun enqueue(projection: CompanionProjection): Boolean {
        val enrollment = enrollments[projection.deviceId]
        require(enrollment?.state == CompanionEnrollmentState.ENROLLED) {
            "Projection device is not enrolled"
        }
        val queue = queues.getOrPut(projection.deviceId) { ArrayDeque() }
        if (queue.any { it.projectionRevision == projection.projectionRevision }) return false
        while (queue.size >= maxQueuedProjections) queue.removeFirst()
        queue.addLast(projection)
        return true
    }

    fun pending(deviceId: CompanionDeviceId, nowEpochMillis: Long): List<CompanionProjection> {
        val queue = queues[deviceId] ?: return emptyList()
        queue.removeAll { !it.isFreshAt(nowEpochMillis) }
        return queue.toList()
    }

    fun reconcile(message: CompanionMessageEnvelope, nowEpochMillis: Long): CompanionReconciliationOutcome {
        val enrollment = enrollments[message.deviceId]
            ?: return CompanionReconciliationOutcome.REJECT_REVOKED
        if (enrollment.state != CompanionEnrollmentState.ENROLLED ||
            message.enrollmentGeneration != enrollment.generation
        ) {
            return CompanionReconciliationOutcome.REJECT_REVOKED
        }
        if (!message.isReplayableAt(nowEpochMillis)) return CompanionReconciliationOutcome.IGNORE_STALE
        val ids = acceptedMessageIds.getOrPut(message.deviceId) { linkedSetOf() }
        if (!ids.add(message.messageId)) return CompanionReconciliationOutcome.IGNORE_DUPLICATE
        return CompanionReconciliationOutcome.APPLY
    }
}

data class CompanionDevicePreferences(
    val notificationsEnabled: Boolean = true,
    val speechEnabled: Boolean = true,
    val preferredLocaleTag: String = "en-US",
) {
    init {
        require(preferredLocaleTag.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})?"))) {
            "Preference locale is invalid"
        }
    }
}
