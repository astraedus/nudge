package com.astraedus.nudge.domain.events

/**
 * One-line JSON codec for [AccessibilityEventRecord].
 *
 * Both directions live in ONE object on purpose. The device writes these lines
 * (`AccessibilityEventTrace` → logcat → `scripts/a11y-capture.sh`) and the JVM tests read them back
 * (`A11yCaptureLoader`), so an encoder and a decoder that drifted apart would silently turn every
 * captured fixture into a different event stream than the one the device actually saw — the fixture
 * would still "pass", against the wrong data. A single round-trip-tested codec makes that
 * impossible.
 *
 * Hand-rolled rather than pulling in a JSON library: the schema is one flat object of primitives,
 * the encoder runs on the accessibility hot path in debug builds, and a fixture format the test
 * suite depends on should have no external moving parts.
 *
 * Unknown keys are ignored and missing keys take the record's defaults, so a capture taken before a
 * field was added still replays.
 */
object AccessibilityEventCodec {

    // Short keys: these lines are emitted at hundreds per second during a capture.
    private const val KEY_TYPE = "t"
    private const val KEY_PACKAGE = "p"
    private const val KEY_CLASS = "c"
    private const val KEY_WINDOW_ID = "w"
    private const val KEY_EVENT_TIME = "ts"
    private const val KEY_FROM_INDEX = "fi"
    private const val KEY_TO_INDEX = "ti"
    private const val KEY_CURRENT_ITEM = "ci"
    private const val KEY_ITEM_COUNT = "n"
    private const val KEY_SCROLL_DELTA_X = "dx"
    private const val KEY_SCROLL_DELTA_Y = "dy"
    private const val KEY_SCROLL_X = "sx"
    private const val KEY_SCROLL_Y = "sy"
    private const val KEY_MAX_SCROLL_X = "mx"
    private const val KEY_MAX_SCROLL_Y = "my"
    private const val KEY_CONTENT_CHANGE_TYPES = "cct"
    private const val KEY_SOURCE_VIEW_ID = "vid"
    private const val KEY_SCROLLABLE = "scr"

