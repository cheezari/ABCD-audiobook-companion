package com.cheezari.chaptercompanion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale

enum class ThemeModeOption(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class AccentPreset(val label: String, val hexColor: Long) {
    AMBER("Warm Amber", 0xFFD49A2A),
    SAGE("Forest Sage", 0xFF5A8F7B),
    SLATE_BLUE("Nordic Slate", 0xFF5C7AEA),
    TERRACOTTA("Terracotta", 0xFFC86D51),
    PLUM("Quiet Plum", 0xFF8C6BB1)
}

data class AppPreferencesState(
    val geminiApiKey: String = "",
    val defaultNoiseDb: Int = -20,
    val defaultSilenceSec: Int = 2,
    val maxRetries: Int = 3,
    val timeoutSeconds: Int = 30,
    val themeMode: ThemeModeOption = ThemeModeOption.SYSTEM,
    val accentPreset: AccentPreset = AccentPreset.AMBER,
    val matchCoverAccent: Boolean = true
)

fun loadAppPreferences(context: Context): AppPreferencesState {
    val prefs = context.getSharedPreferences("abcd_prefs", Context.MODE_PRIVATE)
    val themeName = prefs.getString("theme_mode", ThemeModeOption.SYSTEM.name)
    val themeMode = runCatching { ThemeModeOption.valueOf(themeName ?: "") }
        .getOrDefault(ThemeModeOption.SYSTEM)

    val accentName = prefs.getString("accent_preset", AccentPreset.AMBER.name)
    val accentPreset = runCatching { AccentPreset.valueOf(accentName ?: "") }
        .getOrDefault(AccentPreset.AMBER)

    return AppPreferencesState(
        geminiApiKey = prefs.getString("gemini_api_key", "") ?: "",
        defaultNoiseDb = prefs.getInt("default_noise_db", -20),
        defaultSilenceSec = prefs.getInt("default_silence_sec", 2).coerceAtLeast(1),
        maxRetries = prefs.getInt("max_retries", 3).coerceIn(1, 5),
        timeoutSeconds = prefs.getInt("timeout_seconds", 30).coerceIn(10, 120),
        themeMode = themeMode,
        accentPreset = accentPreset,
        matchCoverAccent = prefs.getBoolean("match_cover_accent", true)
    )
}

fun saveAppPreferences(context: Context, state: AppPreferencesState) {
    val prefs = context.getSharedPreferences("abcd_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putString("gemini_api_key", state.geminiApiKey.trim())
        .putInt("default_noise_db", state.defaultNoiseDb)
        .putInt("default_silence_sec", state.defaultSilenceSec.coerceAtLeast(1))
        .putInt("max_retries", state.maxRetries.coerceIn(1, 5))
        .putInt("timeout_seconds", state.timeoutSeconds.coerceIn(10, 120))
        .putString("theme_mode", state.themeMode.name)
        .putString("accent_preset", state.accentPreset.name)
        .putBoolean("match_cover_accent", state.matchCoverAccent)
        .apply()
}

fun updateAndSavePreferences(
    context: Context,
    current: AppPreferencesState,
    transform: (AppPreferencesState) -> AppPreferencesState
): AppPreferencesState {
    val updated = transform(current)
    saveAppPreferences(context, updated)
    return updated
}

fun validateIntRange(rawText: String, min: Int, max: Int, label: String): String? {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) return "$label cannot be empty ($min–$max)."
    val parsed = trimmed.toIntOrNull() ?: return "$label must be a whole number ($min–$max)."
    if (parsed !in min..max) return "$label must be between $min and $max."
    return null
}
fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}

suspend fun <T> executeWithRetry(
    maxRetries: Int,
    onRetry: (attempt: Int, maxAttempts: Int, waitMs: Long, reason: String) -> Unit = { _, _, _, _ -> },
    block: suspend () -> T
): T {
    val safeMaxRetries = maxRetries.coerceIn(1, 5)
    var lastError: Throwable? = null

    for (attempt in 1..safeMaxRetries) {
        try {
            return block()
        } catch (t: Throwable) {
            lastError = t
            val msg = t.message.orEmpty()
            val isRetryable = t is SocketTimeoutException ||
                    t is IOException ||
                    msg.contains("timeout", ignoreCase = true) ||
                    msg.contains("503") ||
                    msg.contains("502") ||
                    msg.contains("504") ||
                    msg.contains("429") ||
                    msg.contains("UNAVAILABLE", ignoreCase = true)

            if (!isRetryable || attempt >= safeMaxRetries) {
                throw t
            }

            val waitMs = (1000L * (1L shl (attempt - 1))).coerceAtMost(8000L)
            val reason = when {
                t is SocketTimeoutException || msg.contains("timeout", ignoreCase = true) -> "Request timed out"
                msg.contains("503") || msg.contains("UNAVAILABLE", ignoreCase = true) -> "Server busy (503)"
                msg.contains("429") -> "Rate limited (429)"
                else -> "Temporary network error"
            }
            onRetry(attempt, safeMaxRetries, waitMs, reason)
            delay(waitMs)
        }
    }

    throw lastError ?: RuntimeException("Operation failed after $safeMaxRetries attempts.")
}

fun formatDiagnosticReport(
    status: String,
    bookTitle: String,
    bookAuthor: String,
    ffmpegLog: String,
    lastError: String
): String {
    return buildString {
        appendLine("ABCD - Audiobook Chapter Decoder Diagnostic Report")
        appendLine("Generated: ${String.format(Locale.US, "Book='%s', Author='%s'", bookTitle, bookAuthor)}")
        appendLine("Status: $status")
        if (lastError.isNotBlank()) {
            appendLine("--- Last Error ---")
            appendLine(lastError.trim())
        }
        if (ffmpegLog.isNotBlank()) {
            appendLine("--- FFmpeg Log (Tail) ---")
            appendLine(ffmpegLog.trim().takeLast(2500))
        }
    }
}