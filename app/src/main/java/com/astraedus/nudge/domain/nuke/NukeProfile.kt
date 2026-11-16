package com.astraedus.nudge.domain.nuke

/**
 * A saved, named set of apps for Nuke (#183): "Calls only", "Social and browsers".
 *
 * A profile is a PRESET, never a live reference. Applying one (only while Nuke is off) copies its
 * [packages] into the working list, and a running Nuke reads only the working list, so renaming,
 * updating or deleting a profile can never change what a running Nuke blocks. [packages] is RAW like
 * the working list: it may hold a package the safety floor refuses, and enforcement still goes
 * through [NukePolicy], so a profile can never make an essential app blockable.
 */
data class NukeProfile(val name: String, val packages: Set<String>)

/** Every profile decision, as pure functions over the stored list. Order is the order saved. */
object NukeProfiles {

    /** Long enough for "Comms on, social off", short enough to stay one chip. */
    const val MAX_NAME_LENGTH = 30

    /** A row or two of chips, not a library. */
    const val MAX_COUNT = 10

    /** Why a profile edit was refused. */
    enum class Refusal { BLANK_NAME, NAME_TOO_LONG, NAME_TAKEN, TOO_MANY, EMPTY_LIST, NOT_FOUND }

    /** The outcome of an edit: the new list, or why nothing changed. */
    sealed interface Edit {
        data class Done(val profiles: List<NukeProfile>) : Edit
        data class Refused(val reason: Refusal) : Edit
    }

    /** A name as stored: trimmed, inner runs of whitespace collapsed. */
    fun normalizeName(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")

    /** The profile called [name] (case-insensitive), or null. */
    fun find(profiles: List<NukeProfile>, name: String): NukeProfile? {
        val key = normalizeName(name)
        return profiles.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }

    /** Why [name] cannot be a profile name at all (ignoring duplicates), or null when it can. */
    fun nameProblem(name: String): Refusal? {
        val normalized = normalizeName(name)
        return when {
            normalized.isEmpty() -> Refusal.BLANK_NAME
            normalized.length > MAX_NAME_LENGTH -> Refusal.NAME_TOO_LONG
            else -> null
        }
    }

    /**
     * Save [packages] as [name]. A name that already exists (case-insensitive) UPDATES that profile's
     * apps in place, keeping its spelling and position: the save dialog says "Update" when it will.
     */
    fun save(profiles: List<NukeProfile>, name: String, packages: Set<String>): Edit {
        nameProblem(name)?.let { return Edit.Refused(it) }
        val apps = cleanPackages(packages)
        if (apps.isEmpty()) return Edit.Refused(Refusal.EMPTY_LIST)
        val existing = find(profiles, name)
        if (existing != null) {
            return Edit.Done(profiles.map { if (it === existing) it.copy(packages = apps) else it })
        }
        if (profiles.size >= MAX_COUNT) return Edit.Refused(Refusal.TOO_MANY)
        return Edit.Done(profiles + NukeProfile(normalizeName(name), apps))
    }

    /** Rename [oldName] to [newName]. Changing only the case of a name is allowed. */
    fun rename(profiles: List<NukeProfile>, oldName: String, newName: String): Edit {
        val target = find(profiles, oldName) ?: return Edit.Refused(Refusal.NOT_FOUND)
        nameProblem(newName)?.let { return Edit.Refused(it) }
        val clash = find(profiles, newName)
        if (clash != null && clash !== target) return Edit.Refused(Refusal.NAME_TAKEN)
        val renamed = target.copy(name = normalizeName(newName))
        return Edit.Done(profiles.map { if (it === target) renamed else it })
    }

    fun delete(profiles: List<NukeProfile>, name: String): Edit {
        val target = find(profiles, name) ?: return Edit.Refused(Refusal.NOT_FOUND)
        return Edit.Done(profiles.filter { it !== target })
    }

    /**
     * Whatever was decoded from storage, made safe: bad names and empty profiles dropped, duplicates
     * after the first dropped, at most [MAX_COUNT]. Never throws; the worst case is an empty list.
     */
    fun sanitize(decoded: List<NukeProfile>): List<NukeProfile> {
        val out = mutableListOf<NukeProfile>()
        for (profile in decoded) {
            if (out.size >= MAX_COUNT) break
            if (nameProblem(profile.name) != null) continue
            val apps = cleanPackages(profile.packages)
            if (apps.isEmpty()) continue
            if (find(out, profile.name) != null) continue
            out += NukeProfile(normalizeName(profile.name), apps)
        }
        return out
    }

    /** The profile the working list currently equals exactly, or null (an honest "none"). */
    fun matching(profiles: List<NukeProfile>, workingList: Set<String>): NukeProfile? =
        profiles.firstOrNull { it.packages == workingList }

    private fun cleanPackages(packages: Set<String>): Set<String> =
        packages.map { it.trim() }.filterTo(linkedSetOf()) { it.isNotEmpty() }
}
