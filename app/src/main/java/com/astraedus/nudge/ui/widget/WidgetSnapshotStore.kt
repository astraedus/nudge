package com.astraedus.nudge.ui.widget

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The latest snapshot for each widget, as Compose state the widgets READ DURING COMPOSITION.
 *
 * ## Why this exists: `provideGlance` runs once per session, not once per update
 *
 * Verified against `glance-appwidget` 1.2.0 rather than assumed. `AppWidgetSession.processEvent`
 * handles the `UpdateGlanceState` event by reading the widget's `GlanceStateDefinition` through
 * `configManager` and assigning it to an internal `MutableState` inside a Compose snapshot. It
 * never calls `GlanceAppWidget.provideGlance`. So an `update`/`updateAll` on a widget whose session
 * is still alive **recomposes the existing content lambda**; it does not re-run the suspend prelude
 * that produced it.
 *
 * Every widget here used to do `val snapshot = WidgetReads...` *before* `provideContent`, which
 * makes that value a captured constant for the life of the session. Recomposition faithfully
 * re-rendered the stale capture, so a refresh could run, log, throw nothing, and change nothing.
 *
 * Session lifetimes make it intermittent rather than constant, which is what made it so confusing
 * to chase. `TimeoutOptions` defaults in 1.2.0 are `initialTimeout = 45s`, `additionalTime = 5s`,
 * `idleTimeout = 5s`. A refresh landing outside that window gets a fresh session and looks correct;
 * one landing inside it silently renders the previous values. Device evidence matched exactly: a
 * refresh 4.5 s after another showed stale data, one 93 s later was correct, and one 30 s after a
 * session started was stale again (still inside the 45 s window).
 *
 * ## Why a state holder rather than the Glance state store
 *
 * The idiomatic alternative is to write each snapshot into `PreferencesGlanceStateDefinition` and
 * compose from `currentState`, which is exactly what `UpdateGlanceState` refreshes. It is the right
 * shape for scalar data and the wrong one here: the Top-blocked widget carries app-icon `Bitmap`s,
 * which do not belong in a `Preferences` store. A `mutableStateOf` read inside composition gets the
 * same property - Compose records the read, and writing it schedules recomposition - without
 * pretending bitmaps are preferences.
 *
 * The widgets run in the app's own process (Glance drives sessions through `SessionWorker` here),
 * so this singleton is genuinely shared with them. It is a CACHE, never the source of truth: a cold
 * session whose store is empty reads through to `WidgetReads` itself, which is what happens after a
 * process death or when the launcher first adds a widget.
 */
@Singleton
class WidgetSnapshotStore @Inject constructor() {

    var today by mutableStateOf<WidgetSnapshot.Today?>(null)
        private set

    var topBlocked by mutableStateOf<WidgetReads.TopBlockedRead?>(null)
        private set

    var protection by mutableStateOf<WidgetSnapshot.Protection?>(null)
        private set

    fun publishToday(snapshot: WidgetSnapshot.Today) {
        today = snapshot
    }

    fun publishTopBlocked(read: WidgetReads.TopBlockedRead) {
        topBlocked = read
    }

    fun publishProtection(snapshot: WidgetSnapshot.Protection) {
        protection = snapshot
    }
}
