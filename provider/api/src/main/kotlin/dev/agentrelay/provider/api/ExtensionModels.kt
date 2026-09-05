package dev.agentrelay.provider.api

@JvmInline
value class ExtensionId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) {
            "Extension id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

data class ExtensionApiVersion(val major: Int, val minor: Int) {
    init {
        require(major >= 1) { "Extension API major version must be positive" }
        require(minor >= 0) { "Extension API minor version must not be negative" }
    }
}

enum class ExtensionCapability { CONNECTOR, AGENT, WORKFLOW, PRESENTATION }

enum class ExtensionPermission { READ_FIELDS, PROPOSE_ACTIONS, REQUEST_APPROVAL, WRITE_FIELDS }

@JvmInline
value class ExtensionField(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) {
            "Extension field must be a stable identifier"
        }
    }
    override fun toString(): String = value
}

@JvmInline
value class ExtensionAction(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) {
            "Extension action must be a stable identifier"
        }
    }
    override fun toString(): String = value
}

data class ExtensionResourceBudget(
    val maxInputBytes: Int,
    val maxOutputBytes: Int,
    val maxInvocations: Int,
    val maxDurationMillis: Long,
) {
    init {
        require(maxInputBytes in 1..1_048_576) { "Input budget must be between 1 byte and 1 MiB" }
        require(maxOutputBytes in 1..1_048_576) { "Output budget must be between 1 byte and 1 MiB" }
        require(maxInvocations in 1..10_000) { "Invocation budget must be between 1 and 10000" }
        require(maxDurationMillis in 1..300_000) { "Duration budget must be between 1 ms and 5 minutes" }
    }
}

data class ExtensionLifecycle(
    val startup: Boolean = true,
    val shutdown: Boolean = true,
    val health: Boolean = true,
)

data class ExtensionManifest(
    val id: ExtensionId,
    val displayName: String,
    val apiVersion: ExtensionApiVersion,
    val schemaVersion: Int,
    val capabilities: Set<ExtensionCapability>,
    val permissions: Set<ExtensionPermission>,
    val requestedFields: Set<ExtensionField>,
    val triggers: Set<ExtensionField>,
    val actions: Set<ExtensionAction>,
    val budget: ExtensionResourceBudget,
    val lifecycle: ExtensionLifecycle = ExtensionLifecycle(),
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 80) { "Extension display name is invalid" }
        require(schemaVersion >= 1) { "Extension schema version must be positive" }
        require(capabilities.isNotEmpty()) { "Extension must declare a capability" }
        require(triggers.isNotEmpty() || actions.isNotEmpty()) { "Extension must declare a trigger or action" }
        require(ExtensionPermission.READ_FIELDS !in permissions || requestedFields.isNotEmpty()) {
            "Read permission requires declared fields"
        }
        require(ExtensionPermission.WRITE_FIELDS !in permissions || actions.isNotEmpty()) {
            "Write permission requires declared actions"
        }
    }

    fun compatibleWith(hostApi: ExtensionApiVersion, hostSchema: Int): Boolean =
        apiVersion.major == hostApi.major && apiVersion.minor <= hostApi.minor && schemaVersion == hostSchema

    fun narrowedPermissions(requested: Set<ExtensionPermission>): Set<ExtensionPermission> =
        requested intersect permissions
}

enum class ExtensionRejection {
    UNKNOWN_EXTENSION,
    INCOMPATIBLE_VERSION,
    REVOKED,
    UNDECLARED_FIELD,
    UNDECLARED_ACTION,
    PERMISSION_NOT_GRANTED,
    INPUT_TOO_LARGE,
    INVOCATION_BUDGET_EXCEEDED,
    STALE_REVISION,
    CANCELLED,
}

data class ExtensionInvocation(
    val extensionId: ExtensionId,
    val idempotencyKey: String,
    val trigger: ExtensionField,
    val input: Map<ExtensionField, String>,
    val requestedPermissions: Set<ExtensionPermission> = emptySet(),
    val revision: Long,
) {
    init {
        require(idempotencyKey.matches(Regex("[A-Za-z0-9._-]{8,128}"))) { "Invalid idempotency key" }
        require(revision >= 0) { "Revision must not be negative" }
        require(input.values.none { it.length > 16_384 }) { "Input field value is too large" }
    }
}

sealed interface ExtensionInvocationDecision {
    data class Accepted(val permissions: Set<ExtensionPermission>) : ExtensionInvocationDecision
    data class Rejected(val reason: ExtensionRejection) : ExtensionInvocationDecision
}

class ExtensionInvocationLedger {
    private val completed = mutableMapOf<String, ExtensionInvocationDecision.Accepted>()
    private val counts = mutableMapOf<ExtensionId, Int>()

    fun evaluate(
        manifest: ExtensionManifest?,
        invocation: ExtensionInvocation,
        hostApi: ExtensionApiVersion,
        hostSchema: Int,
        grantedPermissions: Set<ExtensionPermission>,
        inputBytes: Int,
        currentRevision: Long,
        revoked: Boolean = false,
        cancelled: Boolean = false,
    ): ExtensionInvocationDecision {
        if (cancelled) return ExtensionInvocationDecision.Rejected(ExtensionRejection.CANCELLED)
        if (manifest == null || manifest.id != invocation.extensionId) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.UNKNOWN_EXTENSION)
        }
        if (!manifest.compatibleWith(hostApi, hostSchema)) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.INCOMPATIBLE_VERSION)
        }
        if (revoked) return ExtensionInvocationDecision.Rejected(ExtensionRejection.REVOKED)
        if (invocation.revision != currentRevision) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.STALE_REVISION)
        }
        if (invocation.trigger !in manifest.triggers) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.UNDECLARED_ACTION)
        }
        if (invocation.input.keys.any { it !in manifest.requestedFields }) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.UNDECLARED_FIELD)
        }
        if (inputBytes !in 0..manifest.budget.maxInputBytes) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.INPUT_TOO_LARGE)
        }
        if (!manifest.permissions.containsAll(invocation.requestedPermissions) ||
            !grantedPermissions.containsAll(invocation.requestedPermissions)
        ) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.PERMISSION_NOT_GRANTED)
        }
        completed[invocation.idempotencyKey]?.let { return it }
        val count = counts.getOrDefault(invocation.extensionId, 0)
        if (count >= manifest.budget.maxInvocations) {
            return ExtensionInvocationDecision.Rejected(ExtensionRejection.INVOCATION_BUDGET_EXCEEDED)
        }
        val accepted = ExtensionInvocationDecision.Accepted(manifest.narrowedPermissions(invocation.requestedPermissions))
        counts[invocation.extensionId] = count + 1
        completed[invocation.idempotencyKey] = accepted
        return accepted
    }
}
