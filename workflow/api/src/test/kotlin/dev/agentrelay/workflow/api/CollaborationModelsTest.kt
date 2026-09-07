package dev.agentrelay.workflow.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CollaborationModelsTest {
    private val digest = "B".repeat(43)
    private val lease = CollaborationLease("lease_v1_owner", "actor_v1_owner", 0, 100)
    private val owner = CollaborationActor(
        "actor_v1_owner",
        CollaborationRole.OWNER,
        CollaborationCapability.entries.toSet(),
        lease,
    )

    @Test
    fun concurrentCompareAndSetAcceptsOnlyOneRevision() {
        val authority = CollaborationAuthority("project_v1_demo")
        assertEquals(CollaborationDecisionReason.APPLIED, authority.register(owner).reason)
        val first = authority.apply(owner.id, CollaborationCapability.WRITE, 1, 10, digest)
        val stale = authority.apply(owner.id, CollaborationCapability.WRITE, 1, 10, digest)
        assertEquals(CollaborationDecisionReason.APPLIED, first.reason)
        assertEquals(CollaborationDecisionReason.STALE_REVISION, stale.reason)
        assertEquals(2, authority.currentRevision())
    }

    @Test
    fun delegationCannotEscalateScopeAndEveryMutationIsAudited() {
        val authority = CollaborationAuthority("project_v1_demo")
        val delegator = owner.copy(
            capabilities = setOf(CollaborationCapability.READ, CollaborationCapability.WRITE, CollaborationCapability.DELEGATE),
        )
        authority.register(delegator)
        val editor = CollaborationActor(
            "actor_v1_editor",
            CollaborationRole.EDITOR,
            setOf(CollaborationCapability.READ, CollaborationCapability.WRITE),
            CollaborationLease("lease_v1_editor", "actor_v1_editor", 0, 100),
        )
        assertEquals(
            CollaborationDecisionReason.APPLIED,
            authority.delegate(delegator.id, editor, 1, 10, digest).reason,
        )
        val reviewer = editor.copy(
            id = "actor_v1_reviewer",
            role = CollaborationRole.REVIEWER,
            capabilities = setOf(CollaborationCapability.APPROVE),
            lease = CollaborationLease("lease_v1_reviewer", "actor_v1_reviewer", 0, 100),
        )
        assertEquals(
            CollaborationDecisionReason.SCOPE_ESCALATION,
            authority.delegate(delegator.id, reviewer, 2, 10, digest).reason,
        )
        assertTrue(authority.auditTrail().all { it.commentDigest == digest || it.commentDigest == "A".repeat(43) })
    }

    @Test
    fun expiredAndRevokedLeasesFailClosed() {
        val authority = CollaborationAuthority("project_v1_demo")
        authority.register(owner)
        val expired = owner.copy(
            id = "actor_v1_expired",
            lease = CollaborationLease("lease_v1_expired", "actor_v1_expired", 0, 2),
        )
        authority.delegate(owner.id, expired, 1, 1, digest)
        assertEquals(
            CollaborationDecisionReason.LEASE_EXPIRED,
            authority.apply(expired.id, CollaborationCapability.READ, 2, 3, digest).reason,
        )
        assertEquals(
            CollaborationDecisionReason.APPLIED,
            authority.revoke(owner.id, 2, 10, digest).reason,
        )
        assertEquals(
            CollaborationDecisionReason.LEASE_REVOKED,
            authority.apply(owner.id, CollaborationCapability.READ, 3, 10, digest).reason,
        )
    }
}
