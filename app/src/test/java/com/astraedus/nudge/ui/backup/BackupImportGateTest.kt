package com.astraedus.nudge.ui.backup

import com.astraedus.nudge.data.export.ExportedSettings
import com.astraedus.nudge.data.export.ImportResult
import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.usecase.ExportRulesUseCase
import com.astraedus.nudge.domain.usecase.ImportOutcome
import com.astraedus.nudge.domain.usecase.ImportPreview
import com.astraedus.nudge.domain.usecase.ImportRulesUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The security contract of the import path: **an import must not be a way around Strict Mode.**
 *
 * A backup file carries the user's app SETTINGS as well as their rules, and it is plain,
 * hand-editable JSON — so without this gate, typing `"strictModeEnabled": false` into a text editor
 * and importing the file would release the commitment lock in one tap, on the one write path whose
 * values never pass through the Settings screen's own toggles.
 *
 * These drive the real [BackupViewModel] rather than re-stating its logic, because the thing being
 * pinned is the ORDER of its decisions (ask whether the payload weakens, gate, only then write),
 * and a re-statement would happily keep passing after the ViewModel stopped doing it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupImportGateTest {

    private lateinit var exportRulesUseCase: ExportRulesUseCase
    private lateinit var importRulesUseCase: ImportRulesUseCase
    private lateinit var preferences: NudgePreferences

    /** Every `execute` the ViewModel actually reached — i.e. everything that was WRITTEN. */
    private lateinit var writes: MutableList<ImportResult>

    private val parsed = ImportResult(
        rules = emptyList(),
        groups = emptyList(),
        version = 1,
        settings = ExportedSettings(strictModeEnabled = false)
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        exportRulesUseCase = mockk()
        importRulesUseCase = mockk()
        preferences = mockk()
        writes = mutableListOf()

        coEvery { importRulesUseCase.preview(any()) } returns ImportPreview(parsed, 0)
        coEvery { importRulesUseCase.execute(any()) } answers {
            writes.add(firstArg())
            ImportOutcome(
                importedCount = 0,
                duplicateCount = 0,
                groupsCreated = 0,
                settingsApplied = true
            )
        }
        every { preferences.strictModeChallengeLength } returns flowOf(StrictModeChallenge.DEFAULT_LENGTH)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(strictOn: Boolean, weakens: Boolean): BackupViewModel {
        every { preferences.isStrictModeEnabled } returns flowOf(strictOn)
        coEvery { importRulesUseCase.weakensProtection(any()) } returns weakens
        return BackupViewModel(exportRulesUseCase, importRulesUseCase, preferences)
    }

    /**
     * Drives the real user sequence: pick a file, then confirm the dialog.
     *
     * The wait is not incidental — `previewImport` reads the file on `Dispatchers.IO` (an export
     * carries the user's whole block history, so the read is unbounded work that must stay off the
     * UI thread), which is a real thread pool the test scheduler does not control. Waiting on the
     * state the dialog is driven from is the same thing the user does.
     */
    private suspend fun BackupViewModel.previewThenConfirm() {
        previewImport { "{}" }
        uiState.first { it.importPreview != null }
        confirmImport()
    }

    @Test
    fun `an import that weakens protection writes nothing until the challenge is passed`() = runTest {
        val vm = viewModel(strictOn = true, weakens = true)

        vm.previewThenConfirm()

        assertTrue("nothing may be written before the unlock", writes.isEmpty())
        assertNull("no outcome dialog while the import is pending", vm.uiState.value.importOutcome)
        val challenge = vm.challenge.value
        assertNotNull("a challenge must be raised", challenge)
        assertEquals("Import settings that reduce protection", challenge!!.prompt)
        // The confirmation dialog steps aside so the challenge does not stack on top of it.
        assertNull(vm.uiState.value.importPreview)
    }

    @Test
    fun `passing the challenge lets the same import through`() = runTest {
        val vm = viewModel(strictOn = true, weakens = true)
        vm.previewThenConfirm()

        vm.verifyChallenge(vm.challenge.value!!.target)

        assertEquals(listOf(parsed), writes)
        assertNull(vm.challenge.value)
        assertNotNull(vm.uiState.value.importOutcome)
    }

    @Test
    fun `cancelling the challenge leaves the device untouched`() = runTest {
        val vm = viewModel(strictOn = true, weakens = true)
        vm.previewThenConfirm()

        vm.cancelChallenge()

        assertTrue("a cancelled import must never write", writes.isEmpty())
        assertNull(vm.challenge.value)
        assertNull(vm.uiState.value.importOutcome)
    }

    @Test
    fun `a wrong answer does not let the import through`() = runTest {
        val vm = viewModel(strictOn = true, weakens = true)
        vm.previewThenConfirm()

        vm.verifyChallenge("definitely-wrong-input")

        assertTrue(writes.isEmpty())
        assertNotNull("the challenge stays up so the user can retry", vm.challenge.value)
    }

    /**
     * The common case, and the backward-compatible one: rules-only and history-only backups (every
     * file written before settings existed) weaken nothing, so importing them is as frictionless
     * under Strict Mode as it always was.
     */
    @Test
    fun `an import that weakens nothing is written immediately even under strict mode`() = runTest {
        val vm = viewModel(strictOn = true, weakens = false)

        vm.previewThenConfirm()

        assertEquals(listOf(parsed), writes)
        assertNull("no challenge for a non-weakening import", vm.challenge.value)
        assertNotNull(vm.uiState.value.importOutcome)
    }

    @Test
    fun `with strict mode off a weakening import needs no challenge`() = runTest {
        val vm = viewModel(strictOn = false, weakens = true)

        vm.previewThenConfirm()

        assertEquals(listOf(parsed), writes)
        assertNull(vm.challenge.value)
    }

    /**
     * The gate lives in the ViewModel, so it is only as good as the call sites: a SECOND place that
     * called `execute` would write settings without ever asking whether they weaken protection, and
     * every test above would still pass. This asserts there is exactly one writer and that it is
     * the gated one.
     *
     * Source-level on purpose, in the same spirit as `BlockOverlayWalkAwayContractTest`: the defect
     * class is an added call site, which is not a value any unit test can observe.
     */
    @Test
    fun `there is exactly one call site that writes an import, and it consults the gate`() {
        val mainSources = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.exists() }
            ?: error("main sources not found from working dir ${File("").absolutePath}")

        val writers = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("importRulesUseCase.execute(") }
            .map { it.name }
            .toList()

        assertEquals(
            "every import write must go through the Strict Mode gate in BackupViewModel",
            listOf("BackupViewModel.kt"),
            writers
        )
        assertTrue(
            "the writer must ask whether the payload weakens protection",
            File(mainSources, "com/astraedus/nudge/ui/backup/BackupViewModel.kt")
                .readText()
                .contains("importRulesUseCase.weakensProtection(")
        )
    }

    /** The gate is asked about the file that is actually being confirmed, on every import. */
    @Test
    fun `every import is asked whether it weakens, not just the first`() = runTest {
        val vm = viewModel(strictOn = true, weakens = false)

        vm.previewThenConfirm()
        vm.clearImportOutcome()
        vm.previewThenConfirm()

        assertEquals(2, writes.size)
        assertTrue(writes.all { it === parsed })
    }
}
