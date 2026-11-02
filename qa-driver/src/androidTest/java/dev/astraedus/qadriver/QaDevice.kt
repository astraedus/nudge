package dev.astraedus.qadriver

import android.app.UiAutomation
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice

/**
 * The ONE way a qa-driver test gets a [UiDevice] or runs a shell command.
 *
 * Everything below exists for one ordering fact: Android suppresses every other accessibility
 * service while a `UiAutomation` is connected WITHOUT `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`,
 * and Nudge is an accessibility service. `UiDevice.getUiAutomation()` reads
 * `Configurator.getUiAutomationFlags()` on every call, and `Instrumentation.getUiAutomation(flags)`
 * RECONNECTS when the flags differ from the cached connection's. So:
 *
 *  - the flag is set before the first `UiDevice.getInstance` (that constructor already connects);
 *  - nothing here ever calls the zero-arg `instrumentation.uiAutomation`, which would silently
 *    reconnect with flags 0 and suppress Nudge mid-test. Shell commands go through
 *    [UiDevice.executeShellCommand], i.e. the same flagged connection.
 *
 * `-e qa.suppress true` on `am instrument` skips the flag. It exists ONLY for the control run that
 * proves the flag is what keeps Nudge bound (docs/TESTING.md, L6); no real test passes it.
 */
object QaDevice {

    const val NUDGE_PACKAGE = "dev.astraedus.nudge"

    /** Whether this run keeps other accessibility services alive. False only on the control run. */
    val dontSuppress: Boolean by lazy {
        InstrumentationRegistry.getArguments().getString("qa.suppress") != "true"
    }

    val device: UiDevice by lazy {
        if (dontSuppress) {
            Configurator.getInstance().uiAutomationFlags =
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        }
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    }

    /**
     * Runs [command] as the shell uid. No shell interpretation: no pipes, no quotes, no `&&`
     * (`UiAutomation.executeShellCommand` splits on whitespace). Filter in Kotlin instead.
     */
    fun shell(command: String): String = device.executeShellCommand(command)

    /**
     * Where this run's screenshots and logcat captures go: `-e qa.out <dir>` from
     * `scripts/qa-driver.sh`, which pulls the directory afterwards. Under /data/local/tmp because
     * the shell uid writes there with no storage permission, and `screencap`/`logcat -f` run as shell.
     */
    val outDir: String by lazy {
        val dir = InstrumentationRegistry.getArguments().getString("qa.out") ?: "/data/local/tmp/qa-driver/adhoc"
        require(Regex("""/data/local/tmp/qa-driver/[A-Za-z0-9._-]+""").matches(dir)) { "qa.out must be a /data/local/tmp/qa-driver/<run> dir, got '$dir'" }
        shell("mkdir -p $dir")
        dir
    }

    /** Saves a screenshot as `<outDir>/<name>.png`. Evidence, never an oracle. */
    fun screenshot(name: String) {
        shell("screencap -p $outDir/$name.png")
    }

    /** The package the user is looking at ([DeviceText.resumedPackage]). */
    fun foregroundPackage(): String? = DeviceText.resumedPackage(shell("dumpsys activity activities"))

    /**
     * Polls [condition] every [intervalMs] until it holds or [timeoutMs] passes. A wall-clock wait
     * is never the oracle: every wait in these tests ends on an observed fact.
     */
    fun poll(timeoutMs: Long, intervalMs: Long = 500, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (condition()) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(intervalMs)
        }
    }

    fun awaitForeground(pkg: String, timeoutMs: Long): Boolean =
        poll(timeoutMs) { foregroundPackage() == pkg }

    /** The launcher, as the bash harness reached it: Home, then wait for the launcher in front. */
    fun goHome(timeoutMs: Long = 10_000): Boolean {
        device.pressHome()
        return awaitForeground(device.launcherPackageName, timeoutMs)
    }

    /**
     * Opens [pkg] the way a launcher tap does. `monkey` rather than `context.startActivity`: the
     * driver's own process starting another app's activity is a background-activity-start question
     * the app under test never faces.
     */
    fun launch(pkg: String) {
        shell("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
    }
}
