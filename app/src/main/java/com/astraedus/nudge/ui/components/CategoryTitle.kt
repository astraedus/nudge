package com.astraedus.nudge.ui.components

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.astraedus.nudge.domain.packages.AppCategory
import com.astraedus.nudge.service.androidCategoryOf

/**
 * A category's name in the phone's language, as Android itself names it ("Social & Communication"):
 * `ApplicationInfo.getCategoryTitle` (API 26, our minSdk). Falls back to the enum name in plain case
 * for a category the platform has no title for.
 */
fun categoryTitle(context: Context, category: AppCategory): String =
    ApplicationInfo.getCategoryTitle(context, androidCategoryOf(category))?.toString()
        ?: category.name.lowercase().replaceFirstChar { it.uppercase() }

@Composable
fun rememberCategoryTitle(category: AppCategory): String {
    val context = LocalContext.current
    return remember(context, category) { categoryTitle(context, category) }
}

/**
 * The first half of a two-part category title, for a tag that sits on every row: "Social &
 * Communication" is "Social", "Music and audio" is "Music". A one-part title is returned whole. On
 * the bench the full title ellipsised on every member row ("Social & Communica…").
 */
fun shortCategoryTitle(title: String): String =
    SHORT_TITLE_SEPARATORS.fold(title) { acc, sep -> acc.substringBefore(sep) }.trim().ifEmpty { title }

private val SHORT_TITLE_SEPARATORS = listOf(" & ", " and ")

@Composable
fun rememberShortCategoryTitle(category: AppCategory): String =
    shortCategoryTitle(rememberCategoryTitle(category))
