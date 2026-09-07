package dev.agentrelay.provider.api

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

@JvmInline
value class IntegrationGrantId(val value: String) {
    init {
        require(value.matches(STABLE_INTEGRATION_ID)) { "Integration grant id must be stable" }
    }
}

@JvmInline
value class IntegrationDeliveryId(val value: String) {
    init {
        require(value.matches(STABLE_INTEGRATION_ID)) { "Integration delivery id must be stable" }
    }
}

enum class IntegrationCapability {
    ISSUE_READ,
    ISSUE_COMMENT,
    CI_STATUS_READ,
    CI_RETRY,
}

enum class IntegrationDataScope {
    ISSUE_METADATA,
    ISSUE_BODY,
    CI_METADATA,
    CI_LOGS,
}

enum class IntegrationEffectScope {
    ISSUE_COMMENT,
    CI_RETRY,
}

data class IntegrationCapabilityContract(
    val capability: IntegrationCapability,
    val dataScopes: Set<IntegrationDataScope>,
    val effectScopes: Set<IntegrationEffectScope>,
) {
    init {
        require(dataScopes.isNotEmpty()) { "A connector capability must declare readable data" }
    }
}

data class PermissionedConnectorDescriptor(
    val connectorId: ConnectorId,
    val displayName: String,
    val contracts: Set<IntegrationCapabilityContract>,
    val rateLimit: ConnectorRateLimit,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 80) {
            "Connector display name must be bounded"
        }
        require(contracts.isNotEmpty()) { "Connector must declare capabilities" }
        require(contracts.map { it.capability }.toSet().size == contracts.size) {
            "Connector capability contracts must be unique"
        }
    }

    fun contract(capability: IntegrationCapability): IntegrationCapabilityContract? =
        contracts.firstOrNull { it.capability == capability }
}

data class ConnectorCredentialLease(
    val reference: ConnectorAuthenticationReference,
    val generation: Long,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(generation > 0) { "Credential generation must be positive" }
        require(expiresAtEpochSeconds > 0) { "Credential lease must expire" }
    }
}

private data class StoredCredential(
    val lease: ConnectorCredentialLease,
    val revoked: Boolean,
)

enum class CredentialLeaseStatus {
    ACTIVE,
    UNAVAILABLE,
    STALE,
    EXPIRED,
    REVOKED,
}

/** Stores only opaque approved-store references and lease metadata, never credential material. */
class ConnectorCredentialRegistry(private val nowEpochSeconds: () -> Long) {
    private val credentials = mutableMapOf<ConnectorAuthenticationReference, StoredCredential>()

    fun install(lease: ConnectorCredentialLease) {
        require(credentials[lease.reference]?.revoked != false) {
            "An active credential reference must be rotated, not overwritten"
        }
        credentials[lease.reference] = StoredCredential(lease, revoked = false)
    }

    fun rotate(
        currentReference: ConnectorAuthenticationReference,
        replacement: ConnectorCredentialLease,
    ) {
        val current = credentials[currentReference]
            ?: throw IllegalArgumentException("Credential reference is unavailable")
        require(!current.revoked) { "Revoked credentials cannot be rotated" }
        require(replacement.reference != currentReference) { "Rotation requires a new opaque reference" }
        require(replacement.generation > current.lease.generation) {
            "Rotation must increase the credential generation"
        }
        credentials[currentReference] = current.copy(revoked = true)
        install(replacement)
    }

    fun revoke(reference: ConnectorAuthenticationReference) {
        credentials[reference]?.let { credentials[reference] = it.copy(revoked = true) }
    }

    fun current(reference: ConnectorAuthenticationReference): ConnectorCredentialLease? =
        credentials[reference]?.takeUnless { it.revoked }?.lease

    fun isRevoked(reference: ConnectorAuthenticationReference): Boolean =
        credentials[reference]?.revoked == true

