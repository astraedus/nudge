package com.astraedus.nudge.ui.backup

import com.astraedus.nudge.data.preferences.NudgePreferences
import com.astraedus.nudge.domain.lock.StrictModeChallenge
import com.astraedus.nudge.domain.usecase.ExportRulesUseCase
import com.astraedus.nudge.domain.usecase.ImportRulesUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Saving a backup to the device, and sharing one.
 *
 * The defect these exist for is not a crash, it is a silence: a save that did not happen while the
 * user believes it did. A backup you think you have is worse than one you know you do not, so a
 * refused write has to reach the screen as a failure, not as nothing.
 *
 * Both paths hand the platform work (writing through a `Uri`, opening the share sheet) in as a
 * lambda, which is what keeps them JVM-testable at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private lateinit var exportRulesUseCase: ExportRulesUseCase
    private lateinit var importRulesUseCase: ImportRulesUseCase
    private lateinit var preferences: NudgePreferences
    private lateinit var viewModel: BackupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        exportRulesUseCase = mockk()
        importRulesUseCase = mockk()
        preferences = mockk()
        coEvery { exportRulesUseCase.invoke() } returns BACKUP_JSON
        every { preferences.isStrictModeEnabled } returns flowOf(false)
        every { preferences.strictModeChallengeLength } returns
            flowOf(StrictModeChallenge.DEFAULT_LENGTH)
        viewModel = BackupViewModel(exportRulesUseCase, importRulesUseCase, preferences)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saving writes the exported backup and says it landed`() = runTest {
        val written = mutableListOf<String>()

        viewModel.saveBackup { json -> written.add(json); true }

        assertEquals(listOf(BACKUP_JSON), written)
        assertEquals(BackupViewModel.SAVE_SUCCESS_MESSAGE, viewModel.uiState.value.saveMessage)
    }

    /**
     * A storage provider can refuse the write (a read-only folder, a revoked permission, a full
     * disk). The user must be told, or they walk away believing a backup exists.
     */
    @Test
    fun `a write that did not land is reported as a failure`() = runTest {
        viewModel.saveBackup { false }

        assertEquals(BackupViewModel.SAVE_FAILURE_MESSAGE, viewModel.uiState.value.saveMessage)
    }

    @Test
    fun `dismissing the save message clears it`() = runTest {
        viewModel.saveBackup { true }

        viewModel.dismissSaveMessage()

        assertNull(viewModel.uiState.value.saveMessage)
    }

    @Test
    fun `sharing hands the same exported backup to the share sheet`() = runTest {
        val shared = mutableListOf<String>()

        viewModel.shareBackup { json -> shared.add(json) }

        assertEquals(listOf(BACKUP_JSON), shared)
        assertNull("sharing reports nothing -- the share sheet is the feedback", viewModel.uiState.value.saveMessage)
    }

    /**
     * Backing out of the file picker must cost nothing. The screen simply never calls back, and
     * serializing an unbounded history is real work -- so the export must not have run by the time
     * a destination exists.
     */
    @Test
    fun `nothing is exported until there is somewhere to put it`() = runTest {
        coVerify(exactly = 0) { exportRulesUseCase.invoke() }

        viewModel.saveBackup { true }

        coVerify(exactly = 1) { exportRulesUseCase.invoke() }
    }

    private companion object {
        const val BACKUP_JSON = """{"version":1,"rules":[]}"""
    }
}
