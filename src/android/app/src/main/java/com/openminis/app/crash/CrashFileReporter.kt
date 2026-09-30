package com.openminis.app.crash

import android.content.Context
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.ReportField
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import java.io.File

/**
 * T283: Java/Kotlin crash → file. Writes a single text report into
 * `filesDir/logs/` using the same `.log` extension that
 * [com.openminis.app.logging.AppLogger.listLogFiles] already filters
 * for, so reports surface in LogManagementScreen without needing a
 * separate crash-files screen.
 *
 * Naming and retention are [CrashReportStore]'s job (millisecond stamps so two
 * crashes in the same second cannot share a file, plus a visible count cap), so
 * that both are reachable from a JVM test; this class only gathers the report
 * body out of ACRA.
 *
 * Service-loader registered via
 * `META-INF/services/org.acra.sender.ReportSenderFactory`.
 *
 * No network. No HTTP sender. No external dependencies beyond
 * `acra-core`.
 */
class CrashFileSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val dir = File(context.filesDir, "logs")
        // One clock read for both the report's Time: line and its filename, so
        // the two can never disagree. Millisecond stamp: a second-resolution one
        // made two crashes inside the same second overwrite each other.
        val now = System.currentTimeMillis()
        val stamp = CrashReportStore.stamp(now)

        val body = buildString {
            appendLine("=== Minis Java/Kotlin Crash ===")
            appendLine("Time: $stamp")
            appendLine("Version: ${errorContent.getString(ReportField.APP_VERSION_NAME)} " +
                "(${errorContent.getString(ReportField.APP_VERSION_CODE)})")
            appendLine("Android: ${errorContent.getString(ReportField.ANDROID_VERSION)} " +
                "(SDK ${errorContent.getString(ReportField.BUILD)})")
            appendLine("Device: ${errorContent.getString(ReportField.PHONE_MODEL)} " +
                "(${errorContent.getString(ReportField.BRAND)})")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(errorContent.getString(ReportField.STACK_TRACE))
            val logcat = errorContent.getString(ReportField.LOGCAT)
            if (!logcat.isNullOrBlank()) {
                appendLine()
                appendLine("--- Logcat (last 200 lines) ---")
                appendLine(logcat)
            }
        }
        // The store owns naming + retention, and has already appended its own
        // "N oldest reports were deleted" notice to the body when it had to drop
        // something — so the count is readable in the Logs screen. Mirror it to
        // logcat too, since a crash-file write is otherwise silent.
        val outcome = CrashReportStore.writeReport(dir, body, now)
        if (outcome.dropped > 0) {
            android.util.Log.w(
                "CrashFileSender",
                "[crash-retention] deleted ${outcome.dropped} oldest crash report(s) to stay within " +
                    "CrashReportStore.MAX_REPORTS=${CrashReportStore.MAX_REPORTS}; " +
                    "the count is recorded inside ${outcome.file.name}",
            )
        }
    }
}

/**
 * Service-loader factory. ACRA discovers and instantiates senders via
 * this factory at crash time on the dedicated `:acra` reporter process.
 */
class CrashFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        CrashFileSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
