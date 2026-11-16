package com.astraedus.nudge.data.preferences

import com.astraedus.nudge.domain.nuke.NukeProfile
import com.astraedus.nudge.domain.nuke.NukeProfiles
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** L1: how profiles (#183) are stored, and that a corrupt value fails soft instead of crashing. */
class NukeProfilesCodecTest {

    private val social = setOf("com.instagram.android", "com.zhiliaoapp.musically")

    @Test
    fun `profiles round-trip, in order`() {
        val profiles = listOf(NukeProfile("Social", social), NukeProfile("Browsers", setOf("com.android.chrome")))
        assertEquals(profiles, NukeProfilesCodec.decode(NukeProfilesCodec.encode(profiles)))
    }

    @Test
    fun `nothing stored is no profiles`() {
        assertEquals(emptyList<NukeProfile>(), NukeProfilesCodec.decode(null))
        assertEquals(emptyList<NukeProfile>(), NukeProfilesCodec.decode(""))
    }

    @Test
    fun `a value that is not a list of profiles is no profiles, never a crash`() {
        listOf(
            "not json",
            "{\"name\":\"x\"}",
            "[",
            "42",
            "null",
            "[1, 2, 3]",
            "[null]",
            "[\"Social\"]"
        ).forEach { raw ->
            assertEquals(raw, emptyList<NukeProfile>(), NukeProfilesCodec.decode(raw))
        }
    }

    @Test
    fun `one bad entry is dropped and the rest survive`() {
        val raw = """
            [
              {"name": "Good", "packages": ["com.instagram.android"]},
              {"name": "No apps"},
              {"packages": ["a.b"]},
              {"name": "Wrong type", "packages": "com.instagram.android"},
              {"name": "Mixed", "packages": ["com.android.chrome", 7, null, ""]},
              {"name": 5, "packages": ["a.b"]}
            ]
        """.trimIndent()
        val decoded = NukeProfilesCodec.decode(raw)
        // A name that is not a string is dropped, the same on Android's org.json and the desktop one.
        assertEquals(listOf("Good", "Mixed"), decoded.map { it.name })
        assertEquals(setOf("com.android.chrome"), decoded[1].packages)
    }

    @Test
    fun `a hand-edited value cannot exceed the bounds`() {
        val array = JSONArray()
        (1..50).forEach { i ->
            array.put(org.json.JSONObject().put("name", "P$i").put("packages", JSONArray().put("a.b")))
        }
        array.put(org.json.JSONObject().put("name", "x".repeat(500)).put("packages", JSONArray().put("a.b")))
        val decoded = NukeProfilesCodec.decode(array.toString())
        assertEquals(NukeProfiles.MAX_COUNT, decoded.size)
        decoded.forEach { assertEquals(null, NukeProfiles.nameProblem(it.name)) }
    }
}
