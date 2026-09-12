package com.astraedus.nudge.ui.widget

/**
 * The ONE translation between "which widget did the user tap" and "which NavGraph route does the
 * app open on".
 *
 * Pure Kotlin, no `android.*`, so every route it can produce is exercised on the JVM. That matters
 * because the failure mode here is silent: a route string that does not exist in the graph throws
 * inside `navController.navigate`, on a code path only reachable by tapping a widget on a real
 * launcher — the least observable surface this app has.
 *
 * `MainActivity` already carried a bespoke `EXTRA_OPEN_SETTINGS` boolean for the protection alert.
 * That extra is kept (its `PendingIntent` lives inside notifications already posted on real phones,
 * and must keep working) but it is TRANSLATED into [ROUTE_SETTINGS] at read time rather than being
 * a second, parallel deep-link mechanism. Two mechanisms answering one question is the defect class
 * this repo keeps re-learning; one of the two always rots.
 */
object WidgetDeepLink {

    /**
     * Intent extra carrying the route a widget tap wants.
     *
     * Glance turns each `ActionParameters.Key`'s NAME into an intent extra key verbatim
     * (`ApplyAction` -> `bundleOf` -> `Intent.putExtras`), so the parameter key and the extra key
     * are the same string by construction rather than by two constants agreeing.
     */
    const val EXTRA_ROUTE = "nudge.nav_route"

    const val ROUTE_HOME = "home"
    const val ROUTE_STATS = "stats"
    const val ROUTE_WILLPOWER = "willpower"
    const val ROUTE_INTERVENTIONS = "interventions"
    const val ROUTE_SETTINGS = "settings"

    /** Prefix of the one parameterised route. Must match `Screen.AppDetail.createRoute`. */
    const val APP_DETAIL_PREFIX = "app_detail/"

    private val FIXED_ROUTES = setOf(
        ROUTE_HOME,
        ROUTE_STATS,
        ROUTE_WILLPOWER,
        ROUTE_INTERVENTIONS,
        ROUTE_SETTINGS
    )

    /**
     * `app_detail/<pkg>` for [packageName], or null when it is not a package we are willing to
     * route to. Built here rather than string-concatenated at each call site so the widget can
     * never emit a route [routeFor] would refuse to read back.
     */
    fun appDetailRoute(packageName: String?): String? =
        if (isRoutablePackage(packageName)) APP_DETAIL_PREFIX + packageName else null

    /**
     * The NavGraph route for a widget's extra, or **null** for anything unrecognised.
     *
     * Null is the important answer. An unknown string must not reach `navigate()` — that throws —
     * and it must not be silently coerced to Home either: a widget that opens the wrong screen is
     * a bug report, while an app that simply opens at its start destination is the platform's own
     * default. This function only says whether it knows the route; the caller decides.
     *
     * A package containing `/` cannot forge a different route: [isRoutablePackage] rejects every
     * character that is not legal in an Android package name, so `app_detail/x/../settings` is not
     * something this function will ever hand back.
     */
    fun routeFor(extra: String?): String? {
        val raw = extra?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw in FIXED_ROUTES) return raw
        if (!raw.startsWith(APP_DETAIL_PREFIX)) return null
        val pkg = raw.removePrefix(APP_DETAIL_PREFIX)
        return if (isRoutablePackage(pkg)) raw else null
    }

    /**
     * Android package names are dot-separated segments of ASCII letters, digits and `_`. Anything
     * else — a slash, a space, an encoded `%2F`, an empty segment — is not a package, so it is not
     * something we will put in a route.
     *
     * Deliberately stricter than the platform's own parser. The cost of rejecting an exotic but
     * legal name is that one leaderboard row opens the app at Home instead of a detail screen; the
     * cost of accepting a crafted one is navigating somewhere nobody asked to go.
     */
    private fun isRoutablePackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        if (pkg.startsWith('.') || pkg.endsWith('.') || pkg.contains("..")) return false
        return pkg.all { ch ->
            (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '_' || ch == '.'
        }
    }
}
