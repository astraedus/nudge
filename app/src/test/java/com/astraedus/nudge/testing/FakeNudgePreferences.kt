package com.astraedus.nudge.testing

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.astraedus.nudge.data.preferences.NudgePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The REAL [NudgePreferences] over an in-memory store, for JVM tests.
 *
 * Not a reimplementation: every key, every default, every flow and every setter is production
 * code. Only the storage under it is swapped (the class's `internal` primary constructor takes the
 * [DataStore]). A test sets state through the real setters, in [seed] or later, and asserts on
 * what the real getters read back, instead of stubbing a getter with `every` and verifying a
 * setter was called.
 *
 * ```
 * val prefs = FakeNudgePreferences { setStrictModeEnabled(true) }
 * ```
 */
fun FakeNudgePreferences(seed: suspend NudgePreferences.() -> Unit = {}): NudgePreferences =
    NudgePreferences(InMemoryPreferencesDataStore()).also { prefs -> runBlocking { prefs.seed() } }

/**
 * A [DataStore] of [Preferences] held in memory. Writes are serialised (as DataStore's are) and
 * frozen to an immutable snapshot; an edit that changes nothing does not re-emit, matching
 * DataStore, which skips a write whose result equals the current data.
 */
class InMemoryPreferencesDataStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    private val writeLock = Mutex()

    /** How many writes actually changed the data. */
    var writes: Int = 0
        private set

    override val data: Flow<Preferences> = state.asStateFlow()

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        writeLock.withLock {
            val current = state.value
            val next = transform(current).toPreferences()
            if (next != current) {
                state.value = next
                writes++
            }
            next
        }
}
