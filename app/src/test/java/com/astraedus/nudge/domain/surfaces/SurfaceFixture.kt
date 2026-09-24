package com.astraedus.nudge.domain.surfaces

import java.io.File

/** One node of a recorded view hierarchy, reduced to the attributes this feature reads. */
data class FixtureNode(
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val selected: Boolean,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /**
     * The node's bounds as a validated cover placement, via production's own validator.
     *
     * Four plain `Int`s straight from the `bounds` attribute into [TabCoverPlacement.of]. There is
     * deliberately no `android.graphics.Rect` anywhere in test sources: `unitTests.isReturnDefaultValues`
     * is NOT set in `app/build.gradle.kts`, so any `android.*` call in a JVM test throws — and setting
     * it would silently turn every unmocked android call in ~1550 existing tests into `0`/null.
     */
    fun placement(): TabCoverPlacement? = TabCoverPlacement.of(left, top, right, bottom)
}

/**
 * Reads the recorded Instagram hierarchies in `app/src/test/resources/surface-fixtures/`.
 *
 * Regex, not a parser, and no new Gradle dependency: a hierarchy dump is one long line of
 * self-describing `<node .../>` tags with no nested quoting, so attribute extraction is exact.
 * Nesting is not modelled because nothing here needs it — the feature asks "is this id present",
 * "what does this node say" and "where is this node", all of which are per-node questions.
 *
 * Resolves from the working directory rather than the classpath, and from both plausible roots, for
 * the same reason [com.astraedus.nudge.domain.events.A11yCapture] does: Gradle runs JVM tests with
 * the module directory as CWD, a run launched from the repo root does not.
 */
object SurfaceFixture {

    private const val DIR = "src/test/resources/surface-fixtures"

    private val NODE = Regex("""<node\b[^>]*>""")
    private val BOUNDS = Regex("""bounds="\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)]"""")

    /** Every fixture on disk, so a test can assert a property over ALL of them at once. */
    fun names(): List<String> = directory().listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.endsWith(".xml") }
        .map { it.name.removeSuffix(".xml") }
        .sorted()

    /** Every node in one fixture, in document order. */
    fun load(name: String): List<FixtureNode> {
        val nodes = NODE.findAll(file(name).readText()).map { match ->
            val tag = match.value
            val bounds = BOUNDS.find(tag)
            FixtureNode(
                viewId = attribute(tag, "resource-id").takeUnless { it.isNullOrEmpty() },
                className = attribute(tag, "class").takeUnless { it.isNullOrEmpty() },
                text = attribute(tag, "text").takeUnless { it.isNullOrEmpty() },
                contentDescription = attribute(tag, "content-desc").takeUnless { it.isNullOrEmpty() },
                selected = attribute(tag, "selected") == "true",
                clickable = attribute(tag, "clickable") == "true",
                left = bounds?.groupValues?.get(1)?.toInt() ?: 0,
                top = bounds?.groupValues?.get(2)?.toInt() ?: 0,
                right = bounds?.groupValues?.get(3)?.toInt() ?: 0,
                bottom = bounds?.groupValues?.get(4)?.toInt() ?: 0
            )
        }.toList()
        // A fixture that silently loaded as zero nodes would make every assertion over it pass
        // vacuously — the exact failure mode a fixture suite exists to prevent.
        check(nodes.isNotEmpty()) { "fixture '$name' parsed to zero nodes" }
        return nodes
    }

    /**
     * The fixture reduced to the pure [SurfaceObservation] the service builds from a live tree read.
     *
     * This is what makes the fixtures honest: production's classifier is handed a value of exactly
     * this shape, built by exactly this reduction, so the test and the device are comparing the same
     * thing. `actionBarTitle` is read off the host app's title node, preferring `text` and falling
     * back to `contentDescription` (Instagram's Following header carries the string in both).
     */
    fun observe(name: String): SurfaceObservation {
        val nodes = load(name)
        val title = nodes.firstOrNull { it.viewId == InstagramSurfaces.ID_ACTION_BAR_TITLE }
        return SurfaceObservation(
            presentViewIds = nodes.mapNotNull { it.viewId }.toSet(),
            actionBarTitle = title?.text ?: title?.contentDescription
        )
    }

    /** The first node in [name] matching [locator], or null. Drives production locators directly. */
    fun find(name: String, locator: NodeLocator): FixtureNode? = findAll(name, locator).firstOrNull()

    /**
     * Every node in [name] matching [locator].
     *
     * Asks [NodeLocator.matchesNode] — the SAME function `HostNodeFinder` asks of a real node — so a
     * fixture test cannot answer a different question than the accessibility service does. This used
     * to spell the scoped/unscoped branch out here, mirroring the one in `HostNodeFinder`; two copies
     * of a matching rule is how a fixture ends up agreeing with its author instead of with the device
     * (`docs/testing-strategy.md` rule (b)) — the dump reader would have selected "Following" under OR
     * while production selected whichever row came first, green about the wrong thing.
     */
    fun findAll(name: String, locator: NodeLocator): List<FixtureNode> = load(name).filter { node ->
        locator.matchesNode(node.viewId, node.text, node.contentDescription)
    }

    private fun attribute(tag: String, name: String): String? =
        Regex("""\s${Regex.escape(name)}="([^"]*)"""").find(tag)?.groupValues?.get(1)

    private fun file(name: String): File {
        val f = File(directory(), "$name.xml")
        check(f.exists()) { "fixture '$name' not found at ${f.absolutePath}" }
        return f
    }

    private fun directory(): File = listOf(File(DIR), File("app/$DIR"))
        .firstOrNull { it.isDirectory }
        ?: error("surface-fixtures directory not found from ${File("").absolutePath}")
}
