package com.astraedus.nudge.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraedus.nudge.domain.sitting.ShortTrips

internal const val SHORT_TRIPS_ROW_TITLE = "Keep the pass on short trips"

/**
 * What the row says. Pure so both states, and the Strict Mode note, are testable without Compose.
 */
internal fun shortTripsRowSubtitle(minutes: Int, strictModeEnabled: Boolean): String {
    val body = if (minutes <= ShortTrips.OFF) {
        "Off: after you wait, going to the home screen or swiping to another app costs a new wait."
    } else {
        "After you wait, you can leave the app (home screen, another app, screen off) and come " +
            "back within $minutes min without waiting again."
    }
    return if (strictModeEnabled) "$body Turning it on or longer requires the unlock challenge." else body
}

/**
 * The Settings row for "keep the pass on short trips" (issue #110): a switch, plus the length chips
 * while it is on.
 *
 * Every change goes through [onChange], which SettingsScreen routes through the Strict Mode policy
 * (`LockedToggle.SHORT_TRIPS`, weakening = `ShortTrips.isWeakening`), so this row cannot apply a
 * loosening change on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShortTripsRow(minutes: Int, strictModeEnabled: Boolean, onChange: (Int) -> Unit) {
    val on = minutes > ShortTrips.OFF
    val toggle = { onChange(if (on) ShortTrips.OFF else ShortTrips.DEFAULT_MINUTES) }

    ListItem(
        headlineContent = { Text(SHORT_TRIPS_ROW_TITLE) },
        supportingContent = { Text(shortTripsRowSubtitle(minutes, strictModeEnabled)) },
        leadingContent = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
        trailingContent = { Switch(checked = on, onCheckedChange = { toggle() }) },
        modifier = Modifier.clickable { toggle() }
    )

    if (on) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ShortTrips.OPTIONS_MINUTES.forEach { option ->
                FilterChip(
                    selected = minutes == option,
                    onClick = { onChange(option) },
                    label = { Text("$option min") }
                )
            }
        }
    }
}
