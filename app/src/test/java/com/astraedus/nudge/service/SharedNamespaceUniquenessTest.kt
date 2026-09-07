package com.astraedus.nudge.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every value that Android resolves GLOBALLY for this app must be claimed by exactly one owner.
 *
 * ## The bug this is the guard for
 *
 * Two lanes branched from the same commit and independently built a "blocking has stopped"
 * notification: `NudgeMonitorService`'s health alert and [ProtectionAlertNotifier]'s. Both picked
 * notification id 2. Each lane's suite was green, and each lane was correct in isolation. Merged,
 * they overwrote each other, and the monitor's `onDestroy` cancelled id 2 - so the monitor dying
 * would have wiped the alert about the monitor dying, at the exact moment it became true.
 *
 * **No per-file assertion could have caught it, because every file was individually right.** The
 * defect lived in a namespace no single file owns. That is the class this test exists for, and it
 * is exactly the shape of `ProtectionAlertCopyTest`: enumerate the whole set and assert a property
 * over it, rather than checking instances one at a time.
 *
 * ## Why it discovers rather than reads a registry
 *
 * A central registry of ids would be a second place to look, and the failure mode here was two
 * authors who never looked at each other's file - neither would have looked at the registry either.
 * Scanning the source means a new id is caught the moment it is written, with no discipline
 * required from the person writing it.
 *
 * A failure names BOTH owners, because the person who hits this will be merging two lanes under
 * time pressure, and "duplicate id 2" without owners is a puzzle rather than a fix.
 */
class SharedNamespaceUniquenessTest {

    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /** Strip comments, so prose naming an id is not mistaken for a declaration of it. */
    private fun code(text: String): String = text
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines().joinToString("\n") { it.substringBefore("//") }

    /**
     * Every [pattern] match across `src/main`, as value -> the files declaring it.
     * The capture group is the claimed value.
     */
    private fun claimsBy(pattern: Regex): Map<String, List<String>> {
        val claims = mutableMapOf<String, MutableList<String>>()
        mainSources().forEach { file ->
            pattern.findAll(code(file.readText())).forEach { match ->
                claims.getOrPut(match.groupValues[1]) { mutableListOf() }.add(file.name)
            }
        }
        return claims
    }

    private fun assertUnique(kind: String, claims: Map<String, List<String>>, why: String) {
        val collisions = claims.filterValues { it.distinct().size > 1 }
        assertTrue(
            buildString {
                append("$kind must be claimed by exactly one owner. $why\n")
                collisions.forEach { (value, owners) ->
                    append("  $kind $value is claimed by BOTH ${owners.distinct().joinToString(" and ")}\n")
                }
                append(
                    "Pick a free value for the newer owner. If two lanes merged, check what each " +
                        "one's teardown cancels as well as what it posts."
                )
            },
            collisions.isEmpty()
        )

        assertTrue(
            "$kind: found none at all, so this test is asserting nothing. The declaration style " +
                "must have changed - fix the pattern rather than deleting the test.",
            claims.isNotEmpty()
        )
    }

    @Test
    fun `notification ids are unique across the app`() {
        assertUnique(
            kind = "Notification id",
            claims = claimsBy(Regex("""(?:const\s+val|val)\s+\w*NOTIFICATION_ID\w*\s*=\s*(\d+)""")),
            why = "Android keys a notification on (package, tag, id), so a second poster with the " +
                "same id silently REPLACES the first's notification - and a cancel of that id " +
                "dismisses whichever one is currently showing, no matter who posted it."
        )
    }

    @Test
    fun `notification channel ids are unique across the app`() {
        assertUnique(
            kind = "Notification channel id",
            claims = claimsBy(Regex("""(?:const\s+val|val)\s+\w*CHANNEL_ID\w*\s*=\s*"([^"]+)"""")),
            why = "A channel's importance and its user-facing name are fixed at creation and " +
                "CANNOT be raised afterwards. Two owners sharing a channel id means the second " +
                "silently inherits the first's importance - which is how an urgent alert ends up " +
                "as a silent line on the permanent low-importance channel."
        )
    }

    /**
     * A `PendingIntent` is identified by (creator package, request code, Intent) with the Intent's
     * EXTRAS deliberately excluded from that comparison. So two `getActivity` calls with the same
     * request code and the same target differ only in extras, and `FLAG_UPDATE_CURRENT` makes the
     * second overwrite the first's extras rather than creating a second PendingIntent.
     *
     * Live risk, not theory: the monitor's ongoing notification and the protection alert both
     * target `MainActivity` from different files, and only the alert sets `EXTRA_OPEN_SETTINGS`.
     * Sharing a request code would silently send both taps to the same screen.
     */
    @Test
    fun `PendingIntent request codes are unique across the app`() {
        val pattern = Regex(
            """PendingIntent\.get\w+\(\s*[^,]+,\s*(?:/\*\s*requestCode\s*=\s*\*/\s*)?(\d+)\s*,""",
            RegexOption.DOT_MATCHES_ALL
        )
        // Comments are NOT stripped here: the request code is often written as an inline
        // `/* requestCode = */ 1` label, which the stripper would remove along with the value.
        val claims = mutableMapOf<String, MutableList<String>>()
        mainSources().forEach { file ->
            pattern.findAll(file.readText()).forEach { match ->
                claims.getOrPut(match.groupValues[1]) { mutableListOf() }.add(file.name)
            }
        }

        assertUnique(
            kind = "PendingIntent request code",
            claims = claims,
            why = "PendingIntent identity ignores extras, so two owners sharing a request code and " +
                "a target Activity collapse into one PendingIntent, and FLAG_UPDATE_CURRENT lets " +
                "the later one silently rewrite the earlier one's extras."
        )
    }

    /**
     * `enqueueUniquePeriodicWork` keys on this name. Two workers sharing one would not both run:
     * with `KEEP` the second is silently dropped, and with `REPLACE` they would evict each other
     * on every process start.
     */
    @Test
    fun `WorkManager unique-work names are unique across the app`() {
        assertUnique(
            kind = "WorkManager unique-work name",
            claims = claimsBy(Regex("""(?:const\s+val|val)\s+\w*WORK_NAME\w*\s*=\s*"([^"]+)"""")),
            why = "enqueueUniquePeriodicWork keys on this name: with KEEP a second worker claiming " +
                "it is silently never scheduled at all."
        )
    }
}
