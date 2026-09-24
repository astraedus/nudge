package com.astraedus.nudge.domain.surfaces

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy gate over the committed surface fixtures, and the sibling of
 * [com.astraedus.nudge.domain.events.A11yCapturePrivacyTest].
 *
 * Nudge has no `INTERNET` permission, no telemetry, and `allowBackup="false"`. Its device fixtures
 * are the one thing in this project that writes a real person's activity to a file — and those files
 * are **committed to a PUBLIC repository**.
 *
 * A raw view-hierarchy dump of Instagram is a transcript of somebody's feed: usernames, captions,
 * story labels, ad copy, like counts, all sitting in `text` and `content-desc`. The dumps these
 * fixtures came from contained exactly that. `scripts/scrub-surface-fixture.py` blanks every such
 * value except a closed allowlist of Instagram's own chrome labels, and this test is what stops a
 * future un-scrubbed dump being committed quietly — because "just add the raw dump, we'll scrub it
 * later" is a reasonable instinct at 2am and the leak would be invisible until it shipped.
 *
 * It checks the **data**, not the script, because the data is what gets published.
 */
class SurfaceFixturePrivacyTest {

    /**
     * The ONLY strings allowed to survive scrubbing: the five bottom-nav tab content-descriptions,
     * the Home-feed logo's description, and the two title-dropdown labels. Every one is Instagram's
     * own UI chrome and every one is read by production code in [InstagramSurfaces].
     *
     * An allowlist, not a denylist, on purpose: a denylist has to anticipate what a caption looks
     * like and is wrong the first time a username is "Following".
     */
    private val allowlist = setOf(
        "Following",
        "Favorites",
        "Instagram Home Feed",
        "Home",
        "Reels",
        "Message",
        "Search and explore",
        "Profile"
    )

    @Test
    fun `no committed fixture contains any text or content description outside the allowlist`() {
        val fixtures = SurfaceFixture.names()
        assertTrue("no surface fixtures found — the suite would pass vacuously", fixtures.isNotEmpty())

        fixtures.forEach { name ->
            SurfaceFixture.load(name).forEach { node ->
                listOfNotNull(
                    node.text?.let { "text" to it },
                    node.contentDescription?.let { "content-desc" to it }
                ).forEach { (field, value) ->
                    assertTrue(
                        "fixture '$name' has a $field outside the allowlist: '$value'. Fixtures are " +
                            "PUBLISHED — this is probably a real person's feed content. Re-scrub with " +
                            "scripts/scrub-surface-fixture.py before committing.",
                        value in allowlist
                    )
                }
            }
        }
    }

    /**
     * The scrubber's allowlist and this test's allowlist must be the same set.
     *
     * Rule (b), fixture honesty: two copies of a list drift, and the drift is silent in the direction
     * that matters — a string added to the scrubber but not here would pass the gate above without
     * anyone deciding it was safe. Read from the script rather than retyped.
     */
    @Test
    fun `the scrubber allowlist is exactly this test's allowlist`() {
        val script = listOf(
            File("../scripts/scrub-surface-fixture.py"),
            File("scripts/scrub-surface-fixture.py")
        ).firstOrNull { it.exists() }
            ?: error("scrub-surface-fixture.py not found from ${File("").absolutePath}")

        val body = script.readText()
            .substringAfter("ALLOWLIST = frozenset(")
            .substringBefore(")")
        val inScript = Regex(""""([^"]+)"""").findAll(body).map { it.groupValues[1] }.toSet()

        assertEquals(
            "the scrubber's allowlist and this gate's allowlist have drifted",
            allowlist,
            inScript
        )
    }

    /**
     * No allowlist entry is dead weight.
     *
     * The gate's whole strength is that the allowlist is small, and the way it rots is somebody
     * widening it until a stubborn fixture passes. Requiring every entry to appear in at least one
     * committed fixture means an exemption cannot be added speculatively: it has to be a string the
     * recorded device actually produced.
     *
     * Derived from the fixtures rather than hand-listed, per rule (b) — a hand-written expectation
     * here would just be this test agreeing with its author.
     */
    @Test
    fun `every allowlisted string actually appears in a committed fixture`() {
        val present = SurfaceFixture.names()
            .flatMap { SurfaceFixture.load(it) }
            .flatMap { listOfNotNull(it.text, it.contentDescription) }
            .toSet()

        allowlist.forEach { entry ->
            assertTrue(
                "allowlisted string '$entry' appears in no committed fixture — a speculative " +
                    "exemption weakens the gate for every future dump. Remove it, or commit the " +
                    "fixture that needs it.",
                entry in present
            )
        }
    }

    /** The three labels production reasons about must be allowlisted, or scrubbing breaks the feature. */
    @Test
    fun `the labels production reads are allowlisted`() {
        listOf(
            InstagramSurfaces.LABEL_FOLLOWING,
            InstagramSurfaces.DESC_HOME_FEED,
            InstagramSurfaces.DESC_REELS_TAB
        ).forEach { label ->
            assertTrue(
                "production reads '$label' but the scrubber would blank it, so no fixture could " +
                    "ever exercise the code path that reads it",
                label in allowlist
            )
        }
    }
}
