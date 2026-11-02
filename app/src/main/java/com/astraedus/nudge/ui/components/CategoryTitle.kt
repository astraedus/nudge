package com.astraedus.nudge.ui.components

import com.astraedus.nudge.domain.packages.AppCategory

/**
 * A category's name on a chip: "Social & Communication", "Music & Audio". Nudge's own titles, in one
 * style (Title Case, joined by "&"). Android's `ApplicationInfo.getCategoryTitle` was used first, and
 * it named seven categories "X & Y" and one "Music and audio", so one chip read differently from the
 * other seven. The app is English-only, so its own strings lose nothing.
 *
 * UNDEFINED is never offered as a chip; it is named for exhaustiveness only.
 */
fun categoryTitle(category: AppCategory): String = when (category) {
    AppCategory.SOCIAL -> "Social & Communication"
    AppCategory.VIDEO -> "Movies & Video"
    AppCategory.GAME -> "Games"
    AppCategory.NEWS -> "News & Magazines"
    AppCategory.AUDIO -> "Music & Audio"
    AppCategory.IMAGE -> "Photos & Images"
    AppCategory.MAPS -> "Maps & Navigation"
    AppCategory.PRODUCTIVITY -> "Productivity"
    AppCategory.UNDEFINED -> "Other"
}

/**
 * The tag on a member row: the first part of [categoryTitle] ("Social", "Music"). The full title
 * ellipsised on every row on the bench ("Social & Communica…").
 */
fun shortCategoryTitle(category: AppCategory): String = categoryTitle(category).substringBefore(" & ")
