package com.astraedus.nudge.service

import android.content.Context
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.domain.nuke.NukePolicy
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1: the one essentials list (spec section 5.4). Each device source is a fake here; what the real
 * queries return on a phone is the device-QA half (Messages and Clock stay open under Nuke).
 */
class EssentialAppsTest {

    private class RecordingLog : NudgeLog {
        val warnings = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun i(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) { warnings += message }
        override fun e(message: String, throwable: Throwable?) = Unit
    }

    /** One fake per production source, each answering a package named after its label. */
    private fun fakeSources(throwing: Set<String> = emptySet()): List<EssentialSource> =
        EssentialApps.DEVICE_SOURCE_LABELS.map { label ->
            EssentialSource(label) {
                if (label in throwing) throw SecurityException("$label denied")
                setOf(fakePackage(label))
            }
        }

    private fun fakePackage(label: String) = "fake.$label"

    @Test
    fun `every source's packages are essential, on top of the static floor`() {
        val essentials = EssentialApps(fakeSources(), NudgeLog.NoOp).packages(now = 0L)

        for (label in EssentialApps.DEVICE_SOURCE_LABELS) {
            assertTrue("$label missing", fakePackage(label) in essentials)
        }
        assertTrue(essentials.containsAll(EssentialApps.STATIC_ESSENTIALS))
        assertTrue(essentials.containsAll(NukePolicy.STATIC_FLOOR))
        assertTrue(essentials.containsAll(EssentialApps.EMERGENCY_PACKAGES))
    }

    @Test
    fun `a source that throws drops only its own packages, and is logged by its label`() {
        for (broken in EssentialApps.DEVICE_SOURCE_LABELS) {
            val log = RecordingLog()
            val essentials = EssentialApps(fakeSources(throwing = setOf(broken)), log).packages(now = 0L)

            assertFalse("$broken threw, so its package is gone", fakePackage(broken) in essentials)
            for (label in EssentialApps.DEVICE_SOURCE_LABELS - broken) {
                assertTrue("$broken threw and took $label with it", fakePackage(label) in essentials)
            }
            assertTrue(essentials.containsAll(EssentialApps.STATIC_ESSENTIALS))
            assertEquals(1, log.warnings.size)
            assertTrue(log.warnings.single(), log.warnings.single().endsWith(broken))
        }
    }

    @Test
    fun `every source throwing still leaves the whole static floor`() {
        val all = EssentialApps.DEVICE_SOURCE_LABELS.toSet()
        val essentials = EssentialApps(fakeSources(throwing = all), NudgeLog.NoOp).packages(now = 0L)
        assertEquals(EssentialApps.STATIC_ESSENTIALS, essentials)
    }

    @Test
    fun `blank package names are never on the list`() {
        val sources = listOf(EssentialSource("blanks") { setOf("", " ", "fake.real") })
        val essentials = EssentialApps(sources, NudgeLog.NoOp).packages(now = 0L)
        assertTrue("fake.real" in essentials)
        assertTrue(essentials.none { it.isBlank() })
    }

    @Test
    fun `the answer is cached for five minutes and re-resolved after, or on invalidate`() {
        var calls = 0
        val sources = listOf(EssentialSource("counting") { calls++; setOf("fake.$calls") })
        val essentials = EssentialApps(sources, NudgeLog.NoOp)

        essentials.packages(now = 0L)
        essentials.packages(now = 4 * 60_000L)
        assertEquals("inside the window: one resolve", 1, calls)

        essentials.packages(now = 5 * 60_000L)
        assertEquals("window elapsed: resolved again", 2, calls)

        essentials.invalidate()
        assertTrue("fake.3" in essentials.packages(now = 5 * 60_000L + 1))
    }

    /**
     * The production source list, built over a Context whose every Android call fails the way a JVM
     * stub does: each source must be isolated (no exception escapes), the labels must be exactly the
     * pinned set (so no source can be dropped in a refactor), and the static floor must survive.
     */
    @Test
    fun `the device sources are exactly the pinned set, each isolated`() {
        val context: Context = mockk(relaxed = true)
        val sources = EssentialApps.deviceSources(context)
        assertEquals(EssentialApps.DEVICE_SOURCE_LABELS, sources.map { it.label })

        val essentials = EssentialApps(sources, NudgeLog.NoOp).packages(now = 0L)
        assertTrue(essentials.containsAll(EssentialApps.STATIC_ESSENTIALS))
    }

    /** Owner decision (spec section 8): Camera is not an essential. */
    @Test
    fun `no camera package is on the static list`() {
        val cameras = EssentialApps.STATIC_ESSENTIALS.filter { it.contains("camera", ignoreCase = true) }
        assertEquals(emptyList<String>(), cameras)
    }
}
