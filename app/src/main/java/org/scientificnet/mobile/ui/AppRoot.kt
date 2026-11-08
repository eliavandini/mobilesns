package org.scientificnet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.scientificnet.mobile.auth.LoginBrowser
import org.scientificnet.mobile.data.Account

enum class Tab(val label: String, val icon: ImageVector) {
    LECTURES("Lectures", Icons.Default.DateRange),
    EXAMS("Exams", Icons.AutoMirrored.Filled.List),
    GRADES("Grades", Icons.Default.Star),
}

@Composable
fun AppRoot(vm: AppViewModel = viewModel()) {
    val account by vm.account.collectAsStateWithLifecycle()
    val authMode by vm.authMode.collectAsStateWithLifecycle()
    val accountError by vm.accountError.collectAsStateWithLifecycle()
    val canRetryProfile by vm.canRetryProfile.collectAsStateWithLifecycle()

    when {
        authMode != null -> LoginBrowser(
            silent = authMode == AuthMode.SILENT,
            onCaptured = vm::onCaptured,
            onCancel = vm::cancelAuth,
        )
        account == null -> WelcomeScreen(
            error = accountError,
            onAddAccount = vm::startAddAccount,
            onRetry = if (canRetryProfile) vm::retryProfile else null,
        )
        else -> MainScreen(vm, account!!)
    }
}

@Composable
private fun WelcomeScreen(error: String?, onAddAccount: () -> Unit, onRetry: (() -> Unit)?) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.AccountCircle, contentDescription = null,
                modifier = Modifier.size(96.dp), tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(24.dp))
            Text("Welcome to MySNS", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Add your university account to see your lectures, exams and grades from my.scientificnet.org.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onAddAccount) { Text("Add account") }
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel, account: Account) {
    var tab by rememberSaveable { mutableStateOf(Tab.LECTURES) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.label) },
                actions = {
                    IconButton(onClick = {
                        when (tab) {
                            Tab.LECTURES -> vm.loadLectures()
                            Tab.EXAMS -> vm.loadExams()
                            Tab.GRADES -> vm.loadGrades()
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.AccountCircle, contentDescription = "Account")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(account.displayName, style = MaterialTheme.typography.titleMedium)
                                account.email?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                account.degree?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Sign out") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    vm.signOut()
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                Tab.LECTURES -> LecturesScreen(vm)
                Tab.EXAMS -> ExamsScreen(vm)
                Tab.GRADES -> GradesScreen(vm)
            }
        }
    }
}
