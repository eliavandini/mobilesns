package org.scientificnet.mobile

import android.app.Application
import org.scientificnet.mobile.crash.CrashReporter

class SnsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The crash screen runs in its own ":crash" process; don't install the handler there.
        if (!CrashReporter.isCrashProcess(this)) CrashReporter.install(this)
    }
}
