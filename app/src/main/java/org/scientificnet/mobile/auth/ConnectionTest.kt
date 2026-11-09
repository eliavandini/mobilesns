package org.scientificnet.mobile.auth

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Network diagnostics for the login browser: what kind of network the phone is on (VPN,
 * Private DNS...) and whether the hosts involved in sign-in are reachable with the app's own
 * HTTP stack (OkHttp), which is independent from the WebView's.
 */
object ConnectionTest {
    private val targets = listOf(
        "Microsoft login" to "https://login.microsoftonline.com/common/discovery/instance?api-version=1.1" +
            "&authorization_endpoint=https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        "scientificnet" to "https://my.scientificnet.org/",
        "Internet (Google)" to "https://www.google.com/generate_204",
    )

    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        buildString {
            appendLine("Connection test")
            appendLine(networkSummary(context))
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
            for ((name, url) in targets) {
                val host = java.net.URI(url).host
                val dns = runCatching { InetAddress.getAllByName(host).joinToString { it.hostAddress ?: "?" } }
                    .getOrElse { "DNS failed: ${it.javaClass.simpleName}: ${it.message}" }
                val started = System.nanoTime()
                val result = runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { "HTTP ${it.code}" }
                }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
                val ms = (System.nanoTime() - started) / 1_000_000
                appendLine("- $name ($host)")
                appendLine("    DNS: $dns")
                appendLine("    App HTTPS: $result (${ms} ms)")
            }
        }.trimEnd()
    }

    private fun networkSummary(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "Network: unknown"
        val network = cm.activeNetwork ?: return "Network: none (offline?)"
        val caps = cm.getNetworkCapabilities(network)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile data")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
        }.ifEmpty { listOf("unknown") }
        val link = cm.getLinkProperties(network)
        val privateDns = if (Build.VERSION.SDK_INT >= 28 && link != null) {
            when {
                !link.isPrivateDnsActive -> "off"
                link.privateDnsServerName != null -> "on (${link.privateDnsServerName})"
                else -> "on (automatic)"
            }
        } else "?"
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        return "Network: ${transports.joinToString(" + ")}, Private DNS: $privateDns, internet validated: $validated"
    }
}
