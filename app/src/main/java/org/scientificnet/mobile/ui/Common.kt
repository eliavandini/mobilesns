package org.scientificnet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Shows loading / error / content for a [LoadState], with pull-to-refresh. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> StateContent(
    state: LoadState<T>,
    onRefresh: () -> Unit,
    onRetry: () -> Unit = onRefresh,
    content: @Composable (T) -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = state.loading && state.data != null,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        val data = state.data
        when {
            data != null -> content(data)
            state.error != null -> CenteredMessage(state.error, actionLabel = "Retry", onAction = onRetry)
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
    }
}

@Composable
fun CenteredMessage(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun Pill(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(50)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

object Fmt {
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
    val dayHeader: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
    val shortDay: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val examDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    fun parseDateTime(s: String?): LocalDateTime? = s?.let { runCatching { LocalDateTime.parse(it.removeSuffix("Z")) }.getOrNull() }

    /** Exam enrolment dates use dd.MM.yyyy. */
    fun parseExamDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it.trim(), examDate) }.getOrNull() }

    fun number(d: Double?): String = when {
        d == null -> "–"
        d % 1.0 == 0.0 -> d.toLong().toString()
        else -> String.format(Locale.getDefault(), "%.2f", d)
    }
}
