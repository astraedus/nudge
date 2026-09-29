package com.astraedus.nudge

import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.JUnitCore

/**
 * TEMPORARY DIAGNOSTIC (issue #53). Runs every test class in the tree through `JUnitCore`, one at a
 * time, draining kotlinx's process-global `ExceptionCollector` before each and asking it after each
 * whether that class left anything behind. Prints the offenders by name.
 *
 * A SETTLE delay sits between the class finishing and the drain: CI is ~4.5x slower than this
 * machine, so anything a class left running in real time on `Dispatchers.IO` has far longer there to
 * come back and throw into a torn-down fixture. Tune with `-Dnudge.leakhunter.settleMs=N`.
 *
 * Run it alone:
 * `./gradlew testReleaseUnitTest --tests 'com.astraedus.nudge.LeakHunterTest'`
 *
 * Delete before committing.
 */
class LeakHunterTest {

    private val settleMs = System.getProperty("nudge.leakhunter.settleMs")?.toLong() ?: 400L

    @Test
    fun `find every class that leaves an exception in the global collector`() {
        val root = listOf(
            File("src/test/java/com/astraedus/nudge"),
            File("app/src/test/java/com/astraedus/nudge")
        ).first { it.isDirectory }

        val classes = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE)
                    .find(text)?.groupValues?.get(1) ?: return@mapNotNull null
                if (!text.contains("@Test")) return@mapNotNull null
                runCatching { Class.forName("$pkg.${file.nameWithoutExtension}") }.getOrNull()
            }
            .filterNot { it == LeakHunterTest::class.java }
            .filter { c ->
                System.getProperty("nudge.leakhunter.only")
                    ?.split(",")?.any { c.name.endsWith(it.trim()) } ?: true
            }
            .sortedBy { it.name }
            .toList()

        println("LEAK-HUNTER: scanning ${classes.size} test classes, settleMs=$settleMs")
        val leakers = mutableListOf<String>()
        val redFailures = mutableListOf<String>()

        classes.forEach { clazz ->
            LeakedCoroutineExceptions.drain()
            val before = liveThreads()
            val result = runCatching { JUnitCore.runClasses(clazz) }
            result.onSuccess { r ->
                if (!r.wasSuccessful()) redFailures += "${clazz.name}: ${r.failures}"
            }.onFailure { redFailures += "${clazz.name}: harness threw $it" }

            Thread.sleep(settleMs)
            val leaked = LeakedCoroutineExceptions.drain()
            val newThreads = (liveThreads() - before).filterNot { it.startsWith("kotlinx.coroutines") }
            if (leaked) {
                leakers += clazz.name
                println("LEAK-HUNTER: LEAKER ${clazz.name} (new threads still alive: $newThreads)")
            }
            println("LEAK-HUNTER: after ${clazz.name} main=${mainDispatcherState()} newThreads=$newThreads")
        }

        println("LEAK-HUNTER: leakers = $leakers")
        println("LEAK-HUNTER: red classes = $redFailures")
    }

    private fun liveThreads(): Set<String> =
        Thread.getAllStackTraces().keys.filter { it.isAlive }.map { it.name }.toSet()

    private fun mainDispatcherState(): String =
        runCatching { Dispatchers.Main.toString() }.getOrElse { "threw ${it::class.simpleName}" }
}
