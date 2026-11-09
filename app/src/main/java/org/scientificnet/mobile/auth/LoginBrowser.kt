package org.scientificnet.mobile.auth

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.scientificnet.mobile.data.Audience
import org.scientificnet.mobile.data.Jwt
import org.scientificnet.mobile.data.StoredToken

private const val TAG = "LoginBrowser"
private const val SITE_ORIGIN = "https://my.scientificnet.org"

/**
 * The exam-enrolment app calls both backends on load (timetable/userdata backend and the exam
 * backend), so opening it is enough to capture both tokens.
 */
const val LOGIN_START_URL = "$SITE_ORIGIN/exam-enrolment/en/"

data class CaptureResult(
    val tokens: Map<Audience, StoredToken>,
    val username: String,
    val domain: String,
)

/** A page that failed to load in the login browser. */
data class PageError(val title: String, val detail: String, val url: String?, val code: Int? = null)

/** scheme://host/path only: query strings and fragments can carry auth codes. */
internal fun sanitizeUrl(url: String?): String {
    if (url.isNullOrBlank()) return "-"
    return runCatching {
        val u = java.net.URI(url.trim())
        "${u.scheme}://${u.host.orEmpty()}${u.rawPath.orEmpty()}"
    }.getOrElse { url.substringBefore('?').substringBefore('#') }
}

private fun sslErrorName(code: Int): String = when (code) {
    SslError.SSL_NOTYETVALID -> "certificate not yet valid (check the phone's date and time)"
    SslError.SSL_EXPIRED -> "certificate expired (check the phone's date and time)"
    SslError.SSL_IDMISMATCH -> "certificate hostname mismatch"
    SslError.SSL_UNTRUSTED -> "certificate not trusted"
    SslError.SSL_DATE_INVALID -> "certificate date invalid (check the phone's date and time)"
    else -> "invalid certificate"
}

/** WebView error codes that mean the network, not the page, is the problem. */
private val NETWORK_ERROR_CODES = setOf(
    WebViewClient.ERROR_CONNECT,
    WebViewClient.ERROR_TIMEOUT,
    WebViewClient.ERROR_HOST_LOOKUP,
    WebViewClient.ERROR_IO,
    WebViewClient.ERROR_PROXY_AUTHENTICATION,
)

/**
 * Opens non-web links (Authenticator, passkeys, app links) outside the WebView.
 * Returns true if the WebView should not load the URL itself.
 */
private fun handleExternalScheme(context: Context, view: WebView, url: String): Boolean {
    val uri = Uri.parse(url)
    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data" || scheme == "blob") return false
    try {
        if (scheme == "intent") {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                // Only allow what a browser would allow.
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) {
                    view.loadUrl(fallback)
                }
            }
        } else {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't open external link $scheme://", e)
    }
    return true
}

