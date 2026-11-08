package org.scientificnet.mobile.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.scientificnet.mobile.data.ActionTag
import org.scientificnet.mobile.data.EnrolmentExam

private enum class ExamFilter(val label: String) { ALL("All"), ENROLLED("Enrolled"), NOT_ENROLLED("Not enrolled") }

private const val EXAM_WEBSITE = "https://my.scientificnet.org/exam-enrolment/en/"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExamsScreen(vm: AppViewModel) {
    val state by vm.exams.collectAsStateWithLifecycle()
    val busy by vm.busyExam.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(ExamFilter.ALL) }
    var showInactive by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pair<EnrolmentExam, Boolean>?>(null) }

    StateContent(state, onRefresh = vm::loadExams, onRetry = vm::retry) { data ->
        val active = data.activeGroup.orEmpty()
            .sortedWith(compareBy({ Fmt.parseExamDate(it.examDate) }, { it.examTime }))
        val inactive = data.inactiveGroup.orEmpty()
        val shown = active.filter {
            when (filter) {
                ExamFilter.ALL -> true
                ExamFilter.ENROLLED -> it.enrolmentDone == true
                ExamFilter.NOT_ENROLLED -> it.enrolmentDone != true
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExamFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                    }
                }
            }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (active.isEmpty()) "No exams or surveys available for enrolment right now."
                        else "No exams match this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }
            items(shown) { exam ->
                ExamCard(
                    exam = exam,
                    busy = busy != null && busy == exam.examSessionId,
                    onEnrol = { confirm = exam to true },
                    onUnenrol = { confirm = exam to false },
                )
            }
            if (inactive.isNotEmpty()) {
                item {
                    TextButton(onClick = { showInactive = !showInactive }) {
                        Text("Not available for enrolment (${inactive.size})")
                        Icon(
                            if (showInactive) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                        )
                    }
                }
                if (showInactive) {
                    items(inactive) { exam ->
                        ExamCard(exam, busy = false, onEnrol = null, onUnenrol = null)
                    }
                }
            }
        }
    }

    confirm?.let { (exam, enrol) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (enrol) "Enrol in exam?" else "Cancel enrolment?") },
            text = {
                Text(
                    listOfNotNull(
                        exam.examDescription,
                        listOfNotNull(exam.examDate, exam.examTime).joinToString(" ").ifBlank { null },
                    ).joinToString("\n"),
                )
            },
            confirmButton = {
                Button(onClick = {
                    confirm = null
                    vm.setEnrolment(exam, enrol)
                }) { Text(if (enrol) "Enrol" else "Unenrol") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Back") } },
        )
    }
}

private fun commentText(comment: String?): String? = when (comment) {
    null, "" -> null
    "TXT_ENROL" -> "You can enrol"
    "TXT_UNENROL" -> "You can unenrol"
    "TXT_EVAL" -> "Please complete the corresponding course evaluation first"
    "TXT_ENROLMENT_NOT_YET_OPEN" -> "Enrolment is not yet open"
    "TXT_UNENROL_NOT_POSSIBLE" -> "Cancelling your enrolment is no longer possible"
    else -> comment.removePrefix("TXT_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private fun tagText(tag: ActionTag): String? = when (tag.name) {
    "ENROLMENT_DEADLINE" -> "Enrolment deadline: ${tag.value}"
    "UNENROLMENT_DEADLINE" -> "Unenrolment deadline: ${tag.value}"
    "ENROLMENT_START_DATE" -> "Available from: ${tag.value}"
    "ENROLMENT_POSITION_COUNT" -> "Position/Count: ${tag.value}"
    null -> null
    else -> listOfNotNull(tag.name, tag.value).joinToString(": ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExamCard(
    exam: EnrolmentExam,
    busy: Boolean,
    onEnrol: (() -> Unit)?,
    onUnenrol: (() -> Unit)?,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    exam.examDescription ?: "Exam",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (exam.enrolmentDone == true) {
                    Pill("Enrolled", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                } else {
                    Pill("Not enrolled", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val whenText = listOfNotNull(exam.examDate, exam.examTime).joinToString(" · ")
            if (whenText.isNotBlank()) IconLine(Icons.Default.DateRange, whenText)
            val meta = listOfNotNull(exam.examCode?.let { "Code $it" }, exam.examTypeDescription).joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val people = exam.examProfessorName ?: exam.commissionPresident
            if (!people.isNullOrBlank()) IconLine(Icons.Default.Person, people)
            if (!exam.commissionMembers.isNullOrBlank()) {
                Text(
                    "Commission: ${exam.commissionMembers}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            commentText(exam.action?.comment)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            val tags = exam.action?.tags.orEmpty().mapNotNull { tagText(it) }
            if (tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { Pill(it, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer) }
                }
            }

            val type = exam.action?.type
            if (busy) {
                Spacer(Modifier.height(4.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
            } else if (type == "ENROL" && onEnrol != null) {
                Spacer(Modifier.height(4.dp))
                Button(onClick = onEnrol) { Text("Enrol") }
            } else if (type == "UNENROL" && onUnenrol != null) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = onUnenrol) { Text("Unenrol") }
            } else if (type == "EVAL") {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(EXAM_WEBSITE))) }
                }) { Text("Evaluate on website") }
            }
        }
    }
}
