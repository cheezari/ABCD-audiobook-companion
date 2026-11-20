package com.cheezari.chaptercompanion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ExportRecord(
    val id: String,
    val exportedAtMillis: Long,
    val fileLabel: String,
    val chapterCount: Int
)

data class SavedBookRecord(
    val id: String,
    val title: String,
    val author: String,
    val sourceFileName: String = "",
    val sourceExtension: String = "mp3",
    val coverFilePath: String = "",
    val lastListenedChapter: Int = 1,
    val savedSummaryText: String = "",
    val chapters: List<FinalChapter> = emptyList(),
    val exportHistory: List<ExportRecord> = emptyList(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("author", author)
        put("sourceFileName", sourceFileName)
        put("sourceExtension", sourceExtension)
        put("coverFilePath", coverFilePath)
        put("lastListenedChapter", lastListenedChapter)
        put("savedSummaryText", savedSummaryText)
        put("updatedAtMillis", updatedAtMillis)
        put("chapters", JSONArray().apply {
            chapters.forEach { ch ->
                put(JSONObject().apply {
                    put("startSeconds", ch.startSeconds)
                    put("title", ch.title)
                })
            }
        })
        put("exportHistory", JSONArray().apply {
            exportHistory.forEach { rec ->
                put(JSONObject().apply {
                    put("id", rec.id)
                    put("exportedAtMillis", rec.exportedAtMillis)
                    put("fileLabel", rec.fileLabel)
                    put("chapterCount", rec.chapterCount)
                })
            }
        })
    }

    companion object {
        fun fromJson(obj: JSONObject): SavedBookRecord {
            val chArray = obj.optJSONArray("chapters") ?: JSONArray()
            val parsedChapters = buildList {
                for (i in 0 until chArray.length()) {
                    val item = chArray.optJSONObject(i) ?: continue
                    add(
                        FinalChapter(
                            startSeconds = item.optDouble("startSeconds", 0.0),
                            title = item.optString("title", "Chapter ${i + 1}")
                        )
                    )
                }
            }

            val histArray = obj.optJSONArray("exportHistory") ?: JSONArray()
            val parsedHistory = buildList {
                for (i in 0 until histArray.length()) {
                    val item = histArray.optJSONObject(i) ?: continue
                    add(
                        ExportRecord(
                            id = item.optString("id", UUID.randomUUID().toString()),
                            exportedAtMillis = item.optLong("exportedAtMillis", 0L),
                            fileLabel = item.optString("fileLabel", "Chapterized Copy"),
                            chapterCount = item.optInt("chapterCount", 0)
                        )
                    )
                }
            }

            return SavedBookRecord(
                id = obj.optString("id", UUID.randomUUID().toString()),
                title = obj.optString("title", "Untitled Book"),
                author = obj.optString("author", ""),
                sourceFileName = obj.optString("sourceFileName", ""),
                sourceExtension = obj.optString("sourceExtension", "mp3"),
                coverFilePath = obj.optString("coverFilePath", ""),
                lastListenedChapter = obj.optInt("lastListenedChapter", 1).coerceAtLeast(1),
                savedSummaryText = obj.optString("savedSummaryText", ""),
                chapters = parsedChapters,
                exportHistory = parsedHistory,
                updatedAtMillis = obj.optLong("updatedAtMillis", System.currentTimeMillis())
            )
        }
    }
}

private fun libraryFile(context: Context): File =
    File(context.filesDir, "abcd_library_books.json")

private fun coversDir(context: Context): File =
    File(context.filesDir, "book_covers").apply { mkdirs() }

fun loadSavedBooks(context: Context): List<SavedBookRecord> {
    val file = libraryFile(context)
    if (!file.exists()) return emptyList()
    return try {
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        val array = JSONArray(text)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(SavedBookRecord.fromJson(obj))
            }
        }.sortedByDescending { it.updatedAtMillis }
    } catch (_: Throwable) {
        emptyList()
    }
}

fun saveSavedBooks(context: Context, books: List<SavedBookRecord>) {
    val array = JSONArray()
    books.forEach { array.put(it.toJson()) }
    libraryFile(context).writeText(array.toString(2))
}

fun saveCoverBytesToDisk(context: Context, bookId: String, bytes: ByteArray?): String {
    if (bytes == null || bytes.isEmpty()) return ""
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return ""
    val outFile = File(coversDir(context), "$bookId.jpg")
    FileOutputStream(outFile).use { fos ->
        decoded.compress(Bitmap.CompressFormat.JPEG, 88, fos)
    }
    return outFile.absolutePath
}

suspend fun fetchBookCoverFromOpenLibrary(
    context: Context,
    bookId: String,
    title: String,
    author: String
): String = withContext(Dispatchers.IO) {
    if (title.isBlank()) return@withContext ""
    val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    try {
        val qTitle = URLEncoder.encode(title.trim(), "UTF-8")
        val qAuthor = if (author.isNotBlank()) "&author=" + URLEncoder.encode(author.trim(), "UTF-8") else ""
        val searchUrl = "https://openlibrary.org/search.json?title=$qTitle$qAuthor&limit=1"
        val searchReq = Request.Builder().url(searchUrl).build()
        val coverId: Int? = client.newCall(searchReq).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string().orEmpty()
            val docs = JSONObject(body).optJSONArray("docs") ?: return@use null
            val first = docs.optJSONObject(0) ?: return@use null
            val id = first.optInt("cover_i", -1)
            if (id > 0) id else null
        } ?: return@withContext ""

        val imgUrl = "https://covers.openlibrary.org/b/id/$coverId-L.jpg"
        val imgReq = Request.Builder().url(imgUrl).build()
        client.newCall(imgReq).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext ""
            val bytes = resp.body?.bytes() ?: return@withContext ""
            saveCoverBytesToDisk(context, bookId, bytes)
        }
    } catch (_: Throwable) {
        ""
    }
}

fun formatCacheSizeLabel(context: Context): String {
    val bytes = calculateCacheBytes(context)
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

fun calculateCacheBytes(context: Context): Long {
    fun dirBytes(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
    return dirBytes(context.cacheDir) + dirBytes(context.externalCacheDir)
}

fun clearAppCacheFiles(context: Context) {
    fun cleanDir(dir: File?) {
        if (dir == null || !dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                child.deleteRecursively()
            } else {
                child.delete()
            }
        }
    }
    cleanDir(context.cacheDir)
    cleanDir(context.externalCacheDir)
}
