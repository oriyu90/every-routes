package com.everyroutes.app.model

import java.security.SecureRandom

/** Mirrors spec/schemas/routine-profile.schema.json v1. */
data class RoutineBlock(
    val id: String,
    val start: String, // "HH:MM"
    val end: String, // "HH:MM"; end < start means overnight
    val title: String,
    val note: String = "",
)

enum class HolidayPolicy { EXCLUDE, INCLUDE, ONLY }

data class Recurrence(
    val daysOfWeek: List<String> = emptyList(), // MON..SUN
    val holiday: HolidayPolicy = HolidayPolicy.EXCLUDE,
)

data class RoutineProfile(
    val schemaVersion: Int = 1,
    val routineAddress: String? = null, // null = local-only, not yet shared
    val name: String,
    val lastModified: String, // UTC RFC3339
    val recurrence: Recurrence = Recurrence(),
    val blocks: List<RoutineBlock> = emptyList(),
    val isEnabled: Boolean = true,
)

enum class TaskSource { GOOGLE_TASKS, AGENT, MANUAL }
enum class TaskStatus { NEEDS_ACTION, COMPLETED }

data class Task(
    val schemaVersion: Int = 1,
    val id: String,
    val source: TaskSource,
    val externalId: String? = null,
    val title: String,
    val at: String, // tz-aware RFC3339
    val allDay: Boolean = false,
    val status: TaskStatus = TaskStatus.NEEDS_ACTION,
    val lastModified: String,
    val deleted: Boolean = false,
)

/** 256-bit routine address: `rt_` + 64 hex (design 02 §5.2). */
fun generateRoutineAddress(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return "rt_" + bytes.joinToString("") { "%02x".format(it) }
}

fun isRoutineAddressValid(address: String?): Boolean {
    if (address == null) return false
    return Regex("^rt_[0-9a-fA-F]{64}$").matches(address)
}
