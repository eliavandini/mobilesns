package org.scientificnet.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.scientificnet.mobile.data.TimetableEntry
import java.time.LocalDate
import java.time.LocalDateTime
import androidx.compose.foundation.layout.size

@Composable
fun LecturesScreen(vm: AppViewModel) {
    val state by vm.lectures.collectAsStateWithLifecycle()
    val offset by vm.weekOffset.collectAsStateWithLifecycle()
    val (start, end) = vm.weekRange(offset)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.shiftWeek(-1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${start.format(Fmt.shortDay)} – ${end.format(Fmt.shortDay)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    when (offset) {
                        0 -> "Next 7 days"
                        1 -> "In 1 week"
                        -1 -> "Last week"
                        else -> if (offset > 0) "In $offset weeks" else "${-offset} weeks ago"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (offset != 0) TextButton(onClick = { vm.shiftWeek(0) }) { Text("Today") }
            IconButton(onClick = { vm.shiftWeek(1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
            }
        }

        StateContent(state, onRefresh = vm::loadLectures, onRetry = vm::retry) { entries ->
            if (entries.isEmpty()) {
                CenteredMessage("No lectures in this period.")
            } else {
                val byDay = entries.groupBy { Fmt.parseDateTime(it.StartDate)?.toLocalDate() }
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    byDay.forEach { (day, list) ->
                        item(key = "h-$day") { DayHeader(day) }
                        items(list, key = { "${it.Id}-${it.StartDate}" }) { LectureCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: LocalDate?) {
    val today = LocalDate.now()
    val label = when (day) {
        null -> "Unknown date"
        today -> "Today · ${day.format(Fmt.dayHeader)}"
        today.plusDays(1) -> "Tomorrow · ${day.format(Fmt.dayHeader)}"
        else -> day.format(Fmt.dayHeader)
    }
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

private fun activityLabel(code: String?): String? = when (code?.uppercase()) {
    null, "" -> null
    "LECT" -> "Lecture"
    "LAB" -> "Lab"
    "EXER", "EX" -> "Exercise"
    "SEM" -> "Seminar"
    "EXAM" -> "Exam"
    else -> code
}

@Composable
private fun LectureCard(e: TimetableEntry) {
    val context = LocalContext.current
    val startAt = Fmt.parseDateTime(e.StartDate)
    val endAt = Fmt.parseDateTime(e.EndDate)
    val now = LocalDateTime.now()
    val ongoing = startAt != null && endAt != null && now.isAfter(startAt) && now.isBefore(endAt)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (ongoing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        onClick = {
            e.MoodleUrl?.takeIf { it.isNotBlank() }?.let {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
            }
        },
    ) {
        Row(Modifier.padding(12.dp)) {
            Column(Modifier.width(56.dp)) {
                Text(
                    startAt?.format(Fmt.time) ?: "--:--",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!e.HideEndTime && endAt != null) {
                    Text(
                        endAt.format(Fmt.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        e.CourseDescription ?: e.ActivityDescription ?: "Lecture",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    activityLabel(e.ActivityCode)?.let {
                        Spacer(Modifier.width(6.dp))
                        Pill(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    if (ongoing) {
                        Spacer(Modifier.width(6.dp))
                        Pill("Now", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                    }
                }
                if (!e.ActivityDescription.isNullOrBlank() && e.ActivityDescription != e.CourseDescription) {
                    Text(
                        e.ActivityDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val room = e.RoomDescription?.takeIf { it.isNotBlank() } ?: e.RoomName
                if (!room.isNullOrBlank()) IconLine(Icons.Default.LocationOn, room)
                val staff = e.Staff.orEmpty().map { it.displayName }.filter { it.isNotBlank() }
                if (staff.isNotEmpty()) IconLine(Icons.Default.Person, staff.joinToString(", "))
            }
        }
    }
}

@Composable
fun IconLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
