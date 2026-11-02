package com.astraedus.nudge.service

import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The category coverage debug receiver (and its broadcast action) is a debug-only seam: it must
 * exist under app/src/debug and nowhere in app/src/main, so a release build can never carry it.
 */
class CategoryDebugReceiverScopeContractTest {

    private val needles = listOf("CategoryCoverageDebugReceiver", "dev.astraedus.nudge.debug.LOG_CATEGORIES")

    private fun sourcesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.toList()

    @Test
    fun `debug receiver and action appear only under src debug`() {
        val debugText = sourcesUnder(SourceScan.appFile("src/debug")).joinToString("\n") { it.readText() }
        needles.forEach { assertTrue("$it must exist under src/debug", debugText.contains(it)) }

        val leaks = sourcesUnder(SourceScan.appFile("src/main"))
            .filter { f -> needles.any { f.readText().contains(it) } }
            .map { it.path }
        assertTrue("debug-only receiver leaked into src/main: $leaks", leaks.isEmpty())
    }
}
