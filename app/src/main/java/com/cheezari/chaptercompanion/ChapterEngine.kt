package com.cheezari.chaptercompanion

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

data class SilencePause(
    val id: Long,
    val startSeconds: Double,
    val silenceDurationSeconds: Double? = null, // null = manually inserted break
    val isSelected: Boolean = true,
    val presetOption: String = "[Sequential Chapter]",
    val customTitle: String = ""
)

data class FinalChapter(
    val startSeconds: Double,
    val title: String
)

fun formatHms(totalSeconds: Double): String {
    val safeSec = if (totalSeconds.isNaN() || totalSeconds < 0.0) 0L else totalSeconds.toLong()
    val hours = safeSec / 3600
    val minutes = (safeSec % 3600) / 60
    val seconds = safeSec % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

fun formatPauseLabel(pause: SilencePause): String {
    val hms = formatHms(pause.startSeconds)
    val durationPart = pause.silenceDurationSeconds?.let { dur ->
        "(${String.format(Locale.US, "%.2f", dur)}s)"
    } ?: "(Manual)"
    return "$hms $durationPart"
}

/**
 * Parses a timestamp string entered either as seconds ("95" or "95.5")
 * or as "hh:mm:ss" / "mm:ss" ("00:01:35" or "01:35").
 */
fun parseTimestampInput(rawInput: String): Double? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    if (":" in trimmed) {
        val parts = trimmed.split(":")
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.trim().toDoubleOrNull() }
        if (nums.any { it == null || it < 0.0 }) return null

        return if (parts.size == 3) {
            val h = nums[0]!!
            val m = nums[1]!!
            val s = nums[2]!!
            (h * 3600.0) + (m * 60.0) + s
        } else {
            val m = nums[0]!!
            val s = nums[1]!!
            (m * 60.0) + s
        }
    }

    val sec = trimmed.toDoubleOrNull() ?: return null
    return if (sec >= 0.0) sec else null
}

/**
 * Sorts pauses chronologically first so manual insertions and +1s/-1s nudges
 * always produce clean, in-order sequential chapter numbers across selected pauses.
 */
fun computeFinalChapters(pauses: List<SilencePause>): List<FinalChapter> {
    val sortedSelected = pauses
        .filter { it.isSelected }
        .sortedBy { it.startSeconds }

    var seqChapterNum = 1
    val results = mutableListOf<FinalChapter>()

    for (pause in sortedSelected) {
        val title = when (pause.presetOption) {
            "[Sequential Chapter]" -> {
                val label = "Chapter $seqChapterNum"
                seqChapterNum += 1
                label
            }
            "[Custom Freetext]" -> {
                pause.customTitle.trim().ifBlank {
                    "Chapter $seqChapterNum".also { seqChapterNum += 1 }
                }
            }
            else -> {
                if (pause.presetOption.startsWith("Chapter ")) {
                    val parsed = pause.presetOption.removePrefix("Chapter ").trim().toIntOrNull()
                    if (parsed != null) seqChapterNum = parsed + 1
                }
                pause.presetOption
            }
        }

        results.add(FinalChapter(startSeconds = pause.startSeconds, title = title))
    }

    return results
}

suspend fun copyUriToCache(context: Context, uri: Uri, fileName: String): File =
    withContext(Dispatchers.IO) {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFile = File(context.cacheDir, "input_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open selected audiobook file.")
        outFile
    }

suspend fun runSilenceDetection(
    inputFile: File,
    noiseDb: Int = -20,
    minDurationSec: Int = 2
): Pair<List<Pair<Double, Double>>, String> = withContext(Dispatchers.IO) {
    require(inputFile.exists() && inputFile.length() > 0L) {
        "Cached input audio file is missing or empty."
    }

    val rawCmd =
        "-i \"${inputFile.absolutePath}\" -af silencedetect=noise=${noiseDb}dB:d=${minDurationSec} -f null -"

    val logBuilder = StringBuilder()
    val detectedPairs = mutableListOf<Pair<Double, Double>>()
    var pendingStart: Double? = null

    try {
        val args = FFmpegKitConfig.parseArguments(rawCmd)
        val session = FFmpegKit.executeWithArguments(args)
        session.logs?.forEach { log ->
            val msg = log?.message.orEmpty()
            if (msg.isNotEmpty()) {
                logBuilder.appendLine(msg)

                if ("silence_start:" in msg) {
                    val afterStart = msg.substringAfter("silence_start:").trim()
                    val numToken = afterStart.substringBefore(" ").trim()
                    pendingStart = numToken.toDoubleOrNull()
                }

                if ("silence_duration:" in msg && pendingStart != null) {
                    val afterDur = msg.substringAfter("silence_duration:").trim()
                    val durToken = afterDur.substringBefore(" ").trim()
                    val durSec = durToken.toDoubleOrNull()
                    if (durSec != null) {
                        detectedPairs.add(pendingStart!! to durSec)
                        pendingStart = null
                    }
                }
            }
        }
    } catch (t: Throwable) {
        throw RuntimeException("FFmpeg native execution failed: ${t.message ?: t::class.java.simpleName}")
    }

    detectedPairs to logBuilder.toString()
}

