package com.everyroutes.app.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Timeline entry for the Today view (design 00 §4.3). Shared by app + widget. */
sealed interface TimelineEntry {
    val sortKey: String
}

data class BlockEntry(
    val routineName: String,
    val block: RoutineBlock,
) : TimelineEntry {
    override val sortKey: String get() = block.start
}

data class TaskEntry(val task: Task) : TimelineEntry {
    override val sortKey: String get() = task.at
}

data class TodayView(
    val date: LocalDate,
    val entries: List<TimelineEntry>,
    val currentBlock: BlockEntry? = null,
    val nextBlock: BlockEntry? = null,
    val nextTask: TaskEntry? = null,
)

private fun dayCode(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "MON"
    DayOfWeek.TUESDAY -> "TUE"
    DayOfWeek.WEDNESDAY -> "WED"
    DayOfWeek.THURSDAY -> "THU"
    DayOfWeek.FRIDAY -> "FRI"
    DayOfWeek.SATURDAY -> "SAT"
    DayOfWeek.SUNDAY -> "SUN"
    else -> "MON"
}

/**
 * Assemble the Today view: pick routines matching [date], expand blocks,
 * merge tasks whose `at` falls on [date] (caller filters by TZ).
 */
fun buildTodayView(
    date: LocalDate,
    routines: List<RoutineProfile>,
    tasksForDay: List<Task>,
    isHoliday: Boolean = false,
    now: LocalTime = LocalTime.now(),
): TodayView {
    val code = dayCode(date)
    val matched = routines.filter { r ->
        if (!r.isEnabled) return@filter false
        val rec = r.recurrence
        val weekdayHit = code in rec.daysOfWeek
        when (rec.holiday) {
            HolidayPolicy.EXCLUDE -> if (isHoliday) false else weekdayHit
            HolidayPolicy.INCLUDE -> weekdayHit || isHoliday
            HolidayPolicy.ONLY -> isHoliday
        }
    }

    val blocks = matched.flatMap { r -> r.blocks.map { BlockEntry(r.name, it) } }
        .sortedBy { it.block.start }
    val tasks = tasksForDay.filter { !it.deleted }.map { TaskEntry(it) }

    val entries: List<TimelineEntry> = (blocks + tasks).sortedBy { it.sortKey }

    fun LocalTime.isIn(block: RoutineBlock): Boolean {
        val s = LocalTime.parse(block.start)
        val e = LocalTime.parse(block.end)
        return if (e > s) !this.isBefore(s) && this.isBefore(e) else !this.isBefore(s) || this.isBefore(e)
    }

    val current = blocks.firstOrNull { now.isIn(it.block) }
    val nextB = blocks.filter { LocalTime.parse(it.block.start) > now }.minByOrNull { it.block.start }
    val nextT = tasks.sortedBy { it.task.at }.firstOrNull()

    return TodayView(date, entries, current, nextB, nextT)
}

/** Last-write-wins (design 00 §6.2). Returns true if [incoming] should replace [stored]. */
fun shouldAdoptIncoming(incomingLastModified: String, storedLastModified: String): Boolean =
    incomingLastModified > storedLastModified
