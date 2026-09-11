package com.astraedus.nudge.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for [WidgetDeepLink]: the one translation between a widget tap's intent extra
 * and the NavGraph route it may open.
 *
 * The security-relevant half of this contract is that a package string cannot forge a route by
 * smuggling `/` (or anything else outside a legal package name) through `app_detail/<pkg>` — see
 * the "forged route" section below.
 */
class WidgetDeepLinkTest {

    // ------------------------------------------------------------ fixed routes

    @Test
    fun `every fixed route round-trips through routeFor`() {
        assertEquals(WidgetDeepLink.ROUTE_HOME, WidgetDeepLink.routeFor(WidgetDeepLink.ROUTE_HOME))
        assertEquals(WidgetDeepLink.ROUTE_STATS, WidgetDeepLink.routeFor(WidgetDeepLink.ROUTE_STATS))
        assertEquals(
            WidgetDeepLink.ROUTE_WILLPOWER,
            WidgetDeepLink.routeFor(WidgetDeepLink.ROUTE_WILLPOWER)
        )
        assertEquals(
            WidgetDeepLink.ROUTE_INTERVENTIONS,
            WidgetDeepLink.routeFor(WidgetDeepLink.ROUTE_INTERVENTIONS)
        )
        assertEquals(
            WidgetDeepLink.ROUTE_SETTINGS,
            WidgetDeepLink.routeFor(WidgetDeepLink.ROUTE_SETTINGS)
        )
    }

    // ------------------------------------------------------------ null / blank / unknown

    @Test
    fun `null, empty, blank and unknown extras all return null rather than a fallback route`() {
        assertNull(WidgetDeepLink.routeFor(null))
        assertNull(WidgetDeepLink.routeFor(""))
        assertNull(WidgetDeepLink.routeFor("   "))
        assertNull("an unrecognised word must not silently become Home", WidgetDeepLink.routeFor("frobnicate"))
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        assertEquals(WidgetDeepLink.ROUTE_HOME, WidgetDeepLink.routeFor("  home  "))
        assertEquals(WidgetDeepLink.ROUTE_SETTINGS, WidgetDeepLink.routeFor("\tsettings\n"))
        val detail = WidgetDeepLink.appDetailRoute("com.instagram.android")
        assertEquals(detail, WidgetDeepLink.routeFor("  $detail  "))
    }

    // ------------------------------------------------------------ app_detail round trip

    @Test
    fun `appDetailRoute builds the prefixed route and it reads back unmangled`() {
        val route = WidgetDeepLink.appDetailRoute("com.instagram.android")
        assertEquals("app_detail/com.instagram.android", route)
        assertEquals(route, WidgetDeepLink.routeFor(route))
    }

    // ------------------------------------------------------------ security: forged routes

    @Test
    fun `a package containing a slash cannot forge a different route via routeFor`() {
        assertNull(WidgetDeepLink.routeFor("app_detail/com.x/../settings"))
        assertNull(WidgetDeepLink.routeFor("app_detail/a/b"))
    }

    @Test
    fun `a package containing a slash is refused at construction time too`() {
        assertNull(WidgetDeepLink.appDetailRoute("com.x/../settings"))
    }

    // ------------------------------------------------------------ rejected package shapes

    @Test
    fun `rejected package shapes return null from both appDetailRoute and routeFor`() {
        val badPackages = listOf(
            "" to "empty",
            ".foo" to "leading dot",
            "foo." to "trailing dot",
            ".." to "just two dots",
            "com foo" to "a space",
            "com.%2Fexample" to "a literal percent-encoded slash",
            "com.exämple" to "a non-ASCII character"
        )

        for ((pkg, description) in badPackages) {
            assertNull(
                "appDetailRoute must refuse a $description package: '$pkg'",
                WidgetDeepLink.appDetailRoute(pkg)
            )
            assertNull(
                "routeFor must refuse a $description package: '$pkg'",
                WidgetDeepLink.routeFor(WidgetDeepLink.APP_DETAIL_PREFIX + pkg)
            )
        }
    }

    @Test
    fun `the bare app_detail prefix with no package is null`() {
        assertNull(WidgetDeepLink.routeFor(WidgetDeepLink.APP_DETAIL_PREFIX))
        assertNull(WidgetDeepLink.routeFor("app_detail/"))
    }

    // ------------------------------------------------------------ the wire constant

    @Test
    fun `EXTRA_ROUTE is the pinned literal nudge dot nav_route`() {
        // This is an over-the-wire intent-extra key baked into every widget PendingIntent already
        // sitting on real launchers. Renaming the constant without also changing this literal
        // string would silently break every already-pinned widget on next tap.
        assertEquals("nudge.nav_route", WidgetDeepLink.EXTRA_ROUTE)
    }
}
