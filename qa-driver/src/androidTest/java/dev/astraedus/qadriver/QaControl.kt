package dev.astraedus.qadriver

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject

/**
 * The client side of the qa build's control receiver (`app/src/qa/.../QaControlReceiver.kt`):
 * `reset`, `seed-rules`, `state`. Sent with `am broadcast` through [QaDevice.shell], i.e. as the
 * shell uid, the only sender the receiver's DUMP guard admits.
 *
 * Each call returns the receiver's JSON answer and fails the test when the answer says `ok:false`
 * or never arrives: a precondition that silently did not apply is how a QA case asserts on the
 * previous case's leftovers.
 */
object QaControl {

    private const val COMPONENT = "${QaDevice.NUDGE_PACKAGE}/com.astraedus.nudge.qa.QaControlReceiver"
    private const val ACTION = "dev.astraedus.nudge.qa.CMD"

    /** The bench fixture, shared with the bash harness (`.maestro/fixtures/rules.json`, see build.gradle.kts). */
    private const val FIXTURE_ASSET = "rules.json"

    private fun send(extras: String): JSONObject {
        val out = QaDevice.shell("am broadcast -n $COMPONENT -a $ACTION $extras")
        val data = DeviceText.broadcastData(out)
            ?: error("no answer from the qa control receiver (is the qa APK installed?): $out")
        val answer = JSONObject(data)
        check(answer.optBoolean("ok")) { "qa control '$extras' failed: $data" }
        return answer
    }

    fun reset(): JSONObject = send("--es cmd reset")

    fun state(): JSONObject = send("--es cmd state")

    /**
     * Imports the bench fixture through the app's own import path. Base64 with NO_WRAP because the
     * shell call splits on whitespace and has no quoting.
     */
    fun seedFixture(): JSONObject {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open(FIXTURE_ASSET).use { it.readBytes() }
        return send("--es cmd seed-rules --es b64 ${Base64.encodeToString(bytes, Base64.NO_WRAP)}")
    }
}
