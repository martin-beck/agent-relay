package com.example.agentrelay.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.agentrelay.companion.api.DebugWearEvidence
import dev.agentrelay.companion.api.DebugWearPacketCodec
import dev.agentrelay.companion.api.DebugWearReceiver
import dev.agentrelay.companion.api.DebugWearRole

/**
 * Debug-only ADB injection endpoint for deterministic phone/Wear contract tests.
 *
 * This activity is deliberately under the debug source set. It does not create a
 * pairing grant and does not use the authenticated Data Layer transport.
 */
class DebugWearAdbActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val encoded = intent.getStringExtra(EXTRA_PACKET)
        val result = if (encoded == null) {
            RESULT_MISSING
        } else {
            val packet = DebugWearPacketCodec.decode(encoded)
            if (packet == null) {
                RESULT_MALFORMED
            } else {
                DebugWearReceiver(
                    expectedRole = DebugWearRole.PHONE,
                    evidence = DebugWearEvidence("debug"),
                ).receive(packet, System.currentTimeMillis())
                    .name
            }
        }
        setContentView(TextView(this).apply { text = result })
    }

    private companion object {
        const val EXTRA_PACKET = "packet"
        const val RESULT_MISSING = "MISSING"
        const val RESULT_MALFORMED = "MALFORMED"
    }
}
