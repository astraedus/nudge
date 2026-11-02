package dev.astraedus.qadriver

import androidx.test.platform.app.InstrumentationRegistry

/** `-e <key> <value>` instrumentation arguments the runner may pass to tune a case's length. */
object QaArgs {
    fun int(key: String, default: Int): Int {
        val raw = InstrumentationRegistry.getArguments().getString(key) ?: return default
        return raw.toIntOrNull()?.takeIf { it > 0 } ?: error("-e $key must be a positive integer, got '$raw'")
    }
}
