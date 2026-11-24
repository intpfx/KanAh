package com.live.tv.music.core

import kotlin.math.roundToLong

data class SynchronizedLyricLine(
    val timeSeconds: Double,
    val text: String,
    val translation: String? = null,
)

object SynchronizedLyrics {
    private val timestamp = Regex("""\[(\d{1,3}):(\d{2}(?:\.\d{1,3})?)]""")
    private val productionCredit =
        Regex(
            """^(?:作词|作曲|编曲|制作人|录音|混音|母带|吉他|贝斯|鼓|和声|弦乐|笛子|萧|二胡|古筝|琵琶|小提琴|大提琴|键盘|配唱|人声编辑|监制|策划|出品|发行|OP|SP)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )

    fun parse(
        lyric: String,
        translatedLyric: String = "",
    ): List<SynchronizedLyricLine> {
        val translations =
            parseRows(translatedLyric).associate { line ->
                timestampKey(line.timeSeconds) to line.text
            }

        return parseRows(lyric).map { line ->
            line.copy(translation = translations[timestampKey(line.timeSeconds)])
        }
    }

    fun activeIndexAt(
        lines: List<SynchronizedLyricLine>,
        currentTimeSeconds: Double,
    ): Int {
        var low = 0
        var high = lines.lastIndex
        var active = -1

        while (low <= high) {
            val middle = low + (high - low) / 2
            if (lines[middle].timeSeconds <= currentTimeSeconds) {
                active = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }

        return active
    }

    private fun parseRows(source: String): List<SynchronizedLyricLine> =
        buildList {
            source.lineSequence().forEach { rawLine ->
                val matches = timestamp.findAll(rawLine).toList()
                val text = rawLine.replace(timestamp, "").trim()
                if (matches.isEmpty() || text.isEmpty() || productionCredit.containsMatchIn(text)) {
                    return@forEach
                }

                matches.forEach { match ->
                    val minutes = match.groupValues[1].toDouble()
                    val seconds = match.groupValues[2].toDouble()
                    add(
                        SynchronizedLyricLine(
                            timeSeconds = minutes * SECONDS_PER_MINUTE + seconds,
                            text = text,
                        ),
                    )
                }
            }
        }.sortedBy(SynchronizedLyricLine::timeSeconds)

    private fun timestampKey(timeSeconds: Double): Long =
        (timeSeconds * HUNDREDTHS_PER_SECOND).roundToLong()

    private const val SECONDS_PER_MINUTE = 60.0
    private const val HUNDREDTHS_PER_SECOND = 100.0
}
