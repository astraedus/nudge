package com.astraedus.nudge.service

import android.content.Context
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.logging.NudgeLog
import com.astraedus.nudge.testing.FakeInstalledAppsRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** The one package-change receiver also refreshes the installed-app list the UI counts read. */
class AndroidPackageFactsRefreshTest {

    private fun newFacts(repo: InstalledAppsRepository): AndroidPackageFacts {
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager } returns mockk(relaxed = true)
        return AndroidPackageFacts(
            context = context,
            io = Dispatchers.Unconfined,
            logger = mockk<NudgeLog>(relaxed = true),
            installedApps = dagger.Lazy { repo }
        )
    }

    @Test
    fun `a package change refreshes the installed apps`() {
        val repo = FakeInstalledAppsRepository()
        val before = repo.changes.value
        newFacts(repo).onPackageChanged("com.example.new")
        assertEquals("refreshed exactly once", before + 1, repo.changes.value)
    }

    @Test
    fun `a package change with no package still refreshes`() {
        val repo = FakeInstalledAppsRepository()
        val before = repo.changes.value
        newFacts(repo).onPackageChanged(null)
        assertEquals("refreshed exactly once", before + 1, repo.changes.value)
    }
}
