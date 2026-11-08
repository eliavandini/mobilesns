package org.scientificnet.mobile.crash

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.scientificnet.mobile.MainActivity
import org.scientificnet.mobile.ui.SnsTheme
import kotlin.system.exitProcess

/** Shown (in the separate ":crash" process) after the app crashed. */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val report = CrashReporter.takePending(this)
            ?: CrashReporter.lastReport(this)
            ?: "No crash report was saved."
        setContent {
            SnsTheme {
                CrashScreen(
                    report = report,
                    onRestart = ::restart,
                    onCopy = { copy(report) },
                    onShare = { share(report) },
                    onReset = ::resetData,
                    onClose = ::close,
                )
            }
        }
    }

    private fun restart() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        close()
    }

    private fun close() {
        finishAndRemoveTask()
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }

    private fun copy(report: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MySNS crash report", report))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share(report: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "MySNS crash report")
            .putExtra(Intent.EXTRA_TEXT, report)
        startActivity(Intent.createChooser(send, "Share crash report"))
    }

    /** Wipes all app data (account, tokens, browser session) and closes the app. */
    private fun resetData() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (!am.clearApplicationUserData()) {
            Toast.makeText(this, "Couldn't reset app data", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun CrashScreen(
    report: String,
    onRestart: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmReset by remember { mutableStateOf(false) }
    val headline = report.lineSequence().firstOrNull { it.startsWith("Error:") }?.removePrefix("Error:")?.trim()

    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Warning, contentDescription = null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(48.dp),
            )
            Text("MySNS crashed", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Something went wrong and the app had to close. You can restart it, or send the " +
                    "report below so the problem can be fixed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (headline != null) {
                Text(headline, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            }

            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Restart app") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy report") }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share report") }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SelectionContainer {
                    Text(
                        report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
                    )
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "If the app keeps crashing right after opening, resetting its data usually helps. " +
                    "You'll need to sign in again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmReset = true }) { Text("Reset app data") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset app data?") },
            text = { Text("This signs you out and clears all saved data. The app will close.") },
            confirmButton = {
                Button(onClick = { confirmReset = false; onReset() }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
