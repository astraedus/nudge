package dev.astraedus.qadriver

/**
 * Is Nudge's accessibility service BOUND right now (not merely enabled)? The one fact every
 * qa-driver test depends on. The parse lives in [DeviceText.boundServicesLine], with its reasons.
 */
object A11yState {

    fun boundServicesLine(dump: String): String? = DeviceText.boundServicesLine(dump)

    fun isNudgeBound(dump: String): Boolean = DeviceText.isNudgeBound(dump)

    fun read(): String = QaDevice.shell("dumpsys accessibility")

    fun isBound(): Boolean = isNudgeBound(read())

    /** Polls until the bound state equals [bound], up to [timeoutMs]. */
    fun awaitBound(bound: Boolean, timeoutMs: Long): Boolean =
        QaDevice.poll(timeoutMs, 1_000) { isBound() == bound }
}
