package dev.agentrelay.session.android

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionArtifactAvailability
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionNotificationPriority
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionPreferences
import dev.agentrelay.session.api.SessionPresentationText
import dev.agentrelay.session.api.SessionPresentationTextKind
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionQuestionOption
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.storage.android.SecureDocumentStore
import dev.agentrelay.storage.android.SecureStoreCorruptException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AndroidEncryptedSessionHubStoreTest {
    @Test
    fun completeSnapshotRoundTripsAndPlaintextBuffersAreCleared() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        val expected = completeSnapshot()

        store.save(expected)

        assertTrue(documents.lastWriteReference?.all { it == 0.toByte() } == true)
        assertEquals(expected, store.load())
        assertTrue(documents.lastReadReference?.all { it == 0.toByte() } == true)
    }

    @Test
    fun sessionDataUsesDedicatedKeystoreNamespace() {
        assertEquals("session-secure-store", SESSION_HUB_NAMESPACE.directoryName)
        assertEquals("agent-relay:session-store:v1", SESSION_HUB_NAMESPACE.associatedDataPrefix)
        assertEquals("agent-relay.session.secure-store.v1", SESSION_HUB_NAMESPACE.keyAlias)
    }

    @Test
    fun malformedDocumentFailsClosedAndClearsReadBuffer() = runTest {
        val documents = InMemoryDocuments("not-json".encodeToByteArray())
        val store = AndroidEncryptedSessionHubStore(documents)

        assertFailsWith<SecureStoreCorruptException> {
            store.load()
        }
        assertTrue(documents.lastReadReference?.all { it == 0.toByte() } == true)
    }

    @Test
    fun unsupportedVersionAndDuplicateRecordsFailClosed() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        store.save(completeSnapshot())
        val valid = Json.parseToJsonElement(documents.storedText()).jsonObject

        documents.replace(
            JsonObject(valid + ("formatVersion" to JsonPrimitive(3))).toString().encodeToByteArray(),
        )
        assertFailsWith<SecureStoreCorruptException> {
            store.load()
        }

        val sessions = valid.getValue("sessions").jsonArray
        documents.replace(
            JsonObject(valid + ("sessions" to JsonArray(sessions + sessions.first())))
                .toString()
                .encodeToByteArray(),
        )
        assertFailsWith<SecureStoreCorruptException> {
            store.load()
        }
    }

    @Test
    fun legacyVersionOneDocumentWithoutActionFieldsStillLoads() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        store.save(completeSnapshot())
        val valid = Json.parseToJsonElement(documents.storedText()).jsonObject
        val legacyActivities = JsonArray(
            valid.getValue("activities").jsonArray.map { element ->
                JsonObject(
                    element.jsonObject -
                        setOf("actionRequestId", "summaryKind", "summaryArgument"),
                )
            },
        )
        val legacy = JsonObject(
            (valid - "actionRequests" - "artifacts") +
                ("formatVersion" to JsonPrimitive(1)) +
                ("activities" to legacyActivities),
        )
        documents.replace(legacy.toString().encodeToByteArray())

        val restored = store.load()

        assertTrue(restored.actionRequests.isEmpty())
        assertTrue(restored.artifacts.isEmpty())
        val activity = restored.activities.single()
        assertEquals(null, activity.actionRequestId)
        assertEquals(
            SessionActivitySummary.Verbatim("Command approval required"),
            activity.summary,
        )
    }

    @Test
    fun generatedSummaryRoundTripsWithoutPersistingFallbackCopy() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        val generated = SessionActivity(
            id = "reconnected:workstation",
            locator = ssh,
            type = SessionActivityType.RECONNECTED,
            summary = SessionActivitySummary.Generated(
                SessionActivitySummaryKind.CONNECTION_RECONNECTED,
                "Workstation",
            ),
            eventAnchorId = "reconnect-event",
            occurredAtEpochMillis = 40,
        )
        val expected = completeSnapshot().copy(activities = listOf(generated))

        store.save(expected)

        val document = Json.parseToJsonElement(documents.storedText()).jsonObject
        assertEquals(JsonPrimitive(2), document.getValue("formatVersion"))
        val storedActivity = document.getValue("activities").jsonArray.single().jsonObject
        assertEquals(JsonNull, storedActivity.getValue("summary"))
        assertEquals(
            JsonPrimitive("CONNECTION_RECONNECTED"),
            storedActivity.getValue("summaryKind"),
        )
        assertEquals(JsonPrimitive("Workstation"), storedActivity.getValue("summaryArgument"))
        assertEquals(expected, store.load())
    }

    @Test
    fun generatedActionAndQuestionTextRoundTripsWithoutFallbackCopy() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        val baseline = completeSnapshot()
        val baselineAction = baseline.actionRequests.single()
        val generatedAction = baselineAction.copy(
            title = SessionPresentationText.Generated(
                SessionPresentationTextKind.ACTION_REVIEW_REQUIRED,
            ),
            questions = baselineAction.questions.map { question ->
                question.copy(
                    prompt = SessionPresentationText.Generated(
                        SessionPresentationTextKind.AGENT_QUESTION,
                    ),
                )
            },
        )
        val expected = baseline.copy(actionRequests = listOf(generatedAction))

        store.save(expected)

        val document = Json.parseToJsonElement(documents.storedText()).jsonObject
        val storedAction = document.getValue("actionRequests").jsonArray.single().jsonObject
        assertEquals(JsonNull, storedAction.getValue("title"))
        assertEquals(
            JsonPrimitive("ACTION_REVIEW_REQUIRED"),
            storedAction.getValue("titleKind"),
        )
        val storedQuestion = storedAction.getValue("questions").jsonArray.single().jsonObject
        assertEquals(JsonNull, storedQuestion.getValue("prompt"))
        assertEquals(JsonPrimitive("AGENT_QUESTION"), storedQuestion.getValue("promptKind"))
        assertEquals(expected, store.load())
    }

    @Test
    fun legacyVersionOneActionTextLoadsAsVerbatim() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        val expected = completeSnapshot()
        store.save(expected)
        val valid = Json.parseToJsonElement(documents.storedText()).jsonObject
        val legacyActions = JsonArray(
            valid.getValue("actionRequests").jsonArray.map { actionElement ->
                val action = actionElement.jsonObject
                val legacyQuestions = JsonArray(
                    action.getValue("questions").jsonArray.map { questionElement ->
                        JsonObject(questionElement.jsonObject - "promptKind")
                    },
                )
                JsonObject((action - "titleKind") + ("questions" to legacyQuestions))
            },
        )
        val legacy = JsonObject(
            valid +
                ("formatVersion" to JsonPrimitive(1)) +
                ("actionRequests" to legacyActions),
        )
        documents.replace(legacy.toString().encodeToByteArray())

        assertEquals(expected, store.load())
    }

    @Test
    fun contradictoryActionPresentationShapeFailsClosed() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        store.save(completeSnapshot())
        val valid = Json.parseToJsonElement(documents.storedText()).jsonObject
        val invalidActions = JsonArray(
            valid.getValue("actionRequests").jsonArray.map { actionElement ->
                JsonObject(
                    actionElement.jsonObject +
                        ("titleKind" to JsonPrimitive("ACTION_REVIEW_REQUIRED")),
                )
            },
        )
        documents.replace(
            JsonObject(valid + ("actionRequests" to invalidActions)).toString().encodeToByteArray(),
        )

        assertFailsWith<SecureStoreCorruptException> {
            store.load()
        }
    }

    @Test
    fun contradictoryActivitySummaryShapeFailsClosed() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidEncryptedSessionHubStore(documents)
        store.save(completeSnapshot())
        val valid = Json.parseToJsonElement(documents.storedText()).jsonObject
        val invalidActivities = JsonArray(
            valid.getValue("activities").jsonArray.map { element ->
                JsonObject(element.jsonObject + ("summaryArgument" to JsonPrimitive("unexpected")))
            },
        )
        documents.replace(
            JsonObject(valid + ("activities" to invalidActivities)).toString().encodeToByteArray(),
        )

        assertFailsWith<SecureStoreCorruptException> {
            store.load()
        }
    }

    private class InMemoryDocuments(initial: ByteArray? = null) : SecureDocumentStore {
        private var value: ByteArray? = initial?.copyOf()
        var lastWriteReference: ByteArray? = null
            private set
        var lastReadReference: ByteArray? = null
            private set

        override suspend fun read(documentId: String): ByteArray? =
            value?.copyOf()?.also { lastReadReference = it }

        override suspend fun write(
            documentId: String,
            plaintext: ByteArray,
        ) {
            lastWriteReference = plaintext
            value = plaintext.copyOf()
        }

        override suspend fun delete(documentId: String) {
            value = null
        }

        fun replace(replacement: ByteArray) {
            value = replacement.copyOf()
        }

        fun storedText(): String = checkNotNull(value).decodeToString()
    }

    companion object {
        private val ssh = SessionLocator(
            connectionProviderId = ConnectionProviderId("ssh.secure-shell"),
            connectionProfileId = ConnectionProfileId("workstation"),
            agentProviderId = AgentProviderId("codex"),
            agentSessionId = AgentSessionId("remote-thread"),
        )
        private val local = SessionLocator(
            connectionProviderId = ConnectionProviderId("local.device"),
            connectionProfileId = ConnectionProfileId("local"),
            agentProviderId = AgentProviderId("aider"),
            agentSessionId = AgentSessionId("local-chat"),
        )

        private fun completeSnapshot() = SessionHubSnapshot(
            sessions = listOf(
                SessionRecord(
                    observation = observation(
                        locator = ssh,
                        connectionLabel = "Workstation",
                        connectionTarget = "developer@example.test:22",
                        providerLabel = "Codex",
                        state = AgentSessionState.WAITING_FOR_APPROVAL,
                        updatedAt = 30L,
                    ),
                    preferences = SessionPreferences(
                        pinned = true,
                        notificationPriority = SessionNotificationPriority.ALL_ACTIVITY,
                    ),
                    unreadCount = 1,
                    lastActivityAtEpochMillis = 30L,
                ),
                SessionRecord(
                    observation = observation(
                        locator = local,
                        connectionLabel = "This device",
                        connectionTarget = "Android app sandbox",
                        providerLabel = "Aider",
                        state = AgentSessionState.IDLE,
                        updatedAt = 20L,
                    ),
                    preferences = SessionPreferences(archived = true),
                    unreadCount = 0,
                    lastActivityAtEpochMillis = 20L,
                ),
            ),
            drafts = mapOf(
                ssh to SessionDraft("Approve after review", 7, 12, 31L),
            ),
            activities = listOf(
                SessionActivity(
                    id = "approval:remote-thread:one",
                    locator = ssh,
                    type = SessionActivityType.APPROVAL_REQUIRED,
                    summary = SessionActivitySummary.Verbatim("Command approval required"),
                    eventAnchorId = "event-one",
                    actionRequestId = "action-one",
                    occurredAtEpochMillis = 30L,
                ),
            ),
            transcripts = mapOf(
                ssh to listOf(
                    CachedTranscriptEntry(
                        id = "message-one",
                        turnId = "turn-one",
                        role = AgentTranscriptRole.AGENT,
                        channel = AgentMessageChannel.COMMENTARY,
                        text = "I need approval before continuing.",
                        createdAtEpochMillis = 29L,
                        metadata = mapOf("model" to "gpt"),
                    ),
                ),
                local to listOf(
                    CachedTranscriptEntry(
                        id = "message-two",
                        turnId = null,
                        role = AgentTranscriptRole.USER,
                        channel = null,
                        text = "Inspect this project.",
                        createdAtEpochMillis = 19L,
                    ),
                ),
            ),
            actionRequests = listOf(
                SessionActionRequest(
                    id = "action-one",
                    providerApprovalId = "provider-approval-one",
                    locator = ssh,
                    turnId = "turn-one",
                    type = AgentApprovalType.COMMAND,
                    title = SessionPresentationText.Verbatim("Remove generated output"),
                    description = "Clean the generated output before rebuilding",
                    command = "rm -rf /workspace/project/build",
                    workingDirectory = "/workspace/project",
                    questions = listOf(
                        SessionQuestion(
                            id = "question-scope",
                            providerQuestionId = "scope",
                            header = "Scope",
                            prompt = SessionPresentationText.Verbatim("Apply once or for this session?"),
                            options = listOf(
                                SessionQuestionOption("once", "Only this command"),
                                SessionQuestionOption("session", "Similar commands this session"),
                            ),
                            allowsOther = false,
                        ),
                    ),
                    availableDecisions = setOf(
                        AgentApprovalDecision.SUBMIT,
                        AgentApprovalDecision.CANCEL,
                    ),
                    riskReasons = setOf(SessionActionRisk.DESTRUCTIVE_COMMAND),
                    receivedAtEpochMillis = 30L,
                    state = SessionActionState.DELIVERING,
                    decision = AgentApprovalDecision.SUBMIT,
                    answeredQuestionIds = setOf("question-scope"),
                    additionalConfirmationGiven = true,
                    decisionAtEpochMillis = 31L,
                ),
            ),
            artifacts = listOf(
                SessionArtifact(
                    id = "artifact-one",
                    locator = ssh,
                    providerPath = "/workspace/project/reports/result.txt",
                    relativePath = "reports/result.txt",
                    oldProviderPath = null,
                    oldRelativePath = null,
                    kind = AgentFileChangeKind.MODIFIED,
                    turnId = "turn-one",
                    availability = SessionArtifactAvailability.DOWNLOADABLE,
                    observedAtEpochMillis = 32L,
                ),
                SessionArtifact(
                    id = "artifact-deleted",
                    locator = local,
                    providerPath = "old.log",
                    relativePath = "old.log",
                    oldProviderPath = null,
                    oldRelativePath = null,
                    kind = AgentFileChangeKind.DELETED,
                    turnId = null,
                    availability = SessionArtifactAvailability.DELETED,
                    observedAtEpochMillis = 21L,
                ),
            ),
        )

        private fun observation(
            locator: SessionLocator,
            connectionLabel: String,
            connectionTarget: String,
            providerLabel: String,
            state: AgentSessionState,
            updatedAt: Long,
        ) = SessionObservation(
            locator = locator,
            connectionLabel = connectionLabel,
            connectionTarget = connectionTarget,
            projectPath = "/workspace/project",
            agentProviderLabel = providerLabel,
            title = "$providerLabel session",
            preview = "Latest durable output",
            agentState = state,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = updatedAt,
            metadata = mapOf("source" to "provider"),
        )
    }
}
