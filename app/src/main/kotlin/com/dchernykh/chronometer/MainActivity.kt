package com.dchernykh.chronometer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder launcher activity. It exists only so the skeleton produces a
 * runnable APK for the CI and release wiring; the real UI is future work.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val message = TextView(this)
        message.text = getString(R.string.placeholder_message)
        setContentView(message)
    }
}
