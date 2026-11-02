package com.astraedus.nudge.qa

import com.astraedus.nudge.BuildConfig
import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * L4 gate: the qa control channel (`QaControlReceiver`, which wipes rules and history on command)
 * exists in the qa APK and NOWHERE else, and only the shell can reach it.
 *
 * Reads the MERGED manifests AGP produced (paths passed in by app/build.gradle.kts as
 * `nudge.releaseMergedManifest` / `nudge.qaMergedManifest`), not the source manifests: the
 * merged one is what ships, the same reason `verify<Variant>MergedPermissions` reads it.
 *
 * The receiver name and action are DERIVED from `app/src/qa/AndroidManifest.xml`, never retyped
 * here (docs/testing-strategy.md, fixture honesty), and the qa merged manifest is the positive
 * control: the matcher that finds nothing in release must find the receiver in qa.
 */
class QaControlManifestTest {

    private fun merged(property: String): String {
        val path = System.getProperty(property)
            ?: error("-D$property is not set: run this through Gradle (app/build.gradle.kts wires it)")
        return File(path).readText()
    }

    private val qaSourceManifest by lazy { SourceScan.appFile("src/qa/AndroidManifest.xml").readText() }

    /** `.qa.QaControlReceiver` as declared, resolved against the namespace like AGP does. */
    private val receiverClass: String by lazy {
        val declared = Regex("""<receiver\b[^>]*?android:name="([^"]+)"""")
            .find(qaSourceManifest)?.groupValues?.get(1)
            ?: error("no <receiver> in src/qa/AndroidManifest.xml")
        if (declared.startsWith(".")) BuildConfig::class.java.packageName + declared else declared
    }

    private val action: String by lazy {
        Regex("""<action\b[^>]*?android:name="([^"]+)"""").find(qaSourceManifest)?.groupValues?.get(1)
            ?: error("no <action> in src/qa/AndroidManifest.xml")
    }

    /** `tag name permission` for every `exported="true"` component in a merged manifest. */
    private fun exportedComponents(manifest: String): Set<String> {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val app = factory.newDocumentBuilder().parse(manifest.byteInputStream())
            .getElementsByTagName("application").item(0) as Element
        val ns = "http://schemas.android.com/apk/res/android"
        val children = app.childNodes
        return (0 until children.length).map { children.item(it) }.filterIsInstance<Element>()
            .filter { it.getAttributeNS(ns, "exported") == "true" }
            .map { e ->
                val permission = e.getAttributeNS(ns, "permission").ifEmpty { "-" }
                "${e.tagName} ${e.getAttributeNS(ns, "name")} $permission"
            }
            .toSet()
    }

    private fun receiverBlock(manifest: String): String? =
        Regex("""<receiver\b[^>]*?android:name="${Regex.escape(receiverClass)}"[^>]*>""").find(manifest)?.value

    @Test
    fun `the release merged manifest declares no qa control receiver and no qa action`() {
        val release = merged("nudge.releaseMergedManifest")
        // A wrong path or an empty file must not pass as "clean".
        assertTrue("not a merged Nudge manifest", release.contains("NudgeAccessibilityService"))
        assertEquals(null, receiverBlock(release))
        assertFalse("release names the qa receiver class", release.contains(receiverClass))
        assertFalse("release names the qa action $action", release.contains(action))
    }

    /**
     * Release's exported surface, pinned to what origin/main shipped when the qa build type landed
     * (the merged release manifest was byte-identical before and after). Two DUMP-guarded receivers
     * are already here and are library code, not ours: WorkManager's diagnostics and ProfileInstaller.
     * A new entry, ours or a library's, fails this until someone looks at it and updates the pin.
     */
    @Test
    fun `release exports exactly the pinned components, and no DUMP-guarded one of ours`() {
        val exported = exportedComponents(merged("nudge.releaseMergedManifest"))
        assertEquals(
            setOf(
                "activity com.astraedus.nudge.MainActivity -",
                "receiver com.astraedus.nudge.ui.widget.TodayWidgetReceiver -",
                "receiver com.astraedus.nudge.ui.widget.TopBlockedWidgetReceiver -",
                "receiver com.astraedus.nudge.ui.widget.ProtectionWidgetReceiver -",
                "service androidx.glance.appwidget.GlanceRemoteViewsService android.permission.BIND_REMOTEVIEWS",
                "service androidx.work.impl.background.systemjob.SystemJobService android.permission.BIND_JOB_SERVICE",
                "receiver androidx.work.impl.diagnostics.DiagnosticsReceiver android.permission.DUMP",
                "receiver androidx.profileinstaller.ProfileInstallReceiver android.permission.DUMP",
            ),
            exported
        )
        val ownDumpGuarded = exported.filter {
            it.endsWith(" android.permission.DUMP") && !it.split(' ')[1].startsWith("androidx.")
        }
        assertEquals("a DUMP-guarded component of ours in release", emptyList<String>(), ownDumpGuarded)
    }

    @Test
    fun `the qa merged manifest declares the receiver, exported and guarded by DUMP`() {
        val block = receiverBlock(merged("nudge.qaMergedManifest"))
            ?: error("the qa merged manifest has no $receiverClass: the release check above proves nothing")
        assertTrue(block, block.contains("""android:exported="true""""))
        // DUMP is signature|privileged|development: the shell holds it, no installed app can.
        assertTrue(block, block.contains("""android:permission="android.permission.DUMP""""))
    }

    @Test
    fun `the receiver source lives only in the qa source set`() {
        val simpleName = receiverClass.substringAfterLast('.')
        // Every non-qa APK's source sets; `release` has none today, and that is fine.
        val src = SourceScan.appFile("src")
        val roots = listOf("main", "debug", "release").map { File(src, it) }.filter { it.exists() }
        val leaks = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
                .filter { it.readText().contains(simpleName) || it.readText().contains(action) }
                .map { it.path }
                .toList()
        }
        assertEquals("qa control code outside src/qa", emptyList<String>(), leaks)
        val source = SourceScan.appFile("src/qa/java/${receiverClass.replace('.', '/')}.kt").readText()
        // The receiver re-checks the action itself (an explicit broadcast skips the filter), so its
        // constant must be the manifest's action, not a drifted copy.
        assertTrue("receiver does not check $action", source.contains("ACTION_CMD = \"$action\""))
    }
}
