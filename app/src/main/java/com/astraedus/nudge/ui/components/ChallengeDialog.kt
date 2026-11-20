package com.astraedus.nudge.ui.components

import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.CompositionLocalProvider
import com.astraedus.nudge.domain.lock.ChallengeState
import com.astraedus.nudge.domain.lock.StrictModeChallenge

/**
 * No-op [TextToolbar] used to suppress the copy/paste/cut/select-all floating menu inside the
 * challenge TextField. Pasting the challenge string would defeat the whole point of a
 * commitment lock, so we replace the toolbar with one that shows nothing.
 */
private val NoOpTextToolbar = object : TextToolbar {
    override val status: TextToolbarStatus = TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        // Intentionally do nothing — no selection/paste menu.
    }

    override fun hide() {
        // Nothing to hide.
    }
}

/**
 * The soft-input mode the challenge dialog's window runs with: the caller's `SOFT_INPUT_STATE_*`
 * bits kept, the adjust bits set to RESIZE.
 *
 * Why (1.31.1): a Compose dialog window gets `adjust=pan` by default (read off the bench with
 * `dumpsys window`: `sim={adjust=pan}`). Pan slides the whole dialog up until the cursor clears the
 * keyboard, and the bottom of the dialog, where Unlock / End it and "I changed my mind" sit, went
 * under the keyboard as the 48- and 64-character codes wrapped onto more lines. RESIZE instead fits
 * the dialog into the space above the keyboard; the text slot (already a vertical scroll, and
 * AlertDialog's only flexible slot) shrinks and scrolls, and the buttons stay on screen.
 *
 * `SOFT_INPUT_ADJUST_RESIZE` is deprecated from API 30 in favour of window insets, but it is still
 * honoured, it is what Compose's own dialog sets for a window that does not fit system windows, and
 * the inset route would mean `decorFitsSystemWindows = false` plus hand-placing the dialog, a wider
 * change than this bug. Kept as a pure function so the bits are unit-tested.
 */
@Suppress("DEPRECATION")
internal fun challengeSoftInputMode(current: Int): Int =
    (current and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) or
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

/**
 * Makes the dialog window this is composed in resize for the keyboard ([challengeSoftInputMode]).
 * Must be called from INSIDE the dialog's content: there, `LocalView`'s parent is the dialog's
 * layout, which is the [DialogWindowProvider]. Anywhere else it finds no dialog and does nothing.
 */
@Composable
private fun ResizeDialogWindowForKeyboard() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: return
    SideEffect {
        val mode = challengeSoftInputMode(window.attributes.softInputMode)
        if (window.attributes.softInputMode != mode) window.setSoftInputMode(mode)
    }
}

/**
 * Full-screen-style modal that gates a weakening action behind typing a random unlock string.
 *
 * @param target the raw challenge string (no dashes); shown grouped via [StrictModeChallenge.forDisplay].
 * @param prompt short description of what is being unlocked.
 * @param onUnlock invoked with the typed input once it exactly matches [target].
 * @param onCancel invoked when the user backs out ("I changed my mind"), or dismisses.
 */
@Composable
fun ChallengeDialog(
    target: String,
    prompt: String,
    onUnlock: (String) -> Unit,
    onCancel: () -> Unit,
    /** Nuke Mode reuses this dialog for its 64-character emergency code under its own title. */
    title: String = "Strict Mode locked",
    confirmLabel: String = "Unlock"
) {
    val focusManager = LocalFocusManager.current
    var input by remember(target) { mutableStateOf("") }
    // Counter and match both measure dash-stripped characters via the same helper, so the
    // "x/y" progress can never disagree with what verify() actually compares.
    val rawLength = remember(target) { StrictModeChallenge.rawLength(target) }
    val typedLength = StrictModeChallenge.rawLength(input)
    val matches = StrictModeChallenge.verify(input, target)
    val display = remember(target) { StrictModeChallenge.forDisplay(target) }

    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            // Keeps the buttons above the keyboard; the column below scrolls instead.
            ResizeDialogWindowForKeyboard()
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    // Dashes are display-only grouping; input is dash-insensitive (typing
                    // with OR without the dashes both pass), so we must NOT claim "exactly".
                    "Type the code to continue:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                // The target — monospace, larger, selectable-but-not-copyable region is unnecessary;
                // it is plain text the user reads and retypes.
                Text(
                    display,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 20.sp,
                        lineBreak = LineBreak.Simple
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))

                // Disable the paste/copy toolbar so the user can't shortcut the challenge.
                CompositionLocalProvider(LocalTextToolbar provides NoOpTextToolbar) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Unlock code") },
                        singleLine = false,
                        isError = input.isNotEmpty() && !matches && typedLength >= rawLength,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                            imeAction = ImeAction.Done
                        ),
                        // Dismiss the keyboard (and its autocomplete bar) on Done so a stray
                        // suggestion tap can't land between the field and the Unlock button.
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$typedLength/$rawLength",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (input.isNotEmpty() && !matches && typedLength >= rawLength) {
                        Text(
                            "Doesn't match",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onUnlock(input) },
                enabled = matches
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("I changed my mind")
            }
        }
    )
}

/**
 * Convenience host: renders [ChallengeDialog] when [challenge] is non-null, otherwise nothing.
 * Drop one of these into any screen whose ViewModel exposes a Strict Mode challenge so the
 * dialog wiring is a single line and identical everywhere.
 */
@Composable
fun StrictModeChallengeHost(
    challenge: ChallengeState?,
    onVerify: (String) -> Unit,
    onCancel: () -> Unit
) {
    challenge?.let {
        ChallengeDialog(
            target = it.target,
            prompt = it.prompt,
            onUnlock = { input -> onVerify(input) },
            onCancel = onCancel
        )
    }
}
