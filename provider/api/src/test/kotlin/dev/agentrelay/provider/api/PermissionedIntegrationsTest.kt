/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.yield
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PermissionedIntegrationsTest {
    @Test
    fun `negotiation grants only declared capabilities and scopes`() = runTest {
        val fixture = Fixture()
        val allowed = fixture.registry.negotiate(
            connectorId = IssueTrackerConnector.ID,
            grantId = IntegrationGrantId("grant_v1_issues"),
            capabilities = setOf(IntegrationCapability.ISSUE_READ),
            dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes = emptySet(),
            credential = fixture.issueCredential,
            expiresAtEpochSeconds = 200,
        )

        val grant = assertIs<IntegrationNegotiation.Allowed>(allowed).grant
        assertEquals(setOf(IntegrationCapability.ISSUE_READ), grant.capabilities)
        assertEquals(setOf(IntegrationDataScope.ISSUE_METADATA), grant.dataScopes)
        assertTrue(grant.effectScopes.isEmpty())

        val denied = fixture.registry.negotiate(
            connectorId = IssueTrackerConnector.ID,
            grantId = IntegrationGrantId("grant_v1_excess"),
            capabilities = setOf(IntegrationCapability.ISSUE_READ),
            dataScopes = setOf(IntegrationDataScope.CI_LOGS),
            effectScopes = emptySet(),
            credential = fixture.issueCredential,
            expiresAtEpochSeconds = 200,
        )
        assertEquals(IntegrationDenial.UNDECLARED_SCOPE, assertIs<IntegrationNegotiation.Denied>(denied).reason)
        assertEquals(0, fixture.issueService.calls)
    }

    @Test
    fun `expired rotated and revoked credentials fail closed`() = runTest {
        val fixture = Fixture()
        val grant = fixture.issueGrant()
        fixture.credentials.rotate(
            fixture.issueCredential.reference,
            ConnectorCredentialLease(
                ConnectorAuthenticationReference("ref://issues/rotated"),
                generation = 2,
                expiresAtEpochSeconds = 300,
            ),
        )

        val stale = fixture.registry.invoke(
            grant,
            IntegrationDeliveryId("delivery_v1_stale"),
            IssueTrackerOperation.ReadIssue("AR", "AR-2164", includeBody = false),
        )
        assertEquals(IntegrationDenial.CREDENTIAL_REVOKED, assertIs<IntegrationResponse.Denied>(stale).reason)
        assertEquals(0, fixture.issueService.calls)

        val rotatedGrant = assertIs<IntegrationNegotiation.Allowed>(
            fixture.registry.negotiate(
                IssueTrackerConnector.ID,
                IntegrationGrantId("grant_v1_rotated"),
                setOf(IntegrationCapability.ISSUE_READ),
                setOf(IntegrationDataScope.ISSUE_METADATA),
                emptySet(),
                fixture.credentials.current(ConnectorAuthenticationReference("ref://issues/rotated"))!!,
                250,
            ),
        ).grant
        fixture.credentials.revoke(rotatedGrant.credential.reference)
        val revoked = fixture.registry.invoke(
            rotatedGrant,
            IntegrationDeliveryId("delivery_v1_revoked"),
            IssueTrackerOperation.ReadIssue("AR", "AR-2164", includeBody = false),
        )
        assertEquals(IntegrationDenial.CREDENTIAL_REVOKED, assertIs<IntegrationResponse.Denied>(revoked).reason)

        fixture.now = 251
        val expired = fixture.registry.invoke(
            rotatedGrant,
            IntegrationDeliveryId("delivery_v1_expired"),
            IssueTrackerOperation.ReadIssue("AR", "AR-2164", includeBody = false),
        )
        assertEquals(IntegrationDenial.GRANT_EXPIRED, assertIs<IntegrationResponse.Denied>(expired).reason)
        assertEquals(0, fixture.issueService.calls)
    }

    @Test
    fun `duplicate effects execute once and conflicting reuse fails closed`() = runTest {
        val fixture = Fixture()
        val grant = fixture.issueGrant(
            capabilities = setOf(IntegrationCapability.ISSUE_COMMENT),
            dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes = setOf(IntegrationEffectScope.ISSUE_COMMENT),
        )
        val delivery = IntegrationDeliveryId("delivery_v1_comment")
        val operation = IssueTrackerOperation.AddComment("AR", "AR-2164", "Looks good")

        val first = fixture.registry.invoke(grant, delivery, operation)
        val duplicate = fixture.registry.invoke(grant, delivery, operation)
        assertIs<IntegrationResponse.Completed>(first)
        assertEquals(first.evidence, assertIs<IntegrationResponse.Duplicate>(duplicate).originalEvidence)
        assertEquals(1, fixture.issueService.commentCalls)

        val conflict = fixture.registry.invoke(
            grant,
            delivery,
            operation.copy(comment = "Different effect"),
        )
        assertEquals(IntegrationDenial.DELIVERY_CONFLICT, assertIs<IntegrationResponse.Denied>(conflict).reason)
        assertEquals(1, fixture.issueService.commentCalls)
    }

    @Test
    fun `concurrent duplicate effects are linearized`() = runTest {
        val fixture = Fixture()
        val grant = fixture.issueGrant(
            capabilities = setOf(IntegrationCapability.ISSUE_COMMENT),
            dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes = setOf(IntegrationEffectScope.ISSUE_COMMENT),
        )
        val delivery = IntegrationDeliveryId("delivery_v1_concurrent")
        val operation = IssueTrackerOperation.AddComment("AR", "AR-2164", "Only once")

        val responses = listOf(
            async { fixture.registry.invoke(grant, delivery, operation) },
            async { fixture.registry.invoke(grant, delivery, operation) },
        ).awaitAll()

        assertEquals(1, responses.count { it is IntegrationResponse.Completed })
        assertEquals(1, responses.count { it is IntegrationResponse.Duplicate })
        assertEquals(1, fixture.issueService.commentCalls)
    }

    @Test
    fun `rate limit and retry recovery are deterministic`() = runTest {
        val fixture = Fixture(rateLimit = ConnectorRateLimit(maximumRequests = 1, windowSeconds = 10))
        val grant = fixture.ciGrant()
        fixture.ciService.outcomes += IntegrationBackendResult.RetryableFailure("UPSTREAM_BUSY", retryAfterEpochSeconds = 105)
        fixture.ciService.outcomes += IntegrationBackendResult.Records(
            listOf(ConnectorRecord(mapOf("status" to "passed"), observedAtEpochSeconds = 110)),
        )

        val operation = CiConnectorOperation.ReadRun("agent-relay", "34080537454", includeLogs = false)
        val delivery = IntegrationDeliveryId("delivery_v1_ci")
        val retryable = fixture.registry.invoke(grant, delivery, operation)
        assertEquals(105, assertIs<IntegrationResponse.Deferred>(retryable).retryAtEpochSeconds)

        fixture.now = 104
        val early = fixture.registry.invoke(grant, delivery, operation)
        assertEquals(105, assertIs<IntegrationResponse.Deferred>(early).retryAtEpochSeconds)
        assertEquals(1, fixture.ciService.calls)

        fixture.now = 105
        val limited = fixture.registry.invoke(grant, delivery, operation)
        assertEquals(110, assertIs<IntegrationResponse.Deferred>(limited).retryAtEpochSeconds)
        assertEquals(1, fixture.ciService.calls)

        fixture.now = 110
        val recovered = assertIs<IntegrationResponse.Completed>(fixture.registry.invoke(grant, delivery, operation))
        assertEquals(IntegrationEvidenceOutcome.COMPLETED, recovered.evidence.outcome)
        assertEquals(2, recovered.evidence.attempt)
        assertEquals(2, fixture.ciService.calls)
    }

    @Test
    fun `retry exhaustion is bounded and delivery remains bound to one request`() = runTest {
        val fixture = Fixture()
        val grant = fixture.ciGrant()
        repeat(3) { index ->
            fixture.ciService.outcomes += IntegrationBackendResult.RetryableFailure(
                "UPSTREAM_BUSY",
                retryAfterEpochSeconds = 101L + index,
            )
        }
        val delivery = IntegrationDeliveryId("delivery_v1_exhaustion")
        val operation = CiConnectorOperation.ReadRun("agent-relay", "run-1", includeLogs = false)

        assertIs<IntegrationResponse.Deferred>(fixture.registry.invoke(grant, delivery, operation))
        fixture.now = 101
        assertIs<IntegrationResponse.Deferred>(fixture.registry.invoke(grant, delivery, operation))
        fixture.now = 102
        val exhausted = assertIs<IntegrationResponse.Failed>(fixture.registry.invoke(grant, delivery, operation))
        assertEquals("RETRY_EXHAUSTED", exhausted.evidence.code)
        assertEquals(3, fixture.ciService.calls)

        val terminalReplay = assertIs<IntegrationResponse.Duplicate>(
            fixture.registry.invoke(grant, delivery, operation),
        )
        assertEquals(exhausted.evidence, terminalReplay.originalEvidence)
        assertEquals(3, fixture.ciService.calls)

        val conflict = fixture.registry.invoke(
            grant,
            delivery,
            operation.copy(runId = "run-2"),
        )
        assertEquals(IntegrationDenial.DELIVERY_CONFLICT, assertIs<IntegrationResponse.Denied>(conflict).reason)
        assertEquals(3, fixture.ciService.calls)
    }

    @Test
    fun `credential absence and expiry are denied during negotiation`() {
        val fixture = Fixture()
        val unavailable = ConnectorCredentialLease(
            ConnectorAuthenticationReference("ref://issues/missing"),
            generation = 1,
            expiresAtEpochSeconds = 300,
        )
        val deniedMissing = fixture.registry.negotiate(
            IssueTrackerConnector.ID,
            IntegrationGrantId("grant_v1_missing"),
            setOf(IntegrationCapability.ISSUE_READ),
            setOf(IntegrationDataScope.ISSUE_METADATA),
            emptySet(),
            unavailable,
            200,
        )
        assertEquals(
            IntegrationDenial.CREDENTIAL_UNAVAILABLE,
            assertIs<IntegrationNegotiation.Denied>(deniedMissing).reason,
        )

        fixture.now = 301
        val deniedExpired = fixture.registry.negotiate(
            IssueTrackerConnector.ID,
            IntegrationGrantId("grant_v1_expired"),
            setOf(IntegrationCapability.ISSUE_READ),
            setOf(IntegrationDataScope.ISSUE_METADATA),
            emptySet(),
            fixture.issueCredential,
            400,
        )
        assertEquals(
            IntegrationDenial.CREDENTIAL_EXPIRED,
            assertIs<IntegrationNegotiation.Denied>(deniedExpired).reason,
        )
    }

    @Test
    fun `backend exceptions are redacted while cancellation propagates`() = runTest {
        val fixture = Fixture()
        val grant = fixture.ciGrant()
        fixture.ciService.failure = IllegalStateException("token=private-value")
        val failed = fixture.registry.invoke(
            grant,
            IntegrationDeliveryId("delivery_v1_exception"),
            CiConnectorOperation.ReadRun("agent-relay", "run-1", includeLogs = false),
        )
        val evidence = assertIs<IntegrationResponse.Failed>(failed).evidence
        assertEquals("UPSTREAM_FAILURE", evidence.code)
        assertTrue("private-value" !in evidence.toString())

        fixture.ciService.failure = CancellationException("cancel")
        assertFailsWith<CancellationException> {
            fixture.registry.invoke(
                grant,
                IntegrationDeliveryId("delivery_v1_cancel"),
                CiConnectorOperation.ReadRun("agent-relay", "run-2", includeLogs = false),
            )
        }
    }

    @Test
    fun `ambiguous and cancelled effects remain terminal and cannot replay`() = runTest {
        val fixture = Fixture()
        val grant = fixture.issueGrant(
            capabilities = setOf(IntegrationCapability.ISSUE_COMMENT),
            dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes = setOf(IntegrationEffectScope.ISSUE_COMMENT),
        )
        val operation = IssueTrackerOperation.AddComment("AR", "AR-2164", "Apply once")

        fixture.issueService.commentFailure = IllegalStateException("token=private-value")
        val uncertainDelivery = IntegrationDeliveryId("delivery_v1_uncertain_effect")
        val uncertain = assertIs<IntegrationResponse.Uncertain>(
            fixture.registry.invoke(grant, uncertainDelivery, operation),
        )
        assertEquals("UPSTREAM_OUTCOME_UNKNOWN", uncertain.evidence.code)
        assertTrue("private-value" !in uncertain.evidence.toString())
        assertIs<IntegrationResponse.Duplicate>(
            fixture.registry.invoke(grant, uncertainDelivery, operation),
        )
        assertEquals(1, fixture.issueService.commentCalls)

        fixture.issueService.commentFailure = CancellationException("cancel after dispatch")
        val cancelledDelivery = IntegrationDeliveryId("delivery_v1_cancelled_effect")
        assertFailsWith<CancellationException> {
            fixture.registry.invoke(grant, cancelledDelivery, operation)
        }
        val cancelledReplay = assertIs<IntegrationResponse.Duplicate>(
            fixture.registry.invoke(grant, cancelledDelivery, operation),
        )
        assertEquals(IntegrationEvidenceOutcome.UNCERTAIN, cancelledReplay.originalEvidence.outcome)
        assertEquals("CANCELLED_OUTCOME_UNKNOWN", cancelledReplay.originalEvidence.code)
        assertEquals(2, fixture.issueService.commentCalls)
    }

    @Test
    fun `representative issue tracker and CI connectors normalize reads and effects`() = runTest {
        val fixture = Fixture()
        val issueRead = assertIs<IntegrationResponse.Completed>(
            fixture.registry.invoke(
                fixture.issueGrant(),
                IntegrationDeliveryId("delivery_v1_issue_read"),
                IssueTrackerOperation.ReadIssue("AR", "AR-2164", includeBody = false),
            ),
        )
        assertEquals(mapOf("key" to "AR-2164", "state" to "open", "title" to "Connect services"), issueRead.records.single().fields)

        val ciEffect = assertIs<IntegrationResponse.Completed>(
            fixture.registry.invoke(
                fixture.ciGrant(
                    capabilities = setOf(IntegrationCapability.CI_RETRY),
                    effectScopes = setOf(IntegrationEffectScope.CI_RETRY),
                ),
                IntegrationDeliveryId("delivery_v1_ci_retry"),
                CiConnectorOperation.RetryRun("agent-relay", "34080537454"),
            ),
        )
        assertEquals("retry-accepted", ciEffect.effectReceiptId)
        assertEquals(1, fixture.ciService.retryCalls)
    }

    @Test
    fun `fuzzed private payloads never enter bounded evidence`() = runTest {
        val fixture = Fixture()
        val grant = fixture.issueGrant(
            capabilities = setOf(IntegrationCapability.ISSUE_COMMENT),
            dataScopes = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes = setOf(IntegrationEffectScope.ISSUE_COMMENT),
        )
        val random = Random(2164)

        repeat(128) { index ->
            val secret = buildString { repeat(24) { append(('a'.code + random.nextInt(26)).toChar()) } }
            val response = fixture.registry.invoke(
                grant,
                IntegrationDeliveryId("delivery_v1_fuzz_$index"),
                IssueTrackerOperation.AddComment("AR", "AR-2164", "token=$secret"),
            )
            val evidence = assertIs<IntegrationResponse.Completed>(response).evidence.toString()
            assertTrue(evidence.length <= 320)
            assertTrue(secret !in evidence)
            assertTrue("token=" !in evidence)
        }
    }

    @Test
    fun `randomized authorization invariant never invokes outside grant or lease`() = runTest {
        val random = Random(4216)
        repeat(128) { index ->
            val fixture = Fixture()
            val includeBody = random.nextBoolean()
            val grant = fixture.issueGrant(
                dataScopes = if (random.nextBoolean()) {
                    setOf(IntegrationDataScope.ISSUE_METADATA, IntegrationDataScope.ISSUE_BODY)
                } else {
                    setOf(IntegrationDataScope.ISSUE_METADATA)
                },
            )
            if (random.nextBoolean()) fixture.credentials.revoke(grant.credential.reference)
            val operation = IssueTrackerOperation.ReadIssue("AR", "AR-$index", includeBody)
            val response = fixture.registry.invoke(grant, IntegrationDeliveryId("delivery_v1_property_$index"), operation)
            val authorized = !fixture.credentials.isRevoked(grant.credential.reference) &&
                (!includeBody || IntegrationDataScope.ISSUE_BODY in grant.dataScopes)
            assertEquals(authorized, response is IntegrationResponse.Completed)
            assertEquals(if (authorized) 1 else 0, fixture.issueService.calls)
        }
    }

    private class Fixture(rateLimit: ConnectorRateLimit = ConnectorRateLimit(200, 60)) {
        var now = 100L
        val issueCredential = ConnectorCredentialLease(
            ConnectorAuthenticationReference("ref://issues/main"),
            generation = 1,
            expiresAtEpochSeconds = 300,
        )
        private val ciCredential = ConnectorCredentialLease(
            ConnectorAuthenticationReference("ref://ci/main"),
            generation = 1,
            expiresAtEpochSeconds = 300,
        )
        val credentials = ConnectorCredentialRegistry { now }
        val issueService = FakeIssueTrackerService()
        val ciService = FakeCiService()
        val registry: PermissionedIntegrationRegistry

        init {
            credentials.install(issueCredential)
            credentials.install(ciCredential)
            registry = PermissionedIntegrationRegistry(
                connectors = listOf(
                    IssueTrackerConnector(issueService, rateLimit),
                    CiConnector(ciService, rateLimit),
                ),
                credentials = credentials,
                nowEpochSeconds = { now },
                retryPolicy = IntegrationRetryPolicy(maxAttempts = 3),
            )
        }

        fun issueGrant(
            capabilities: Set<IntegrationCapability> = setOf(IntegrationCapability.ISSUE_READ),
            dataScopes: Set<IntegrationDataScope> = setOf(IntegrationDataScope.ISSUE_METADATA),
            effectScopes: Set<IntegrationEffectScope> = emptySet(),
        ): IntegrationGrant = assertIs<IntegrationNegotiation.Allowed>(
            registry.negotiate(
                IssueTrackerConnector.ID,
                IntegrationGrantId("grant_v1_issue"),
                capabilities,
                dataScopes,
                effectScopes,
                issueCredential,
                expiresAtEpochSeconds = 250,
            ),
        ).grant

        fun ciGrant(
            capabilities: Set<IntegrationCapability> = setOf(IntegrationCapability.CI_STATUS_READ),
            dataScopes: Set<IntegrationDataScope> = setOf(IntegrationDataScope.CI_METADATA),
            effectScopes: Set<IntegrationEffectScope> = emptySet(),
        ): IntegrationGrant = assertIs<IntegrationNegotiation.Allowed>(
            registry.negotiate(
                CiConnector.ID,
                IntegrationGrantId("grant_v1_ci"),
                capabilities,
                dataScopes,
                effectScopes,
                ciCredential,
                expiresAtEpochSeconds = 250,
            ),
        ).grant
    }

    private class FakeIssueTrackerService : IssueTrackerService {
        var calls = 0
        var commentCalls = 0
        var commentFailure: Throwable? = null

        override suspend fun readIssue(
            authentication: ConnectorAuthenticationReference,
            projectKey: String,
            issueKey: String,
        ): IssueTrackerSnapshot {
            calls++
            return IssueTrackerSnapshot(issueKey, "Connect services", "open", "private body")
        }

        override suspend fun addComment(
            authentication: ConnectorAuthenticationReference,
            projectKey: String,
            issueKey: String,
            comment: String,
        ): IntegrationBackendResult {
            yield()
            calls++
            commentCalls++
            commentFailure?.let { throw it }
            return IntegrationBackendResult.EffectAccepted("comment-accepted")
        }
    }

    private class FakeCiService : CiService {
        var calls = 0
        var retryCalls = 0
        var failure: RuntimeException? = null
        val outcomes = ArrayDeque<IntegrationBackendResult>()

        override suspend fun readRun(
            authentication: ConnectorAuthenticationReference,
            repositoryKey: String,
            runId: String,
        ): IntegrationBackendResult {
            calls++
            failure?.let { throw it }
            return if (outcomes.isEmpty()) {
                IntegrationBackendResult.Records(
                    listOf(ConnectorRecord(mapOf("run_id" to runId, "status" to "passed"), 100)),
                )
            } else {
                outcomes.removeFirst()
            }
        }

        override suspend fun retryRun(
            authentication: ConnectorAuthenticationReference,
            repositoryKey: String,
            runId: String,
        ): IntegrationBackendResult {
            calls++
            retryCalls++
            return IntegrationBackendResult.EffectAccepted("retry-accepted")
        }
    }
}
