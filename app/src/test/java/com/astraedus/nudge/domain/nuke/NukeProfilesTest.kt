package com.astraedus.nudge.domain.nuke

import com.astraedus.nudge.domain.nuke.NukeProfiles.Edit
import com.astraedus.nudge.domain.nuke.NukeProfiles.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** L1: saved Nuke profiles (#183), every edit and every bound, as pure functions. */
class NukeProfilesTest {

    private val social = setOf("com.instagram.android", "com.zhiliaoapp.musically")
    private val browsers = setOf("com.android.chrome")

    private fun done(edit: Edit): List<NukeProfile> = (edit as Edit.Done).profiles
    private fun refused(edit: Edit): Refusal = (edit as Edit.Refused).reason

    // --- save ------------------------------------------------------------------------------------

    @Test
    fun `saving adds a profile under its trimmed, collapsed name`() {
        val profiles = done(NukeProfiles.save(emptyList(), "  Work   hours ", social))
        assertEquals(listOf(NukeProfile("Work hours", social)), profiles)
    }

    @Test
    fun `saving under a name that exists updates that profile in place, keeping its spelling`() {
        val start = listOf(NukeProfile("Work", social), NukeProfile("Night", browsers))
        val profiles = done(NukeProfiles.save(start, "work", browsers))
        assertEquals(listOf(NukeProfile("Work", browsers), NukeProfile("Night", browsers)), profiles)
    }

    @Test
    fun `a blank or too-long name is refused`() {
        assertEquals(Refusal.BLANK_NAME, refused(NukeProfiles.save(emptyList(), "   ", social)))
        assertEquals(Refusal.BLANK_NAME, refused(NukeProfiles.save(emptyList(), "", social)))
        val longest = "x".repeat(NukeProfiles.MAX_NAME_LENGTH)
        assertEquals(longest, done(NukeProfiles.save(emptyList(), longest, social)).single().name)
        assertEquals(
            Refusal.NAME_TOO_LONG,
            refused(NukeProfiles.save(emptyList(), longest + "x", social))
        )
    }

    @Test
    fun `an empty list cannot be saved, blanks inside a list do not count`() {
        assertEquals(Refusal.EMPTY_LIST, refused(NukeProfiles.save(emptyList(), "None", emptySet())))
        assertEquals(Refusal.EMPTY_LIST, refused(NukeProfiles.save(emptyList(), "None", setOf(" ", ""))))
    }

    @Test
    fun `the count is capped, but an update at the cap still works`() {
        val full = (1..NukeProfiles.MAX_COUNT).map { NukeProfile("P$it", social) }
        assertEquals(Refusal.TOO_MANY, refused(NukeProfiles.save(full, "One more", social)))
        assertEquals(browsers, done(NukeProfiles.save(full, "P3", browsers))[2].packages)
    }

    // --- rename and delete -----------------------------------------------------------------------

    @Test
    fun `rename keeps the apps and the position`() {
        val start = listOf(NukeProfile("Work", social), NukeProfile("Night", browsers))
        val profiles = done(NukeProfiles.rename(start, "work", " Deep  work "))
        assertEquals(listOf(NukeProfile("Deep work", social), NukeProfile("Night", browsers)), profiles)
    }

    @Test
    fun `rename refuses a clash and a bad name, but allows changing only the case`() {
        val start = listOf(NukeProfile("Work", social), NukeProfile("Night", browsers))
        assertEquals(Refusal.NAME_TAKEN, refused(NukeProfiles.rename(start, "Work", "NIGHT")))
        assertEquals(Refusal.BLANK_NAME, refused(NukeProfiles.rename(start, "Work", " ")))
        assertEquals(Refusal.NOT_FOUND, refused(NukeProfiles.rename(start, "Gone", "New")))
        assertEquals("WORK", done(NukeProfiles.rename(start, "Work", "WORK"))[0].name)
    }

    @Test
    fun `delete removes exactly that profile`() {
        val start = listOf(NukeProfile("Work", social), NukeProfile("Night", browsers))
        assertEquals(listOf(NukeProfile("Night", browsers)), done(NukeProfiles.delete(start, "WORK")))
        assertEquals(Refusal.NOT_FOUND, refused(NukeProfiles.delete(start, "Gone")))
    }

    // --- reading back ----------------------------------------------------------------------------

    @Test
    fun `sanitize drops bad names, empty profiles and duplicates, and caps the count`() {
        val decoded = listOf(
            NukeProfile("Good", social),
            NukeProfile(" ", social),
            NukeProfile("x".repeat(NukeProfiles.MAX_NAME_LENGTH + 1), social),
            NukeProfile("Empty", setOf("  ")),
            NukeProfile("good", browsers)
        ) + (1..20).map { NukeProfile("P$it", browsers) }
        val clean = NukeProfiles.sanitize(decoded)
        assertEquals(NukeProfiles.MAX_COUNT, clean.size)
        assertEquals(NukeProfile("Good", social), clean.first())
        assertEquals("the first of two same-named profiles wins", 1, clean.count { it.name.equals("good", true) })
    }

    @Test
    fun `the selected profile is the one whose apps are exactly the list`() {
        val profiles = listOf(NukeProfile("Social", social), NukeProfile("Browsers", browsers))
        assertSame(profiles[1], NukeProfiles.matching(profiles, browsers))
        assertNull("a superset is not a match", NukeProfiles.matching(profiles, social + browsers))
        assertNull(NukeProfiles.matching(profiles, emptySet()))
    }

    /**
     * A profile is raw like the working list: it may hold an essential app, and enforcement still
     * goes through the floor. So a list started from a profile locks exactly the profile's apps,
     * minus the floor.
     */
    @Test
    fun `a profile holding an essential app still cannot make it blockable`() {
        val floor = setOf("com.android.dialer")
        val profile = done(NukeProfiles.save(emptyList(), "Sneaky", social + floor)).single()
        val started = NukeState(active = true, packages = profile.packages, keyHash = "h")
        assertEquals(social, NukePolicy.enforceable(started.packages, floor))
        assertEquals(2, NukePolicy.nukedCount(started, floor))
    }
}
