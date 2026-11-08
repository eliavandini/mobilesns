package org.scientificnet.mobile.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
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

/**
 * Hosts the website in a WebView. The site performs the Microsoft login itself; once it starts
 * calling its APIs we read the bearer tokens it sends and call [onCaptured].
 *
 * @param silent if true the browser stays hidden behind a progress screen and only becomes
 *  visible if the login page actually needs the user (e.g. the Microsoft session expired).
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginBrowser(
    silent: Boolean,
    onCaptured: (CaptureResult) -> Unit,
    onCancel: () -> Unit,
) {
    val captured = remember { mutableMapOf<Audience, StoredToken>() }
    var username by remember { mutableStateOf<String?>(null) }
    var domain by remember { mutableStateOf<String?>(null) }
    var firstCaptureAt by remember { mutableStateOf<Long?>(null) }
    var captureCount by remember { mutableStateOf(0) }
    var finished by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(!silent) }
    var loading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf("") }
    var lastNavigationAt by remember { mutableStateOf(System.currentTimeMillis()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val onCapturedState by rememberUpdatedState(onCaptured)

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
        val msg = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) }.getOrNull()
            as? kotlinx.serialization.json.JsonObject ?: return
        val url = (msg["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
        val token = (msg["token"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return
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
        Log.d(TAG, "captured token for $audience")
        finishIfReady(force = false)
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
            if ((onForeignPage && !loading && idleFor > 2_500) ||
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (silent && !revealed) "Refreshing session" else "Sign in to scientificnet") },
                navigationIcon = {
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, contentDescription = "Cancel") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize().alpha(if (revealed) 1f else 0f),
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
                            WebViewCompat.addWebMessageListener(this, "SnsBridge", setOf(SITE_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
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
                                if (!modern && url.startsWith(SITE_ORIGIN)) view.evaluateJavascript(script, null)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                                currentUrl = url
                                lastNavigationAt = System.currentTimeMillis()
                                if (!modern && url.startsWith(SITE_ORIGIN)) view.evaluateJavascript(script, null)
                                CookieManager.getInstance().flush()
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                lastNavigationAt = System.currentTimeMillis()
                                return false
                            }
                        }
                        loadUrl(LOGIN_START_URL)
                        webViewRef = this
                    }
                },
                onRelease = { it.destroy() },
            )

            if (loading && revealed) {
                LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }

            if (!revealed || finished) {
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
