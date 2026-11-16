package com.astraedus.nudge.data.preferences

import com.astraedus.nudge.domain.nuke.NukeProfile
import com.astraedus.nudge.domain.nuke.NukeProfiles
import org.json.JSONArray
import org.json.JSONObject

/**
 * How Nuke profiles (#183) are stored: one JSON string, `[{"name": "...", "packages": ["..."]}]`.
 *
 * Decoding FAILS SOFT, entry by entry: an unreadable value is no profiles, an unreadable entry is
 * dropped, and everything left goes through [NukeProfiles.sanitize]. It never throws, and it lives on
 * its own key, so a corrupt value can never touch the working list or the running Nuke.
 */
internal object NukeProfilesCodec {

    fun encode(profiles: List<NukeProfile>): String {
        val array = JSONArray()
        NukeProfiles.sanitize(profiles).forEach { profile ->
            array.put(
                JSONObject()
                    .put(NAME, profile.name)
                    .put(PACKAGES, JSONArray().apply { profile.packages.sorted().forEach { put(it) } })
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<NukeProfile> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val decoded = (0 until array.length()).mapNotNull { i ->
            // Type checks by hand, never getString: Android's org.json turns the number 5 into the
            // name "5" while the desktop one throws, and a stored value must read the same on both.
            val entry = array.opt(i) as? JSONObject ?: return@mapNotNull null
            val name = entry.opt(NAME) as? String ?: return@mapNotNull null
            val packages = entry.opt(PACKAGES) as? JSONArray ?: return@mapNotNull null
            NukeProfile(
                name = name,
                packages = (0 until packages.length()).mapNotNullTo(linkedSetOf()) { j ->
                    packages.opt(j) as? String
                }
            )
        }
        return NukeProfiles.sanitize(decoded)
    }

    private const val NAME = "name"
    private const val PACKAGES = "packages"
}