    fun validate(lease: ConnectorCredentialLease): CredentialLeaseStatus {
        val stored = credentials[lease.reference] ?: return CredentialLeaseStatus.UNAVAILABLE
        return when {
            stored.revoked -> CredentialLeaseStatus.REVOKED
            stored.lease.generation != lease.generation -> CredentialLeaseStatus.STALE
            nowEpochSeconds() >= stored.lease.expiresAtEpochSeconds -> CredentialLeaseStatus.EXPIRED
            else -> CredentialLeaseStatus.ACTIVE
        }
    }
}

data class IntegrationGrant(
    val id: IntegrationGrantId,
    val connectorId: ConnectorId,
    val capabilities: Set<IntegrationCapability>,
    val dataScopes: Set<IntegrationDataScope>,
    val effectScopes: Set<IntegrationEffectScope>,
    val credential: ConnectorCredentialLease,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(capabilities.isNotEmpty()) { "Integration grant must allow a capability" }
        require(dataScopes.isNotEmpty()) { "Integration grant must allow bounded data" }
        require(expiresAtEpochSeconds > 0) { "Integration grant must expire" }
    }
}

enum class IntegrationDenial {
    CONNECTOR_UNAVAILABLE,
    UNDECLARED_CAPABILITY,
    UNDECLARED_SCOPE,
    GRANT_EXPIRED,
    CREDENTIAL_UNAVAILABLE,
    CREDENTIAL_STALE,
    CREDENTIAL_EXPIRED,
    CREDENTIAL_REVOKED,
    DELIVERY_CONFLICT,
}

sealed interface IntegrationNegotiation {
    data class Allowed(val grant: IntegrationGrant) : IntegrationNegotiation

    data class Denied(val reason: IntegrationDenial) : IntegrationNegotiation
}

sealed interface IntegrationOperation {
    val capability: IntegrationCapability
    val dataScopes: Set<IntegrationDataScope>
    val effectScopes: Set<IntegrationEffectScope>

    fun requestDigest(): String
}

sealed interface IssueTrackerOperation : IntegrationOperation {
    data class ReadIssue(
        val projectKey: String,
        val issueKey: String,
        val includeBody: Boolean,
    ) : IssueTrackerOperation {
        init {
            requireStableRemoteKey(projectKey, "Issue project")
            requireStableRemoteKey(issueKey, "Issue key")
        }

        override val capability = IntegrationCapability.ISSUE_READ
        override val dataScopes = buildSet {
            add(IntegrationDataScope.ISSUE_METADATA)
            if (includeBody) add(IntegrationDataScope.ISSUE_BODY)
        }
        override val effectScopes = emptySet<IntegrationEffectScope>()
        override fun requestDigest(): String = digest("issue-read|$projectKey|$issueKey|$includeBody")
    }

    data class AddComment(
        val projectKey: String,
        val issueKey: String,
        val comment: String,
    ) : IssueTrackerOperation {
        init {
            requireStableRemoteKey(projectKey, "Issue project")
            requireStableRemoteKey(issueKey, "Issue key")
            require(comment.isNotBlank() && comment.length <= MAX_EFFECT_CHARS) {
                "Issue comment must be non-empty and bounded"
            }
        }

        override val capability = IntegrationCapability.ISSUE_COMMENT
        override val dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA)
        override val effectScopes = setOf(IntegrationEffectScope.ISSUE_COMMENT)
        override fun requestDigest(): String =
            digest("issue-comment|$projectKey|$issueKey|${digest(comment)}")
    }
}

sealed interface CiConnectorOperation : IntegrationOperation {
    data class ReadRun(
        val repositoryKey: String,
        val runId: String,
        val includeLogs: Boolean,
    ) : CiConnectorOperation {
        init {
            requireStableRemoteKey(repositoryKey, "CI repository")
            requireStableRemoteKey(runId, "CI run")
        }

        override val capability = IntegrationCapability.CI_STATUS_READ
        override val dataScopes = buildSet {
            add(IntegrationDataScope.CI_METADATA)
            if (includeLogs) add(IntegrationDataScope.CI_LOGS)
        }
        override val effectScopes = emptySet<IntegrationEffectScope>()
        override fun requestDigest(): String = digest("ci-read|$repositoryKey|$runId|$includeLogs")
    }

