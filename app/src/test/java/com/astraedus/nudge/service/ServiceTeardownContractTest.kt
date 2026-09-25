package com.astraedus.nudge.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level guard on the accessibility service's TEARDOWN
 * ([#57](https://github.com/astraedus/nudge/issues/57)).
 *
 * ## Why a test that reads source code
 *
 * The defect was not in any value: `stopForegroundTimeTicker` read `lateinit var foregroundClock`,
 * which only `onServiceConnected` assigns, from `onDestroy`, which runs whether or not a connect
 * ever completed. Android wraps the resulting [kotlin.UninitializedPropertyAccessException] as
 * *"Unable to stop service"* and lists the component under `Crashed services:` — and a crashed
 * accessibility service is never rebound, so **every rule silently becomes a no-op with nothing
 * shown to the user**. It presented during QA as "the block screen just did not appear" and cost
 * several rounds chasing a phantom regression elsewhere.
 *
 * There is no value to assert and no JVM-constructible service to destroy, so the invariant is read
 * off the source, in the same spirit as [ServiceLifecycleContractTest] and
 * [AwarenessOverlayContractTest]. [ServiceTeardownTest] owns the behavioural half.
 *
 * ## The rule is DISCOVERED, not listed
 *
 * The issue asked for exactly this: *"a hand-list will rot; prefer a test that discovers them."*
 * There were two of these fields, not one — `endWebSession` reads `webClock.isRunning` even when
 * `activeWebSessionKey` is already null, so it would have thrown the moment the first field was
 * fixed. So the test parses the service, computes which functions `onDestroy` can actually reach,
 * and requires a guard for every `lateinit` any of them touches. A new field, or a new call added
 * to `onDestroy`, is covered the day it is written.
 */
class ServiceTeardownContractTest {

    private val relativePath = "main/java/com/astraedus/nudge/service/NudgeAccessibilityService.kt"

    /**
     * The file's CODE. Comments go first — this file documents the very bug it was fixed for, and
     * naming `isInitialized` in prose must not be able to satisfy the assertion that requires it.
     * String and character literals go too, so the brace counting below cannot be desynchronised by
     * a `"${'$'}{...}"` template or a `'}'` literal.
     */
    private fun code(): String {
        val candidates = listOf(File("src/$relativePath"), File("app/src/$relativePath"))
        val text = (candidates.firstOrNull { it.exists() }
            ?: error("$relativePath not found from working dir ${File("").absolutePath}"))
            .readText()
        return text
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("\"\"\"[\\s\\S]*?\"\"\""), "\"\"")
            .replace(Regex(""""(?:\\.|[^"\\\n])*""""), "\"\"")
            .replace(Regex("""'(?:\\.|[^'\\\n])'"""), "' '")
    }

    /** Every `lateinit var` the service declares, whatever its modifiers or how many there are. */
    private fun lateinitFields(code: String): Set<String> =
        Regex("""\blateinit\s+var\s+(\w+)\s*:""").findAll(code).map { it.groupValues[1] }.toSet()

    /**
     * The service's member functions, by name, with their bodies. Members only: anything nested
     * deeper (a `by lazy` object expression's overrides, the companion) is indented past four
     * spaces and is not reachable as a bare call from `onDestroy`.
     */
    private fun memberFunctions(code: String): Map<String, String> {
        val start = Regex("""^ {4}(?:\w+ )*fun\s+(?:<[^>]+>\s*)?(\w+)\s*\(""")
        val nextMember = Regex("""^ {4}\S""")
        val lines = code.lines()
        val functions = mutableMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val name = start.find(lines[index])?.groupValues?.get(1)
            if (name == null) {
                index++
                continue
            }
            val body = StringBuilder(lines[index]).append('\n')
            var depth = braceDelta(lines[index])
            index++
            while (index < lines.size) {
                // A braced body ends where its brace closes; a brace-less expression body ends
                // where the next member declaration begins.
                if (depth == 0 && (body.contains('{') || nextMember.containsMatchIn(lines[index]))) break
                body.append(lines[index]).append('\n')
                depth += braceDelta(lines[index])
                index++
            }
            functions[name] = body.toString()
        }
        return functions
    }

    private fun braceDelta(line: String): Int = line.count { it == '{' } - line.count { it == '}' }

    /**
     * The functions `onDestroy` can reach. Bare calls only — `(?<![.\w])` drops `teardown.step(`
     * and `entryPoint.nudgeLogger()`, which are methods on other types and would otherwise pull
     * unrelated same-named members of this class into the closure.
     */
    private fun reachableFromOnDestroy(functions: Map<String, String>): Set<String> {
        val reached = mutableSetOf("onDestroy")
        val queue = ArrayDeque(reached)
        while (queue.isNotEmpty()) {
            val body = functions[queue.removeFirst()] ?: continue
            Regex("""(?<![.\w])(\w+)\s*\(""").findAll(body)
                .map { it.groupValues[1] }
                .filter { it in functions && reached.add(it) }
                .forEach { queue.addLast(it) }
        }
        return reached
    }

    /**
     * The parser has to be able to fail loudly: a regex that silently matched nothing would make
     * every assertion below pass over a completely unguarded teardown.
     */
    @Test
    fun `the source parser actually finds the teardown it is guarding`() {
        val code = code()
        val functions = memberFunctions(code)
        val reachable = reachableFromOnDestroy(functions)

        assertTrue(
            "the service must declare lateinit fields for this test to have a subject",
            lateinitFields(code).size >= 2
        )
        assertTrue(
            "onDestroy must be parsed out of $relativePath — found ${functions.size} member functions",
            functions.containsKey("onDestroy")
        )
        assertTrue(
            "onDestroy's call closure must include the clock stop that crashed in production; " +
                "found $reachable",
            "stopForegroundTimeTicker" in reachable
        )
        assertTrue(
            "onDestroy's call closure must include the web session teardown; found $reachable",
            "endWebSession" in reachable
        )
    }

    /**
     * The invariant. Any function teardown can reach must be safe to run on a service that never
     * completed `onServiceConnected` — so it may not read a `lateinit` without asking whether it
     * was assigned. `hideAllOverlays` has used this guard shape since it was written; this makes it
     * the rule rather than one function's habit.
     */
    @Test
    fun `every lateinit reachable from onDestroy is guarded by an isInitialized check`() {
        val code = code()
        val fields = lateinitFields(code)
        val functions = memberFunctions(code)
        val unguarded = mutableListOf<String>()

        reachableFromOnDestroy(functions).sorted().forEach { name ->
            val body = functions[name] ?: return@forEach
            fields.forEach { field ->
                val reads = Regex("""(?<!::)\b$field\b""").containsMatchIn(body)
                val guarded = body.contains("::$field.isInitialized")
                if (reads && !guarded) unguarded += "$name reads $field"
            }
        }

        assertEquals(
            "onDestroy runs whether or not onServiceConnected ever assigned these fields — an " +
                "install-over, a force stop, a memory-pressure kill and rebind, or a fast " +
                "permission toggle all destroy a service that never connected. An unguarded read " +
                "throws out of onDestroy, Android records the service as CRASHED and never rebinds " +
                "it, and every rule becomes a silent no-op (#57). Guard each with " +
                "`if (::field.isInitialized)`, as hideAllOverlays does. Unguarded: $unguarded",
            emptyList<String>(),
            unguarded
        )
    }

    /**
     * The deeper half: guarding the fields stops today's throw, but any teardown statement can
     * throw for its own reasons, and the first one that does both crashes the service and skips
     * every step below it. So `onDestroy` may hold nothing but contained steps.
     */
    @Test
    fun `onDestroy runs every statement through the contained teardown runner`() {
        val body = memberFunctions(code())["onDestroy"] ?: error("onDestroy not found")
        val allowed = listOf("super.onDestroy()", "val teardown = ServiceTeardown", "teardown.step(")
        val bare = mutableListOf<String>()

        var depth = 0
        body.lines().drop(1).forEach { line ->
            val statement = line.trim()
            // Depth 0 here is the function body's own level: the signature line was dropped, and
            // anything inside a step's lambda sits at depth 1 or deeper.
            if (depth == 0 && statement.isNotEmpty() && statement != "}" &&
                allowed.none { statement.startsWith(it) }
            ) {
                bare += statement
            }
            depth += line.count { it == '{' } - line.count { it == '}' }
        }

        assertEquals(
            "every statement in onDestroy must go through ServiceTeardown.step, or the first one " +
                "that throws takes the whole service down as CRASHED and skips the teardown below " +
                "it — before #57 an uninitialised clock on the third line meant serviceScope was " +
                "never cancelled and the overlay managers kept a dead service as their context. " +
                "Bare statements: $bare",
            emptyList<String>(),
            bare
        )
        assertTrue(
            "onDestroy must actually build a ServiceTeardown",
            body.contains("ServiceTeardown")
        )

        // The two orderings that are load-bearing rather than incidental. Containment makes every
        // step run, so their ORDER is now the only thing left to get wrong.
        val steps = body.lines().map { it.trim() }.filter { it.startsWith("teardown.step(") }
        assertTrue("onDestroy must run its teardown as several steps, found $steps", steps.size > 5)
        assertTrue(
            "the instance must be cleared FIRST, so anything reading connection state during the " +
                "rest of teardown gets the truth. First step: ${steps.first()}",
            steps.first().contains("instance = null")
        )
        assertTrue(
            "the service scope must be cancelled LAST — a cancelled scope cannot run the coroutines " +
                "the steps above it may need. Last step: ${steps.last()}",
            steps.last().contains("serviceScope.cancel()")
        )
    }
}
