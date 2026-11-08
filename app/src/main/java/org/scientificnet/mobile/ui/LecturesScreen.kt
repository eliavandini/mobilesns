package org.scientificnet.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.scientificnet.mobile.data.TeamsLink
import org.scientificnet.mobile.data.TimetableEntry
import org.scientificnet.mobile.data.links
import java.time.LocalDate
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LecturesScreen(vm: AppViewModel) {
    val state by vm.lectures.collectAsStateWithLifecycle()
    val offset by vm.weekOffset.collectAsStateWithLifecycle()
    val (start, end) = vm.weekRange(offset)
    var selected by remember { mutableStateOf<TimetableEntry?>(null) }

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
                        items(list, key = { "${it.Id}-${it.StartDate}" }) { LectureCard(it, onClick = { selected = it }) }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            LectureDetails(entry)
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

private fun timeRange(e: TimetableEntry): String {
    val s = Fmt.parseDateTime(e.StartDate)?.format(Fmt.time) ?: "--:--"
    val end = Fmt.parseDateTime(e.EndDate)?.takeIf { !e.HideEndTime }?.format(Fmt.time)
    return if (end != null) "$s – $end" else s
}

// ---- intents / clipboard -------------------------------------------------------------------

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun copyCode(context: Context, clipboard: ClipboardManager, code: String) {
    clipboard.setText(AnnotatedString(code))
    // Android 13+ shows its own "copied" confirmation.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Teams code copied", Toast.LENGTH_SHORT).show()
    }
}

/** Opens the team if we have a link; otherwise copies the join code. */
private fun openTeams(context: Context, clipboard: ClipboardManager, link: TeamsLink) {
    when {
        link.url != null -> openUrl(context, link.url)
        link.joinCode != null -> copyCode(context, clipboard, link.joinCode)
    }
}

// ---- card ----------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LectureCard(e: TimetableEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val startAt = Fmt.parseDateTime(e.StartDate)
    val endAt = Fmt.parseDateTime(e.EndDate)
    val now = LocalDateTime.now()
    val ongoing = startAt != null && endAt != null && now.isAfter(startAt) && now.isBefore(endAt)
    val links = remember(e) { e.links() }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (ongoing) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
        onClick = onClick,
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

                if (!links.isEmpty) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        links.oleUrls.firstOrNull()?.let { url ->
                            AssistChip(onClick = { openUrl(context, url) }, label = { Text("OLE") })
                        }
                        links.teams.firstOrNull()?.let { team ->
                            AssistChip(
                                onClick = { openTeams(context, clipboard, team) },
                                label = { Text(team.joinCode?.let { "Teams · $it" } ?: "Teams") },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---- details sheet -------------------------------------------------------------------------

@Composable
private fun LectureDetails(e: TimetableEntry) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val links = remember(e) { e.links() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(e.CourseDescription ?: e.ActivityDescription ?: "Lecture", style = MaterialTheme.typography.headlineSmall)
        if (!e.ActivityDescription.isNullOrBlank() && e.ActivityDescription != e.CourseDescription) {
            Text(e.ActivityDescription, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        val day = Fmt.parseDateTime(e.StartDate)?.toLocalDate()?.format(Fmt.dayHeader)
        DetailLine(Icons.Default.DateRange, listOfNotNull(day, timeRange(e)).joinToString(" · "))
        val room = listOfNotNull(e.RoomDescription?.takeIf { it.isNotBlank() }, e.RoomName?.takeIf { it.isNotBlank() })
            .distinct().joinToString(" · ")
        if (room.isNotBlank()) DetailLine(Icons.Default.LocationOn, room)
        val staff = e.Staff.orEmpty().map { it.displayName }.filter { it.isNotBlank() }
        if (staff.isNotEmpty()) DetailLine(Icons.Default.Person, staff.joinToString(", "))
        activityLabel(e.ActivityCode)?.let {
            DetailLine(Icons.Default.Info, it + if (e.IsMandatory) " · mandatory attendance" else "")
        }

        if (links.oleUrls.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("OLE course", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            links.oleUrls.forEachIndexed { i, url ->
                Button(onClick = { openUrl(context, url) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (links.oleUrls.size > 1) "Open in OLE (${i + 1})" else "Open in OLE")
                }
                SelectionContainer {
                    Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (links.teams.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Microsoft Teams", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            links.teams.forEach { team ->
                TeamsRow(
                    team = team,
                    showOwner = links.teams.size > 1,
                    onOpen = { team.url?.let { openUrl(context, it) } },
                    onCopy = { team.joinCode?.let { copyCode(context, clipboard, it) } },
                )
            }
        }
        if (links.isEmpty) {
            Spacer(Modifier.height(8.dp))
            Text(
                "No OLE or Teams links for this lecture.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TeamsRow(team: TeamsLink, showOwner: Boolean, onOpen: () -> Unit, onCopy: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        if (showOwner && team.owner != null) {
            Text(team.owner, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (team.joinCode != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Join code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer {
                        Text(team.joinCode, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    }
                }
                OutlinedButton(onClick = onCopy) { Text("Copy code") }
            }
        }
        if (team.url != null) {
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Open in Teams") }
        }
    }
}

@Composable
private fun DetailLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun IconLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
