package com.openminis.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.openminis.app.R

/**
 * [T-android-copy-conversation-id] The clipboard, as a seam.
 *
 * Android's `ClipboardManager` is not available to a JVM unit test, so a test
 * that wants to assert "the thing that reached the clipboard was the expected ID"
 * has to be an instrumented test — which means it does not run in CI and cannot
 * be used as the counter-proof for this requirement. A one-method port keeps that
 * assertion on the JVM: [performClipboardCopy] is the whole behaviour, and
 * production supplies [AndroidClipboardWriter].
 */
internal interface ClipboardWriter {
    /** @return true only when the value actually reached the clipboard. */
    fun write(label: String, value: String): Boolean
}

/** The production writer. Reports failure instead of swallowing it. */
internal class AndroidClipboardWriter(private val context: Context) : ClipboardWriter {
    override fun write(label: String, value: String): Boolean = runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }.isSuccess
}

/** What one copy attempt produced, as the UI must render it. */
internal data class ClipboardCopyOutcome(
    val copied: Boolean,
    /** Already-localised text for the toast. */
    val message: String,
)

/**
 * Copy [value] and decide what the user is told.
 *
 * The failure branch is load-bearing, not decoration: `CLIPBOARD_SERVICE` can be
 * absent and `setPrimaryClip` can throw, and a copy that did not happen must not
 * be reported as if it had. The plain "Copy" on a user message bubble used to
 * call `setPrimaryClip` and say nothing at all, so success and silent failure
 * looked identical on screen — this is the single place that fixes that for
 * every clipboard action the requirement adds.
 */
internal fun performClipboardCopy(
    writer: ClipboardWriter,
    label: String,
    value: String,
    successText: String,
    failureText: String,
): ClipboardCopyOutcome {
    val copied = writer.write(label, value)
    return ClipboardCopyOutcome(
        copied = copied,
        message = if (copied) successText else failureText,
    )
}

/**
 * [performClipboardCopy] against the real clipboard, with the outcome surfaced as
 * a toast.
 *
 * Shared rather than private to the chat screen because request.md:136 names two
 * entry points in two different screens — the chat overflow menu and the session
 * list's long-press menu — and two copies of "copy + tell the user" is exactly
 * how they would drift apart.
 */
internal fun copyToClipboardWithFeedback(
    context: Context,
    label: String,
    value: String,
    successText: String,
) {
    val outcome = performClipboardCopy(
        writer = AndroidClipboardWriter(context),
        label = label,
        value = value,
        successText = successText,
        failureText = context.getString(R.string.chat_copy_id_failed_toast),
    )
    Toast.makeText(context, outcome.message, Toast.LENGTH_SHORT).show()
}