/**
 * Injects ;FFMETADATA1 chapter markers + editable title/artist tags
 * WITHOUT audio re-encoding (-c copy), preserving .mp3 or .m4b container format.
 */

suspend fun injectChaptersWithoutReencodeInCache(
    context: Context,
    inputFile: File,
    extension: String,
    bookTitle: String,
    bookAuthor: String,
    chapters: List<FinalChapter>
): Pair<File, String> = withContext(Dispatchers.IO) {
    require(chapters.isNotEmpty()) { "Select at least one chapter marker before exporting." }
    require(inputFile.exists() && inputFile.length() > 0L) { "Input file not found in cache." }

    val cleanExt = extension.lowercase(Locale.US).removePrefix(".").ifBlank { "mp3" }
    val orderedChapters = chapters.sortedBy { it.startSeconds }
    val metaFile = File(context.cacheDir, "ffmetadata.txt")
    val sb = StringBuilder()
    sb.appendLine(";FFMETADATA1")
    if (bookTitle.isNotBlank()) {
        sb.appendLine("title=${bookTitle.replace("\n", " ").replace("=", " ")}")
    }
    if (bookAuthor.isNotBlank()) {
        sb.appendLine("artist=${bookAuthor.replace("\n", " ").replace("=", " ")}")
    }
    sb.appendLine()

    orderedChapters.forEachIndexed { idx, ch ->
        val startMs = (ch.startSeconds * 1000.0).toLong().coerceAtLeast(0L)
        val endMs = if (idx < orderedChapters.lastIndex) {
            (orderedChapters[idx + 1].startSeconds * 1000.0).toLong().coerceAtLeast(startMs + 1000L)
        } else {
            startMs + 86_400_000L
        }

        sb.appendLine("[CHAPTER]")
        sb.appendLine("TIMEBASE=1/1000")
        sb.appendLine("START=$startMs")
        sb.appendLine("END=$endMs")
        sb.appendLine("title=${ch.title.replace("\n", " ").replace("=", " ")}")
        sb.appendLine()
    }
    metaFile.writeText(sb.toString())

    val outputFile = File(context.cacheDir, "chapterized_copy.$cleanExt")
    if (outputFile.exists()) outputFile.delete()

    val rawCmd = if (cleanExt == "mp3") {
        "-y -i \"${inputFile.absolutePath}\" -i \"${metaFile.absolutePath}\" " +
                "-map_metadata 1 -map_chapters 1 -c copy -id3v2_version 3 \"${outputFile.absolutePath}\""
    } else {
        "-y -i \"${inputFile.absolutePath}\" -i \"${metaFile.absolutePath}\" " +
                "-map_metadata 1 -map_chapters 1 -c copy \"${outputFile.absolutePath}\""
    }

    val logBuilder = StringBuilder()
    try {
        val args = FFmpegKitConfig.parseArguments(rawCmd)
        val session = FFmpegKit.executeWithArguments(args)
        session.logs?.forEach { log ->
            log?.message?.let { logBuilder.appendLine(it) }
        }
    } catch (t: Throwable) {
        throw RuntimeException("Metadata injection error: ${t.message ?: t::class.java.simpleName}")
    }

    val logsStr = logBuilder.toString()
    if (!outputFile.exists() || outputFile.length() == 0L) {
        throw RuntimeException("Metadata injection failed:\n${logsStr.takeLast(800)}")
    }

    outputFile to logsStr
}

suspend fun exportCacheFileToUri(context: Context, sourceFile: File, destUri: Uri) =
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(destUri)?.use { out ->
            sourceFile.inputStream().use { input ->
                input.copyTo(out)
            }
        } ?: throw IllegalStateException("Could not write to destination file.")
    }