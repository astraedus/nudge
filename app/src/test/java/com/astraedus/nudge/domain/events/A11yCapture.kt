package com.astraedus.nudge.domain.events

import java.io.File

/**
 * Loads a device-captured accessibility event stream from `app/src/test/resources/a11y-captures/`.
 *
 * This is how a bug report becomes a regression test in this repo: reproduce it on a device with
 * `scripts/a11y-capture.sh` running, commit the `.jsonl`, write the replay assertion, fix. See
 * `docs/architecture/accessibility-event-pipeline.md`.
 *
 * Resolves the file from the working directory rather than the classpath, and from BOTH plausible
 * roots, for the same reason the source-level contract tests do: Gradle runs JVM tests with the
 * module directory as CWD, but a run launched from the repo root does not.
 */
object A11yCapture {

    private const val DIR = "src/test/resources/a11y-captures"

    fun load(name: String): List<AccessibilityEventRecord> {
        val file = file(name)
        val records = file.useLines { AccessibilityEventCodec.decodeAll(it) }
        // A capture that silently loads as zero events would make every assertion over it pass
        // vacuously — which is precisely the failure mode a fixture suite exists to prevent.
        check(records.isNotEmpty()) { "capture '$name' decoded to zero events (${file.absolutePath})" }
        return records
    }

    /** Every capture on disk, so a test can assert a property over ALL of them at once. */
    fun names(): List<String> = directory().listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".jsonl") }
        .map { it.name.removeSuffix(".jsonl") }
        .sorted()

    /** The `# ...` header lines, which carry each capture's provenance and its expected count. */
    fun header(name: String): List<String> =
        file(name).readLines().filter { it.trimStart().startsWith("#") }

    private fun file(name: String): File {
        val f = File(directory(), "$name.jsonl")
        check(f.exists()) { "capture '$name' not found at ${f.absolutePath}" }
        return f
    }

    private fun directory(): File = listOf(File(DIR), File("app/$DIR"))
        .firstOrNull { it.isDirectory }
        ?: error("a11y-captures directory not found from ${File("").absolutePath}")
}
