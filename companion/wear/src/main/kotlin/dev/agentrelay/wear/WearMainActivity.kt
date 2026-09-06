package dev.agentrelay.wear

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal attention-first surface; phone transport and actions arrive in later slices. */
class WearMainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 24)
        }
        root.addView(
            TextView(this).apply {
                text = getString(R.string.wear_title)
                textSize = 22f
                gravity = Gravity.CENTER
            },
        )
        root.addView(
            TextView(this).apply {
                text = getString(R.string.wear_phone_required)
                gravity = Gravity.CENTER
            },
        )
        setContentView(root)
    }
}
