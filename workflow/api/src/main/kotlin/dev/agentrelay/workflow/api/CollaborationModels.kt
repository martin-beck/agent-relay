package dev.agentrelay.workflow.api

enum class CollaborationRole { OWNER, EDITOR, REVIEWER, OBSERVER }

enum class CollaborationCapability { READ, WRITE, APPROVE, DELEGATE }

data class CollaborationLease(
    val id: String,
    val actorId: String,
    val issuedAtSeconds: Long,
    val expiresAtSeconds: Long,
    val revoked: Boolean = false,
) {
    init {
        require(id.matches(Regex("lease_v1_[a-z0-9-]{1,48}"))) { "Collaboration lease id is invalid" }
        require(actorId.matches(Regex("actor_v1_[a-z0-9-]{1,48}"))) { "Actor id is invalid" }
        require(issuedAtSeconds >= 0 && expiresAtSeconds > issuedAtSeconds) { "Lease interval is invalid" }
    }
}

data class CollaborationActor(
    val id: String,
    val role: CollaborationRole,
    val capabilities: Set<CollaborationCapability>,
    val lease: CollaborationLease,
) {
    init {
        require(id == lease.actorId) { "Lease actor differs from actor" }
        require(role == CollaborationRole.OWNER || CollaborationCapability.DELEGATE !in capabilities) {
            "Only owners may delegate authority"
        }
    }
}

data class CollaborationAuditEntry(
    val revision: Long,
    val actorId: String,
    val action: String,
    val commentDigest: String,
)

enum class CollaborationDecisionReason {
    APPLIED,
    STALE_REVISION,
    UNKNOWN_ACTOR,
    LEASE_REVOKED,
    LEASE_EXPIRED,
    CAPABILITY_DENIED,
    SCOPE_ESCALATION,
}

data class CollaborationDecision(val revision: Long, val reason: CollaborationDecisionReason)

/** Serialized authority for shared projects; every mutation has a redacted audit entry. */
class CollaborationAuthority(private val projectId: String) {
    private val actors = linkedMapOf<String, CollaborationActor>()
    private val audit = mutableListOf<CollaborationAuditEntry>()
    private var revision = 0L

    init {
        require(projectId.matches(PROJECT_ID_PATTERN)) { "Project id is invalid" }
    }

    @Synchronized
    fun register(actor: CollaborationActor): CollaborationDecision {
        require(actor.id !in actors) { "Actor is already registered" }
        actors[actor.id] = actor
        return record(actor.id, "register", ZERO_DIGEST)
    }

    @Synchronized
    fun delegate(
        delegatorId: String,
        delegatee: CollaborationActor,
        expectedRevision: Long,
        nowSeconds: Long,
        commentDigest: String,
    ): CollaborationDecision {
        val delegator = actors[delegatorId] ?: return reject(CollaborationDecisionReason.UNKNOWN_ACTOR)
        val failure = authorize(delegator, CollaborationCapability.DELEGATE, nowSeconds)
        if (failure != null) return reject(failure)
        if (expectedRevision != revision) return reject(CollaborationDecisionReason.STALE_REVISION)
        if (!delegator.capabilities.containsAll(delegatee.capabilities)) {
            return reject(CollaborationDecisionReason.SCOPE_ESCALATION)
        }
        if (delegatee.lease.actorId != delegatee.id) return reject(CollaborationDecisionReason.SCOPE_ESCALATION)
        actors[delegatee.id] = delegatee
        return record(delegatorId, "delegate", commentDigest)
    }

    @Synchronized
    fun apply(
        actorId: String,
        capability: CollaborationCapability,
        expectedRevision: Long,
        nowSeconds: Long,
        commentDigest: String,
    ): CollaborationDecision {
        val actor = actors[actorId] ?: return reject(CollaborationDecisionReason.UNKNOWN_ACTOR)
        val failure = authorize(actor, capability, nowSeconds)
        if (failure != null) return reject(failure)
        if (expectedRevision != revision) return reject(CollaborationDecisionReason.STALE_REVISION)
        return record(actorId, "apply", commentDigest)
    }

    @Synchronized
    fun revoke(actorId: String, expectedRevision: Long, nowSeconds: Long, commentDigest: String): CollaborationDecision {
        val actor = actors[actorId] ?: return reject(CollaborationDecisionReason.UNKNOWN_ACTOR)
        val failure = authorize(actor, CollaborationCapability.DELEGATE, nowSeconds)
        if (failure != null) return reject(failure)
        if (expectedRevision != revision) return reject(CollaborationDecisionReason.STALE_REVISION)
        actors[actorId] = actor.copy(lease = actor.lease.copy(revoked = true))
        return record(actorId, "revoke", commentDigest)
    }

    @Synchronized
    fun auditTrail(): List<CollaborationAuditEntry> = audit.toList()

    @Synchronized
    fun currentRevision(): Long = revision

    private fun authorize(actor: CollaborationActor, capability: CollaborationCapability, nowSeconds: Long): CollaborationDecisionReason? {
        if (actor.lease.revoked) return CollaborationDecisionReason.LEASE_REVOKED
        if (nowSeconds !in actor.lease.issuedAtSeconds until actor.lease.expiresAtSeconds) {
            return CollaborationDecisionReason.LEASE_EXPIRED
        }
        return capability.takeUnless { it in actor.capabilities }?.let { CollaborationDecisionReason.CAPABILITY_DENIED }
    }

    private fun record(actorId: String, action: String, commentDigest: String): CollaborationDecision {
        require(commentDigest.matches(DIGEST_PATTERN)) { "Comment digest must be redacted" }
        revision += 1
        audit += CollaborationAuditEntry(revision, actorId, action, commentDigest)
        return CollaborationDecision(revision, CollaborationDecisionReason.APPLIED)
    }

    private fun reject(reason: CollaborationDecisionReason) = CollaborationDecision(revision, reason)
}

private const val ZERO_DIGEST = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
private val PROJECT_ID_PATTERN = Regex("project_v1_[a-z0-9-]{1,48}")
private val DIGEST_PATTERN = Regex("[A-Za-z0-9_-]{43}")
