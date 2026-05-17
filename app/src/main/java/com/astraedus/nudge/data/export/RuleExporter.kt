package com.astraedus.nudge.data.export

import com.astraedus.nudge.data.db.entity.AppGroup
import com.astraedus.nudge.data.db.entity.AppGroupMember
import com.astraedus.nudge.data.db.entity.BlockRule
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class ImportResult(
    val rules: List<ExportedRule>,
    val groups: List<ExportedGroup>,
    val version: Int,
    val error: String? = null
)

@Singleton
class RuleExporter @Inject constructor() {

    companion object {
        private const val CURRENT_VERSION = 1
    }

    /**
     * Exports rules and groups to a pretty-printed JSON string.
     */
    fun exportRules(
        rules: List<BlockRule>,
        groups: List<AppGroup>,
        groupMembers: Map<Long, List<AppGroupMember>>
    ): String {
        val groupIdToName = groups.associateBy({ it.id }, { it.name })

        val exportedRules = rules.map { rule ->
            ExportedRule(
                packageName = rule.packageName,
                groupName = rule.groupId?.let { groupIdToName[it] },
                mode = rule.mode,
                delaySeconds = rule.delaySeconds,
                dailyLimitMinutes = rule.dailyLimitMinutes,
                enabled = rule.enabled,
                scheduleDays = rule.scheduleDays,
                scheduleStartMinute = rule.scheduleStartMinute,
                scheduleEndMinute = rule.scheduleEndMinute,
                inAppFeatures = rule.inAppFeatures,
                grayscale = rule.grayscale,
                showCounter = rule.showCounter,
                autoKickAfter = rule.autoKickAfter,
                showTimeRemaining = rule.showTimeRemaining,
                autoKickCooldownSeconds = rule.autoKickCooldownSeconds,
                webDomains = rule.webDomains
            )
        }

        val exportedGroups = groups.map { group ->
            val members = groupMembers[group.id]?.map { it.packageName } ?: emptyList()
            ExportedGroup(name = group.name, members = members)
        }

        val export = NudgeExport(
            rules = exportedRules,
            groups = exportedGroups
        )

        return serializeToJson(export)
    }

    /**
     * Parses and validates a JSON string into an ImportResult.
     * Returns an ImportResult with error field set if validation fails.
     */
    fun importRules(json: String): ImportResult {
        return try {
            val root = JSONObject(json)

            val version = root.optInt("version", 0)
            if (version < 1) {
                return ImportResult(
                    rules = emptyList(),
                    groups = emptyList(),
                    version = 0,
                    error = "Invalid or missing version field"
                )
            }
            if (version > CURRENT_VERSION) {
                return ImportResult(
                    rules = emptyList(),
                    groups = emptyList(),
                    version = version,
                    error = "Export version $version is newer than supported ($CURRENT_VERSION). Please update the app."
                )
            }

            val rulesArray = root.optJSONArray("rules") ?: JSONArray()
            val groupsArray = root.optJSONArray("groups") ?: JSONArray()

            val rules = (0 until rulesArray.length()).map { i ->
                parseRule(rulesArray.getJSONObject(i))
            }

            val groups = (0 until groupsArray.length()).map { i ->
                parseGroup(groupsArray.getJSONObject(i))
            }

            ImportResult(rules = rules, groups = groups, version = version)
        } catch (e: JSONException) {
            ImportResult(
                rules = emptyList(),
                groups = emptyList(),
                version = 0,
                error = "Invalid JSON format: ${e.message}"
            )
        } catch (e: IllegalArgumentException) {
            ImportResult(
                rules = emptyList(),
                groups = emptyList(),
                version = 0,
                error = "Invalid data: ${e.message}"
            )
        }
    }

    private fun serializeToJson(export: NudgeExport): String {
        val root = JSONObject()
        root.put("version", export.version)
        root.put("exportedAt", export.exportedAt)

        val rulesArray = JSONArray()
        export.rules.forEach { rule ->
            val obj = JSONObject()
            obj.put("packageName", rule.packageName ?: JSONObject.NULL)
            obj.put("groupName", rule.groupName ?: JSONObject.NULL)
            obj.put("mode", rule.mode)
            obj.put("delaySeconds", rule.delaySeconds)
            obj.put("dailyLimitMinutes", rule.dailyLimitMinutes ?: JSONObject.NULL)
            obj.put("enabled", rule.enabled)
            obj.put("scheduleDays", rule.scheduleDays ?: JSONObject.NULL)
            obj.put("scheduleStartMinute", rule.scheduleStartMinute ?: JSONObject.NULL)
            obj.put("scheduleEndMinute", rule.scheduleEndMinute ?: JSONObject.NULL)
            obj.put("inAppFeatures", rule.inAppFeatures ?: JSONObject.NULL)
            obj.put("grayscale", rule.grayscale)
            obj.put("showCounter", rule.showCounter)
            obj.put("autoKickAfter", rule.autoKickAfter ?: JSONObject.NULL)
            obj.put("showTimeRemaining", rule.showTimeRemaining)
            obj.put("autoKickCooldownSeconds", rule.autoKickCooldownSeconds)
            obj.put("webDomains", rule.webDomains ?: JSONObject.NULL)
            rulesArray.put(obj)
        }
        root.put("rules", rulesArray)

        val groupsArray = JSONArray()
        export.groups.forEach { group ->
            val obj = JSONObject()
            obj.put("name", group.name)
            val membersArr = JSONArray()
            group.members.forEach { membersArr.put(it) }
            obj.put("members", membersArr)
            groupsArray.put(obj)
        }
        root.put("groups", groupsArray)

        return root.toString(2)
    }

    private fun parseRule(obj: JSONObject): ExportedRule {
        val mode = obj.getString("mode")
        require(mode in listOf("HARD_BLOCK", "DELAY", "BREATHING")) {
            "Unknown block mode: $mode"
        }

        return ExportedRule(
            packageName = obj.optStringOrNull("packageName"),
            groupName = obj.optStringOrNull("groupName"),
            mode = mode,
            delaySeconds = obj.optInt("delaySeconds", 15),
            dailyLimitMinutes = obj.optIntOrNull("dailyLimitMinutes"),
            enabled = obj.optBoolean("enabled", true),
            scheduleDays = obj.optStringOrNull("scheduleDays"),
            scheduleStartMinute = obj.optIntOrNull("scheduleStartMinute"),
            scheduleEndMinute = obj.optIntOrNull("scheduleEndMinute"),
            inAppFeatures = obj.optStringOrNull("inAppFeatures"),
            grayscale = obj.optBoolean("grayscale", false),
            showCounter = obj.optBoolean("showCounter", false),
            autoKickAfter = obj.optIntOrNull("autoKickAfter"),
            showTimeRemaining = obj.optBoolean("showTimeRemaining", false),
            autoKickCooldownSeconds = obj.optInt("autoKickCooldownSeconds", 60),
            webDomains = obj.optStringOrNull("webDomains")
        )
    }

    private fun parseGroup(obj: JSONObject): ExportedGroup {
        val name = obj.getString("name")
        val membersArr = obj.optJSONArray("members") ?: JSONArray()
        val members = (0 until membersArr.length()).map { membersArr.getString(it) }
        return ExportedGroup(name = name, members = members)
    }

    /**
     * Extension: returns null for JSONObject.NULL or missing keys, otherwise the string value.
     */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return getString(key)
    }

    /**
     * Extension: returns null for JSONObject.NULL or missing keys, otherwise the int value.
     */
    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return getInt(key)
    }
}