    fun encode(record: AccessibilityEventRecord): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.appendString(KEY_TYPE, record.type.name, first = true)
        sb.appendString(KEY_PACKAGE, record.packageName)
        record.className?.let { sb.appendString(KEY_CLASS, it) }
        sb.appendInt(KEY_WINDOW_ID, record.windowId)
        sb.appendLong(KEY_EVENT_TIME, record.eventTimeMs)
        sb.appendInt(KEY_FROM_INDEX, record.fromIndex)
        sb.appendInt(KEY_TO_INDEX, record.toIndex)
        sb.appendInt(KEY_CURRENT_ITEM, record.currentItemIndex)
        sb.appendInt(KEY_ITEM_COUNT, record.itemCount)
        sb.appendInt(KEY_SCROLL_DELTA_X, record.scrollDeltaX)
        sb.appendInt(KEY_SCROLL_DELTA_Y, record.scrollDeltaY)
        sb.appendInt(KEY_SCROLL_X, record.scrollX)
        sb.appendInt(KEY_SCROLL_Y, record.scrollY)
        sb.appendInt(KEY_MAX_SCROLL_X, record.maxScrollX)
        sb.appendInt(KEY_MAX_SCROLL_Y, record.maxScrollY)
        sb.appendInt(KEY_CONTENT_CHANGE_TYPES, record.contentChangeTypes)
        record.sourceViewId?.let { sb.appendString(KEY_SOURCE_VIEW_ID, it) }
        if (record.isScrollable) sb.append(",\"").append(KEY_SCROLLABLE).append("\":true")
        sb.append('}')
        return sb.toString()
    }

    /** Decode one line. Returns null for a blank line, a comment (`#`) or anything unparseable. */
    fun decode(line: String): AccessibilityEventRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val fields = parseFlatObject(trimmed) ?: return null
        val type = fields[KEY_TYPE]?.let { name ->
            A11yEventType.entries.firstOrNull { it.name == name }
        } ?: return null
        val packageName = fields[KEY_PACKAGE] ?: return null
        val defaults = AccessibilityEventRecord(type = type, packageName = packageName)
        return defaults.copy(
            className = fields[KEY_CLASS],
            windowId = fields.int(KEY_WINDOW_ID, defaults.windowId),
            eventTimeMs = fields[KEY_EVENT_TIME]?.toLongOrNull() ?: defaults.eventTimeMs,
            fromIndex = fields.int(KEY_FROM_INDEX, defaults.fromIndex),
            toIndex = fields.int(KEY_TO_INDEX, defaults.toIndex),
            currentItemIndex = fields.int(KEY_CURRENT_ITEM, defaults.currentItemIndex),
            itemCount = fields.int(KEY_ITEM_COUNT, defaults.itemCount),
            scrollDeltaX = fields.int(KEY_SCROLL_DELTA_X, defaults.scrollDeltaX),
            scrollDeltaY = fields.int(KEY_SCROLL_DELTA_Y, defaults.scrollDeltaY),
            scrollX = fields.int(KEY_SCROLL_X, defaults.scrollX),
            scrollY = fields.int(KEY_SCROLL_Y, defaults.scrollY),
            maxScrollX = fields.int(KEY_MAX_SCROLL_X, defaults.maxScrollX),
            maxScrollY = fields.int(KEY_MAX_SCROLL_Y, defaults.maxScrollY),
            contentChangeTypes = fields.int(KEY_CONTENT_CHANGE_TYPES, defaults.contentChangeTypes),
            sourceViewId = fields[KEY_SOURCE_VIEW_ID],
            isScrollable = fields[KEY_SCROLLABLE] == "true"
        )
    }

    /** Decode a whole capture, skipping blank/comment/unparseable lines. */
    fun decodeAll(lines: Sequence<String>): List<AccessibilityEventRecord> =
        lines.mapNotNull(::decode).toList()

    private fun Map<String, String>.int(key: String, fallback: Int): Int =
        this[key]?.toIntOrNull() ?: fallback

    private fun StringBuilder.appendString(key: String, value: String, first: Boolean = false) {
        if (!first) append(',')
        append('"').append(key).append("\":\"").appendEscaped(value).append('"')
    }

    private fun StringBuilder.appendInt(key: String, value: Int) {
        append(',').append('"').append(key).append("\":").append(value)
    }

    private fun StringBuilder.appendLong(key: String, value: Long) {
        append(',').append('"').append(key).append("\":").append(value)
    }

    private fun StringBuilder.appendEscaped(value: String): StringBuilder {
        for (ch in value) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append("\\u").append("%04x".format(ch.code))
                else -> append(ch)
            }
        }
        return this
    }

    /**
     * Parse a flat `{"k":"v","k2":123}` object. Returns null on malformed input rather than
     * throwing: a truncated logcat line must skip, never fail a whole capture load.
     */
    private fun parseFlatObject(json: String): Map<String, String>? {
        val out = mutableMapOf<String, String>()
        var i = 1
        val end = json.length - 1
        while (i < end) {
            when (json[i]) {
                ' ', ',' -> { i++; continue }
                '"' -> Unit
                else -> return null
            }
            val key = StringBuilder()
            i = readQuoted(json, i, key) ?: return null
            if (i >= end || json[i] != ':') return null
            i++
            if (i >= end) return null
            if (json[i] == '"') {
                val value = StringBuilder()
                i = readQuoted(json, i, value) ?: return null
                out[key.toString()] = value.toString()
            } else {
                val start = i
                while (i < end && json[i] != ',') i++
                out[key.toString()] = json.substring(start, i).trim()
            }
        }
        return out
    }

    /** Read a quoted string starting at [start] (which must be `"`). Returns the index after it. */
    private fun readQuoted(json: String, start: Int, out: StringBuilder): Int? {
        var i = start + 1
        while (i < json.length) {
            when (val ch = json[i]) {
                '"' -> return i + 1
                '\\' -> {
                    i++
                    if (i >= json.length) return null
                    when (val esc = json[i]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 >= json.length) return null
                            val code = json.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(esc)
                    }
                    i++
                }
                else -> { out.append(ch); i++ }
            }
        }
        return null
    }
}
