package com.astraedus.nudge.service

import android.content.pm.ApplicationInfo
import com.astraedus.nudge.domain.packages.AppCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidPackageFactsCategoryTest {
    private val mapping = mapOf(
        ApplicationInfo.CATEGORY_GAME to AppCategory.GAME,
        ApplicationInfo.CATEGORY_AUDIO to AppCategory.AUDIO,
        ApplicationInfo.CATEGORY_VIDEO to AppCategory.VIDEO,
        ApplicationInfo.CATEGORY_IMAGE to AppCategory.IMAGE,
        ApplicationInfo.CATEGORY_SOCIAL to AppCategory.SOCIAL,
        ApplicationInfo.CATEGORY_NEWS to AppCategory.NEWS,
        ApplicationInfo.CATEGORY_MAPS to AppCategory.MAPS,
        ApplicationInfo.CATEGORY_PRODUCTIVITY to AppCategory.PRODUCTIVITY
    )

    @Test
    fun eachConstantMapsToItsEnum() {
        mapping.forEach { (constant, expected) -> assertEquals(expected, categoryOf(constant)) }
    }

    @Test
    fun undefinedAndUnknownMapToUndefined() {
        listOf(ApplicationInfo.CATEGORY_UNDEFINED, 8, 99, -5).forEach {
            assertEquals("category $it", AppCategory.UNDEFINED, categoryOf(it))
        }
    }

    @Test
    fun everyNonUndefinedEnumIsReachableFromExactlyOneConstant() {
        assertEquals(AppCategory.values().size - 1, mapping.size)
        val reached = mapping.keys.map(::categoryOf)
        AppCategory.values().filter { it != AppCategory.UNDEFINED }.forEach { e ->
            assertEquals("$e", 1, reached.count { it == e })
        }
    }
}
