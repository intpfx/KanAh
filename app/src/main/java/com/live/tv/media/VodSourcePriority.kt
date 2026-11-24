package com.live.tv.media

import java.text.Normalizer
import java.util.Locale

internal object VodSourcePriority {
    fun rank(sourceName: String): Int = when (sourceName.trim()) {
        "西瓜资源站" -> 0
        "360资源" -> 1
        "魔都资源" -> 2
        else -> 3
    }

    fun titleKey(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFKC).filterNot(Char::isWhitespace).lowercase(Locale.ROOT)

    /** Keep each title's position while ordering its independently selectable sources. */
    fun <T> orderTitles(items: List<T>, title: (T) -> String, priority: (T) -> Int): List<T> =
        items.groupBy { titleKey(title(it)) }.values.flatMap { group -> group.sortedBy(priority) }
}
