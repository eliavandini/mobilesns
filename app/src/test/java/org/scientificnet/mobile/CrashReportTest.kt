package org.scientificnet.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import org.scientificnet.mobile.crash.CrashReporter
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CrashReportTest {
    @Test fun reportContainsContextAndStackTrace() {
        val error = IllegalStateException("boom", RuntimeException("root cause"))
        val report = CrashReporter.formatReport(
            time = ZonedDateTime.of(2026, 11, 8, 12, 0, 0, 0, ZoneOffset.UTC),
            appVersion = "0.1.4 (4)",
            android = "14 (API 34)",
            device = "Acme Phone",
            threadName = "main",
            error = error,
        )
        assertTrue(report.contains("App:     0.1.4 (4)"))
        assertTrue(report.contains("Error:   java.lang.IllegalStateException: boom"))
        assertTrue(report.contains("Caused by: java.lang.RuntimeException: root cause"))
        assertTrue(report.contains("CrashReportTest.reportContainsContextAndStackTrace"))
    }
}
