package com.astraedus.nudge.domain.lock

/**
 * Pure-Kotlin decision logic for Strict Mode "OS escape route" guarding (Phase 2).
 *
 * Phase 1 gates Nudge's own UI behind the typed unlock challenge. The obvious bypass that
 * remained is leaving the app entirely:
 *   1. Settings → Accessibility → Nudge → turn the service toggle OFF, or
 *   2. App Info / App details for Nudge → Force stop / Uninstall.
 *
 * While Strict Mode is on, when the user lands on one of those screens we intercept with the
 * SAME unlock challenge. This object decides *whether* to guard; it has no Android imports and
 * is fully unit-testable.
 *
 * ## Real-device signatures this matcher was tuned to
 * Captured via `astra-adb dump` on a Pixel 3 (Android 12, AOSP/Pixel Settings) on 2026-06-24,
 * app label "Nudge - App Blocker", package `dev.astraedus.nudge`:
 *
 * - **Accessibility detail page** — window `com.android.settings/.SubSettings`. Node text includes
 *   the toggle label "Use Nudge - App Blocker", "Nudge - App Blocker shortcut", and the service
 *   description. Discriminator: the app label co-occurs with the word "shortcut" OR the "Use <label>"
 *   toggle row. The Accessibility LIST page ALSO shows "Nudge - App Blocker" (and an "Accessibility"
 *   header), so we deliberately do NOT key on the bare word "accessibility" — that would trap the
 *   user on the list of all services. "shortcut" / "use <label>" are present only on the detail page.
 * - **App Info page** — window `com.android.settings/.applications.InstalledAppDetails`. Node text
 *   includes the app label "Nudge - App Blocker" plus action buttons "Force stop" and "Uninstall".
 *   Discriminator: app label co-occurs with "Force stop" or "Uninstall".
 *
 * ## Safety bias
 * This matcher is intentionally biased toward FEWER false positives — it is better to occasionally
 * miss a guard than to trap the user on an unrelated Settings page. Every positive requires BOTH a
 * settings package AND the app label AND a strong escape-route signal. Strict Mode OFF or an active
 * grace window short-circuits to "do not guard" immediately.
 */
object StrictModeEscapeGuard {

    /**
     * Settings packages we tolerate as the host of a protected screen. AOSP/Pixel first
     * (`com.android.settings`), plus the most common OEM settings packages so the guard still
     * bites on Samsung/Xiaomi/etc. Adding a package here only *enables* matching; the text
     * signals below still have to be present, so a broad list does not by itself cause false
     * positives.
     */
    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",            // AOSP / Pixel
        "com.samsung.android.settings",    // some Samsung builds
        "com.miui.securitycenter",         // Xiaomi MIUI app-ops / app details
        "com.coloros.safecenter",          // Oppo/Realme ColorOS
        "com.oplus.settings",              // newer Oppo/OnePlus OxygenOS/ColorOS
        "com.oneplus.settings",            // OnePlus
        "com.vivo.settings",               // Vivo
        "com.transsion.settings"           // Tecno/Infinix
    )

    /**
     * Keyword that, alongside the app label, marks the Accessibility detail page for our service:
     * the toggle row is "<label> shortcut" / "Use <label>". We deliberately avoid the bare word
     * "accessibility" because it appears as a header on the Accessibility LIST page (which also
     * lists our label) and would cause a false positive there. Lowercased; matched case-insensitively.
     */
    private const val ACCESSIBILITY_DETAIL_SIGNAL = "shortcut"

    /**
     * Keywords that, when present ALONGSIDE the app label, indicate the App Info / App details page
     * (Force stop / Uninstall). Lowercased; matched case-insensitively.
     */
    private val APP_INFO_SIGNALS = listOf("force stop", "uninstall")

    /**
     * Decide whether to launch the unlock challenge over the current foreground screen.
     *
     * @param foregroundPkg the package owning the foreground window.
     * @param windowText concatenated, user-visible text harvested from the foreground window's
     *   node tree (text + content descriptions). Case-insensitive matching is applied internally.
     * @param appLabel our own app's user-visible label (read at runtime from PackageManager so we
     *   never hardcode "Nudge - App Blocker"); e.g. "Nudge - App Blocker".
     * @param strictEnabled whether Strict Mode is currently on. OFF → never guard.
     * @param withinGrace whether we are inside a post-unlock grace window. In grace → never guard
     *   (so a user who just solved the challenge can actually complete the disable they committed to).
     * @return true if the protected-screen challenge overlay should be shown.
     */
    fun shouldGuardSettingsScreen(
        foregroundPkg: String,
        windowText: String,
        appLabel: String,
        strictEnabled: Boolean,
        withinGrace: Boolean
    ): Boolean {
        // Cheap short-circuits first — these dominate the common case and keep us off the hot path.
        if (!strictEnabled) return false
        if (withinGrace) return false
        if (foregroundPkg !in SETTINGS_PACKAGES) return false

        val haystack = windowText.lowercase()
        val needleLabel = appLabel.trim().lowercase()
        // Without a non-blank label we cannot anchor the match → fail closed (do not guard).
        if (needleLabel.isEmpty()) return false
        if (!haystack.contains(needleLabel)) return false

        // Accessibility detail: "<label> shortcut" or the "Use <label>" toggle row — both present
        // only on the detail page, not the list of all services.
        val isAccessibilityDetail = haystack.contains(ACCESSIBILITY_DETAIL_SIGNAL) ||
            haystack.contains("use $needleLabel")
        val isAppInfo = APP_INFO_SIGNALS.any { haystack.contains(it) }
        return isAccessibilityDetail || isAppInfo
    }
}
