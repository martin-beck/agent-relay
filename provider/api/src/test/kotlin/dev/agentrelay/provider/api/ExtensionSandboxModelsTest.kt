package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExtensionSandboxModelsTest {
    private val manifest = ExtensionManifest(
        id = ExtensionId("calendar.agent"),
        displayName = "Calendar agent",
        apiVersion = ExtensionApiVersion(1, 0),
        schemaVersion = 1,
        capabilities = setOf(ExtensionCapability.AGENT),
        permissions = setOf(ExtensionPermission.READ_FIELDS, ExtensionPermission.PROPOSE_ACTIONS),
        requestedFields = setOf(ExtensionField("calendar.events")),
        triggers = setOf(ExtensionField("calendar.refresh")),
        actions = setOf(ExtensionAction("calendar.propose")),
        budget = ExtensionResourceBudget(1024, 2048, 3, 1000),
    )

    private val policy = ExtensionSandboxPolicy(
        executionClass = ExtensionExecutionClass.SANDBOXED,
        limits = ExtensionSandboxLimits(
            maxCpuMillis = 100,
            maxWallMillis = 200,
            maxMemoryBytes = 4 * 1024 * 1024,
            maxStorageBytes = 1024,
            maxInputBytes = 1024,
            maxOutputBytes = 2048,
            maxInvocations = 3,
        ),
    )

    @Test
    fun `admission narrows permissions and asks approval for external effects`() {
        val request = request(requiresExternalEffect = true)
        val admission = ExtensionSandboxCoordinator().admit(
            manifest,
            request,
            ExtensionApiVersion(1, 0),
            1,
            setOf(ExtensionPermission.READ_FIELDS),
            4,
        )
        val accepted = assertIs<ExtensionAdmission.Accepted>(admission)
        assertEquals(setOf(ExtensionPermission.READ_FIELDS), accepted.permissions)
        assertEquals(ExtensionApprovalState.PENDING, accepted.approval)
    }

    @Test
    fun `authority requests are denied before invocation`() {
        val coordinator = ExtensionSandboxCoordinator()
        assertEquals(
            ExtensionSandboxRejection.NETWORK_DENIED,
            coordinator.admit(
                manifest,
                request(requestedHost = "outside.example"),
                ExtensionApiVersion(1, 0),
                1,
                emptySet(),
                4,
            ).reason(),
        )
        assertEquals(
            ExtensionSandboxRejection.SUBPROCESS_DENIED,
            coordinator.admit(
                manifest,
                request(requestsSubprocess = true),
                ExtensionApiVersion(1, 0),
                1,
                emptySet(),
                4,
            ).reason(),
        )
    }

    @Test
    fun `cancellation and uncertain effects remain explicit in redacted evidence`() {
        val coordinator = ExtensionSandboxCoordinator()
        val request = request()
        val admission = coordinator.admit(
            manifest,
            request,
            ExtensionApiVersion(1, 0),
            1,
            setOf(ExtensionPermission.READ_FIELDS),
            4,
            cancelled = true,
        )
        val evidence = coordinator.evidence(request, admission, cancellationRequested = true)
        assertEquals(ExtensionExecutionOutcome.REJECTED, evidence.outcome)
        assertEquals(ExtensionSandboxRejection.CANCELLED, evidence.rejection)
        assertTrue(evidence.inputDigest.matches(Regex("[0-9a-f]{64}")))
        assertTrue(evidence.outputDigest.matches(Regex("[0-9a-f]{64}")))
        assertTrue(evidence.policyDigest.matches(Regex("[0-9a-f]{64}")))
        assertEquals(evidence, coordinator.evidence(request, admission, cancellationRequested = true))

        val uncertain = ExtensionAgentResult(ExtensionExecutionOutcome.UNCERTAIN, externalEffectObserved = true)
        val allowed = ExtensionSandboxCoordinator().admit(
            manifest,
            request(),
            ExtensionApiVersion(1, 0),
            1,
            setOf(ExtensionPermission.READ_FIELDS),
            4,
        )
        assertEquals(
            ExtensionExecutionOutcome.UNCERTAIN,
            coordinator.evidence(request(), allowed, uncertain).outcome,
        )

        val oversized = coordinator.evidence(
            request(),
            allowed,
            ExtensionAgentResult(ExtensionExecutionOutcome.COMPLETED, "x".repeat(3_000)),
        )
        assertEquals(ExtensionExecutionOutcome.REJECTED, oversized.outcome)
        assertEquals(ExtensionSandboxRejection.RESOURCE_LIMIT_EXCEEDED, oversized.rejection)
    }

    @Test
    fun `limits reject unsafe roots and built-ins cannot authorize effects`() {
        runCatching {
            ExtensionSandboxLimits(1, 1, 1_048_576, 1, 1, 1, 1, filesystemRoot = "../escape")
        }.onSuccess { error("unsafe root was accepted") }
        runCatching {
            ExtensionSandboxPolicy(ExtensionExecutionClass.TRUSTED_BUILT_IN, policy.limits, allowExternalEffects = true)
        }.onSuccess { error("built-in effect policy was accepted") }
    }

    private fun request(
        requiresExternalEffect: Boolean = false,
        requestedHost: String? = null,
        requestsSubprocess: Boolean = false,
    ) = ExtensionAgentRequest(
        invocation = ExtensionInvocation(
            extensionId = manifest.id,
            idempotencyKey = "request-001",
            trigger = ExtensionField("calendar.refresh"),
            input = mapOf(ExtensionField("calendar.events") to "opaque input"),
            requestedPermissions = setOf(ExtensionPermission.READ_FIELDS),
            revision = 4,
        ),
        policy = policy.copy(allowExternalEffects = requiresExternalEffect),
        requiresExternalEffect = requiresExternalEffect,
        requestedHost = requestedHost,
        requestsSubprocess = requestsSubprocess,
    )

    private fun ExtensionAdmission.reason(): ExtensionSandboxRejection =
        (this as ExtensionAdmission.Rejected).reason
}
