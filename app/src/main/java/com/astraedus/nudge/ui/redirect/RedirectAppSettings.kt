package com.astraedus.nudge.ui.redirect

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astraedus.nudge.data.repository.RedirectAppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Holds Settings' [RedirectAppController]. No block on screen, so nothing extra is excluded. */
@HiltViewModel
class RedirectAppSettingsViewModel @Inject constructor(
    repository: RedirectAppRepository
) : ViewModel() {
    val controller = RedirectAppController(repository, viewModelScope)

    init {
        controller.refresh()
    }
}

/**
 * The Settings row for the redirect app, so the feature can be found, changed and cleared without
 * first being blocked. Opens the same picker the overlay's bubble does.
 */
@Composable
fun RedirectAppSettingsRow(controller: RedirectAppController) {
    val target = controller.target
    ListItem(
        headlineContent = { Text("Redirect app") },
        supportingContent = {
            Text(
                if (target == null) {
                    "Offer one better app on every block screen, like a to-do list or a reader."
                } else {
                    "${target.appName} is offered on every block screen. Tap to change or remove."
                }
            )
        },
        leadingContent = {
            val icon = target?.icon
            if (icon != null) {
                val bitmap = remember(icon) { icon.toBitmap(96, 96).asImageBitmap() }
                Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
            } else {
                Icon(Icons.Outlined.AddCircleOutline, contentDescription = null)
            }
        },
        modifier = Modifier.clickable { controller.openPicker() }
    )
    RedirectAppPickerHost(controller)
}
