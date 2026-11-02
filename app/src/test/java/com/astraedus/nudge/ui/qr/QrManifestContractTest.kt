package com.astraedus.nudge.ui.qr

import com.astraedus.nudge.testing.SourceScan
import com.astraedus.nudge.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's manifest wiring, none of which any code path can observe until it is missing on a
 * user's phone: an undeclared CAMERA permission makes the runtime request fail silently, a REQUIRED
 * camera feature drops every camera-less device from the Play listing, and an unregistered or
 * exported activity is a crash or an open door. (INTERNET is policed on the MERGED manifest by the
 * `verify<Variant>MergedPermissions` build task, where library-added permissions are visible.)
 */
class QrManifestContractTest {

    private val manifest: String by lazy {
        SourceScan.mainFile("AndroidManifest.xml").readText()
    }

    /** `.ui.qr.QrScanActivity`, derived from the class and the namespace, never retyped. */
    private val relativeActivityName: String =
        QrScanActivity::class.java.name.removePrefix(BuildConfig::class.java.packageName)

    private fun block(tag: String, name: String): String =
        Regex("""<$tag\b[^>]*?android:name="${Regex.escape(name)}"[^>]*?/?>""")
            .find(manifest)?.value
            ?: error("no <$tag android:name=\"$name\"> in the manifest")

    @Test
    fun `the camera permission is declared`() {
        block("uses-permission", "android.permission.CAMERA")
    }

    @Test
    fun `the camera feature is optional so camera-less devices can still install`() {
        val feature = block("uses-feature", "android.hardware.camera.any")
        assertTrue(feature, feature.contains("""android:required="false""""))
        assertTrue(
            "no other camera feature may be declared required",
            Regex("""<uses-feature\b[^>]*android:name="android\.hardware\.camera[^"]*"[^>]*required="true"""")
                .find(manifest) == null
        )
    }

    @Test
    fun `the scanner activity is registered and not exported`() {
        val activity = block("activity", relativeActivityName)
        assertTrue(activity, activity.contains("""android:exported="false""""))
    }
}