    data class RetryRun(
        val repositoryKey: String,
        val runId: String,
    ) : CiConnectorOperation {
        init {
            requireStableRemoteKey(repositoryKey, "CI repository")
            requireStableRemoteKey(runId, "CI run")
        }

        override val capability = IntegrationCapability.CI_RETRY
        override val dataScopes = setOf(IntegrationDataScope.CI_METADATA)
        override val effectScopes = setOf(IntegrationEffectScope.CI_RETRY)
        override fun requestDigest(): String = digest("ci-retry|$repositoryKey|$runId")
    }
}

data class IssueTrackerSnapshot(
    val key: String,
    val title: String,
    val state: String,
    val body: String,
    val observedAtEpochSeconds: Long = 1,
) {
    init {
        requireStableRemoteKey(key, "Issue key")
        require(title.isNotBlank() && title.length <= MAX_RESULT_FIELD_CHARS)
        require(state.matches(STABLE_REMOTE_KEY))
        require(body.length <= MAX_EFFECT_CHARS)
        require(observedAtEpochSeconds > 0)
    }
}

sealed interface IntegrationBackendResult {
    data class Records(val records: List<ConnectorRecord>) : IntegrationBackendResult {
        init {
            require(records.isNotEmpty() && records.size <= MAX_INTEGRATION_RECORDS) {
                "Integration record count must be bounded"
            }
        }
    }

    data class EffectAccepted(val receiptId: String) : IntegrationBackendResult {
        init {
            require(receiptId.matches(STABLE_REMOTE_KEY)) { "Effect receipt must be a stable identifier" }
        }
    }

    data class RetryableFailure(
        val code: String,
        val retryAfterEpochSeconds: Long,
    ) : IntegrationBackendResult {
        init {
            requireFailureCode(code)
            require(retryAfterEpochSeconds > 0)
        }
    }

    data class Failed(val code: String) : IntegrationBackendResult {
        init {
            requireFailureCode(code)
        }
    }

    /** The service may have accepted an effect, so this delivery must not be replayed automatically. */
    data class Uncertain(val code: String) : IntegrationBackendResult {
        init {
            requireFailureCode(code)
        }
    }
}

interface IssueTrackerService {
    suspend fun readIssue(
        authentication: ConnectorAuthenticationReference,
        projectKey: String,
        issueKey: String,
    ): IssueTrackerSnapshot

    suspend fun addComment(
        authentication: ConnectorAuthenticationReference,
        projectKey: String,
        issueKey: String,
        comment: String,
    ): IntegrationBackendResult
}

interface CiService {
    suspend fun readRun(
        authentication: ConnectorAuthenticationReference,
        repositoryKey: String,
        runId: String,
    ): IntegrationBackendResult

    suspend fun retryRun(
        authentication: ConnectorAuthenticationReference,
        repositoryKey: String,
        runId: String,
    ): IntegrationBackendResult
}

interface PermissionedIntegrationConnector {
    val descriptor: PermissionedConnectorDescriptor

    suspend fun execute(
        operation: IntegrationOperation,
        authentication: ConnectorAuthenticationReference,
    ): IntegrationBackendResult
}

