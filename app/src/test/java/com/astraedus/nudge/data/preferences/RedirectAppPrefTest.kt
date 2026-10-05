package com.astraedus.nudge.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How the redirect app is stored. [NudgePreferences] cannot be built on the JVM, so its two
 * redirect members are one-line delegations to [RedirectAppPref], and this drives that object both
 * in memory and through a REAL file-backed DataStore (the same `preferences_pb` format the app
 * writes), so "saved, then read back after a restart" is a fact, not an assumption.
 */
class RedirectAppPrefTest {

    @get:Rule val tmp = TemporaryFolder()

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `nothing stored reads as no redirect app`() {
        assertNull(RedirectAppPref.read(preferencesOf()))
    }

    @Test
    fun `a stored package reads back trimmed, and a blank value reads as none`() {
        assertEquals("org.wikipedia", RedirectAppPref.read(preferencesOf(RedirectAppPref.KEY to " org.wikipedia ")))
        assertNull(RedirectAppPref.read(preferencesOf(RedirectAppPref.KEY to "   ")))
    }

    @Test
    fun `writing null or blank clears the key instead of storing an empty string`() {
        listOf(null, "", "  ").forEach { blank ->
            val prefs = mutablePreferencesOf(RedirectAppPref.KEY to "org.wikipedia")
            RedirectAppPref.write(prefs, blank)
            assertFalse("[$blank] must remove the key", prefs.contains(RedirectAppPref.KEY))
        }
    }

    /** The key is part of the on-disk format: renaming it silently forgets every user's choice. */
    @Test
    fun `the storage key is stable`() {
        assertEquals(stringPreferencesKey("redirect_app_package"), RedirectAppPref.KEY)
    }

    @Test
    fun `save, change and remove survive a fresh DataStore over the same file`() = runBlocking {
        val file = tmp.newFile("nudge_prefs.preferences_pb").also { it.delete() }

        val first = PreferenceDataStoreFactory.create(scope = scope) { file }
        first.edit { RedirectAppPref.write(it, "org.wikipedia") }
        assertEquals("org.wikipedia", RedirectAppPref.read(first.data.first()))
        first.edit { RedirectAppPref.write(it, "org.tasks") }
        scope.cancel()
        job.join() // DataStore releases the file only once its scope has completed

        // A second instance over the same file: what a process restart sees.
        val restartScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = PreferenceDataStoreFactory.create(scope = restartScope) { file }
            assertEquals("org.tasks", RedirectAppPref.read(reopened.data.first()))
            reopened.edit { RedirectAppPref.write(it, null) }
            assertNull(RedirectAppPref.read(reopened.data.first()))
        } finally {
            restartScope.cancel()
        }
    }
}
