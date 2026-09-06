package dev.agentrelay.session.api

/** Framework-neutral input; workflow ownership and scheduling remain outside the engine. */
data class ExecutionEngineRequest(
    val requestId: String,
    val operation: String,
    val inputDigest: String,
    val deadlineEpochMillis: Long,
    val maxOutputBytes: Long,
) {
    init {
        requireStableEngineValue(requestId, "Engine request id")
        requireStableEngineValue(operation, "Engine operation")
        require(inputDigest.matches(DIGEST_PATTERN)) { "Engine input digest is invalid" }
        require(deadlineEpochMillis > 0) { "Engine deadline is invalid" }
        require(maxOutputBytes in 1..MAX_ENGINE_OUTPUT_BYTES) { "Engine output budget is invalid" }
    }
}

enum class ExecutionEngineProgressPhase { STARTED, RUNNING, WAITING, CHECKPOINTED, FINISHED }

data class ExecutionEngineProgress(
    val sequence: Long,
    val phase: ExecutionEngineProgressPhase,
    val message: String,
    val percent: Int?,
) {
    init {
        require(sequence > 0) { "Engine progress sequence must be positive" }
        requireEngineText(message, "Engine progress message", MAX_ENGINE_MESSAGE_CHARS)
        require(percent == null || percent in 0..100) { "Engine progress percent is invalid" }
    }
}

data class ExecutionEngineArtifact(
    val name: String,
    val mediaType: String,
    val digest: String,
    val sizeBytes: Long,
) {
    init {
        requireStableEngineValue(name, "Engine artifact name", MAX_ENGINE_NAME_CHARS)
        requireStableEngineValue(mediaType, "Engine artifact media type", MAX_ENGINE_NAME_CHARS)
        require(digest.matches(DIGEST_PATTERN)) { "Engine artifact digest is invalid" }
        require(sizeBytes in 1..MAX_ENGINE_OUTPUT_BYTES) { "Engine artifact size is invalid" }
    }
}

data class ExecutionEngineQuestion(
    val questionId: String,
    val prompt: String,
    val options: List<String> = emptyList(),
    val required: Boolean = true,
) {
    init {
        requireStableEngineValue(questionId, "Engine question id")
        requireEngineText(prompt, "Engine question prompt", MAX_ENGINE_MESSAGE_CHARS)
        require(options.size <= MAX_ENGINE_OPTIONS) { "Too many engine question options" }
        require(options.all { it.isNotBlank() && it.length <= MAX_ENGINE_NAME_CHARS }) {
            "Engine question option is invalid"
        }
    }
}

data class ExecutionEngineCheckpoint(
    val sequence: Long,
    val digest: String,
    val resumable: Boolean,
) {
    init {
        require(sequence > 0) { "Engine checkpoint sequence must be positive" }
        require(digest.matches(DIGEST_PATTERN)) { "Engine checkpoint digest is invalid" }
    }
}

enum class ExecutionEngineOutcome { SUCCEEDED, FAILED, CANCELLED, WAITING_FOR_QUESTION, UNKNOWN }

data class ExecutionEngineResult(
    val outcome: ExecutionEngineOutcome,
    val summary: String,
    val artifacts: List<ExecutionEngineArtifact> = emptyList(),
    val checkpoint: ExecutionEngineCheckpoint? = null,
    val question: ExecutionEngineQuestion? = null,
) {
    init {
        requireEngineText(summary, "Engine result summary", MAX_ENGINE_MESSAGE_CHARS)
        require(artifacts.size <= MAX_ENGINE_ARTIFACTS) { "Too many engine artifacts" }
        require(outcome == ExecutionEngineOutcome.WAITING_FOR_QUESTION == (question != null)) {
            "Waiting outcomes require exactly one question"
        }
    }
}

private const val MAX_ENGINE_OUTPUT_BYTES = 100L * 1024 * 1024
private const val MAX_ENGINE_MESSAGE_CHARS = 2_000
private const val MAX_ENGINE_NAME_CHARS = 128
private const val MAX_ENGINE_OPTIONS = 16
private const val MAX_ENGINE_ARTIFACTS = 32
private val DIGEST_PATTERN = Regex("[a-fA-F0-9]{64}")
private val ENGINE_VALUE_PATTERN = Regex("[A-Za-z0-9._:/-]{1,128}")

private fun requireStableEngineValue(value: String, field: String, max: Int = 128) {
    require(value.length <= max && value.matches(ENGINE_VALUE_PATTERN)) { "$field is invalid" }
}

private fun requireEngineText(value: String, field: String, max: Int) {
    require(value.isNotBlank() && value.length <= max && !value.contains('\u0000')) {
        "$field is invalid"
    }
}
