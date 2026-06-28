package com.immichframe.immichframe

import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.preference.PreferenceManager
import com.immichframe.immichframe.ui.theme.ImmichFrameTheme
import java.text.DateFormatSymbols
import java.util.Locale

// Calendar weekday constants (SUNDAY=1 .. SATURDAY=7) ordered Mon..Sun for display.
private val WEEKDAY_ORDER = listOf(2, 3, 4, 5, 6, 7, 1)
private val WORKDAYS = setOf(2, 3, 4, 5, 6)
private val WEEKEND = setOf(7, 1)
private val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)

// Locale-aware short weekday name for a Calendar day constant (follows the OS language).
private fun weekdayLabel(dayInt: Int): String {
    val names = DateFormatSymbols(Locale.getDefault()).shortWeekdays
    return names.getOrNull(dayInt)?.takeIf { it.isNotBlank() } ?: dayInt.toString()
}

private val TIME_REGEX = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$")

private fun isValidTime(time: String): Boolean = time.matches(TIME_REGEX)

// Validates the editor state before saving. Returns an error message for the first problem
// found, or null when the schedule is safe to persist. An empty rule list is allowed and means
// "always active". Every rule that does exist must be complete: at least one day, at least one
// range, all times well-formed, and no zero-length range (start == end would be treated as
// active all day by isActiveNow, which is almost never what the user intends).
private fun validateRules(rules: List<RuleState>): String? {
    rules.forEachIndexed { index, rule ->
        val position = index + 1
        if (rule.days.isEmpty()) {
            return "Rule $position has no days selected."
        }
        if (rule.ranges.isEmpty()) {
            return "Rule $position has no time ranges."
        }
        rule.ranges.forEach { (start, end) ->
            if (!isValidTime(start) || !isValidTime(end)) {
                return "Rule $position has an invalid time ($start–$end)."
            }
            if (start == end) {
                return "Rule $position has a zero-length range ($start–$end)."
            }
        }
    }
    return null
}

private class RuleState(days: Set<Int>, ranges: List<Helpers.ActiveRange>) {
    val days: SnapshotStateList<Int> = mutableStateListOf<Int>().apply { addAll(days) }
    val ranges: SnapshotStateList<Pair<String, String>> =
        mutableStateListOf<Pair<String, String>>().apply { addAll(ranges.map { it.start to it.end }) }
}

class ActiveScheduleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val initial = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))

        setContent {
            ImmichFrameTheme(darkTheme = true, dynamicColor = false) {
                ScheduleEditorScreen(
                    initial = initial,
                    onSave = { schedule ->
                        prefs.edit()
                            .putString("activeSchedule", Helpers.serializeActiveSchedule(schedule))
                            .apply()
                        finish()
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditorScreen(
    initial: Helpers.ActiveSchedule,
    onSave: (Helpers.ActiveSchedule) -> Unit,
) {
    val context = LocalContext.current
    val rules = remember {
        mutableStateListOf<RuleState>().apply {
            initial.rules.forEach { add(RuleState(it.days, it.ranges)) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Active Schedule") },
                actions = {
                    TextButton(onClick = {
                        val error = validateRules(rules)
                        if (error != null) {
                            Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                            return@TextButton
                        }
                        val cleaned = rules.map { rule ->
                            Helpers.ActiveRule(
                                rule.days.toSortedSet(),
                                rule.ranges.map { Helpers.ActiveRange(it.first, it.second) },
                            )
                        }
                        onSave(Helpers.ActiveSchedule(cleaned))
                    }) {
                        Text("Save")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (rules.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No rules yet. Add a rule to define when the frame is active. " +
                                "Leaving this empty keeps the frame always on.",
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            items(rules) { rule ->
                RuleCard(
                    rule = rule,
                    onDelete = { rules.remove(rule) },
                    onPickTime = { current, onPicked -> showTimePicker(context, current, onPicked) },
                )
            }

            item {
                Button(
                    onClick = { rules.add(RuleState(emptySet(), listOf(Helpers.ActiveRange("07:00", "22:00")))) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add rule")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(
    rule: RuleState,
    onDelete: () -> Unit,
    onPickTime: (String, (String) -> Unit) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Days",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDelete) { Text("Delete") }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WEEKDAY_ORDER.forEach { dayInt ->
                    val selected = rule.days.contains(dayInt)
                    FilterChip(
                        selected = selected,
                        onClick = {
                            if (selected) rule.days.remove(dayInt) else rule.days.add(dayInt)
                        },
                        label = { Text(weekdayLabel(dayInt)) },
                    )
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { setDays(rule.days, WORKDAYS) }, label = { Text("Workdays") })
                AssistChip(onClick = { setDays(rule.days, WEEKEND) }, label = { Text("Weekends") })
                AssistChip(onClick = { setDays(rule.days, ALL_DAYS) }, label = { Text("Every day") })
                AssistChip(onClick = { rule.days.clear() }, label = { Text("Clear") })
            }

            HorizontalDivider()

            Text("Time ranges", style = MaterialTheme.typography.titleMedium)

            rule.ranges.forEachIndexed { index, range ->
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onPickTime(range.first) { picked ->
                                rule.ranges[index] = picked to rule.ranges[index].second
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(range.first, color = MaterialTheme.colorScheme.onSurface) }
                    Text("\u2013")
                    OutlinedButton(
                        onClick = {
                            onPickTime(range.second) { picked ->
                                rule.ranges[index] = rule.ranges[index].first to picked
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text(range.second, color = MaterialTheme.colorScheme.onSurface) }
                    TextButton(onClick = { rule.ranges.removeAt(index) }) { Text("\u2715") }
                }
            }

            TextButton(onClick = { rule.ranges.add("07:00" to "22:00") }) {
                Text("Add time range")
            }
        }
    }
}

private fun setDays(target: SnapshotStateList<Int>, values: Set<Int>) {
    target.clear()
    target.addAll(values)
}

private fun showTimePicker(context: Context, current: String, onPicked: (String) -> Unit) {
    val parts = current.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
    TimePickerDialog(
        context,
        { _, pickedHour, pickedMinute ->
            onPicked(String.format(Locale.US, "%02d:%02d", pickedHour, pickedMinute))
        },
        hour,
        minute,
        android.text.format.DateFormat.is24HourFormat(context),
    ).show()
}
