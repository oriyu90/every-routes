package com.everyroutes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.everyroutes.app.R
import com.everyroutes.app.model.HolidayPolicy
import com.everyroutes.app.model.Recurrence
import com.everyroutes.app.model.RoutineBlock
import com.everyroutes.app.model.RoutineProfile
import java.time.Instant
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.util.UUID

private val dayCodes = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
private val dayLabels = listOf(
    R.string.day_mon, R.string.day_tue, R.string.day_wed, R.string.day_thu,
    R.string.day_fri, R.string.day_sat, R.string.day_sun,
)

@Composable
fun RoutinesScreen(
    routines: List<RoutineProfile>,
    onAdd: (RoutineProfile) -> Unit,
    onEnabledChange: (RoutineProfile, Boolean) -> Unit,
    onDelete: (RoutineProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<RoutineProfile?>(null) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            FloatingActionButton(onClick = { showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_routine))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.routines_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (routines.isEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.routines_empty))
                        Button(onClick = { showEditor = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text(stringResource(R.string.add_routine), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            } else {
                items(routines, key = { it.lastModified }) { routine ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(routine.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        localizedDays(routine.recurrence.daysOfWeek),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = routine.isEnabled,
                                    onCheckedChange = { onEnabledChange(routine, it) },
                                )
                            }
                            routine.blocks.forEach { block ->
                                Text("${block.start}–${block.end}  ${block.title}")
                            }
                            TextButton(onClick = { pendingDelete = routine }) {
                                Text(stringResource(R.string.delete_routine))
                            }
                        }
                    }
                }
            }
        }
    }
    if (showEditor) {
        RoutineEditorDialog(
            onDismiss = { showEditor = false },
            onSave = {
                onAdd(it)
                showEditor = false
            },
        )
    }
    pendingDelete?.let { routine ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_routine)) },
            text = { Text(stringResource(R.string.delete_routine_confirm, routine.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(routine)
                        pendingDelete = null
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun localizedDays(days: List<String>): String = dayCodes.mapIndexedNotNull { index, code ->
    if (code in days) stringResource(dayLabels[index]) else null
}.joinToString(" ")

@Composable
private fun RoutineEditorDialog(
    onDismiss: () -> Unit,
    onSave: (RoutineProfile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("07:00") }
    var end by remember { mutableStateOf("08:00") }
    var selectedDays by remember { mutableStateOf(dayCodes.take(5).toSet()) }
    val valid = name.isNotBlank() && title.isNotBlank() && selectedDays.isNotEmpty() &&
        isValidTime(start) && isValidTime(end) && start != end

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_routine)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.routine_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    Text(stringResource(R.string.repeat_days), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        dayCodes.forEachIndexed { index, day ->
                            FilterChip(
                                selected = day in selectedDays,
                                onClick = {
                                    selectedDays = if (day in selectedDays) selectedDays - day else selectedDays + day
                                },
                                label = { Text(stringResource(dayLabels[index])) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.block_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text(stringResource(R.string.start_time)) },
                            supportingText = { Text("HH:MM") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it },
                            label = { Text(stringResource(R.string.end_time)) },
                            supportingText = { Text("HH:MM") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                    }
                }
                if (!valid) {
                    item {
                        Text(
                            stringResource(R.string.routine_validation),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    onSave(
                        RoutineProfile(
                            name = name.trim(),
                            lastModified = Instant.now().toString(),
                            recurrence = Recurrence(
                                daysOfWeek = dayCodes.filter { it in selectedDays },
                                holiday = HolidayPolicy.EXCLUDE,
                            ),
                            blocks = listOf(
                                RoutineBlock(
                                    id = "blk_${UUID.randomUUID()}",
                                    start = start,
                                    end = end,
                                    title = title.trim(),
                                ),
                            ),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun isValidTime(value: String): Boolean = try {
    LocalTime.parse(value)
    value.length == 5
} catch (_: DateTimeParseException) {
    false
}
