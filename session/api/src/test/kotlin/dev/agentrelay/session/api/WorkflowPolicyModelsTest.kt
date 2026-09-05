package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowPolicyModelsTest {
    private val workflow = WorkflowId("workflow")
    private val run = WorkflowRunId("run")
    private val lease = WorkflowPermissionLease(
        id = "lease",
        workflowId = workflow,
        runId = run,
        capabilities = setOf("read", "write"),
        prohibitedEffects = setOf(WorkflowEffectClass.EXTERNAL),
        issuedAtMillis = 10,
        expiresAtMillis = 100,
        maxUses = 2,
    )

    @Test
    fun policyRequiresLeaseApprovalAndHonorsBudgets() {
        val request = request(WorkflowEffectClass.MUTATING)
        val budget = WorkflowBudget(100, 100, 100, 2)
        assertEquals(WorkflowPolicyDecision.DENY, WorkflowPolicyEvaluator.evaluate(request, null, budget, WorkflowBudgetUsage(), 20))
        assertEquals(
            WorkflowPolicyDecision.ALLOW,
            WorkflowPolicyEvaluator.evaluate(request, lease, budget, WorkflowBudgetUsage(), 20),
        )
        assertEquals(
            WorkflowPolicyDecision.BUDGET_EXCEEDED,
            WorkflowPolicyEvaluator.evaluate(
                request.copy(estimatedUsage = WorkflowBudgetUsage(101)),
                lease,
                budget,
                WorkflowBudgetUsage(),
                20,
            ),
        )
        assertEquals(
            WorkflowPolicyDecision.REQUIRE_APPROVAL,
            WorkflowPolicyEvaluator.evaluate(request(WorkflowEffectClass.DESTRUCTIVE), lease, budget, WorkflowBudgetUsage(), 20),
        )
    }

    @Test
    fun policyRejectsExpiredAndProhibitedRequests() {
        assertEquals(
            WorkflowPolicyDecision.DENY,
            WorkflowPolicyEvaluator.evaluate(request(), lease, budget(), WorkflowBudgetUsage(), 0),
        )
        assertEquals(
            WorkflowPolicyDecision.DENY,
            WorkflowPolicyEvaluator.evaluate(
                request(WorkflowEffectClass.EXTERNAL),
                lease,
                budget(),
                WorkflowBudgetUsage(),
                20,
            ),
        )
        assertFalse(lease.isActive(100))
        assertTrue(lease.permits("read", WorkflowEffectClass.READ_ONLY))
    }

    @Test
    fun completionRequiresChecksArtifactsAndRetainedEvidence() {
        val contract = WorkflowCompletionContract(setOf("check"), setOf("artifact"), true)
        val evidence = WorkflowEvidenceReference("check", "test", "digest", 10, 50, true)
        val incomplete = WorkflowCompletionVerifier.verify(
            contract,
            WorkflowCompletionObservation(checks = setOf("check"), evidence = listOf(evidence), observedAtMillis = 20),
        )
        assertFalse(incomplete.complete)
        assertEquals(setOf("artifact"), incomplete.missingArtifacts)
        assertTrue(incomplete.humanAcceptanceMissing)
        val complete = WorkflowCompletionVerifier.verify(
            contract,
            WorkflowCompletionObservation(
                checks = setOf("check"),
                artifacts = setOf("artifact"),
                evidence = listOf(evidence, evidence.copy(id = "artifact")),
                humanAccepted = true,
                observedAtMillis = 20,
            ),
        )
        assertTrue(complete.complete)
    }

    private fun request(effect: WorkflowEffectClass = WorkflowEffectClass.READ_ONLY) = WorkflowPolicyRequest(
        workflowId = workflow,
        runId = run,
        capability = "read",
        effect = effect,
        reversibility = WorkflowReversibility.REVERSIBLE,
        risk = WorkflowRisk.R1,
        estimatedUsage = WorkflowBudgetUsage(1, 1, 1, 1),
    )

    private fun budget() = WorkflowBudget(10, 10, 10, 1)
}
