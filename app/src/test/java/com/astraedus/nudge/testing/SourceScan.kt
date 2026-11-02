package com.astraedus.nudge.testing

import java.io.File
import org.junit.Assert.assertTrue

/**
 * The ONE place that knows where production source lives, for the `*ContractTest` source-scan
 * tests. A scan test is allowed only for a SHAPE bug (call order, one definition, never-in-main)
 * that the compiler cannot see; it goes through this helper, names the bug it guards in the
 * failure message, and is deleted once the compiler or lint enforces the shape
 * (`~/.claude/context/code-quality-reference.md` section 9, rule 8).
 *
 * Nothing outside this file builds a `File("app/src/main...")` / `File("src/main...")` path.
 */
object SourceScan {

    /** The repo root: the nearest ancestor of the working dir holding `settings.gradle.kts`. */
    val projectRoot: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
    }

    /** A file or directory relative to the repo root, e.g. `scripts/a11y-capture.sh`. */
    fun rootFile(relative: String): File {
        val file = File(projectRoot, relative)
        assertTrue("$relative must exist under the repo root (looked at ${file.path})", file.exists())
        return file
    }

    /** A file or directory relative to the `app` module, e.g. `src/main/AndroidManifest.xml`. */
    fun appFile(relative: String): File {
        val file = File(File(projectRoot, "app"), relative)
        assertTrue("$relative must exist under app/ (looked at ${file.path})", file.exists())
        return file
    }

    /** A file or directory under `app/src/main`, e.g. `res/xml/backup_rules.xml`. */
    fun mainFile(relative: String): File = appFile("src/main/$relative")

    /**
     * A production Kotlin file or package directory, relative to `app/src/main/java`, e.g.
     * `kotlinFile("com/astraedus/nudge/service/NudgeAccessibilityService.kt")`. A path that is not
     * an existing directory and has no `.kt` suffix gets `.kt` appended.
     */
    fun kotlinFile(relative: String): File {
        val asGiven = File(projectRoot, "app/src/main/java/$relative")
        return if (asGiven.isDirectory || relative.endsWith(".kt")) mainFile("java/$relative")
        else mainFile("java/$relative.kt")
    }

    /** Every `.kt` file under [subdir] of `app/src/main/java` (default: the whole app). */
    fun kotlinSources(subdir: String = ""): List<File> =
        kotlinFile(subdir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Source with `/* */` blocks and `//` tails removed, so prose about a call is not the call. */
    fun stripComments(text: String): String = text
        .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }

    /** [file]'s text with comments stripped. */
    fun code(file: File): String = stripComments(file.readText())

    /** The `{ ... }` body that follows [signature] in [code], brace-matched. */
    fun bodyOf(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue("$signature not found", start >= 0)
        val from = code.indexOf('{', start)
        assertTrue("no body after $signature", from >= 0)
        var depth = 0
        for (i in from until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return code.substring(from, i + 1)
            }
        }
        error("unbalanced braces after $signature")
    }

    /** [file] contains [pattern] in code (comments ignored). [why] names the bug being guarded. */
    fun assertCall(file: File, pattern: Regex, why: String) {
        assertTrue("${file.name}: expected ${pattern.pattern}. $why", pattern.containsMatchIn(code(file)))
    }

    fun assertCall(file: File, literal: String, why: String) =
        assertCall(file, Regex(Regex.escape(literal)), why)

    /** [file] never contains [pattern] in code (comments ignored). [why] names the bug. */
    fun assertNoCall(file: File, pattern: Regex, why: String) {
        val hit = pattern.find(code(file))
        assertTrue("${file.name}: must not contain ${pattern.pattern}, found '${hit?.value}'. $why", hit == null)
    }

    fun assertNoCall(file: File, literal: String, why: String) =
        assertNoCall(file, Regex(Regex.escape(literal)), why)

    /** In [file], [first] occurs and [second] occurs after it. [why] names the bug. */
    fun assertCallBefore(file: File, first: Regex, second: Regex, why: String) {
        val text = code(file)
        val a = first.find(text)
        assertTrue("${file.name}: expected ${first.pattern}. $why", a != null)
        val b = second.find(text, a!!.range.last + 1)
        assertTrue("${file.name}: expected ${second.pattern} AFTER ${first.pattern}. $why", b != null)
    }

    fun assertCallBefore(file: File, first: String, second: String, why: String) =
        assertCallBefore(file, Regex(Regex.escape(first)), Regex(Regex.escape(second)), why)
}
