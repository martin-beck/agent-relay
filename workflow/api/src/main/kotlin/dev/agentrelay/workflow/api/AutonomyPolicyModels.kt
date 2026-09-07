package dev.agentrelay.workflow.api

enum class AutonomyMode { REVIEW_EVERY_EFFECT, APPROVE_SENSITIVE, TRUST_SAFE }
enum class PolicyEffect { READ_CONTEXT, NOTIFY_USER, RUN_SAFE_COMMAND, EXTERNAL_WRITE }
enum class PolicyDecisionReason {
    ALLOWED,
    PAUSED,
    LEASE_REVOKED,
    LEASE_EXPIRED,
    POLICY_VERSION_MISMATCH,
    EFFECT_NOT_ALLOWED,
    EFFECT_BUDGET_EXHAUSTED,
}

data class AutonomyPolicy(
    val version: Int,
    val mode: AutonomyMode,
    val allowedEffects: Set<PolicyEffect>,
    val maxEffects: Int,
    val maxRuntimeSeconds: Long,
) {
    init {
        require(version > 0) { "Policy version must be positive" }
        require(maxEffects in 1..10_000) { "Effect budget is invalid" }
        require(maxRuntimeSeconds in 1..86_400) { "Runtime budget is invalid" }
        require(PolicyEffect.EXTERNAL_WRITE !in allowedEffects) { "External writes are not supported" }
        if (mode == AutonomyMode.REVIEW_EVERY_EFFECT) {
            require(allowedEffects.isEmpty()) { "Review mode cannot pre-authorize effects" }
        }
    }
}

data class PolicyLease(
    val id: String,
    val policyVersion: Int,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val revoked: Boolean = false,
) {
    init {
        require(id.matches(Regex("lease_v1_[a-z0-9-]{1,48}"))) { "Lease id is invalid" }
        require(policyVersion > 0) { "Lease policy version must be positive" }
        require(expiresAtSeconds > issuedAtSeconds) { "Lease expiry must follow issuance" }
    }

    fun revoke(): PolicyLease = copy(revoked = true)
}

data class PolicyDecision(
    val allowed: Boolean,
    val reason: PolicyDecisionReason,
    val policyVersion: Int,
    val leaseId: String,
)

object AutonomyPolicyEvaluator {
    fun evaluate(
        policy: AutonomyPolicy,
        lease: PolicyLease,
        effect: PolicyEffect,
        nowSeconds: Long,
        usedEffects: Int,
        paused: Boolean = false,
    ): PolicyDecision {
        val reason = when {
            paused -> PolicyDecisionReason.PAUSED
            lease.revoked -> PolicyDecisionReason.LEASE_REVOKED
            nowSeconds !in lease.issuedAtSeconds until lease.expiresAtSeconds -> PolicyDecisionReason.LEASE_EXPIRED
            lease.policyVersion != policy.version -> PolicyDecisionReason.POLICY_VERSION_MISMATCH
            effect !in policy.allowedEffects -> PolicyDecisionReason.EFFECT_NOT_ALLOWED
            usedEffects !in 0 until policy.maxEffects -> PolicyDecisionReason.EFFECT_BUDGET_EXHAUSTED
            else -> PolicyDecisionReason.ALLOWED
        }
        return PolicyDecision(reason == PolicyDecisionReason.ALLOWED, reason, policy.version, lease.id)
    }
}

data class AutonomyControlState(val paused: Boolean = false) {
    fun pauseAll(): AutonomyControlState = copy(paused = true)
    fun resume(): AutonomyControlState = copy(paused = false)
}
