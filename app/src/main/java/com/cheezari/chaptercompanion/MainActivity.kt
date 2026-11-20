package com.cheezari.chaptercompanion

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private enum class AppTab(val label: String) {
    CHAPTERIZER("Chapterizer"),
    LIBRARY("Library"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ChapterDecoderRoot()
        }
    }
}

@Composable
fun ChapterDecoderRoot() {
    val context = LocalContext.current
    var appSettings by remember { mutableStateOf(loadAppPreferences(context)) }
    var coverAccentColor by remember { mutableStateOf<Color?>(null) }

    AbcdTheme(
        themeMode = appSettings.themeMode,
        accentPreset = appSettings.accentPreset,
        coverAccentColor = coverAccentColor,
        matchCoverAccent = appSettings.matchCoverAccent
    ) {
        ChapterDecoderApp(
            appSettings = appSettings,
            onSettingsUpdated = { updated -> appSettings = updated },
            onCoverAccentExtracted = { seed -> coverAccentColor = seed }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDecoderApp(
    appSettings: AppPreferencesState,
    onSettingsUpdated: (AppPreferencesState) -> Unit,
    onCoverAccentExtracted: (Color?) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pauseIdGenerator = remember { AtomicLong(1L) }

    var currentTab by remember { mutableStateOf(AppTab.CHAPTERIZER) }

    // Saved Library state
    var savedBooks by remember { mutableStateOf(loadSavedBooks(context)) }
    var activeBookId by remember { mutableStateOf(savedBooks.firstOrNull()?.id ?: UUID.randomUUID().toString()) }
    var currentChapterBookmark by remember {
        mutableIntStateOf(savedBooks.firstOrNull()?.lastListenedChapter ?: 1)
    }

    // Manual book lookup fields in Library
    var manualBookTitle by remember { mutableStateOf("") }
    var manualBookAuthor by remember { mutableStateOf("") }

    // Manual / pasted summary draft state
    var manualSummaryInput by remember { mutableStateOf("") }

    // Cache size state
    var cacheSizeLabel by remember { mutableStateOf(formatCacheSizeLabel(context)) }

    // Raw text states so typing and backspacing are never blocked
    var noiseDbText by remember(appSettings) { mutableStateOf(appSettings.defaultNoiseDb.toString()) }
    var silenceSecText by remember(appSettings) { mutableStateOf(appSettings.defaultSilenceSec.toString()) }
    var retriesText by remember(appSettings) { mutableStateOf(appSettings.maxRetries.toString()) }
    var timeoutSecText by remember(appSettings) { mutableStateOf(appSettings.timeoutSeconds.toString()) }

    val noiseDbError = validateIntRange(noiseDbText, -80, 0, "Noise (dB)")
    val silenceSecError = validateIntRange(silenceSecText, 1, 60, "Silence (s)")
    val retriesError = validateIntRange(retriesText, 1, 5, "Retries")
    val timeoutSecError = validateIntRange(timeoutSecText, 10, 120, "Timeout (s)")

    var cachedInputFile by remember { mutableStateOf<File?>(null) }
    var cachedOutputFile by remember { mutableStateOf<File?>(null) }

    var fileName by remember { mutableStateOf("No audiobook selected") }
    var sourceExtension by remember { mutableStateOf("mp3") }
    var bookTitle by remember { mutableStateOf("") }
    var bookAuthor by remember { mutableStateOf("") }
    var activeCoverPath by remember { mutableStateOf("") }

    var manualTimestampInput by remember { mutableStateOf("") }
    var pauses by remember { mutableStateOf<List<SilencePause>>(emptyList()) }
    var lockedChapters by remember { mutableStateOf<List<FinalChapter>>(emptyList()) }

    var summaryText by remember { mutableStateOf("") }
    var lastFfmpegLog by remember { mutableStateOf("") }
    var lastErrorMessage by remember { mutableStateOf("") }
    var showRetrySummaryButton by remember { mutableStateOf(false) }

    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val presetOptions = remember {
        listOf(
            "Intro",
            "Foreword",
            "Prologue",
            "[Sequential Chapter]",
            "Interlude",
            "Epilogue",
            "Afterword",
            "Conclusion",
            "Credits",
            "[Custom Freetext]"
        )
    }

    fun refreshCacheLabel() {
        cacheSizeLabel = formatCacheSizeLabel(context)
    }

    fun upsertBookRecord(
        bookId: String = activeBookId,
        titleVal: String = bookTitle.ifBlank { fileName.substringBeforeLast('.').ifBlank { "Untitled Book" } },
        authorVal: String = bookAuthor,
        srcFileName: String = if (fileName == "No audiobook selected") "" else fileName,
        srcExt: String = sourceExtension,
        coverPath: String = activeCoverPath,
        bookmarkCh: Int = currentChapterBookmark,
        summaryVal: String = summaryText,
        chaptersVal: List<FinalChapter> = lockedChapters,
        newExport: ExportRecord? = null
    ): SavedBookRecord {
        val existing = savedBooks.firstOrNull { it.id == bookId }
        val mergedHistory = if (newExport != null) {
            listOf(newExport) + (existing?.exportHistory ?: emptyList())
        } else {
            existing?.exportHistory ?: emptyList()
        }

        val record = SavedBookRecord(
            id = bookId,
            title = titleVal.trim().ifBlank { "Untitled Book" },
            author = authorVal.trim(),
            sourceFileName = srcFileName.ifBlank { existing?.sourceFileName ?: "" },
            sourceExtension = srcExt.ifBlank { existing?.sourceExtension ?: "mp3" },
            coverFilePath = coverPath.ifBlank { existing?.coverFilePath ?: "" },
            lastListenedChapter = bookmarkCh.coerceAtLeast(1),
            savedSummaryText = summaryVal.ifBlank { existing?.savedSummaryText ?: "" },
            chapters = if (chaptersVal.isNotEmpty()) chaptersVal else (existing?.chapters ?: emptyList()),
            exportHistory = mergedHistory,
            updatedAtMillis = System.currentTimeMillis()
        )

        val updatedList = (listOf(record) + savedBooks.filterNot { it.id == bookId })
            .sortedByDescending { it.updatedAtMillis }
        savedBooks = updatedList
        saveSavedBooks(context, updatedList)
        activeBookId = record.id
        return record
    }

    fun saveManualSummary() {
        val cleaned = manualSummaryInput.trim()
        if (cleaned.isBlank()) {
            statusMessage = "Paste or type a chapter summary first."
            return
        }
        val targetTitle = bookTitle.trim().ifBlank {
            fileName.substringBeforeLast('.').ifBlank { "Untitled Book" }
        }
        if (bookTitle.isBlank()) {
            bookTitle = targetTitle
        }
        summaryText = cleaned
        showRetrySummaryButton = false
        lastErrorMessage = ""
        upsertBookRecord(
            titleVal = targetTitle,
            authorVal = bookAuthor,
            summaryVal = cleaned
        )
        statusMessage = "Saved pasted summary for '$targetTitle' with spoiler protection."
    }

    fun triggerSummaryGeneration(overrideTitle: String? = null, overrideAuthor: String? = null) {
        val key = appSettings.geminiApiKey.trim()
        if (key.isBlank()) {
            statusMessage = "Add your Gemini API key in Settings first."
            currentTab = AppTab.SETTINGS
            return
        }
        val targetTitle = (overrideTitle ?: bookTitle).trim()
        val targetAuthor = (overrideAuthor ?: bookAuthor).trim()
        if (targetTitle.isBlank()) {
            statusMessage = "Enter or load a Book Title before generating summaries."
            return
        }

        bookTitle = targetTitle
        bookAuthor = targetAuthor

        isBusy = true
        showRetrySummaryButton = false
        lastErrorMessage = ""
        statusMessage = "Contacting Gemini (gemini-3.8-flash)..."

        scope.launch {
            try {
                val result = executeWithRetry(
                    maxRetries = appSettings.maxRetries,
                    onRetry = { attempt, maxAttempts, waitMs, reason ->
                        statusMessage = "$reason - retrying ($attempt/$maxAttempts) in ${waitMs / 1000}s..."
                    }
                ) {
                    fetchGeminiChapterSummaries(
                        apiKey = key,
                        title = targetTitle,
                        author = targetAuthor,
                        timeoutSeconds = appSettings.timeoutSeconds
                    )
                }
                summaryText = result
                manualSummaryInput = result
                upsertBookRecord(summaryVal = result)
                statusMessage = "Chapter summaries loaded and saved."
            } catch (t: Throwable) {
                val errMsg = t.message ?: "Request failed or timed out."
                lastErrorMessage = errMsg
                showRetrySummaryButton = true
                statusMessage = "Summary error: $errMsg"
            } finally {
                isBusy = false
            }
        }
    }

    val saveCopyLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { destUri: Uri? ->
        val outFile = cachedOutputFile
        if (destUri != null && outFile != null && outFile.exists()) {
            isBusy = true
            statusMessage = "Saving chapterized copy..."
            scope.launch {
                try {
                    exportCacheFileToUri(context, outFile, destUri)
                    lastErrorMessage = ""
                    val baseName = (bookTitle.ifBlank { fileName.substringBeforeLast('.') })
                        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                        .trim()
                    val rec = ExportRecord(
                        id = UUID.randomUUID().toString(),
                        exportedAtMillis = System.currentTimeMillis(),
                        fileLabel = "${baseName}_Chapterized.$sourceExtension",
                        chapterCount = lockedChapters.size
                    )
                    upsertBookRecord(newExport = rec)
                    refreshCacheLabel()
                    statusMessage = "Saved copy. Ready in Voice Audiobook Player."
                } catch (t: Throwable) {
                    val errMsg = t.message ?: "Save failed."
                    lastErrorMessage = errMsg
                    statusMessage = "Save error: $errMsg"
                } finally {
                    isBusy = false
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val newId = UUID.randomUUID().toString()
            activeBookId = newId
            pauses = emptyList()
            lockedChapters = emptyList()
            cachedOutputFile = null
            lastErrorMessage = ""
            currentChapterBookmark = 1

            val extracted = extractAudioMetadata(context, uri)
            val seedColor = extractSeedColorFromCoverBytes(extracted.coverBytes)
            onCoverAccentExtracted(seedColor)

            fileName = extracted.displayName
            val ext = extracted.displayName.substringAfterLast('.', missingDelimiterValue = "mp3")
                .lowercase(Locale.US)
                .ifBlank { "mp3" }
            sourceExtension = ext

            if (extracted.title.isNotBlank()) bookTitle = extracted.title
            if (extracted.artist.isNotBlank()) bookAuthor = extracted.artist

            isBusy = true
            statusMessage = "Staging audiobook in cache..."
            scope.launch {
                try {
                    val copied = copyUriToCache(context, uri, extracted.displayName)
                    cachedInputFile = copied
                    var savedCover = saveCoverBytesToDisk(context, newId, extracted.coverBytes)
                    if (savedCover.isBlank()) {
                        savedCover = fetchBookCoverFromOpenLibrary(
                            context = context,
                            bookId = newId,
                            title = bookTitle.ifBlank { extracted.displayName.substringBeforeLast('.') },
                            author = bookAuthor
                        )
                    }
                    activeCoverPath = savedCover
                    upsertBookRecord(
                        bookId = newId,
                        srcFileName = extracted.displayName,
                        srcExt = ext,
                        coverPath = savedCover,
                        bookmarkCh = 1
                    )
                    refreshCacheLabel()
                    statusMessage = "Ready (${sourceExtension.uppercase(Locale.US)}): ${extracted.displayName}"
                } catch (t: Throwable) {
                    val errMsg = t.message ?: "Copy error."
                    lastErrorMessage = errMsg
                    statusMessage = "Copy error: $errMsg"
                } finally {
                    isBusy = false
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (bookTitle.isNotBlank()) bookTitle else "ABCD Chapter Decoder",
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1
                        )
                        Text(
                            text = if (bookAuthor.isNotBlank()) bookAuthor else "Calm chapter companion for Voice",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            if (tab == AppTab.LIBRARY) refreshCacheLabel()
                            currentTab = tab
                        },
                        label = { Text(tab.label) },
                        icon = {}
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            when (currentTab) {
                AppTab.SETTINGS -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Appearance",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Text(text = "Theme", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeModeOption.entries.forEach { mode ->
                                    FilterChip(
                                        selected = appSettings.themeMode == mode,
                                        onClick = {
                                            onSettingsUpdated(
                                                updateAndSavePreferences(context, appSettings) {
                                                    it.copy(themeMode = mode)
                                                }
                                            )
                                        },
                                        label = { Text(mode.label) }
                                    )
                                }
                            }

                            Text(text = "Accent", style = MaterialTheme.typography.titleSmall)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AccentPreset.entries.take(3).forEach { preset ->
                                    FilterChip(
                                        selected = appSettings.accentPreset == preset,
                                        onClick = {
                                            onSettingsUpdated(
                                                updateAndSavePreferences(context, appSettings) {
                                                    it.copy(accentPreset = preset)
                                                }
                                            )
                                        },
                                        label = { Text(preset.label, maxLines = 1) }
                                    )
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                AccentPreset.entries.drop(3).forEach { preset ->
                                    FilterChip(
                                        selected = appSettings.accentPreset == preset,
                                        onClick = {
                                            onSettingsUpdated(
                                                updateAndSavePreferences(context, appSettings) {
                                                    it.copy(accentPreset = preset)
                                                }
                                            )
                                        },
                                        label = { Text(preset.label, maxLines = 1) }
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Match cover accent",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = appSettings.matchCoverAccent,
                                    onCheckedChange = { checked ->
                                        onSettingsUpdated(
                                            updateAndSavePreferences(context, appSettings) {
                                                it.copy(matchCoverAccent = checked)
                                            }
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Silence & Gemini",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            OutlinedTextField(
                                value = appSettings.geminiApiKey,
                                onValueChange = { newKey ->
                                    onSettingsUpdated(
                                        updateAndSavePreferences(context, appSettings) {
                                            it.copy(geminiApiKey = newKey)
                                        }
                                    )
                                },
                                label = { Text("Gemini API Key") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = noiseDbText,
                                        onValueChange = { raw ->
                                            noiseDbText = raw
                                            raw.trim().toIntOrNull()?.takeIf { it in -80..0 }?.let { valid ->
                                                onSettingsUpdated(
                                                    updateAndSavePreferences(context, appSettings) {
                                                        it.copy(defaultNoiseDb = valid)
                                                    }
                                                )
                                            }
                                        },
                                        label = { Text("Noise (dB)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (noiseDbError != null) {
                                        Text(
                                            text = noiseDbError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = silenceSecText,
                                        onValueChange = { raw ->
                                            silenceSecText = raw
                                            raw.trim().toIntOrNull()?.takeIf { it in 1..60 }?.let { valid ->
                                                onSettingsUpdated(
                                                    updateAndSavePreferences(context, appSettings) {
                                                        it.copy(defaultSilenceSec = valid)
                                                    }
                                                )
                                            }
                                        },
                                        label = { Text("Silence (s)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (silenceSecError != null) {
                                        Text(
                                            text = silenceSecError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = retriesText,
                                        onValueChange = { raw ->
                                            retriesText = raw
                                            raw.trim().toIntOrNull()?.takeIf { it in 1..5 }?.let { valid ->
                                                onSettingsUpdated(
                                                    updateAndSavePreferences(context, appSettings) {
                                                        it.copy(maxRetries = valid)
                                                    }
                                                )
                                            }
                                        },
                                        label = { Text("Retries") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (retriesError != null) {
                                        Text(
                                            text = retriesError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = timeoutSecText,
                                        onValueChange = { raw ->
                                            timeoutSecText = raw
                                            raw.trim().toIntOrNull()?.takeIf { it in 10..120 }?.let { valid ->
                                                onSettingsUpdated(
                                                    updateAndSavePreferences(context, appSettings) {
                                                        it.copy(timeoutSeconds = valid)
                                                    }
                                                )
                                            }
                                        },
                                        label = { Text("Timeout (s)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (timeoutSecError != null) {
                                        Text(
                                            text = timeoutSecError,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Cache & Storage",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Cache used: $cacheSizeLabel",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(
                                onClick = {
                                    clearAppCacheFiles(context)
                                    cachedInputFile = null
                                    cachedOutputFile = null
                                    refreshCacheLabel()
                                    statusMessage = "Cleared cached audio and temporary files."
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .bouncyPress()
                            ) {
                                Text("Clear Cache")
                            }
                        }
                    }
                }

                AppTab.LIBRARY -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Add Book (No Audio Needed)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            OutlinedTextField(
                                value = manualBookTitle,
                                onValueChange = { manualBookTitle = it },
                                label = { Text("Book Title") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = manualBookAuthor,
                                onValueChange = { manualBookAuthor = it },
                                label = { Text("Author (Optional)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Button(
                                onClick = {
                                    val t = manualBookTitle.trim()
                                    if (t.isBlank()) {
                                        statusMessage = "Enter a book title first."
                                        return@Button
                                    }
                                    val newId = UUID.randomUUID().toString()
                                    activeBookId = newId
                                    bookTitle = t
                                    bookAuthor = manualBookAuthor.trim()
                                    currentChapterBookmark = 1
                                    summaryText = ""
                                    manualSummaryInput = ""
                                    lockedChapters = emptyList()
                                    pauses = emptyList()
                                    fileName = "Manual / Audible Entry"
                                    activeCoverPath = ""

                                    isBusy = true
                                    statusMessage = "Fetching cover and saving book..."
                                    scope.launch {
                                        try {
                                            val coverPath = fetchBookCoverFromOpenLibrary(
                                                context = context,
                                                bookId = newId,
                                                title = t,
                                                author = bookAuthor
                                            )
                                            activeCoverPath = coverPath
                                            if (coverPath.isNotBlank()) {
                                                val bytes = File(coverPath).readBytes()
                                                onCoverAccentExtracted(extractSeedColorFromCoverBytes(bytes))
                                            }
                                            upsertBookRecord(
                                                bookId = newId,
                                                titleVal = t,
                                                authorVal = bookAuthor,
                                                srcFileName = "Manual / Audible Entry",
                                                coverPath = coverPath,
                                                bookmarkCh = 1
                                            )
                                            manualBookTitle = ""
                                            manualBookAuthor = ""
                                            statusMessage = "Added '$t' to Library."
                                        } finally {
                                            isBusy = false
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .bouncyPress()
                            ) {
                                Text("Save & Fetch Cover")
                            }
                        }
                    }

                    if (savedBooks.isEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(18.dp)) {
                                Text(
                                    text = "No saved books yet. Pick an audiobook in Chapterizer or add one above.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    } else {
                        savedBooks.forEach { book ->
                            val bitmap: ImageBitmap? = remember(book.coverFilePath) {
                                if (book.coverFilePath.isNotBlank() && File(book.coverFilePath).exists()) {
                                    BitmapFactory.decodeFile(book.coverFilePath)?.asImageBitmap()
                                } else {
                                    null
                                }
                            }
                            val formatter = remember {
                                SimpleDateFormat("dd MMM HH:mm", Locale.getDefault())
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (bitmap != null) {
                                            Surface(
                                                shape = MaterialTheme.shapes.medium,
                                                modifier = Modifier.size(64.dp)
                                            ) {
                                                Image(
                                                    bitmap = bitmap,
                                                    contentDescription = book.title,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier.fillMaxSize()
                                                )
                                            }
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = book.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 2
                                            )
                                            if (book.author.isNotBlank()) {
                                                Text(
                                                    text = book.author,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Text(
                                                text = "Ch. ${book.lastListenedChapter}  •  ${book.exportHistory.size} exports",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                activeBookId = book.id
                                                bookTitle = book.title
                                                bookAuthor = book.author
                                                if (book.sourceFileName.isNotBlank()) {
                                                    fileName = book.sourceFileName
                                                }
                                                sourceExtension = book.sourceExtension
                                                activeCoverPath = book.coverFilePath
                                                currentChapterBookmark = book.lastListenedChapter
                                                summaryText = book.savedSummaryText
                                                manualSummaryInput = book.savedSummaryText
                                                lockedChapters = book.chapters
                                                if (book.coverFilePath.isNotBlank() && File(book.coverFilePath).exists()) {
                                                    val bytes = File(book.coverFilePath).readBytes()
                                                    onCoverAccentExtracted(extractSeedColorFromCoverBytes(bytes))
                                                }
                                                currentTab = AppTab.CHAPTERIZER
                                                statusMessage = "Opened '${book.title}'."
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .bouncyPress()
                                        ) {
                                            Text("Open")
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                val filtered = savedBooks.filterNot { it.id == book.id }
                                                savedBooks = filtered
                                                saveSavedBooks(context, filtered)
                                                if (book.coverFilePath.isNotBlank()) {
                                                    File(book.coverFilePath).delete()
                                                }
                                                statusMessage = "Removed '${book.title}' from Library."
                                            },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .bouncyPress()
                                        ) {
                                            Text("Remove")
                                        }
                                    }

                                    if (book.exportHistory.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text(
                                            text = "Recent Exports",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        book.exportHistory.take(3).forEach { exp ->
                                            Text(
                                                text = "${formatter.format(Date(exp.exportedAtMillis))} • ${exp.fileLabel} (${exp.chapterCount} ch)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                AppTab.CHAPTERIZER -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Audiobook",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Button(
                                onClick = {
                                    filePickerLauncher.launch(
                                        arrayOf("audio/mpeg", "audio/mp4", "audio/x-m4b", "audio/*")
                                    )
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .bouncyPress()
                            ) {
                                Text("Choose File")
                            }

                            Text(
                                text = fileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            OutlinedTextField(
                                value = bookTitle,
                                onValueChange = { bookTitle = it },
                                label = { Text("Title") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = bookAuthor,
                                onValueChange = { bookAuthor = it },
                                label = { Text("Author") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Chapter Breaks",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            FilledTonalButton(
                                onClick = {
                                    val input = cachedInputFile
                                    if (input == null || !input.exists()) {
                                        statusMessage = "Choose an audiobook first."
                                        return@FilledTonalButton
                                    }
                                    isBusy = true
                                    lockedChapters = emptyList()
                                    lastErrorMessage = ""
                                    statusMessage = "Detecting silences (${appSettings.defaultNoiseDb}dB, ${appSettings.defaultSilenceSec}s)..."
                                    scope.launch {
                                        try {
                                            val (foundPairs, logs) = runSilenceDetection(
                                                input,
                                                appSettings.defaultNoiseDb,
                                                appSettings.defaultSilenceSec
                                            )
                                            lastFfmpegLog = logs
                                            val manualOnes = pauses.filter { it.silenceDurationSeconds == null }
                                            val detectedItems = foundPairs.mapIndexed { idx, (ts, dur) ->
                                                val defaultOpt = when {
                                                    idx == 0 -> "Intro"
                                                    idx == foundPairs.lastIndex && foundPairs.size > 1 -> "Credits"
                                                    else -> "[Sequential Chapter]"
                                                }
                                                SilencePause(
                                                    id = pauseIdGenerator.getAndIncrement(),
                                                    startSeconds = ts,
                                                    silenceDurationSeconds = dur,
                                                    isSelected = true,
                                                    presetOption = defaultOpt
                                                )
                                            }
                                            pauses = (detectedItems + manualOnes).sortedBy { it.startSeconds }
                                            refreshCacheLabel()
                                            statusMessage = "Found ${foundPairs.size} breaks."
                                        } catch (t: Throwable) {
                                            val errMsg = t.message ?: "Silence detection error."
                                            lastErrorMessage = errMsg
                                            statusMessage = "Silence detection error: $errMsg"
                                        } finally {
                                            isBusy = false
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .bouncyPress()
                            ) {
                                Text(if (isBusy) "Working..." else "Detect Breaks")
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = manualTimestampInput,
                                    onValueChange = { manualTimestampInput = it },
                                    label = { Text("00:12:30 or 750") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                FilledTonalButton(
                                    onClick = {
                                        val parsedSec = parseTimestampInput(manualTimestampInput)
                                        if (parsedSec == null) {
                                            statusMessage = "Enter seconds (e.g. 120) or hh:mm:ss (e.g. 00:02:00)."
                                            return@FilledTonalButton
                                        }
                                        val newBreak = SilencePause(
                                            id = pauseIdGenerator.getAndIncrement(),
                                            startSeconds = parsedSec,
                                            silenceDurationSeconds = null,
                                            isSelected = true,
                                            presetOption = "[Sequential Chapter]"
                                        )
                                        pauses = (pauses + newBreak).sortedBy { it.startSeconds }
                                        manualTimestampInput = ""
                                        lockedChapters = emptyList()
                                        statusMessage = "Added ${formatHms(parsedSec)}."
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .height(54.dp)
                                        .bouncyPress()
                                ) {
                                    Text("Add")
                                }
                            }
                        }
                    }

                    if (pauses.isNotEmpty()) {
                        Text(
                            text = "Markers (${pauses.count { it.isSelected }})",
                            style = MaterialTheme.typography.titleMedium
                        )

                        pauses.forEach { pause ->
                            var dropdownExpanded by remember(pause.id) { mutableStateOf(false) }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Checkbox(
                                                checked = pause.isSelected,
                                                onCheckedChange = { checked ->
                                                    pauses = pauses.map { item ->
                                                        if (item.id == pause.id) item.copy(isSelected = checked) else item
                                                    }
                                                }
                                            )
                                            Text(
                                                text = formatPauseLabel(pause),
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            )
                                        }

                                        Box {
                                            OutlinedButton(
                                                onClick = { dropdownExpanded = true },
                                                modifier = Modifier.bouncyPress()
                                            ) {
                                                Text(pause.presetOption, style = MaterialTheme.typography.bodySmall)
                                            }
                                            DropdownMenu(
                                                expanded = dropdownExpanded,
                                                onDismissRequest = { dropdownExpanded = false }
                                            ) {
                                                presetOptions.forEach { option ->
                                                    DropdownMenuItem(
                                                        text = { Text(option) },
                                                        onClick = {
                                                            pauses = pauses.map { item ->
                                                                if (item.id == pause.id) item.copy(presetOption = option) else item
                                                            }
                                                            dropdownExpanded = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                pauses = pauses.map { item ->
                                                    if (item.id == pause.id) {
                                                        item.copy(
                                                            startSeconds = (item.startSeconds - 1.0).coerceAtLeast(0.0)
                                                        )
                                                    } else {
                                                        item
                                                    }
                                                }.sortedBy { it.startSeconds }
                                                lockedChapters = emptyList()
                                            },
                                            modifier = Modifier.bouncyPress(),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Text("-1s", style = MaterialTheme.typography.bodySmall)
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                pauses = pauses.map { item ->
                                                    if (item.id == pause.id) {
                                                        item.copy(startSeconds = item.startSeconds + 1.0)
                                                    } else {
                                                        item
                                                    }
                                                }.sortedBy { it.startSeconds }
                                                lockedChapters = emptyList()
                                            },
                                            modifier = Modifier.bouncyPress(),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                        ) {
                                            Text("+1s", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }

                                    if (pause.presetOption == "[Custom Freetext]") {
                                        OutlinedTextField(
                                            value = pause.customTitle,
                                            onValueChange = { text ->
                                                pauses = pauses.map { item ->
                                                    if (item.id == pause.id) item.copy(customTitle = text) else item
                                                }
                                            },
                                            label = { Text("Custom title") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                lockedChapters = computeFinalChapters(pauses)
                                upsertBookRecord(chaptersVal = lockedChapters)
                                statusMessage = "Locked ${lockedChapters.size} chapters."
                            },
                            enabled = !isBusy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .bouncyPress()
                        ) {
                            Text("Lock Chapters")
                        }
                    }

                    if (lockedChapters.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Chapters (${lockedChapters.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                lockedChapters.forEach { ch ->
                                    Text(
                                        text = "${formatHms(ch.startSeconds)}  •  ${ch.title}",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        val input = cachedInputFile
                                        if (input == null || !input.exists()) {
                                            statusMessage = "Missing cached file."
                                            return@Button
                                        }
                                        isBusy = true
                                        lastErrorMessage = ""
                                        statusMessage = "Injecting chapters..."
                                        scope.launch {
                                            try {
                                                val (built, logs) = injectChaptersWithoutReencodeInCache(
                                                    context = context,
                                                    inputFile = input,
                                                    extension = sourceExtension,
                                                    bookTitle = bookTitle.trim(),
                                                    bookAuthor = bookAuthor.trim(),
                                                    chapters = lockedChapters
                                                )
                                                cachedOutputFile = built
                                                lastFfmpegLog = logs
                                                refreshCacheLabel()
                                                val baseName = (bookTitle.ifBlank { fileName.substringBeforeLast('.') })
                                                    .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                                                    .trim()
                                                val suggestedName = "${baseName}_Chapterized.$sourceExtension"
                                                statusMessage = "Ready to save .$sourceExtension."
                                                saveCopyLauncher.launch(suggestedName)
                                            } catch (t: Throwable) {
                                                val errMsg = t.message ?: "Export failed."
                                                lastErrorMessage = errMsg
                                                statusMessage = errMsg
                                            } finally {
                                                isBusy = false
                                            }
                                        }
                                    },
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(54.dp)
                                        .bouncyPress()
                                ) {
                                    Text("Export .${sourceExtension.uppercase(Locale.US)}")
                                }
                            }
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Summaries",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            OutlinedTextField(
                                value = manualSummaryInput,
                                onValueChange = { manualSummaryInput = it },
                                label = { Text("Paste summary (Chapter 1: ...)") },
                                minLines = 3,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth()
                            )

                            FilledTonalButton(
                                onClick = { saveManualSummary() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .bouncyPress()
                            ) {
                                Text("Save Pasted Summary")
                            }

                            Button(
                                onClick = { triggerSummaryGeneration() },
                                enabled = !isBusy,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .bouncyPress()
                            ) {
                                Text("Generate Summaries")
                            }

                            if (showRetrySummaryButton && !isBusy) {
                                OutlinedButton(
                                    onClick = { triggerSummaryGeneration() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp)
                                        .bouncyPress()
                                ) {
                                    Text("Retry")
                                }
                            }

                            if (summaryText.isNotBlank()) {
                                HorizontalDivider()
                                SpoilerSafeSummaryCard(
                                    summaryText = summaryText,
                                    currentChapter = currentChapterBookmark,
                                    onCurrentChapterChange = { updatedCh ->
                                        currentChapterBookmark = updatedCh
                                        upsertBookRecord(bookmarkCh = updatedCh)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (statusMessage.isNotBlank() || lastErrorMessage.isNotBlank() || lastFfmpegLog.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (lastErrorMessage.isNotBlank()) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (statusMessage.isNotBlank()) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (lastErrorMessage.isNotBlank()) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        if (lastErrorMessage.isNotBlank() || lastFfmpegLog.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    val report = formatDiagnosticReport(
                                        status = statusMessage,
                                        bookTitle = bookTitle,
                                        bookAuthor = bookAuthor,
                                        ffmpegLog = lastFfmpegLog,
                                        lastError = lastErrorMessage
                                    )
                                    copyTextToClipboard(context, "ABCD Diagnostics", report)
                                    statusMessage = "Copied diagnostic report to clipboard."
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Copy Diagnostics")
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class AudioMeta(
    val displayName: String,
    val title: String,
    val artist: String,
    val coverBytes: ByteArray?
)

private fun extractAudioMetadata(context: Context, uri: Uri): AudioMeta {
    var displayName = uri.lastPathSegment ?: "Selected Audio"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            displayName = cursor.getString(nameIndex) ?: displayName
        }
    }

    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: ""
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
        val cover = retriever.embeddedPicture
        AudioMeta(displayName = displayName, title = title, artist = artist, coverBytes = cover)
    } catch (_: Throwable) {
        AudioMeta(displayName = displayName, title = "", artist = "", coverBytes = null)
    } finally {
        retriever.release()
    }
}

private suspend fun fetchGeminiChapterSummaries(
    apiKey: String,
    title: String,
    author: String,
    timeoutSeconds: Int
): String = withContext(Dispatchers.IO) {
    val authorClause = if (author.isNotBlank()) " by $author" else ""
    val prompt =
        "Provide a concise, chapter-by-chapter context summary for the well-known book '$title'$authorClause. " +
                "Format it clearly with Chapter numbers and short summary bullet points so a listener can catch up on context."

    val payload = JSONObject().apply {
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", prompt)
                    })
                })
            })
        })
    }

    val safeTimeout = timeoutSeconds.coerceIn(10, 120).toLong()
    val client = OkHttpClient.Builder()
        .connectTimeout(safeTimeout, TimeUnit.SECONDS)
        .readTimeout(safeTimeout, TimeUnit.SECONDS)
        .writeTimeout(safeTimeout, TimeUnit.SECONDS)
        .build()

    val requestBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent?key=$apiKey")
        .post(requestBody)
        .build()

    client.newCall(request).execute().use { response ->
        val bodyStr = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw RuntimeException("HTTP ${response.code}: $bodyStr")
        }
        val json = JSONObject(bodyStr)
        val candidates = json.optJSONArray("candidates") ?: return@withContext "No summary returned."
        val firstCandidate = candidates.optJSONObject(0) ?: return@withContext "No summary returned."
        val content = firstCandidate.optJSONObject("content") ?: return@withContext "No summary returned."
        val parts = content.optJSONArray("parts") ?: return@withContext "No summary returned."
        val texts = buildList {
            for (i in 0 until parts.length()) {
                val t = parts.optJSONObject(i)?.optString("text")
                if (!t.isNullOrBlank()) add(t)
            }
        }
        texts.joinToString("" +
                "").ifBlank { "No summary returned." }
    }
}