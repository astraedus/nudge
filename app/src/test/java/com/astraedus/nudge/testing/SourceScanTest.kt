package com.astraedus.nudge.testing

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** SourceScan reads repo source only: no `..` climb, no absolute path, outside the repo root. */
class SourceScanTest {

    @Test
    fun `parent traversal is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SourceScan.rootFile("../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { SourceScan.appFile("../../../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { SourceScan.mainFile("../../../../../etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { SourceScan.kotlinFile("../../../../../../etc/passwd") }
    }

    @Test
    fun `traversal that normalises back inside the root is allowed`() {
        assertEquals(
            SourceScan.rootFile("settings.gradle.kts"),
            SourceScan.rootFile("app/../settings.gradle.kts")
        )
    }

    @Test
    fun `absolute paths are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SourceScan.rootFile("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { SourceScan.appFile("/etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) {
            SourceScan.resolveInside(SourceScan.projectRoot, File(SourceScan.projectRoot, "app").absolutePath)
        }
    }

    @Test
    fun `a sibling directory sharing the root prefix is not inside it`() {
        val root = SourceScan.projectRoot
        assertThrows(IllegalArgumentException::class.java) {
            SourceScan.resolveInside(File(root, "app"), "../app-evil/x")
        }
    }

    @Test
    fun `a real source file resolves`() {
        assertTrue(SourceScan.mainFile("AndroidManifest.xml").isFile)
    }
}
