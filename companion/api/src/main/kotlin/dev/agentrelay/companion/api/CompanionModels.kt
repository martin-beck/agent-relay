package dev.agentrelay.companion.api

/** Opaque, phone-mediated identity; transport addresses and user secrets never belong here. */
@JvmInline
value class CompanionDeviceId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "Companion device id is invalid" }
    }
}

enum class CompanionDeviceType { WATCH, PHONE, TABLET, DESKTOP, OTHER }

enum class CompanionCapability {
    NOTIFICATIONS,
    SPEECH_INPUT,
    SPEECH_OUTPUT,
    SAFE_ACTIONS,
}

enum class CompanionReachability { ONLINE, OFFLINE, UNKNOWN }

enum class CompanionPrivacyClass { PUBLIC_SUMMARY, PRIVATE_SUMMARY, SENSITIVE_REDACTED }

enum class CompanionActionClass {
    NOTIFICATION,
    NAVIGATION,
    SPEECH_INPUT_REQUEST,
    SPEECH_OUTPUT,
    HIGH_RISK_APPROVAL,
}

/** Device metadata is bounded and excludes endpoint addresses and credential material. */
data class CompanionDeviceIdentity(
    val id: CompanionDeviceId,
    val type: CompanionDeviceType,
    val alias: String,
    val softwareVersion: String,
    val schemaVersion: Int,
    val capabilities: Set<CompanionCapability>,
    val reachability: CompanionReachability,
    val batteryPercent: Int?,
    val lastSeenEpochMillis: Long?,
) {
    init {
        requireBounded(alias, "Companion alias", MAX_ALIAS_CHARS)
        requireBounded(softwareVersion, "Companion software version", MAX_VERSION_CHARS)
        require(schemaVersion in 1..MAX_SCHEMA_VERSION) { "Companion schema version is invalid" }
        require(capabilities.isNotEmpty()) { "Companion must advertise a capability" }
        require(batteryPercent == null || batteryPercent in 0..100) { "Companion battery is invalid" }
        require(lastSeenEpochMillis == null || lastSeenEpochMillis >= 0) {
            "Companion last-seen time is invalid"
        }
    }
}

enum class CompanionEnrollmentState { ENROLLED, REPLACED, REVOKED }

/** Enrollment transitions are explicit; a replacement never silently revives a revoked device. */
data class CompanionEnrollment(
    val device: CompanionDeviceIdentity,
    val state: CompanionEnrollmentState,
    val generation: Long,
    val changedAtEpochMillis: Long,
) {
    init {
        require(generation > 0) { "Enrollment generation must be positive" }
        require(changedAtEpochMillis >= 0) { "Enrollment change time is invalid" }
        require(state != CompanionEnrollmentState.REPLACED || generation > 1) {
            "Replacement must advance enrollment generation"
        }
    }
}

/** Authenticated, replay-safe projection envelope. Payload is opaque to this API. */
data class CompanionProjection(
    val deviceId: CompanionDeviceId,
    val projectionRevision: Long,
    val idempotencyKey: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val privacyClass: CompanionPrivacyClass,
    val actionClass: CompanionActionClass,
    val payload: String,
    val authenticationTag: String,
) {
    init {
        require(projectionRevision > 0) { "Projection revision must be positive" }
        requireBounded(idempotencyKey, "Projection idempotency key", MAX_IDEMPOTENCY_CHARS)
        require(issuedAtEpochMillis >= 0) { "Projection issue time is invalid" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Projection expiry is invalid" }
        require(payload.isNotEmpty() && payload.length <= MAX_PAYLOAD_CHARS) {
            "Projection payload is invalid or too large"
        }
        requireBounded(authenticationTag, "Projection authentication tag", MAX_AUTH_TAG_CHARS)
        require(actionClass != CompanionActionClass.HIGH_RISK_APPROVAL) {
            "Companions cannot approve high-risk actions"
        }
        require(privacyClass != CompanionPrivacyClass.PUBLIC_SUMMARY || !payload.contains("secret", true)) {
            "Public projection cannot contain secret material"
        }
    }

    fun isFreshAt(epochMillis: Long): Boolean =
        epochMillis in issuedAtEpochMillis until expiresAtEpochMillis
}

enum class CompanionDeliveryOutcome { ACCEPTED, DUPLICATE, STALE, EXPIRED, REVOKED, UNKNOWN }

private val ID_PATTERN = Regex("cd_v1_[A-Za-z0-9_-]{8,64}")
private val TOKEN_PATTERN = Regex("[A-Za-z0-9._~-]{1,128}")
private const val MAX_ALIAS_CHARS = 64
private const val MAX_VERSION_CHARS = 32
private const val MAX_SCHEMA_VERSION = 1_000_000
private const val MAX_IDEMPOTENCY_CHARS = 128
private const val MAX_PAYLOAD_CHARS = 16_384
private const val MAX_AUTH_TAG_CHARS = 512

private fun requireBounded(value: String, field: String, max: Int) {
    require(value.isNotBlank() && value.length <= max) { "$field is invalid or too long" }
    require(!value.contains('\u0000')) { "$field contains a NUL" }
}

private fun requireBounded(value: String, field: String, max: Int, pattern: Regex = TOKEN_PATTERN) {
    requireBounded(value, field, max)
    require(value.matches(pattern)) { "$field contains unsupported characters" }
}
