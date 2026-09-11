package com.everyroutes.app.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Small offline store used until the planned Room persistence layer is introduced. */
class RoutineStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RoutineProfile> = runCatching {
        val root = JSONArray(prefs.getString(KEY_ROUTINES, "[]"))
        List(root.length()) { index -> root.getJSONObject(index).toRoutineProfile() }
    }.getOrDefault(emptyList())

    fun save(routines: List<RoutineProfile>) {
        val root = JSONArray()
        routines.forEach { root.put(it.toJson()) }
        prefs.edit().putString(KEY_ROUTINES, root.toString()).apply()
    }

    private fun RoutineProfile.toJson() = JSONObject().apply {
        put("schemaVersion", schemaVersion)
        put("routineAddress", routineAddress)
        put("name", name)
        put("lastModified", lastModified)
        put("holiday", recurrence.holiday.name)
        put("isEnabled", isEnabled)
        put("daysOfWeek", JSONArray(recurrence.daysOfWeek))
        put("blocks", JSONArray().also { blocksJson ->
            blocks.forEach { block ->
                blocksJson.put(JSONObject().apply {
                    put("id", block.id)
                    put("start", block.start)
                    put("end", block.end)
                    put("title", block.title)
                    put("note", block.note)
                })
            }
        })
    }

    private fun JSONObject.toRoutineProfile(): RoutineProfile {
        val days = getJSONArray("daysOfWeek")
        val blockArray = getJSONArray("blocks")
        return RoutineProfile(
            schemaVersion = optInt("schemaVersion", 1),
            routineAddress = optString("routineAddress").ifBlank { null },
            name = getString("name"),
            lastModified = getString("lastModified"),
            recurrence = Recurrence(
                daysOfWeek = List(days.length()) { days.getString(it) },
                holiday = runCatching { HolidayPolicy.valueOf(getString("holiday")) }
                    .getOrDefault(HolidayPolicy.EXCLUDE),
            ),
            blocks = List(blockArray.length()) { index ->
                blockArray.getJSONObject(index).let { block ->
                    RoutineBlock(
                        id = block.getString("id"),
                        start = block.getString("start"),
                        end = block.getString("end"),
                        title = block.getString("title"),
                        note = block.optString("note"),
                    )
                }
            },
            isEnabled = optBoolean("isEnabled", true),
        )
    }

    private companion object {
        const val PREFS_NAME = "routine_profiles"
        const val KEY_ROUTINES = "routines"
    }
}
