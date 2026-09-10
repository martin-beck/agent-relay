/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectorSdkTest {
    private val descriptor = ConnectorDescriptor(
        id = ConnectorId("calendar.demo"),
        displayName = "Calendar demo",
        version = "1.0.0",
        kind = ConnectorKind.EXTERNAL_INFORMATION,
        capabilities = setOf(ConnectorCapability.READ, ConnectorCapability.POLL),
        declaredFields = setOf("title"),
        authenticationRequired = true,
        rateLimit = ConnectorRateLimit(10, 60),
    )

    @Test
    fun `activation is explicit and invocation requires declared fields and auth reference`() = runTest {
        var now = 100L
        val registry = ConnectorRegistry(listOf(fakeFactory())) { now }
        val activation = registry.activate(
            ConnectorId("calendar.demo"),
            ConnectorWorkflowId("workflow-1"),
            setOf("title"),
            expiresAtEpochSeconds = 200,
        )

        val result = registry.invoke(
            ConnectorInvocation(
                activation,
                ConnectorTrigger.WorkflowRun,
                ConnectorAuthenticationReference("ref://calendar/demo"),
            ),
        )
        assertEquals(ConnectorResult.NoData, result)

        now = 200
        var expired = false
        try {
            registry.invoke(
                ConnectorInvocation(
                    activation,
                    ConnectorTrigger.WorkflowRun,
                    ConnectorAuthenticationReference("ref://calendar/demo"),
                ),
            )
        } catch (_: IllegalArgumentException) {
            expired = true
        }
        assertTrue(expired)
    }

    @Test
    fun `undeclared fields fail closed`() {
        val registry = ConnectorRegistry(listOf(fakeFactory())) { 100 }

        assertFailsWith<IllegalArgumentException> {
            registry.activate(
                ConnectorId("calendar.demo"),
                ConnectorWorkflowId("workflow-1"),
                setOf("location"),
                expiresAtEpochSeconds = 200,
            )
        }
    }

    private fun fakeFactory() = object : ConnectorFactory {
        override val descriptor = this@ConnectorSdkTest.descriptor

        override suspend fun invoke(request: ConnectorInvocation): ConnectorResult = ConnectorResult.NoData
    }
}
