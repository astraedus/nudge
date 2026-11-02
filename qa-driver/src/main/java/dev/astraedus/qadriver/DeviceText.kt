package dev.astraedus.qadriver

/**
 * Every parse of a shell command's text output that a qa-driver test asserts on, kept pure so
 * `src/test` can pin each one against a captured sample (a wrong parse here reads as an app bug on
 * the bench, which is how the bash harness invented several). No Android imports: JVM tests run
 * these without a device.
 */
object DeviceText {

    /**
     * The `Bound services:{…}` line's contents from `dumpsys accessibility`, or null if the dump had
     * no such line.
     *
     * Line-exact on purpose: the dump prints `Bound services:{…}` and, on the very next line,
     * `Enabled services:{{dev.astraedus.nudge/…NudgeAccessibilityService}}`. The bound entry names
     * the service by its LABEL ("Nudge - App Blocker"), the enabled entry by its component, so a
     * "grep a few lines after `Bound services` for the class name" check matches the ENABLED line
     * and reads "bound" during exactly the suppression it was meant to detect.
     */
    fun boundServicesLine(dump: String): String? =
        dump.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("Bound services:") }
            ?.removePrefix("Bound services:")

    /** True when a bound accessibility service's label starts with Nudge's. */
    fun isNudgeBound(dump: String): Boolean =
        boundServicesLine(dump)?.contains("label=Nudge") == true

    /**
     * The package of `mResumedActivity` in `dumpsys activity activities`, i.e. what the user is
     * looking at. Null when nothing is resumed (mid-transition).
     */
    fun resumedPackage(dump: String): String? =
        dump.lineSequence()
            .firstOrNull { it.contains("mResumedActivity") }
            ?.let { Regex("""\bu\d+ ([^/\s]+)/""").find(it)?.groupValues?.get(1) }

    /**
     * The result data `am broadcast` printed (`Broadcast completed: result=0, data="…"`), or null
     * when the receiver set none. The data is JSON with its own quotes and `am` does not escape
     * them, so the value runs from `data="` to the LAST quote on that line.
     */
    fun broadcastData(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.startsWith("Broadcast completed:") } ?: return null
        val start = line.indexOf("data=\"").takeIf { it >= 0 } ?: return null
        val end = line.lastIndexOf('"')
        return if (end > start + 6) line.substring(start + 6, end) else null
    }

    /** Nudge's notifications in `dumpsys notification --noredact`: how many, and the ongoing one's last post. */
    data class NotifRecords(val count: Int, val ongoingUpdateTimeMs: Long?)

    /**
     * The count spans EVERY notification of [pkg] (one appearing or vanishing while idle is a
     * finding in itself). The timestamp is the ONGOING one's: id 1, NudgeMonitorService's status
     * notification. Nudge posts others (2 protection alert, 3 bounce check-in, 4 usage reminder) and
     * `dumpsys` promises no order between them: on 2026-10-15 a lingering id=4 was listed before
     * id=1 and "first Nudge record" read the reminder's never-moving timestamp. So each record
     * header re-decides whether its lines count, and an id=1 record with no timestamp line cannot
     * borrow its neighbour's.
     */
    fun notifRecords(dump: String, pkg: String): NotifRecords {
        val header = Regex("""NotificationRecord\(.*\bpkg=${Regex.escape(pkg)} """)
        val ongoing = Regex("""NotificationRecord\(.*\bpkg=${Regex.escape(pkg)} user=\S* id=1 """)
        var count = 0
        var want = false
        var update: Long? = null
        for (line in dump.lineSequence()) {
            if (line.contains("NotificationRecord(")) {
                if (header.containsMatchIn(line)) count++
                want = update == null && ongoing.containsMatchIn(line)
                continue
            }
            if (want) {
                Regex("""mUpdateTimeMs=(\d+)""").find(line)?.let {
                    update = it.groupValues[1].toLong()
                    want = false
                }
            }
        }
        return NotifRecords(count, update)
    }

    /**
     * The mode `appops get <pkg> <op>` reports (`allow`, `ignore`, `default`, …), or null when the op
     * was never set on the package (`No operations.`), which `appops set … default` restores.
     */
    fun appopMode(output: String): String? =
        output.lineSequence().firstOrNull()?.let { Regex("""^[A-Z_]+: ([a-z]+)""").find(it.trim())?.groupValues?.get(1) }

    /** `mWakefulness=` from `dumpsys power` (`Awake`, `Asleep`, `Dozing`, `Dreaming`). */
    fun wakefulness(dump: String): String? =
        Regex("""mWakefulness=([A-Za-z]+)""").find(dump)?.groupValues?.get(1)

    /** Non-overlapping occurrences of [needle], the logcat oracle's unit. */
    fun count(text: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var n = 0
        var from = text.indexOf(needle)
        while (from >= 0) {
            n++
            from = text.indexOf(needle, from + needle.length)
        }
        return n
    }

    /** "`<n>` blocked · `<m>` walked away" from the dashboard's week summary text. */
    fun weekSummary(text: String): Pair<Int, Int>? =
        Regex("""(\d+) blocked · (\d+) walked away""").find(text)
            ?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
}
