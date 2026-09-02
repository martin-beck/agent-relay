package com.example.agentrelay.background

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundTransportControllerTest {
    @Test
    fun startAndStopAreExplicitAndDuplicateSafe() {
        val actions = mutableListOf<BackgroundTransportAction>()
        val controller = BackgroundTransportController(actions::add)

        controller.start()
        controller.start()
        assertEquals(BackgroundTransportState.STARTING, controller.state.value)
        assertEquals(listOf(BackgroundTransportAction.START), actions)

        controller.serviceActivated()
        controller.start()
        assertEquals(BackgroundTransportState.ACTIVE, controller.state.value)
        assertEquals(listOf(BackgroundTransportAction.START), actions)

        controller.stop()
        controller.stop()
        assertEquals(BackgroundTransportState.STOPPING, controller.state.value)
        assertEquals(
            listOf(BackgroundTransportAction.START, BackgroundTransportAction.STOP),
            actions,
        )

        controller.serviceStopped()
        assertEquals(BackgroundTransportState.STOPPED, controller.state.value)
    }

    @Test
    fun failedStartCanBeRetriedWithoutExposingFailureDetails() {
        var attempts = 0
        val controller = BackgroundTransportController {
            attempts += 1
            if (attempts == 1) {
                error("private endpoint detail")
            }
        }

        controller.start()
        assertEquals(BackgroundTransportState.START_FAILED, controller.state.value)

        controller.start()
        assertEquals(BackgroundTransportState.STARTING, controller.state.value)
        assertEquals(2, attempts)
    }

    @Test
    fun failedStopKeepsThePreviousActiveState() {
        val controller = BackgroundTransportController { action ->
            if (action == BackgroundTransportAction.STOP) {
                error("private endpoint detail")
            }
        }
        controller.start()
        controller.serviceActivated()

        controller.stop()

        assertEquals(BackgroundTransportState.ACTIVE, controller.state.value)
    }

    @Test
    fun lateCallbacksCannotOverrideStopOrStoppedStates() {
        val controller = BackgroundTransportController {}
        controller.start()
        controller.stop()

        controller.serviceFailed()
        controller.serviceActivated()
        assertEquals(BackgroundTransportState.STOPPING, controller.state.value)

        controller.start()
        assertEquals(BackgroundTransportState.STOPPING, controller.state.value)

        controller.serviceStopped()
        controller.serviceFailed()
        controller.serviceActivated()
        assertEquals(BackgroundTransportState.STOPPED, controller.state.value)
    }

    @Test
    fun systemRestartReentersStartingAndFailuresCannotLeaveAFalseActiveState() {
        val controller = BackgroundTransportController {}

        controller.serviceRestarting()
        assertEquals(BackgroundTransportState.STARTING, controller.state.value)
        controller.serviceActivated()
        assertEquals(BackgroundTransportState.ACTIVE, controller.state.value)

        controller.serviceFailed()
        assertEquals(BackgroundTransportState.START_FAILED, controller.state.value)
        controller.serviceRestarting()
        assertEquals(BackgroundTransportState.STARTING, controller.state.value)

        controller.serviceStopped()
        controller.serviceFailed()
        assertEquals(BackgroundTransportState.STOPPED, controller.state.value)
    }

    @Test
    fun intentActionsArePackageScopedAndStrictlyParsed() {
        val packageName = "dev.agentrelay.test"
        val start = BackgroundTransportAction.START.intentAction(packageName)
        val stop = BackgroundTransportAction.STOP.intentAction(packageName)

        assertEquals("dev.agentrelay.test.background.START", start)
        assertEquals("dev.agentrelay.test.background.STOP", stop)
        assertEquals(
            BackgroundTransportAction.START,
            start.backgroundTransportAction(packageName),
        )
        assertEquals(
            BackgroundTransportAction.STOP,
            stop.backgroundTransportAction(packageName),
        )
        assertEquals(null, start.backgroundTransportAction("dev.agentrelay.other"))
        assertEquals(null, "dev.agentrelay.test.background.UNKNOWN".backgroundTransportAction(packageName))
        assertEquals(null, null.backgroundTransportAction(packageName))
    }
}
