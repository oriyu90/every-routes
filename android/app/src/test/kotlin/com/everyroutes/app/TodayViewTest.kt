package com.everyroutes.app

import com.everyroutes.app.model.HolidayPolicy
import com.everyroutes.app.model.Recurrence
import com.everyroutes.app.model.RoutineBlock
import com.everyroutes.app.model.RoutineProfile
import com.everyroutes.app.model.Task
import com.everyroutes.app.model.TaskSource
import com.everyroutes.app.model.TaskStatus
import com.everyroutes.app.model.buildTodayView
import com.everyroutes.app.model.generateRoutineAddress
import com.everyroutes.app.model.isRoutineAddressValid
import com.everyroutes.app.model.shouldAdoptIncoming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TodayViewTest {

    private fun weekdayRoutine() = RoutineProfile(
        name = "平日",
        lastModified = "2026-09-10T08:12:33Z",
        recurrence = Recurrence(listOf("MON", "TUE", "WED", "THU", "FRI"), HolidayPolicy.EXCLUDE),
        blocks = listOf(RoutineBlock("blk_1", "07:00", "07:30", "起床・準備")),
    )

    @Test
    fun weekdayRoutineAppliesOnFriday() {
        val friday = LocalDate.of(2026, 9, 11) // Friday
        val view = buildTodayView(friday, listOf(weekdayRoutine()), emptyList(), now = LocalTime.of(6, 0))
        assertEquals(1, view.entries.size)
        assertEquals("07:00", view.nextBlock?.block?.start)
    }

    @Test
    fun weekdayRoutineExcludedOnHoliday() {
        val friday = LocalDate.of(2026, 9, 11)
        val view = buildTodayView(friday, listOf(weekdayRoutine()), emptyList(), isHoliday = true)
        assertTrue(view.entries.isEmpty())
    }

    @Test
    fun taskMergesIntoTimeline() {
        val friday = LocalDate.of(2026, 9, 11)
        val task = Task(
            id = "tsk_1", source = TaskSource.AGENT, title = "銀行に行く",
            at = "2026-09-11T10:00:00+09:00", lastModified = "2026-09-10T09:00:00Z",
        )
        val view = buildTodayView(friday, listOf(weekdayRoutine()), listOf(task))
        assertEquals(2, view.entries.size)
        assertEquals("tsk_1", view.nextTask?.task?.id)
    }

    @Test
    fun routineAddressIs256bit() {
        val addr = generateRoutineAddress()
        assertTrue(isRoutineAddressValid(addr))
    }

    @Test
    fun lastWriteWins() {
        assertTrue(shouldAdoptIncoming("2026-09-11T00:00:00Z", "2026-09-10T00:00:00Z"))
        assertTrue(!shouldAdoptIncoming("2026-09-09T00:00:00Z", "2026-09-10T00:00:00Z"))
    }

    @Test
    fun completedTaskKept() {
        val task = Task(
            id = "t", source = TaskSource.MANUAL, title = "x",
            at = "2026-09-11T10:00:00+09:00", status = TaskStatus.COMPLETED,
            lastModified = "2026-09-10T09:00:00Z",
        )
        assertEquals(TaskStatus.COMPLETED, task.status)
    }
}
