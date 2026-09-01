package dev.agentrelay.session.runtime

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionHubRepository
import dev.agentrelay.session.api.SessionLocator
import kotlinx.coroutines.CancellationException

internal class SessionActionResponseCoordinator(
    private val repository: SessionHubRepository,
    private val now: () -> Long,
) {
    suspend fun respond(
        active: ActiveAgentHandle,
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    ) {
        val request = repository.snapshot.value.actionRequest(locator, requestId)
            ?: throw NoSuchElementException("Action request is unavailable")
        val providerAnswers = validatedProviderAnswers(request, decision, answers)
        repository.beginActionResponse(
            locator = locator,
            requestId = requestId,
            decision = decision,
            answeredQuestionIds = answers.keys,
            additionalConfirmationGiven = additionalConfirmationGiven,
            startedAtEpochMillis = now(),
        )
        deliver(active, request, decision, providerAnswers)
        complete(locator, requestId)
    }

    private suspend fun deliver(
        active: ActiveAgentHandle,
        request: SessionActionRequest,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        try {
            active.connection.respondToApproval(
                approvalId = AgentApprovalId(request.providerApprovalId),
                decision = decision,
                answers = answers,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            throw SessionActionDeliveryUncertainException()
        }
    }

    private suspend fun complete(
        locator: SessionLocator,
        requestId: String,
    ) {
        try {
            repository.completeActionResponse(
                locator = locator,
                requestId = requestId,
                completedAtEpochMillis = now(),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            throw SessionActionAuditFailureException()
        }
    }

    private fun validatedProviderAnswers(
        request: SessionActionRequest,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ): Map<String, List<String>> {
        require(decision in request.availableDecisions) {
            "Decision was not offered by the provider"
        }
        if (decision != AgentApprovalDecision.SUBMIT) {
            require(answers.isEmpty()) {
                "Only a submitted answer may include question answers"
            }
            return emptyMap()
        }
        val questionById = request.questions.associateBy { it.id }
        require(answers.keys == questionById.keys) { "Every question requires an answer" }
        var answerCharacters = 0
        return request.questions.associate { question ->
            val values = checkNotNull(answers[question.id])
            require(values.isNotEmpty()) { "Question answer must not be empty" }
            require(values.size <= MAX_ANSWER_VALUES) { "Question has too many answers" }
            require(question.allowsMultiple || values.size == 1) {
                "Question accepts only one answer"
            }
            require(values.distinct().size == values.size) {
                "Question contains duplicate answers"
            }
            require(values.all { it.isNotBlank() && it.length <= MAX_ANSWER_CHARS }) {
                "Question answer is invalid or too large"
            }
            if (!question.allowsOther) {
                val offered = question.options.mapTo(mutableSetOf()) { it.label }
                require(values.all(offered::contains)) {
                    "Question answer was not offered by the provider"
                }
            }
            answerCharacters += values.sumOf(String::length)
            require(answerCharacters <= MAX_TOTAL_ANSWER_CHARS) {
                "Question answers are too large"
            }
            question.providerQuestionId to values
        }
    }

    private companion object {
        const val MAX_ANSWER_VALUES = 64
        const val MAX_ANSWER_CHARS = 4_096
        const val MAX_TOTAL_ANSWER_CHARS = 64 * 1_024
    }
}
