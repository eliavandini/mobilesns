package org.scientificnet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.scientificnet.mobile.data.CareerExam
import org.scientificnet.mobile.data.CreditPoints

@Composable
fun GradesScreen(vm: AppViewModel) {
    val state by vm.grades.collectAsStateWithLifecycle()
    StateContent(state, onRefresh = vm::loadGrades, onRetry = vm::retry) { grades ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SummaryCard(grades.credits) }
            grades.career.groups.filter { it.exams.isNotEmpty() }.forEach { group ->
                item(key = "g-${group.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            group.descriptionEn ?: "Courses",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${Fmt.number(group.achievedCreditPoints)} / ${Fmt.number(group.minCreditPoints)} CP",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val sorted = group.exams.sortedWith(
                    compareByDescending<CareerExam> { it.status?.id == 2 }
                        .thenByDescending { it.examDate }
                        .thenBy { it.degreeYear }
                        .thenBy { it.title },
                )
                items(sorted, key = { "e-${group.id}-${it.id}" }) { ExamRow(it) }
            }
        }
    }
}

@Composable
private fun SummaryCard(c: CreditPoints) {
    val needed = (c.minimalPointsNecessary ?: 0.0) + (c.finalExamCreditPoints ?: 0.0)
    val earned = c.pointsCurricular ?: 0.0
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Stat("Average", "${Fmt.number(c.averageMark30)} / ${Fmt.number(c.averageMark30Max)}", Modifier.weight(1f))
                Stat("Base mark", "${Fmt.number(c.averageMark)} / ${Fmt.number(c.averageMarkMax)}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Credits: ${Fmt.number(earned)}" + if (needed > 0) " / ${Fmt.number(needed)} CP" else " CP",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (needed > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (earned / needed).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExamRow(e: CareerExam) {
    val passed = e.status?.id == 2
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.bodyLarge)
                val details = listOfNotNull(
                    e.creditPoints?.let { "${Fmt.number(it)} CP" },
                    e.degreeYear?.takeIf { it > 0 }?.let { "Year $it" },
                    Fmt.parseDateTime(e.examDate)?.toLocalDate()?.format(Fmt.date),
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                passed && e.mark != null -> Text(
                    if (e.cumLaude) "${e.mark}L" else "${e.mark}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                passed -> Pill("Passed", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                else -> Pill(
                    e.status?.descriptionEn ?: e.status?.value ?: "To do",
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}
