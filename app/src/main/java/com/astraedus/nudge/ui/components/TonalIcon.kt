package com.astraedus.nudge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A 40dp round icon: the same footprint as an [AppListItem] icon, so a group row or an "Add" row
 * lines its text up with the app rows around it. [primary] marks an action row ("Add apps").
 */
@Composable
fun TonalIcon(icon: ImageVector, primary: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                if (primary) colors.primaryContainer else colors.secondaryContainer,
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (primary) colors.onPrimaryContainer else colors.onSecondaryContainer
        )
    }
}
