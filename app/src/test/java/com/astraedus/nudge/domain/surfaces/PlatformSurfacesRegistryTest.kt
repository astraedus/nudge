package com.astraedus.nudge.domain.surfaces

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlatformSurfacesRegistry]'s invariants, asserted over the DISCOVERED set of adapters rather than
 * over a hand-written list.
 *
 * That distinction is rule (b): a test enumerating "Instagram" by hand claims nothing about a second
 * adapter somebody adds later, and the half-registered adapter is exactly the bug this registry's
 * derive-everything-from-one-list shape exists to prevent. Every property test below iterates
 * [PlatformSurfacesRegistry.adapters], so a YouTube adapter is held to the same bar the day it lands.
 */
class PlatformSurfacesRegistryTest {

    @Test
    fun `instagram resolves`() {
        assertSame(InstagramSurfaces, PlatformSurfacesRegistry.forPackage("com.instagram.android"))
    }

    @Test
    fun `an unknown package resolves to null`() {
        assertNull(PlatformSurfacesRegistry.forPackage("com.example.unknown"))
    }

    /** Host apps Nudge blocks but has no surface adapter for must resolve to null, not to Instagram. */
    @Test
    fun `other supported in-app-blocking packages have no adapter yet`() {
        listOf(
            "com.google.android.youtube",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        ).forEach { pkg ->
            assertNull("$pkg must not silently resolve to another app's adapter", PlatformSurfacesRegistry.forPackage(pkg))
        }
    }

    @Test
    fun `an empty package name resolves to null`() {
        assertNull(PlatformSurfacesRegistry.forPackage(""))
    }

    /** Derived from the registration list, never retyped. */
    @Test
    fun `supported packages is derived from the registered adapters`() {
        assertEquals(
            PlatformSurfacesRegistry.adapters.map { it.packageName }.toSet(),
            PlatformSurfacesRegistry.supportedPackages
        )
    }

    @Test
    fun `the registry is not empty`() {
        assertTrue(
            "an empty registry makes every property test below pass vacuously",
            PlatformSurfacesRegistry.adapters.isNotEmpty()
        )
    }

    /**
     * **Every registered adapter has a non-empty `vanishableTabs`.** Derived, not hand-listed.
     *
     * An adapter with no vanishable tab is a registration that does nothing: the tab-vanish feature
     * would silently no-op for that app while the rule editor happily offered the toggle.
     */
    @Test
    fun `every registered adapter declares at least one vanishable tab`() {
        PlatformSurfacesRegistry.adapters.forEach { adapter ->
            assertTrue(
                "${adapter.packageName} registers no vanishable tab — the feature would silently " +
                    "no-op for it",
                adapter.vanishableTabs.isNotEmpty()
            )
        }
    }

    /** A locator that can never match is the same silent no-op one level down. */
    @Test
    fun `every vanishable tab locator can actually match something`() {
        PlatformSurfacesRegistry.adapters.forEach { adapter ->
            adapter.vanishableTabs.forEach { (feature, locator) ->
                assertFalse(
                    "${adapter.packageName} maps '$feature' to an empty locator, which matches no node",
                    locator.isEmpty
                )
                assertTrue(
                    "${adapter.packageName}/'$feature' must be keyed on a view id, not on text alone " +
                        "— text is the version-fragile fallback",
                    locator.viewIds.isNotEmpty()
                )
            }
        }
    }

    /** Every adapter's ids must belong to its own package; a cross-wired id would target another app. */
    @Test
    fun `every locator's view ids belong to the adapter's own package`() {
        PlatformSurfacesRegistry.adapters.forEach { adapter ->
            val prefix = "${adapter.packageName}:id/"
            val locators = adapter.vanishableTabs.values + listOfNotNull(
                adapter.followingSteer?.entryPoint,
                adapter.followingSteer?.menuItem,
                adapter.followingSteer?.menuItemLabel,
                adapter.titleLocator
            )
            locators.flatMap { it.viewIds }.forEach { id ->
                assertTrue(
                    "${adapter.packageName} declares a locator for '$id', which belongs to another app",
                    id.startsWith(prefix)
                )
            }
        }
    }

