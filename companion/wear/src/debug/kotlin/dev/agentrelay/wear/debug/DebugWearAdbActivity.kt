package dev.agentrelay.wear.debug

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import dev.agentrelay.companion.api.DebugWearEvidence
import dev.agentrelay.companion.api.DebugWearPacketCodec
import dev.agentrelay.companion.api.DebugWearReceiver
import dev.agentrelay.companion.api.DebugWearRole

/** Debug-only ADB endpoint; it is not a production pairing or Data Layer path. */
class DebugWearAdbActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val encoded = intent.getStringExtra(EXTRA_PACKET)
        val result = encoded?.let { packetText ->
            DebugWearPacketCodec.decode(packetText)?.let { packet ->
                DebugWearReceiver(
                    expectedRole = DebugWearRole.WEAR,
                    evidence = DebugWearEvidence("debug"),
                ).receive(packet, System.currentTimeMillis()).name
            } ?: RESULT_MALFORMED
        } ?: RESULT_MISSING
        setContentView(TextView(this).apply { text = result })
    }

    private companion object {
        const val EXTRA_PACKET = "packet"
        const val RESULT_MISSING = "MISSING"
        const val RESULT_MALFORMED = "MALFORMED"
    }
}