/**
 * Hosts the website in a WebView. The site performs the Microsoft login itself; once it starts
 * calling its APIs we read the bearer tokens it sends and call [onCaptured].
 *
 * @param silent if true the browser stays behind a progress screen and only becomes visible if
 *  the login page actually needs the user (e.g. the Microsoft session expired) or a page fails.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginBrowser(
    silent: Boolean,
    onCaptured: (CaptureResult) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val captured = remember { mutableMapOf<Audience, StoredToken>() }
    var username by remember { mutableStateOf<String?>(null) }
    var domain by remember { mutableStateOf<String?>(null) }
    var firstCaptureAt by remember { mutableStateOf<Long?>(null) }
    var captureCount by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(!silent) }
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var lastNavigationAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf<PageError?>(null) }
    /** Bumped to throw away the WebView and create a fresh one. */
    var generation by remember { mutableIntStateOf(0) }
    var rendererCrashes by remember { mutableIntStateOf(0) }
    val history = remember { ArrayDeque<String>() }
    var connTest by remember { mutableStateOf<String?>(null) }
    var connTesting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val onCapturedState by rememberUpdatedState(onCaptured)

    fun log(event: String) {
        history.addLast(event)
        while (history.size > 12) history.removeFirst()
        Log.d(TAG, event)
    }

    fun finishIfReady(force: Boolean) {
        if (finished) return
        val u = username ?: return
        val d = domain ?: return
        if (Audience.COCKPIT !in captured) return
        if (force || Audience.entries.all { it in captured }) {
            finished = true
            onCapturedState(CaptureResult(captured.toMap(), u, d))
        }
    }

    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    // Called with the JSON message from token_capture.js (on the main thread).
    fun handleMessage(raw: String) {
        if (finished) return
        val msg = runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return
        val url = (msg["url"] as? JsonPrimitive)?.content ?: return
        val token = (msg["token"] as? JsonPrimitive)?.content ?: return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        if (!httpUrl.isHttps || !httpUrl.host.endsWith("scientificnet.org")) return

        val payload = Jwt.payload(token) ?: return
        val audience = Jwt.audiences(payload).firstNotNullOfOrNull { Audience.fromAud(it) } ?: return
        val exp = Jwt.expiry(payload) ?: return
        val stored = StoredToken(token, exp)
        if (!stored.isValid()) return

        captured[audience] = stored
        httpUrl.queryParameter("username")?.takeIf { it.isNotBlank() }?.let { username = it }
        httpUrl.queryParameter("domain")?.takeIf { it.isNotBlank() }?.let { domain = it }
        if (firstCaptureAt == null) firstCaptureAt = System.currentTimeMillis()
        captureCount++
        log("captured token for $audience")
        finishIfReady(force = false)
    }

    fun errorDetails(): String = buildString {
        val err = pageError
        appendLine("MySNS login browser error")
        if (err != null) {
            appendLine("Error:   ${err.title}")
            appendLine("Detail:  ${err.detail}")
            appendLine("Page:    ${sanitizeUrl(err.url)}")
        }
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
        val wv = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }.getOrNull()
        appendLine("WebView: ${wv?.packageName ?: "?"} ${wv?.versionName ?: "?"}")
        val app = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("App:     ${app ?: "?"}")
        appendLine("Recent navigation:")
        history.forEach { appendLine("  $it") }
        connTest?.let { appendLine(); append(it) }
    }

    // If only the timetable/userdata token showed up, don't wait forever for the exam one.
    LaunchedEffect(captureCount) {
        if (firstCaptureAt != null && !finished) {
            delay(15_000)
            finishIfReady(force = true)
        }
    }

    // Silent mode: reveal the browser if we're stuck on a non-site page (i.e. a login prompt).
    LaunchedEffect(silent) {
        if (!silent) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        while (!revealed && !finished) {
            delay(500)
            val host = Uri.parse(currentUrl).host.orEmpty()
            val idleFor = System.currentTimeMillis() - lastNavigationAt
            val onForeignPage = host.isNotEmpty() && host != "my.scientificnet.org"
            if (pageError != null ||
                (onForeignPage && !loading && idleFor > 2_500) ||
                System.currentTimeMillis() - startedAt > 45_000
            ) {
                revealed = true
            }
        }
    }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack() && revealed) wv.goBack() else onCancel()
    }

    fun retry() {
        val failedUrl = pageError?.url
        pageError = null
        val wv = webViewRef
        if (wv == null) {
            generation++
        } else if (failedUrl != null && (failedUrl.startsWith("https://") || failedUrl.startsWith("http://"))) {
            wv.loadUrl(failedUrl)
        } else {
            wv.loadUrl(LOGIN_START_URL)
        }
    }

    fun startOver() {
        pageError = null
        log("start over: cleared browser session")
        clearBrowserSession()
        generation++
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (silent && !revealed) "Refreshing session" else "Sign in to scientificnet") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
                actions = {
                    if (revealed) {
                        IconButton(onClick = { webViewRef?.reload() ?: run { generation++ } }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(generation) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                            val script = ctx.assets.open("token_capture.js").bufferedReader().use { it.readText() }
                            val modern = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
                                WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                            if (modern) {
                                WebViewCompat.addDocumentStartJavaScript(this, script, setOf(SITE_ORIGIN))
                                WebViewCompat.addWebMessageListener(this, "SnsBridge", setOf(SITE_ORIGIN)) { _, message, sourceOrigin, _, _ ->
                                    if (sourceOrigin.toString().trimEnd('/') == SITE_ORIGIN) {
                                        message.data?.let { handleMessage(it) }
                                    }
                                }
                            } else {
                                // Older WebView: inject as early as we can and use a classic JS interface.
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun post(message: String) {
                                        mainHandler.post { handleMessage(message) }
                                    }
                                }, "SnsBridgeLegacy")
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    loading = true
                                    currentUrl = url
                                    lastNavigationAt = System.currentTimeMillis()
                                    pageError = null
                                    log("start  ${sanitizeUrl(url)}")
                                    if (!modern && url.startsWith(SITE_ORIGIN)) view.evaluateJavascript(script, null)
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    currentUrl = url
                                    lastNavigationAt = System.currentTimeMillis()
                                    log("done   ${sanitizeUrl(url)}")
                                    if (!modern && url.startsWith(SITE_ORIGIN)) view.evaluateJavascript(script, null)
                                    CookieManager.getInstance().flush()
                                }

                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    lastNavigationAt = System.currentTimeMillis()
                                    val url = request.url.toString()
                                    val external = handleExternalScheme(view.context, view, url)
                                    if (external) log("external ${request.url.scheme}:")
                                    return external
                                }

                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    if (!request.isForMainFrame) return
                                    val desc = error.description?.toString().orEmpty()
                                    log("error  ${error.errorCode} $desc ${sanitizeUrl(request.url.toString())}")
                                    pageError = PageError(
                                        title = "The page couldn't be loaded",
                                        detail = "$desc (code ${error.errorCode})",
                                        url = request.url.toString(),
                                        code = error.errorCode,
                                    )
                                }

                                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                    if (!request.isForMainFrame || response.statusCode < 500) return
                                    log("http   ${response.statusCode} ${sanitizeUrl(request.url.toString())}")
                                    pageError = PageError(
                                        title = "The server returned an error",
                                        detail = "HTTP ${response.statusCode} ${response.reasonPhrase.orEmpty()}".trim(),
                                        url = request.url.toString(),
                                    )
                                }

                                @SuppressLint("WebViewClientOnReceivedSslError")
                                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                                    // Never proceed on certificate errors; explain instead of showing a white page.
                                    handler.cancel()
                                    log("ssl    ${error.primaryError} ${sanitizeUrl(error.url)}")
                                    pageError = PageError(
                                        title = "Secure connection failed",
                                        detail = sslErrorName(error.primaryError),
                                        url = error.url,
                                    )
                                }

                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    // The browser engine crashed or was killed. Returning true keeps the app alive;
                                    // this WebView is unusable now, so replace it with a fresh one.
                                    rendererCrashes++
                                    log("renderer gone (crash=${detail.didCrash()})")
                                    if (webViewRef === view) webViewRef = null
                                    if (rendererCrashes <= 1) {
                                        generation++
                                    } else {
                                        pageError = PageError(
                                            title = "The browser stopped working",
                                            detail = if (detail.didCrash()) "The WebView renderer crashed" else "The WebView renderer was killed (low memory?)",
                                            url = view.url,
                                        )
                                    }
                                    return true
                                }
                            }
                            log("open   ${sanitizeUrl(LOGIN_START_URL)}")
                            loadUrl(LOGIN_START_URL)
                            webViewRef = this
                        }
                    },
                    onRelease = {
                        if (webViewRef === it) webViewRef = null
                        it.destroy()
                    },
                )
            }

            if (loading && revealed) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            if (!revealed || finished) {
                // Covers the (still running) WebView while we work in the background.
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (finished) "Signed in, loading…" else "Connecting to my.scientificnet.org…",
                        textAlign = TextAlign.Center,
                    )
                    if (!finished) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { revealed = true }) { Text("Show browser") }
                    }
                }
            }

            val err = pageError
            if (err != null && !finished) {
                Card(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Column(
                        Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(err.title, style = MaterialTheme.typography.titleMedium)
                        Text(err.detail, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            sanitizeUrl(err.url),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (err.code in NETWORK_ERROR_CODES) {
                            val host = runCatching { java.net.URI(err.url).host }.getOrNull() ?: "the server"
                            Text(
                                "Your network is blocking or cutting the connection to $host. " +
                                    "Try switching between Wi-Fi and mobile data, and turn off any VPN, " +
                                    "Private DNS or ad blocker, then tap Retry.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = ::retry) { Text("Retry") }
                            OutlinedButton(onClick = ::startOver) { Text("Start over") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = !connTesting,
                                onClick = {
                                    connTesting = true
                                    scope.launch {
                                        connTest = runCatching { ConnectionTest.run(context) }
                                            .getOrElse { "Connection test failed: ${it.message}" }
                                        log("connection test done")
                                        connTesting = false
                                    }
                                },
                            ) { Text(if (connTesting) "Testing…" else "Test connection") }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(errorDetails())) }) {
                                Text("Copy details")
                            }
                        }
                        connTest?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { CookieManager.getInstance().flush() }
    }
}

/** Forget the Microsoft/website session so the next login starts fresh. */
fun clearBrowserSession() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
}