class IssueTrackerConnector(
    private val service: IssueTrackerService,
    rateLimit: ConnectorRateLimit = ConnectorRateLimit(60, 60),
) : PermissionedIntegrationConnector {
    override val descriptor = PermissionedConnectorDescriptor(
        connectorId = ID,
        displayName = "Issue tracker",
        contracts = setOf(
            IntegrationCapabilityContract(
                IntegrationCapability.ISSUE_READ,
                setOf(IntegrationDataScope.ISSUE_METADATA, IntegrationDataScope.ISSUE_BODY),
                emptySet(),
            ),
            IntegrationCapabilityContract(
                IntegrationCapability.ISSUE_COMMENT,
                setOf(IntegrationDataScope.ISSUE_METADATA),
                setOf(IntegrationEffectScope.ISSUE_COMMENT),
            ),
        ),
        rateLimit = rateLimit,
    )

    override suspend fun execute(
        operation: IntegrationOperation,
        authentication: ConnectorAuthenticationReference,
    ): IntegrationBackendResult = when (operation) {
        is IssueTrackerOperation.ReadIssue -> {
            val issue = service.readIssue(authentication, operation.projectKey, operation.issueKey)
            val fields = buildMap {
                put("key", issue.key)
                put("state", issue.state)
                put("title", issue.title)
                if (operation.includeBody) put("body", issue.body)
            }
            IntegrationBackendResult.Records(listOf(ConnectorRecord(fields, issue.observedAtEpochSeconds)))
        }

        is IssueTrackerOperation.AddComment -> service.addComment(
            authentication,
            operation.projectKey,
            operation.issueKey,
            operation.comment,
        )

        else -> IntegrationBackendResult.Failed("UNSUPPORTED_OPERATION")
    }

    companion object {
        val ID = ConnectorId("issue-tracker")
    }
}

class CiConnector(
    private val service: CiService,
    rateLimit: ConnectorRateLimit = ConnectorRateLimit(60, 60),
) : PermissionedIntegrationConnector {
    override val descriptor = PermissionedConnectorDescriptor(
        connectorId = ID,
        displayName = "Continuous integration",
        contracts = setOf(
            IntegrationCapabilityContract(
                IntegrationCapability.CI_STATUS_READ,
                setOf(IntegrationDataScope.CI_METADATA, IntegrationDataScope.CI_LOGS),
                emptySet(),
            ),
            IntegrationCapabilityContract(
                IntegrationCapability.CI_RETRY,
                setOf(IntegrationDataScope.CI_METADATA),
                setOf(IntegrationEffectScope.CI_RETRY),
            ),
        ),
        rateLimit = rateLimit,
    )

    override suspend fun execute(
        operation: IntegrationOperation,
        authentication: ConnectorAuthenticationReference,
    ): IntegrationBackendResult = when (operation) {
        is CiConnectorOperation.ReadRun -> filterCiFields(
            service.readRun(authentication, operation.repositoryKey, operation.runId),
            operation.includeLogs,
        )
        is CiConnectorOperation.RetryRun -> service.retryRun(
            authentication,
            operation.repositoryKey,
            operation.runId,
        )
        else -> IntegrationBackendResult.Failed("UNSUPPORTED_OPERATION")
    }

    private fun filterCiFields(
        result: IntegrationBackendResult,
        includeLogs: Boolean,
    ): IntegrationBackendResult {
        if (result !is IntegrationBackendResult.Records) return result
        val allowed = if (includeLogs) CI_METADATA_FIELDS + "logs" else CI_METADATA_FIELDS
        return IntegrationBackendResult.Records(
            result.records.map { record -> record.copy(fields = record.fields.filterKeys(allowed::contains)) },
        )
    }

    companion object {
        val ID = ConnectorId("continuous-integration")
        private val CI_METADATA_FIELDS = setOf("run_id", "status", "conclusion", "started_at")
    }
}

data class IntegrationRetryPolicy(val maxAttempts: Int = 3) {
    init {
        require(maxAttempts in 1..10) { "Integration retry attempts must be bounded" }
    }
}

enum class IntegrationEvidenceOutcome {
    COMPLETED,
    DEFERRED,
    DENIED,
    FAILED,
    UNCERTAIN,
    DUPLICATE,
}

