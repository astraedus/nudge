package com.astraedus.nudge.service

import android.content.Context
import com.astraedus.nudge.data.repository.InstalledAppsRepository
import com.astraedus.nudge.domain.logging.NudgeLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
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
        val repo = mockk<InstalledAppsRepository>(relaxed = true)
        newFacts(repo).onPackageChanged("com.example.new")
        verify(exactly = 1) { repo.refresh() }
    }

    @Test
    fun `a package change with no package still refreshes`() {
        val repo = mockk<InstalledAppsRepository>(relaxed = true)
        newFacts(repo).onPackageChanged(null)
        verify(exactly = 1) { repo.refresh() }
    }
}
