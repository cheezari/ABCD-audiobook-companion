package com.cheezari.chaptercompanion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ChapterSummarySection(
    val chapterNumber: Int,
    val heading: String,
    val body: String
)

fun parseChapterSummarySections(rawText: String): List<ChapterSummarySection> {
    if (rawText.isBlank()) return emptyList()
    val lines = rawText.lines()
    val sections = mutableListOf<ChapterSummarySection>()
    var currentHeading = ""
    val currentBody = StringBuilder()
    var autoNum = 1

    fun flush() {
        val cleanedBody = currentBody.toString().trim()
        if (currentHeading.isNotBlank() || cleanedBody.isNotBlank()) {
            val headingText = currentHeading.ifBlank { "Chapter $autoNum" }
            val matchedNum = Regex("""(?i)\bchapter\s+(\d+)""").find(headingText)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: autoNum
            sections.add(
                ChapterSummarySection(
                    chapterNumber = matchedNum.coerceAtLeast(1),
                    heading = headingText.trim(),
                    body = cleanedBody
                )
            )
            autoNum += 1
            currentHeading = ""
            currentBody.clear()
        }
    }

    val headerRegex = Regex(
        """^\s*(?:#+\s*|\*\*\s*)?(?:Chapter|Part|Section|Prologue|Epilogue)\b.*""",
        RegexOption.IGNORE_CASE
    )
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            if (currentBody.isNotEmpty()) currentBody.appendLine()
            continue
        }
        if (headerRegex.matches(trimmed) && trimmed.length <= 120) {
            flush()
            currentHeading = trimmed.removePrefix("#").removeSurrounding("**").trim()
        } else {
            currentBody.appendLine(line)
        }
    }
    flush()

    if (sections.isEmpty()) {
        return listOf(
            ChapterSummarySection(
                chapterNumber = 1,
                heading = "Summary",
                body = rawText.trim()
            )
        )
    }
    return sections
}

@Composable
fun SpoilerSafeSummaryCard(
    summaryText: String,
    currentChapter: Int,
    onCurrentChapterChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val sections = remember(summaryText) { parseChapterSummarySections(summaryText) }
    val maxChapter = remember(sections, currentChapter) {
        maxOf(currentChapter, sections.maxOfOrNull { it.chapterNumber } ?: 1, 1)
    }
    val revealedChapters = remember { mutableStateMapOf<Int, Boolean>() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Current chapter: $currentChapter",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val prev = (currentChapter - 1).coerceAtLeast(1)
                        onCurrentChapterChange(prev)
                    },
                    modifier = Modifier.bouncyPress(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("- Ch")
                }
                OutlinedButton(
                    onClick = {
                        val next = (currentChapter + 1).coerceAtMost(maxChapter + 1)
                        onCurrentChapterChange(next)
                    },
                    modifier = Modifier.bouncyPress(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("+ Ch")
                }
            }
        }

        Text(
            text = "Chapters past Ch. $currentChapter are hidden to avoid spoilers.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        sections.forEach { sec ->
            val isSpoiler = sec.chapterNumber > currentChapter
            val isRevealed = !isSpoiler || (revealedChapters[sec.chapterNumber] == true)

            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = sec.heading,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSpoiler) {
                            TextButton(
                                onClick = {
                                    revealedChapters[sec.chapterNumber] = !isRevealed
                                },
                                modifier = Modifier.bouncyPress()
                            ) {
                                Text(if (isRevealed) "Hide" else "Reveal")
                            }
                        }
                    }

                    if (isRevealed) {
                        if (sec.body.isNotBlank()) {
                            Text(
                                text = sec.body,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        Text(
                            text = "Spoiler hidden (Chapter ${sec.chapterNumber})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}