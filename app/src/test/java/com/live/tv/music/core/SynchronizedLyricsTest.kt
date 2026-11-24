package com.live.tv.music.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SynchronizedLyricsTest {
    @Test
    fun `one lyric row can publish multiple synchronized timestamps`() {
        val lines = SynchronizedLyrics.parse("[00:04.250][00:01.00]第一句")

        assertEquals(
            listOf(
                SynchronizedLyricLine(timeSeconds = 1.0, text = "第一句"),
                SynchronizedLyricLine(timeSeconds = 4.25, text = "第一句"),
            ),
            lines,
        )
    }

    @Test
    fun `production credits stay out of synchronized lyrics`() {
        val lines =
            SynchronizedLyrics.parse(
                """
                [00:01.00]作词 : 某人
                [00:02.00]编曲：某人
                [00:03.00]笛子 : 某人
                [00:08.00]月光色 女子香
                """.trimIndent(),
            )

        assertEquals(
            listOf(SynchronizedLyricLine(timeSeconds = 8.0, text = "月光色 女子香")),
            lines,
        )
    }

    @Test
    fun `translations merge on the same hundredth of a second`() {
        val lines =
            SynchronizedLyrics.parse(
                lyric = "[00:01.00]第一句\n[00:04.254]第二句",
                translatedLyric = "[00:01.000]First line\n[00:04.25]Second line",
            )

        assertEquals(
            listOf(
                SynchronizedLyricLine(
                    timeSeconds = 1.0,
                    text = "第一句",
                    translation = "First line",
                ),
                SynchronizedLyricLine(
                    timeSeconds = 4.254,
                    text = "第二句",
                    translation = "Second line",
                ),
            ),
            lines,
        )
    }

    @Test
    fun `active lyric is the last line not later than playback time`() {
        val lines =
            listOf(
                SynchronizedLyricLine(timeSeconds = 1.0, text = "第一句"),
                SynchronizedLyricLine(timeSeconds = 4.25, text = "第二句"),
                SynchronizedLyricLine(timeSeconds = 8.0, text = "第三句"),
            )

        assertEquals(-1, SynchronizedLyrics.activeIndexAt(lines, currentTimeSeconds = 0.5))
        assertEquals(0, SynchronizedLyrics.activeIndexAt(lines, currentTimeSeconds = 1.0))
        assertEquals(1, SynchronizedLyrics.activeIndexAt(lines, currentTimeSeconds = 4.5))
        assertEquals(2, SynchronizedLyrics.activeIndexAt(lines, currentTimeSeconds = 20.0))
    }
}