    /** A steer recipe with an unmatchable step could never complete; better to declare none. */
    @Test
    fun `every declared steer recipe has three usable steps`() {
        PlatformSurfacesRegistry.adapters.mapNotNull { it.followingSteer }.forEach { recipe ->
            assertFalse("the steer entry point must be findable", recipe.entryPoint.isEmpty)
            assertFalse("the steer menu row must be findable", recipe.menuItem.isEmpty)
            assertFalse("the steer menu label must be findable", recipe.menuItemLabel.isEmpty)
        }
    }

    @Test
    fun `no two adapters claim the same package`() {
        val packages = PlatformSurfacesRegistry.adapters.map { it.packageName }
        assertEquals("two adapters registered for one package; one would shadow the other", packages.distinct(), packages)
    }

    /** Both themes must give an opaque colour, or the cover would not hide the tab it covers. */
    @Test
    fun `every adapter paints an opaque cover in both themes`() {
        PlatformSurfacesRegistry.adapters.forEach { adapter ->
            listOf(true, false).forEach { night ->
                val alpha = (adapter.navBarColor(night) ushr 24) and 0xFF
                assertEquals(
                    "${adapter.packageName} nightMode=$night is not fully opaque; the tab would show through",
                    0xFF,
                    alpha
                )
            }
        }
    }

    @Test
    fun `instagram's measured nav bar colours are white in light mode and black in dark`() {
        assertEquals(0xFFFFFFFFu.toInt(), InstagramSurfaces.navBarColor(nightMode = false))
        assertEquals(0xFF000000u.toInt(), InstagramSurfaces.navBarColor(nightMode = true))
    }

    /**
     * The tab-vanish keys must be real `InAppDetector.Feature` keys.
     *
     * The map is keyed by string because `domain` may not import `service` (the dependency direction
     * in `CLAUDE.md`), which means nothing at compile time stops a typo like `"REEL"` — and a typo
     * would make tab vanish silently never fire. Read from the production enum, per rule (b), rather
     * than compared against a retyped literal.
     */
    @Test
    fun `every vanishable tab key is a real in-app feature key`() {
        val featureKeys = com.astraedus.nudge.service.InAppDetector.Feature.entries.map { it.key }.toSet()
        PlatformSurfacesRegistry.adapters.forEach { adapter ->
            adapter.vanishableTabs.keys.forEach { key ->
                assertTrue(
                    "'$key' is not an InAppDetector.Feature key ($featureKeys) — tab vanish would " +
                        "never match a blocked feature",
                    key in featureKeys
                )
            }
        }
    }

    /**
     * An adapter that declares a steer must also declare a title locator.
     *
     * Without one, nothing can positively identify the steer's DESTINATION, so the once-per-arrival
     * memory is never marked from it and a user who backs out of that feed gets steered again. The two
     * belong together, and a future adapter shipping one without the other fails here.
     */
    @Test
    fun `every adapter that declares a steer also declares where its title lives`() {
        PlatformSurfacesRegistry.adapters.filter { it.followingSteer != null }.forEach { adapter ->
            val title = adapter.titleLocator
            assertNotNull(
                "${adapter.packageName} declares a steer but no titleLocator, so its destination " +
                    "screen can never be recognised and the steer would repeat",
                title
            )
            assertTrue(
                "${adapter.packageName}'s titleLocator must be keyed on a view id",
                title!!.viewIds.isNotEmpty()
            )
        }
    }

    /**
     * A title locator matched on TEXT would find the dropdown row named "Following" on the Home feed,
     * and the classifier would then read an open menu as the destination screen. Ids only.
     */
    @Test
    fun `no title locator matches on text or content description`() {
        PlatformSurfacesRegistry.adapters.mapNotNull { it.titleLocator }.forEach { title ->
            assertEquals(
                "a title locator matching on text would confuse a menu row with the screen it opens",
                emptyList<String>(),
                title.texts + title.contentDescriptions
            )
        }
    }

    /** Instagram's one registered tab is Reels, and it resolves to a locator on `clips_tab`. */
    @Test
    fun `instagram registers the reels tab on clips_tab`() {
        val locator = InstagramSurfaces.vanishableTabs["REELS"]
        assertNotNull("Instagram must register a REELS tab", locator)
        assertTrue(InstagramSurfaces.ID_CLIPS_TAB in locator!!.viewIds)
    }
}
