package com.astraedus.nudge.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the manifest wiring every widget needs to exist at all.
 *
 * A widget that is missing a declaration does not fail loudly — it simply never appears in the
 * launcher's picker, or appears and renders the framework's generic error box. There is no
 * exception, no log line, and no JVM test that can exercise it, because the failure is entirely in
 * XML the app never reads itself. So the XML gets read here.
 *
 * The three things that silently break a widget, all asserted below:
 *  - a receiver without `exported="true"` (the launcher is a different process and cannot reach it),
 *  - a receiver without the `APPWIDGET_UPDATE` intent filter (nothing ever asks it to draw),
 *  - a `provider` meta-data pointing at an `@xml/…` file that is not there.
 */
class WidgetManifestContractTest {

    private fun projectFile(relativePath: String): File =
        listOf(File("app/$relativePath"), File(relativePath))
            .firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}")

    private val manifestText: String by lazy {
        projectFile("src/main/AndroidManifest.xml").readText()
    }

    /** Each `<receiver …>…</receiver>` block whose android:name points into `ui.widget`. */
    private fun widgetReceiverBlocks(): Map<String, String> =
        Regex("""<receiver\b[\s\S]*?</receiver>""")
            .findAll(manifestText)
            .mapNotNull { match ->
                val block = match.value
                val name = Regex("""android:name="([^"]+)"""").find(block)
                    ?.groupValues?.get(1)
                    ?: return@mapNotNull null
                if (!name.contains(".ui.widget.")) null else name to block
            }
            .toMap()

    /** The Kotlin classes that actually extend `GlanceAppWidgetReceiver`. */
    private fun declaredReceiverClasses(): List<String> {
        val dir = projectFile("src/main/java/com/astraedus/nudge/ui/widget")
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                Regex("""class (\w+) : GlanceAppWidgetReceiver\(\)""")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
            }
            .toList()
            .sorted()
    }

    /**
     * The set of receiver classes and the set of manifest declarations must be the SAME set.
     *
     * Both directions matter. A class with no declaration is a widget the user can never place; a
     * declaration with no class is a receiver the launcher will try to instantiate and fail on.
     * Discovering the classes from source rather than listing them here means a fourth widget added
     * later cannot ship half-wired.
     */
    @Test
    fun `every Glance receiver class is declared in the manifest, and vice versa`() {
        val classes = declaredReceiverClasses()
        assertTrue(
            "No GlanceAppWidgetReceiver subclasses found — this test would otherwise pass vacuously",
            classes.isNotEmpty()
        )
        val declared = widgetReceiverBlocks().keys
            .map { it.substringAfterLast('.') }
            .sorted()

        assertEquals(
            "Every GlanceAppWidgetReceiver must have a <receiver> in AndroidManifest.xml and every " +
                "declared ui.widget receiver must be a real class. A mismatch is a widget that " +
                "either never appears in the picker or crashes when the launcher builds it.",
            classes,
            declared
        )
    }

    @Test
    fun `every widget receiver is exported and listens for APPWIDGET_UPDATE`() {
        val blocks = widgetReceiverBlocks()
        assertTrue("No ui.widget receivers declared", blocks.isNotEmpty())

        blocks.forEach { (name, block) ->
            assertTrue(
                "$name must set android:exported=\"true\". The launcher runs in another process " +
                    "and cannot broadcast to a non-exported receiver, so the widget simply never " +
                    "updates — with no error anywhere.",
                block.contains("""android:exported="true"""")
            )
            assertTrue(
                "$name must declare an APPWIDGET_UPDATE intent-filter, or nothing ever asks it to " +
                    "draw",
                block.contains("""<action android:name="android.appwidget.action.APPWIDGET_UPDATE" />""")
            )
            assertTrue(
                "$name must carry an android.appwidget.provider meta-data naming its info XML",
                block.contains("""android:name="android.appwidget.provider"""")
            )
            assertTrue(
                "$name must have a label, which is what the user reads in the widget picker",
                block.contains("android:label=\"@string/")
            )
        }
    }

    /** A meta-data resource that does not exist makes the widget un-placeable. */
    @Test
    fun `every referenced widget info XML exists and is a valid provider`() {
        val referenced = widgetReceiverBlocks().values.mapNotNull { block ->
            Regex("""android:name="android\.appwidget\.provider"\s+android:resource="@xml/(\w+)"""")
                .find(block)?.groupValues?.get(1)
        }
        assertEquals(
            "Every widget receiver must reference an @xml provider resource",
            widgetReceiverBlocks().size,
            referenced.size
        )

        referenced.forEach { resource ->
            val file = projectFile("src/main/res/xml/$resource.xml")
            val text = file.readText()
            assertTrue(
                "res/xml/$resource.xml must be an <appwidget-provider>",
                text.contains("<appwidget-provider")
            )
            assertTrue(
                "res/xml/$resource.xml must declare an initialLayout — without one the launcher " +
                    "refuses to bind the widget at all",
                text.contains("android:initialLayout=")
            )
            assertTrue(
                "res/xml/$resource.xml must set widgetCategory=\"home_screen\"",
                text.contains("""android:widgetCategory="home_screen"""")
            )
            val layout = Regex("""android:initialLayout="@layout/(\w+)"""")
                .find(text)?.groupValues?.get(1)
                ?: error("res/xml/$resource.xml has no parseable initialLayout")
            assertTrue(
                "res/layout/$layout.xml is referenced by res/xml/$resource.xml but does not exist",
                projectFile("src/main/res/layout/$layout.xml").exists()
            )
        }
    }

    /**
     * `singleTop` on MainActivity is load-bearing for every widget tap.
     *
     * Without it, tapping a widget while the app is already open starts a SECOND MainActivity on top
     * of the first, so `onNewIntent` never runs, the deep link is handled by a fresh instance, and
     * pressing back lands the user on a stale copy of their own dashboard.
     */
    @Test
    fun `MainActivity is singleTop so a widget tap reaches the live instance`() {
        val activity = Regex("""<activity\b[\s\S]*?</activity>""")
            .findAll(manifestText)
            .first { it.value.contains("""android:name=".MainActivity"""") }
            .value

        assertTrue(
            "MainActivity must declare android:launchMode=\"singleTop\" so a widget deep link " +
                "arrives at onNewIntent on the running instance instead of stacking a second copy",
            activity.contains("""android:launchMode="singleTop"""")
        )
    }

    /**
     * Widget strings are user-visible, and they are read in a process that is not ours, where a
     * missing resource is an exception rather than a blank. Every `@string` the widget XML names
     * must actually be in `strings.xml`.
     */
    @Test
    fun `every string the widget XML references exists`() {
        val stringsText = projectFile("src/main/res/values/strings.xml").readText()
        val defined = Regex("""<string name="(\w+)"""")
            .findAll(stringsText)
            .map { it.groupValues[1] }
            .toSet()

        val referencingFiles = buildList {
            add(projectFile("src/main/AndroidManifest.xml"))
            addAll(projectFile("src/main/res/xml").listFiles().orEmpty().filter {
                it.name.endsWith("_widget_info.xml")
            })
            addAll(projectFile("src/main/res/layout").listFiles().orEmpty().filter {
                it.name.startsWith("widget_")
            })
        }

        val missing = referencingFiles.flatMap { file ->
            Regex("""@string/(\w+)""").findAll(file.readText())
                .map { it.groupValues[1] }
                .filterNot { it in defined }
                .map { "${file.name}: @string/$it" }
        }
        assertEquals("Referenced strings that do not exist: $missing", emptyList<String>(), missing)
    }
}
