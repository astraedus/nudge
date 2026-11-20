package com.astraedus.nudge.ui.components

import android.view.WindowManager.LayoutParams
import com.astraedus.nudge.testing.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.31.0 bug: with the keyboard up, the typed-code dialog (Strict Mode unlock, Nuke's emergency
 * code) slid up under `adjust=pan` and its confirm button sat under the keyboard (bench:
 * `sim={adjust=pan}`, the dialog's bottom 76 px below the keyboard top at 32 of 48 characters).
 *
 * Layer: L1 for the mode bits ([challengeSoftInputMode]) plus one L4 shape check that the shared
 * dialog applies them to its own window; the rendered half (buttons above the keyboard's top, from
 * the uiautomator dump) is `scripts/device-qa.sh challenge-ime` on the bench Pixel.
 */
@Suppress("DEPRECATION")
class ChallengeDialogKeyboardTest {

    private fun adjust(mode: Int) = mode and LayoutParams.SOFT_INPUT_MASK_ADJUST
    private fun state(mode: Int) = mode and LayoutParams.SOFT_INPUT_MASK_STATE

    @Test
    fun `whatever the window starts with, the dialog resizes for the keyboard`() {
        for (start in listOf(
            LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED,
            LayoutParams.SOFT_INPUT_ADJUST_PAN, // what the bench's dialog window had
            LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
            LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )) {
            assertEquals("start=$start", LayoutParams.SOFT_INPUT_ADJUST_RESIZE, adjust(challengeSoftInputMode(start)))
        }
    }

    @Test
    fun `the keyboard visibility the window asked for is kept`() {
        for (s in listOf(
            LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED,
            LayoutParams.SOFT_INPUT_STATE_HIDDEN,
            LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )) {
            val out = challengeSoftInputMode(s or LayoutParams.SOFT_INPUT_ADJUST_PAN)
            assertEquals("state=$s", s, state(out))
        }
    }

    @Test
    fun `the shared challenge dialog applies it to its own window`() {
        val code = SourceScan.code(SourceScan.kotlinFile("com/astraedus/nudge/ui/components/ChallengeDialog"))
        val dialog = SourceScan.bodyOf(code, "fun ChallengeDialog(")
        assertTrue(
            "ChallengeDialog must call ResizeDialogWindowForKeyboard() inside its AlertDialog content " +
                "(1.31.0: the confirm button sat under the keyboard)",
            Regex("""text\s*=\s*\{[^}]*ResizeDialogWindowForKeyboard\(\)""").containsMatchIn(dialog)
        )
    }
}
