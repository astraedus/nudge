package com.astraedus.nudge.service

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.service.autofill.AutofillService
import android.telecom.TelecomManager
import com.astraedus.nudge.domain.health.EnabledAccessibilityServices
import com.astraedus.nudge.domain.lock.StrictModeEscapeGuard
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.nuke.NukePolicy
import com.astraedus.nudge.domain.packages.EssentialPackages
import com.astraedus.nudge.util.InputMethods
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The apps no block may ever cover: the one essentials list (spec section 5.4).
 *
 * Nuke, group rules and "All apps" rules read it, and the "Always allowed" sheet renders it, so what
 * the user is told and what the engine skips cannot differ. It holds
 * the static floor ([NukePolicy.STATIC_FLOOR] and the package sets the accessibility service already
 * treats as system surfaces) plus what THIS phone uses, one labelled [EssentialSource] each: the
 * launcher(s), the default dialer, the keyboards, the default SMS app, the clock, emergency and
 * personal-safety apps, every enabled accessibility service, and every installed autofill service.
 *
 * Camera, Maps, the browser and banking apps are deliberately NOT on the list (owner decision, spec
 * sections 5.4 and 8): the user can allow any of them in seconds. A browser that a device source
 * names anyway (Firefox ships an autofill service) is taken back off ([resolve]).
 *
 * Resolved from PackageManager, TelecomManager, InputMethodManager, Telephony and Settings, which are binder
 * calls, so [packages] must be called OFF the main thread. The answer is cached for [REFRESH_MS]: a
 * stale set only errs toward the old default apps staying protected, never toward blocking a new
 * default for longer than a few minutes.
 *
 * **Fails soft per source.** A source that throws contributes nothing and is logged by its label;
 * every other source still applies. No failure makes the list SMALLER than the static floor.
 */
