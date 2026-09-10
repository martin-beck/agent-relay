/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

/** Stable API version for provider, transport, and external-information connectors. */
const val CONNECTOR_SDK_API_VERSION = 1

@JvmInline
value class ConnectorId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) {
            "Connector id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class ConnectorWorkflowId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            "Connector workflow id must be a stable identifier"
        }
    }
}

@JvmInline
value class ConnectorAuthenticationReference(val value: String) {
    init {
        require(value.startsWith("ref://") && !value.any(Char::isWhitespace)) {
            "Connector authentication must use an opaque approved-store reference"
        }
    }
}

enum class ConnectorKind {
    AGENT,
    TRANSPORT,
    EXTERNAL_INFORMATION,
}

enum class ConnectorCapability {
    READ,
    POLL,
    WEBHOOK,
    WRITE_PROPOSAL,
}

data class ConnectorRateLimit(
    val maximumRequests: Int,
    val windowSeconds: Long,
) {
    init {
        require(maximumRequests > 0) { "Connector rate limit must allow at least one request" }
        require(windowSeconds > 0) { "Connector rate-limit window must be positive" }
    }
}

data class ConnectorDescriptor(
    val id: ConnectorId,
    val displayName: String,
    val version: String,
    val kind: ConnectorKind,
    val capabilities: Set<ConnectorCapability>,
    val declaredFields: Set<String>,
    val authenticationRequired: Boolean,
    val rateLimit: ConnectorRateLimit,
    val apiVersion: Int = CONNECTOR_SDK_API_VERSION,
    val enabledByDefault: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "Connector display name must not be blank" }
        require(version.isNotBlank()) { "Connector version must not be blank" }
        require(declaredFields.isNotEmpty()) { "Connector must declare at least one field" }
        require(!enabledByDefault) { "Connectors must be explicitly enabled by a workflow" }
        require(ConnectorCapability.READ in capabilities) {
            "Every connector must expose normalized read results"
        }
    }
}

data class ConnectorActivation(
    val connectorId: ConnectorId,
    val workflowId: ConnectorWorkflowId,
    val fields: Set<String>,
    val expiresAtEpochSeconds: Long,
) {
    init {
        require(fields.isNotEmpty()) { "Connector activation must request fields" }
        require(expiresAtEpochSeconds > 0) { "Connector activation must expire" }
    }

    fun isActive(nowEpochSeconds: Long): Boolean = nowEpochSeconds < expiresAtEpochSeconds
}

sealed interface ConnectorTrigger {
    data object WorkflowRun : ConnectorTrigger
    data class Poll(val scheduledAtEpochSeconds: Long) : ConnectorTrigger
    data class Webhook(val deliveryId: String) : ConnectorTrigger {
        init {
            require(deliveryId.isNotBlank()) { "Webhook delivery id must not be blank" }
        }
    }
}

data class ConnectorInvocation(
    val activation: ConnectorActivation,
    val trigger: ConnectorTrigger,
    val authentication: ConnectorAuthenticationReference?,
    val input: Map<String, String> = emptyMap(),
)

data class ConnectorRecord(
    val fields: Map<String, String>,
    val observedAtEpochSeconds: Long,
) {
    init {
        require(fields.isNotEmpty()) { "Connector result must contain normalized fields" }
        require(observedAtEpochSeconds > 0) { "Connector observation time must be positive" }
    }
}

sealed interface ConnectorResult {
    data class Success(val records: List<ConnectorRecord>) : ConnectorResult
    data object NoData : ConnectorResult
    data class Failed(val code: String, val recoverable: Boolean) : ConnectorResult {
        init {
            require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) {
                "Connector failure code must be stable and redacted"
            }
        }
    }
}

interface ConnectorFactory {
    val descriptor: ConnectorDescriptor

    suspend fun invoke(request: ConnectorInvocation): ConnectorResult
}

class ConnectorRegistry(
    factories: Iterable<ConnectorFactory>,
    private val nowEpochSeconds: () -> Long,
) {
    private val registered: Map<ConnectorId, ConnectorFactory>

    init {
        val all = factories.toList()
        require(all.all { it.descriptor.apiVersion == CONNECTOR_SDK_API_VERSION }) {
            "Connector SDK API mismatch"
        }
        val duplicates = all.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Duplicate connector ids: ${duplicates.joinToString()}" }
        registered = all.associateBy { it.descriptor.id }
    }

    fun descriptors(): List<ConnectorDescriptor> = registered.values
        .map { it.descriptor }
        .sortedBy { it.displayName }

    fun activate(
        connectorId: ConnectorId,
        workflowId: ConnectorWorkflowId,
        fields: Set<String>,
        expiresAtEpochSeconds: Long,
    ): ConnectorActivation {
        val descriptor = factory(connectorId).descriptor
        require(fields.isNotEmpty() && fields.all { it in descriptor.declaredFields }) {
            "Workflow requests undeclared connector fields"
        }
        return ConnectorActivation(connectorId, workflowId, fields, expiresAtEpochSeconds)
    }

    suspend fun invoke(request: ConnectorInvocation): ConnectorResult {
        require(request.activation.isActive(nowEpochSeconds())) { "Connector activation has expired" }
        val descriptor = factory(request.activation.connectorId).descriptor
        require(request.activation.fields.all { it in descriptor.declaredFields }) {
            "Workflow requests undeclared connector fields"
        }
        if (descriptor.authenticationRequired) {
            require(request.authentication != null) { "Connector authentication reference is required" }
        }
        return factory(request.activation.connectorId).invoke(request)
    }

    private fun factory(id: ConnectorId): ConnectorFactory = registered[id]
        ?: throw NoSuchElementException("No connector registered for $id")
}
