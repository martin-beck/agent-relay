package dev.agentrelay.session.runtime

import dev.agentrelay.provider.api.RemoteFileAccess
import dev.agentrelay.provider.api.RemoteFileReference
import dev.agentrelay.provider.api.RemoteFileRevision
import dev.agentrelay.provider.api.RemoteFileSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeRemoteFileAccess(
    private val content: ByteArray,
) : RemoteFileAccess {
    var inspectedReference: RemoteFileReference? = null
        private set
    var readReference: RemoteFileReference? = null
        private set

    override suspend fun inspect(
        reference: RemoteFileReference,
        calculateSha256: Boolean,
    ): RemoteFileSnapshot {
        inspectedReference = reference
        return RemoteFileSnapshot(
            reference = reference,
            revision = RemoteFileRevision(
                sizeBytes = content.size.toLong(),
                modifiedAtEpochMillis = 10L,
                sha256 = if (calculateSha256) "0".repeat(64) else null,
            ),
        )
    }

    override fun read(
        reference: RemoteFileReference,
        expectedRevision: RemoteFileRevision,
        chunkSizeBytes: Int,
    ): Flow<ByteArray> = flow {
        readReference = reference
        emit(content.copyOf())
    }
}
