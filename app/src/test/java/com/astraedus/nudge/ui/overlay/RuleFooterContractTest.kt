package com.astraedus.nudge.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-scan contract (L4-style, over the CLASS): every `*Content.kt` in the overlay package that
 * renders a rule line goes through the shared [RuleFooter], so the four bodies cannot drift, and
 * the All apps additions stay wired in the activity.
 */
class RuleFooterContractTest {

    private fun overlayDir(): File =
        listOf(
            File("src/main/java/com/astraedus/nudge/ui/overlay"),
            File("app/src/main/java/com/astraedus/nudge/ui/overlay")
        ).firstOrNull { it.isDirectory } ?: error("overlay source dir not found")

    private fun code(source: String): String =
        source.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    private val activity by lazy { code(File(overlayDir(), "BlockOverlayActivity.kt").readText()) }

    @Test
    fun `every content that mentions a rule goes through RuleFooter and never prints the line itself`() {
        val contents = overlayDir().listFiles { f -> f.name.endsWith("Content.kt") }!!
            .filter { code(it.readText()).contains("ruleName") }
        assertEquals(
            setOf("HardBlockContent.kt", "DelayContent.kt", "HoldContent.kt", "BreathingContent.kt"),
            contents.map { it.name }.toSet()
        )
        contents.forEach { file ->
            val src = code(file.readText())
            assertTrue("${file.name} must render RuleFooter", src.contains("RuleFooter(ruleName = ruleName, until = untilText)"))
            assertFalse("${file.name} must not print its own rule line", src.contains("\"Rule: "))
        }
    }

    @Test
    fun `the footer is the only place that prints the rule line`() {
        val printers = overlayDir().listFiles { f -> f.name.endsWith(".kt") }!!
            .filter { code(it.readText()).contains("\"Rule: ") }
        assertEquals(listOf("RuleFooter.kt"), printers.map { it.name })
    }

    @Test
    fun `the activity hides the bubble for All apps and exits through the one walk-away path`() {
        assertTrue(activity.contains("OverlayBlockInfo.showRedirectBubble(isAllApps)"))
        assertTrue(activity.contains("AllowedAppsRow(apps = allowedApps.value, onOpen = { navigateHome(redirectPackage = it) })"))
    }

    /**
     * The allowed list is the set the engine allows: the allowed groups' category members are filled
     * in on the device through the one resolution (`DeviceGroupMembers`) and the one ordering
     * (`AllAppsBlock.allowedPackagesOf`, its L1 test is `AllAppsBlockTest`). The service must send
     * the group ids for that to happen at all.
     */
    @Test
    fun `the allowed list fills in category members from the group ids the service sends`() {
        assertTrue(activity.contains("if (isAllApps) fillInGroupMembers(intent, blockPackages)"))
        assertTrue(activity.contains("deviceGroupMembers.of(groupIds)"))
        assertTrue(activity.contains("AllAppsBlock.allowedPackagesOf(own, groupIds)"))
        val serviceDir = File(overlayDir(), "../../service")
        val service = code(File(serviceDir, "NudgeAccessibilityService.kt").readText())
        assertTrue(
            Regex("""BlockOverlayActivity\.EXTRA_ALL_APPS_EXCEPT_GROUP_IDS,\s*it\.exceptGroupIds\.toLongArray\(\)""")
                .containsMatchIn(service)
        )
    }

    @Test
    fun `the activity feeds the footer the All apps title and the until text`() {
        assertTrue(activity.contains("OverlayBlockInfo.visibleRuleName("))
        assertTrue(activity.contains("OverlayBlockInfo.untilLine("))
        assertEquals(4, Regex("""ruleName = visibleRuleName""").findAll(activity).count())
        assertEquals(4, Regex("""untilText = untilText""").findAll(activity).count())
    }

    @Test
    fun `the daily pass is still rendered by every timed body and the hard block`() {
        listOf("HardBlockContent.kt", "DelayContent.kt", "HoldContent.kt", "BreathingContent.kt").forEach {
            assertTrue("$it keeps the daily pass", code(File(overlayDir(), it).readText()).contains("EmergencyPassAction("))
        }
    }
}