data class IntegrationEvidence(
    val deliveryId: IntegrationDeliveryId,
    val connectorId: ConnectorId,
    val capability: IntegrationCapability,
    val outcome: IntegrationEvidenceOutcome,
    val attempt: Int,
    val code: String,
    val requestDigest: String,
    val observedAtEpochSeconds: Long,
) {
    init {
        require(attempt >= 0)
        require(code.matches(STABLE_FAILURE_CODE))
        require(requestDigest.matches(Regex("[a-f0-9]{64}")))
        require(observedAtEpochSeconds > 0)
    }
}

sealed interface IntegrationResponse {
    val evidence: IntegrationEvidence

    data class Completed(
        val records: List<ConnectorRecord>,
        val effectReceiptId: String?,
        override val evidence: IntegrationEvidence,
    ) : IntegrationResponse

    data class Deferred(
        val retryAtEpochSeconds: Long,
        override val evidence: IntegrationEvidence,
    ) : IntegrationResponse

    data class Denied(
        val reason: IntegrationDenial,
        override val evidence: IntegrationEvidence,
    ) : IntegrationResponse

    data class Failed(override val evidence: IntegrationEvidence) : IntegrationResponse

    data class Uncertain(override val evidence: IntegrationEvidence) : IntegrationResponse

    data class Duplicate(
        val originalEvidence: IntegrationEvidence,
        override val evidence: IntegrationEvidence,
    ) : IntegrationResponse
}

private data class TerminalDelivery(
    val requestDigest: String,
    val evidence: IntegrationEvidence,
)

