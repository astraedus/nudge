package com.astraedus.nudge.domain.surfaces

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam gate: Instagram's view ids live in [InstagramSurfaces] and nowhere else.
 *
 * `domain/surfaces/` exists so that an Instagram release which renames a view is a one-file data edit
 * instead of an archaeology session across `service/`. That property is not enforced by anything in
 * the type system — a `"com.instagram.android:id/clips_tab"` string literal compiles perfectly well
 * anywhere — so it is enforced here, by reading the source.
 *
 * Per rule (e) in `docs/testing-strategy.md`, this is the surviving kind of source-level assertion: it
 * checks a **count over a discovered set** (which files contain the prefix), not the presence of a
 * particular spelling. A third file carrying Instagram ids fails it, including one nobody has written
 * yet; a faithful refactor inside either allowed file does not.
 *
 * ## The one legacy holder
 *
 * **TODO: `service/InAppDetector.kt` is the one legacy holder of Instagram view ids.** It predates
 * this seam and still carries the reel-player container ids (`clips_viewer_view_pager`,
 * `clips_video_container`, `clips_media_component`) plus the `tab_bar` tab-id lookup it uses for
 * feature detection. Moving them is a behavioural change to in-app feature detection, which is a
 * different lane and a different risk; it is deliberately not part of this feature. When it is done,
 * delete it from [allowedFiles] and this gate tightens to one file with no other edit.
 */
class InstagramIdsLiveInOneFileTest {

    /** The id prefix that must not spread. */
    private val prefix = "com.instagram.android:id/"

    /**
     * The only two files allowed to contain [prefix]: the seam, and the grandfathered legacy holder.
     * Any third file is a failure.
     */
    private val allowedFiles = setOf(
        "InstagramSurfaces.kt",
        "InAppDetector.kt"
    )

    private fun mainSourceRoot(): File = listOf(
        File("src/main/java"),
        File("app/src/main/java")
    ).firstOrNull { it.isDirectory }
        ?: error("main source root not found from ${File("").absolutePath}")

    /**
     * Comments are stripped before grepping (repo convention, `docs/TESTING.md`): a KDoc block that
     * quotes the ids it explains would otherwise be read as code, and both of the allowed files have
     * exactly that shape.
     */
    private fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { line -> line.substringBefore("//") }

    private fun filesCarryingInstagramIds(): Set<String> = mainSourceRoot()
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { stripComments(it.readText()).contains(prefix) }
        .map { it.name }
        .toSet()

    @Test
    fun `only the instagram adapter and the one legacy holder carry instagram view ids`() {
        assertEquals(
            "Instagram view ids have spread outside domain/surfaces/InstagramSurfaces.kt. That file " +
                "is the seam: every com.instagram.android:id/ string belongs there, so a host-app " +
                "rename is one data edit. service/InAppDetector.kt is the ONE grandfathered " +
                "exception. If you added a third file, move the ids into InstagramSurfaces and " +
                "reference the constant instead.",
            allowedFiles,
            filesCarryingInstagramIds()
        )
    }

    /**
     * The gate must be looking at something. A prefix that appeared in zero files would make the
     * assertion above pass while proving nothing — the standard way a source-grep test rots into a
     * green tick.
     */
    @Test
    fun `the gate is actually finding the ids it polices`() {
        val found = filesCarryingInstagramIds()
        assertTrue(
            "no source file contains '$prefix' — this gate would pass vacuously",
            found.isNotEmpty()
        )
        assertTrue(
            "InstagramSurfaces.kt must be one of the files carrying the ids, or the seam is empty",
            "InstagramSurfaces.kt" in found
        )
    }

    /**
     * No file in `domain/` other than the adapter may carry the ids.
     *
     * Asserted separately from the count above because it is the invariant with teeth for THIS
     * feature's own lane: the decision classes next to the adapter (`TabCoverPresence`,
     * `TabCoverDecider`, `FollowingSteer`) must stay host-app-agnostic, and an id literal creeping
     * into one of them is the specific regression that would make the seam pointless.
     */
    @Test
    fun `no domain file except the adapter carries instagram view ids`() {
        val offenders = File(mainSourceRoot(), "com/astraedus/nudge/domain")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "InstagramSurfaces.kt" }
            .filter { stripComments(it.readText()).contains(prefix) }
            .map { it.name }
            .toList()
        assertEquals(
            "these domain files carry Instagram ids and must not: $offenders. The generic decision " +
                "classes stay host-app-agnostic; the selectors live in the adapter.",
            emptyList<String>(),
            offenders
        )
    }

    /**
     * The adapter must not hold selectors for an app it does not describe.
     *
     * The YouTube roadmap line is documentation; a YouTube id appearing in [InstagramSurfaces] would
     * mean somebody started the second adapter inside the first one, which is the shape that made the
     * ids spread in the first place.
     */
    @Test
    fun `the instagram adapter carries no other app's view ids`() {
        val source = stripComments(
            File(mainSourceRoot(), "com/astraedus/nudge/domain/surfaces/InstagramSurfaces.kt").readText()
        )
        listOf(
            "com.google.android.youtube:id/",
            "com.zhiliaoapp.musically:id/",
            "com.ss.android.ugc.trill:id/"
        ).forEach { foreign ->
            assertTrue(
                "InstagramSurfaces.kt carries '$foreign'. A second host app gets its own " +
                    "PlatformSurfaces implementation and its own registry entry.",
                !source.contains(foreign)
            )
        }
    }
}
