package org.scientificnet.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.scientificnet.mobile.crash.CrashActivity
import org.scientificnet.mobile.crash.CrashReporter
import org.scientificnet.mobile.ui.AppRoot
import org.scientificnet.mobile.ui.SnsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A crash report that couldn't be shown when it happened: show it before anything else.
        if (CrashReporter.hasPending(this)) {
            startActivity(Intent(this, CrashActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            SnsTheme { AppRoot() }
        }
    }
}