class PermissionedIntegrationRegistry(
    connectors: Iterable<PermissionedIntegrationConnector>,
    private val credentials: ConnectorCredentialRegistry,
    private val nowEpochSeconds: () -> Long,
    private val retryPolicy: IntegrationRetryPolicy = IntegrationRetryPolicy(),
) {
    private val connectors: Map<ConnectorId, PermissionedIntegrationConnector>
    private val terminalDeliveries = mutableMapOf<IntegrationDeliveryId, TerminalDelivery>()
    private val pendingDigests = mutableMapOf<IntegrationDeliveryId, String>()
    private val attempts = mutableMapOf<IntegrationDeliveryId, Int>()
    private val retryNotBefore = mutableMapOf<IntegrationDeliveryId, Long>()
    private val calls = mutableMapOf<ConnectorId, ArrayDeque<Long>>()
    private val invocationMutex = Mutex()

    init {
        val all = connectors.toList()
        require(all.map { it.descriptor.connectorId }.toSet().size == all.size) {
            "Permissioned connector ids must be unique"
        }
        this.connectors = all.associateBy { it.descriptor.connectorId }
    }

    @Suppress("LongParameterList")
    fun negotiate(
        connectorId: ConnectorId,
        grantId: IntegrationGrantId,
        capabilities: Set<IntegrationCapability>,
        dataScopes: Set<IntegrationDataScope>,
        effectScopes: Set<IntegrationEffectScope>,
        credential: ConnectorCredentialLease,
        expiresAtEpochSeconds: Long,
    ): IntegrationNegotiation {
        val connector = connectors[connectorId]
            ?: return IntegrationNegotiation.Denied(IntegrationDenial.CONNECTOR_UNAVAILABLE)
        if (expiresAtEpochSeconds <= nowEpochSeconds()) {
            return IntegrationNegotiation.Denied(IntegrationDenial.GRANT_EXPIRED)
        }
        credentialDenial(credential)?.let { return IntegrationNegotiation.Denied(it) }
        val contracts = capabilities.map { capability ->
            connector.descriptor.contract(capability)
                ?: return IntegrationNegotiation.Denied(IntegrationDenial.UNDECLARED_CAPABILITY)
        }
        val declaredData = contracts.flatMapTo(mutableSetOf()) { it.dataScopes }
        val declaredEffects = contracts.flatMapTo(mutableSetOf()) { it.effectScopes }
        if (capabilities.isEmpty() || dataScopes.isEmpty() ||
            !declaredData.containsAll(dataScopes) || !declaredEffects.containsAll(effectScopes)
        ) {
            return IntegrationNegotiation.Denied(IntegrationDenial.UNDECLARED_SCOPE)
        }
        if (expiresAtEpochSeconds > credential.expiresAtEpochSeconds) {
            return IntegrationNegotiation.Denied(IntegrationDenial.CREDENTIAL_EXPIRED)
        }
        return IntegrationNegotiation.Allowed(
            IntegrationGrant(
                grantId,
                connectorId,
                capabilities.toSet(),
                dataScopes.toSet(),
                effectScopes.toSet(),
                credential,
                expiresAtEpochSeconds,
            ),
        )
    }

    suspend fun invoke(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
    ): IntegrationResponse {
        invocationMutex.lock()
        return try {
            invokeSerialized(grant, deliveryId, operation)
        } finally {
            invocationMutex.unlock()
        }
    }

    /** Linearizes authorization, delivery admission, and effects so concurrent duplicates execute once. */
    private suspend fun invokeSerialized(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
    ): IntegrationResponse {
        val now = nowEpochSeconds()
        val connector = connectors[grant.connectorId]
        preflightDenial(connector, grant, operation, now)?.let { denial ->
            return denied(grant, deliveryId, operation, denial, now)
        }
        val availableConnector = requireNotNull(connector)
        val requestDigest = operation.requestDigest()
        deliveryBoundary(grant, deliveryId, operation, requestDigest, now)?.let { response ->
            return response
        }
        val callTimes = calls.getOrPut(grant.connectorId) { ArrayDeque() }
        rateLimitBoundary(
            availableConnector.descriptor.rateLimit,
            callTimes,
            grant,
            deliveryId,
            operation,
            now,
        )?.let { response -> return response }

        val attempt = (attempts[deliveryId] ?: 0) + 1
        attempts[deliveryId] = attempt
        callTimes.addLast(now)
        val backend = try {
            availableConnector.execute(operation, grant.credential.reference)
        } catch (cancelled: CancellationException) {
            if (operation.effectScopes.isNotEmpty()) {
                recordTerminal(
                    deliveryId,
                    requestDigest,
                    evidence(
                        grant,
                        deliveryId,
                        operation,
                        IntegrationEvidenceOutcome.UNCERTAIN,
                        attempt,
                        "CANCELLED_OUTCOME_UNKNOWN",
                        now,
                    ),
                )
            }
            throw cancelled
        } catch (_: Exception) {
            if (operation.effectScopes.isEmpty()) {
                IntegrationBackendResult.Failed("UPSTREAM_FAILURE")
            } else {
                IntegrationBackendResult.Uncertain("UPSTREAM_OUTCOME_UNKNOWN")
            }
        }
        return backendResponse(grant, deliveryId, operation, backend, attempt, now)
    }

    private fun preflightDenial(
        connector: PermissionedIntegrationConnector?,
        grant: IntegrationGrant,
        operation: IntegrationOperation,
        now: Long,
    ): IntegrationDenial? {
        if (connector == null) return IntegrationDenial.CONNECTOR_UNAVAILABLE
        if (now >= grant.expiresAtEpochSeconds) return IntegrationDenial.GRANT_EXPIRED
        credentialDenial(grant.credential)?.let { return it }
        return authorizationDenial(connector.descriptor, grant, operation)
    }

    private fun deliveryBoundary(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        requestDigest: String,
        now: Long,
    ): IntegrationResponse? {
        terminalDeliveries[deliveryId]?.let { terminal ->
            if (terminal.requestDigest != requestDigest) {
                return denied(grant, deliveryId, operation, IntegrationDenial.DELIVERY_CONFLICT, now)
            }
            return IntegrationResponse.Duplicate(
                originalEvidence = terminal.evidence,
                evidence = evidence(
                    grant,
                    deliveryId,
                    operation,
                    IntegrationEvidenceOutcome.DUPLICATE,
                    terminal.evidence.attempt,
                    "DUPLICATE_DELIVERY",
                    now,
                ),
            )
        }
        if (pendingDigests[deliveryId]?.let { it != requestDigest } == true) {
            return denied(grant, deliveryId, operation, IntegrationDenial.DELIVERY_CONFLICT, now)
        }
        pendingDigests[deliveryId] = requestDigest
        retryNotBefore[deliveryId]?.takeIf { now < it }?.let { retryAt ->
            return IntegrationResponse.Deferred(
                retryAtEpochSeconds = retryAt,
                evidence = evidence(
                    grant,
                    deliveryId,
                    operation,
                    IntegrationEvidenceOutcome.DEFERRED,
                    attempts[deliveryId] ?: 0,
                    "RETRY_NOT_READY",
                    now,
                ),
            )
        }
        return null
    }

    @Suppress("LongParameterList")
    private fun rateLimitBoundary(
        rateLimit: ConnectorRateLimit,
        callTimes: ArrayDeque<Long>,
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        now: Long,
    ): IntegrationResponse.Deferred? {
        val cutoff = now - rateLimit.windowSeconds
        while (callTimes.firstOrNull()?.let { it <= cutoff } == true) callTimes.removeFirst()
        if (callTimes.size >= rateLimit.maximumRequests) {
            return IntegrationResponse.Deferred(
                retryAtEpochSeconds = callTimes.first() + rateLimit.windowSeconds,
                evidence = evidence(
                    grant,
                    deliveryId,
                    operation,
                    IntegrationEvidenceOutcome.DEFERRED,
                    attempts[deliveryId] ?: 0,
                    "RATE_LIMITED",
                    now,
                ),
            )
        }
        return null
    }

    private fun backendResponse(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        backend: IntegrationBackendResult,
        attempt: Int,
        now: Long,
    ): IntegrationResponse = when (backend) {
        is IntegrationBackendResult.Records -> completed(
            grant,
            deliveryId,
            operation,
            backend.records,
            null,
            attempt,
            now,
        )
        is IntegrationBackendResult.EffectAccepted -> completed(
            grant,
            deliveryId,
            operation,
            emptyList(),
            backend.receiptId,
            attempt,
            now,
        )
        is IntegrationBackendResult.RetryableFailure -> {
            if (attempt >= retryPolicy.maxAttempts) {
                val response = IntegrationResponse.Failed(
                    evidence(grant, deliveryId, operation, IntegrationEvidenceOutcome.FAILED, attempt, "RETRY_EXHAUSTED", now),
                )
                recordTerminal(deliveryId, operation.requestDigest(), response.evidence)
                response
            } else {
                val retryAt = maxOf(now + 1, backend.retryAfterEpochSeconds)
                retryNotBefore[deliveryId] = retryAt
                IntegrationResponse.Deferred(
                    retryAtEpochSeconds = retryAt,
                    evidence = evidence(
                        grant,
                        deliveryId,
                        operation,
                        IntegrationEvidenceOutcome.DEFERRED,
                        attempt,
                        backend.code,
                        now,
                    ),
                )
            }
        }
        is IntegrationBackendResult.Failed -> {
            val response = IntegrationResponse.Failed(
                evidence(grant, deliveryId, operation, IntegrationEvidenceOutcome.FAILED, attempt, backend.code, now),
            )
            recordTerminal(deliveryId, operation.requestDigest(), response.evidence)
            response
        }
        is IntegrationBackendResult.Uncertain -> {
            val response = IntegrationResponse.Uncertain(
                evidence(grant, deliveryId, operation, IntegrationEvidenceOutcome.UNCERTAIN, attempt, backend.code, now),
            )
            recordTerminal(deliveryId, operation.requestDigest(), response.evidence)
            response
        }
    }

    private fun completed(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        records: List<ConnectorRecord>,
        receiptId: String?,
        attempt: Int,
        now: Long,
    ): IntegrationResponse.Completed {
        val response = IntegrationResponse.Completed(
            records,
            receiptId,
            evidence(grant, deliveryId, operation, IntegrationEvidenceOutcome.COMPLETED, attempt, "COMPLETED", now),
        )
        recordTerminal(deliveryId, operation.requestDigest(), response.evidence)
        return response
    }

    private fun recordTerminal(
        deliveryId: IntegrationDeliveryId,
        requestDigest: String,
        terminalEvidence: IntegrationEvidence,
    ) {
        terminalDeliveries[deliveryId] = TerminalDelivery(requestDigest, terminalEvidence)
        pendingDigests.remove(deliveryId)
        attempts.remove(deliveryId)
        retryNotBefore.remove(deliveryId)
    }

    private fun credentialDenial(credential: ConnectorCredentialLease): IntegrationDenial? =
        when (credentials.validate(credential)) {
            CredentialLeaseStatus.ACTIVE -> null
            CredentialLeaseStatus.UNAVAILABLE -> IntegrationDenial.CREDENTIAL_UNAVAILABLE
            CredentialLeaseStatus.STALE -> IntegrationDenial.CREDENTIAL_STALE
            CredentialLeaseStatus.EXPIRED -> IntegrationDenial.CREDENTIAL_EXPIRED
            CredentialLeaseStatus.REVOKED -> IntegrationDenial.CREDENTIAL_REVOKED
        }

    private fun authorizationDenial(
        descriptor: PermissionedConnectorDescriptor,
        grant: IntegrationGrant,
        operation: IntegrationOperation,
    ): IntegrationDenial? {
        val contract = descriptor.contract(operation.capability)
            ?: return IntegrationDenial.UNDECLARED_CAPABILITY
        if (operation.capability !in grant.capabilities) return IntegrationDenial.UNDECLARED_CAPABILITY
        if (!contract.dataScopes.containsAll(operation.dataScopes) ||
            !contract.effectScopes.containsAll(operation.effectScopes) ||
            !grant.dataScopes.containsAll(operation.dataScopes) ||
            !grant.effectScopes.containsAll(operation.effectScopes)
        ) {
            return IntegrationDenial.UNDECLARED_SCOPE
        }
        return null
    }

    private fun denied(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        reason: IntegrationDenial,
        now: Long,
    ): IntegrationResponse.Denied = IntegrationResponse.Denied(
        reason,
        evidence(
            grant,
            deliveryId,
            operation,
            IntegrationEvidenceOutcome.DENIED,
            attempts[deliveryId] ?: 0,
            reason.name,
            now,
        ),
    )

    private fun evidence(
        grant: IntegrationGrant,
        deliveryId: IntegrationDeliveryId,
        operation: IntegrationOperation,
        outcome: IntegrationEvidenceOutcome,
        attempt: Int,
        code: String,
        now: Long,
    ): IntegrationEvidence = IntegrationEvidence(
        deliveryId,
        grant.connectorId,
        operation.capability,
        outcome,
        attempt,
        code,
        operation.requestDigest(),
        now,
    )
}

private fun requireStableRemoteKey(value: String, label: String) {
    require(value.matches(STABLE_REMOTE_KEY)) { "$label must be a stable bounded identifier" }
}

private fun requireFailureCode(value: String) {
    require(value.matches(STABLE_FAILURE_CODE)) { "Integration failure code must be stable and redacted" }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private val STABLE_INTEGRATION_ID = Regex("[a-z][a-z0-9._-]{2,127}")
private val STABLE_REMOTE_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val STABLE_FAILURE_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")
private const val MAX_EFFECT_CHARS = 4_096
private const val MAX_RESULT_FIELD_CHARS = 1_024
private const val MAX_INTEGRATION_RECORDS = 100
