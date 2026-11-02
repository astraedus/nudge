package dev.astraedus.qadriver

/**
 * The logcat oracle: Nudge's own decision lines (`BlockEngine: block package=… reason=…`,
 * `sitting ended … cause=WENT_HOME`), printed because `reset` turns debug logging on.
 *
 * Each wait CLEARS the buffer first and then waits for one fresh line, instead of comparing
 * cumulative counts across a whole case. The ring buffer is a few hundred KB and debug logging is
 * chatty, so over a multi-minute case an old line can rotate out and a "count went up" check can
 * read a fresh event as nothing new. [save] keeps each window's text as evidence before the next
 * clear.
 */
object Logcat {

    fun clear() {
        QaDevice.shell("logcat -c")
    }

    fun dump(): String = QaDevice.shell("logcat -d")

    fun count(needle: String): Int = DeviceText.count(dump(), needle)

    /** Waits for [needle] to appear at least once since the last [clear]. */
    fun await(needle: String, timeoutMs: Long): Boolean =
        QaDevice.poll(timeoutMs, 1_000) { count(needle) > 0 }

    /** Writes the buffer to `<outDir>/<name>.txt` (`logcat -d -f`, written by the shell uid). */
    fun save(name: String) {
        QaDevice.shell("logcat -d -f ${QaDevice.outDir}/$name.txt")
    }
}