@Singleton
class EssentialApps internal constructor(
    private val sources: List<EssentialSource>,
    logger: NudgeLog,
    /**
     * The browsers on this phone. NEVER essential, whichever source names one ([resolve]). No
     * default: the autofill source names Firefox on a stock phone (it ships an autofill service),
     * and a browser on this list is the website escape groups and All apps exist to close.
     */
    private val browsers: EssentialSource
) : EssentialPackages {

    @Inject
    constructor(@ApplicationContext context: Context, logger: NudgeLog) :
        this(sources = deviceSources(context), logger = logger, browsers = deviceBrowsers(context))

    private val logger: NudgeLog = logger.tagged("EssentialApps")
    @Volatile private var cached: Set<String>? = null
    @Volatile private var cachedAt: Long = 0L

    /** Every essential package. Off-main only; cached for [REFRESH_MS]. */
    fun packages(now: Long = System.currentTimeMillis()): Set<String> {
        val current = cached
        if (current != null && now - cachedAt < REFRESH_MS) return current
        val resolved = resolve()
        cached = resolved
        cachedAt = now
        return resolved
    }

    /**
     * [packages] for the engine ([EssentialPackages]). A fresh cache answers in place, with no
     * dispatcher hop (this is on the evaluation hot path); only a resolution moves off the main thread.
     */
    override suspend fun current(): Set<String> {
        val current = cached
        if (current != null && System.currentTimeMillis() - cachedAt < REFRESH_MS) return current
        return withContext(Dispatchers.IO) { packages() }
    }

    /** The last resolved set, however old, without resolving. Null before the first resolution. */
    override fun cachedOrNull(): Set<String>? = cached

    /** Drop the cache (e.g. after the user changes a default app). */
    fun invalidate() {
        cached = null
    }

    /**
     * The static floor, plus every device source's answer minus the browsers.
     *
     * Browsers are removed from what the SOURCES say, never from the static floor (no browser is on
     * it). A browser reaches a source by accident, not because it is one of the things this list
     * protects: Firefox declares an autofill service, so "installed autofill services" named it, and
     * an All apps rule then never covered Firefox (open Firefox, go to youtube.com) and Nuke could
     * not nuke it. The owner decision (spec 5.4) is that the browser is not essential. If the device
     * query for browsers fails, the browsers Nudge reads URLs from ([KNOWN_BROWSERS]) are still
     * removed.
     */
    private fun resolve(): Set<String> {
        val notEssential = KNOWN_BROWSERS + resolveOrEmpty(browsers)
        val all = STATIC_ESSENTIALS.toMutableSet()
        for (source in sources) {
            all += resolveOrEmpty(source) - notEssential
        }
        return all.filterTo(mutableSetOf()) { it.isNotBlank() }
    }

    private fun resolveOrEmpty(source: EssentialSource): Set<String> =
        try {
            source.resolve()
        } catch (e: Exception) {
            logger.w("essential apps: could not resolve ${source.label}", e)
            emptySet()
        }

    companion object {
        private const val REFRESH_MS = 5 * 60_000L

        /**
         * `TelephonyManager.ACTION_EMERGENCY_ASSISTANCE`. The constant is `@SystemApi`, so it is not
         * in the public SDK, but the intent is an ordinary activity action any app may resolve.
         */
        private const val ACTION_EMERGENCY_ASSISTANCE = "android.telephony.action.EMERGENCY_ASSISTANCE"

        /** Emergency information and personal-safety apps that no intent may resolve to. */
        val EMERGENCY_PACKAGES: Set<String> = setOf(
            "com.android.emergency",
            "com.google.android.apps.safetyhub"
        )

        /** Always on the list, whatever any source answers. */
        val STATIC_ESSENTIALS: Set<String> = NukePolicy.STATIC_FLOOR +
            NudgeAccessibilityService.SYSTEM_PACKAGES +
            NudgeAccessibilityService.IME_PACKAGES +
            StrictModeEscapeGuard.SETTINGS_PACKAGES +
            EMERGENCY_PACKAGES

        /** The labels of [deviceSources], in order. Pinned by a test so a source cannot go missing. */
        val DEVICE_SOURCE_LABELS: List<String> = listOf(
            "self", "launchers", "dialer", "keyboards", "sms", "clock", "emergency",
            "accessibility", "autofill"
        )

        /** This phone's sources. Each is one query, isolated by [resolve]. */
        internal fun deviceSources(context: Context): List<EssentialSource> {
            val pm = { context.packageManager }
            return listOf(
                EssentialSource("self") { setOf(context.packageName) },
                EssentialSource("launchers") {
                    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    activityPackages(pm(), home, PackageManager.MATCH_DEFAULT_ONLY)
                },
                // `getDefaultDialerPackage` is API 23; minSdk is 26.
                EssentialSource("dialer") {
                    setOfNotNull(
                        (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)
                            ?.defaultDialerPackage
                    )
                },
                // Shared with the event classifier's transient set, so the two cannot disagree.
                EssentialSource("keyboards") { InputMethods.enabledPackages(context) },
                // API 19. Codes, 2FA texts and emergency texts arrive here.
                EssentialSource("sms") { setOfNotNull(Telephony.Sms.getDefaultSmsPackage(context)) },
                // Every app that can show alarms: turning one off must never meet a block.
                EssentialSource("clock") {
                    activityPackages(pm(), Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)
                },
                // Most phones resolve this to the emergency-information app.
                EssentialSource("emergency") {
                    activityPackages(pm(), Intent(ACTION_EMERGENCY_ASSISTANCE), 0)
                },
                // A screen reader must never be blocked. The secure setting is the user's intent;
                // what matters here is "what the user turned on", not "what is bound right now".
                EssentialSource("accessibility") {
                    EnabledAccessibilityServices.packages(
                        Settings.Secure.getString(
                            context.contentResolver,
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                        )
                    )
                },
                // Installed autofill services, by service intent (API 26), not by the
                // `autofill_service` secure setting, which may not be readable at targetSdk 36.
                // A password manager shows its own unlock screen while filling a field in an
                // allowed app; blocking it would break the allowed app.
                EssentialSource("autofill") {
                    pm().queryIntentServices(Intent(AutofillService.SERVICE_INTERFACE), 0)
                        .mapNotNullTo(mutableSetOf()) { it.serviceInfo?.packageName }
                }
            )
        }

        /** The browsers Nudge reads website addresses from: never essential, even with no device query. */
        val KNOWN_BROWSERS: Set<String> = WebDomainDetector.BROWSER_URL_BAR_IDS.keys

        /**
         * Every app that opens an ordinary web link: what Android itself counts as a browser. A
         * generic https address, so only apps that take ANY site match (an app that opens only its
         * own domain's links does not). Sees every package (QUERY_ALL_PACKAGES).
         */
        internal fun deviceBrowsers(context: Context): EssentialSource =
            EssentialSource("browsers") {
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                activityPackages(context.packageManager, web, PackageManager.MATCH_ALL)
            }

        private fun activityPackages(pm: PackageManager, intent: Intent, flags: Int): Set<String> =
            pm.queryIntentActivities(intent, flags)
                .mapNotNullTo(mutableSetOf()) { it.activityInfo?.packageName }
    }
}

/** One labelled way of finding essential packages. [resolve] may throw; only its own answer is lost. */
class EssentialSource(val label: String, val resolve: () -> Set<String>)
