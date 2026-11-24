package com.live.tv

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.live.tv.media.RecentVodHistory
import com.live.tv.media.VodCacheManager
import com.live.tv.media.VodRepository
import org.json.JSONObject
import java.util.concurrent.Executors

/** Settings sub-page for the VOD catalogue sources that the former web panel used to manage. */
class TvSourcesActivity : Activity() {
    private lateinit var menuKey: MenuKeyController
    private lateinit var globalSearch: GlobalSearchOverlay
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tv-sources").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val repository by lazy { VodRepository.get(this) }
    private val rows = mutableListOf<View>()
    private val columns: Int get() = when {
        resources.configuration.screenWidthDp >= 800 -> 3
        resources.configuration.screenWidthDp >= 480 -> 2
        else -> 1
    }
    private var cardLane: LinearLayout? = null
    private lateinit var list: LinearLayout
    private lateinit var message: TextView
    private var dialog: AlertDialog? = null
    private var active = false
    private var busy = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(40), dp(64), dp(32))
            setBackgroundColor(BASE)
            tag = "sources-root"
        }
        content.addView(text(getString(R.string.app_name), 14f, SECONDARY))
        content.addView(text("影视来源", 34f, INK).apply {
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(12))
        })
        content.addView(text("输入采集地址仍在电视上进行；来源只用于流媒体搜索与播放。", 15f, SECONDARY).apply {
            setPadding(0, 0, 0, dp(16))
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list, LinearLayout.LayoutParams(-1, -2))
        message = text("", 15f, SECONDARY).apply {
            tag = "sources-message"; setPadding(0, dp(24), 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(message)

        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(BASE) }
        root.addView(ScrollView(this).apply {
            setBackgroundColor(BASE); isFillViewport = true; isFocusable = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, ViewGroup.LayoutParams(-1, -1))
        globalSearch = GlobalSearchOverlay(this)
        root.addView(globalSearch, ViewGroup.LayoutParams(-1, -1))
        menuKey = MenuKeyController(onSearch = { globalSearch.show() })
        setContentView(root)
        refreshRows()
    }

    override fun onResume() { super.onResume(); active = true; refreshRows() }
    override fun onPause() { active = false; dialog?.dismiss(); dialog = null; super.onPause() }
    override fun onDestroy() { generation++; if (::menuKey.isInitialized) menuKey.cancel(); worker.shutdownNow(); super.onDestroy() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::globalSearch.isInitialized && globalSearch.isOpen()) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) globalSearch.hide()
                return true
            }
            return globalSearch.dispatchKeyEvent(event)
        }
        if (::menuKey.isInitialized && menuKey.handle(event)) return true
        val key = event.keyCode
        if (key == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val popup = dialog
                if (popup?.isShowing == true) popup.dismiss()
                else DesktopMode.returnToHome(this)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun refreshRows() {
        if (!active) return
        val token = ++generation
        worker.execute {
            val result = try { repository.state() } catch (_: Exception) { null }
            main.post { if (active && token == generation) render(result) }
        }
    }

    private fun render(state: JSONObject?) {
        list.removeAllViews(); rows.clear(); cardLane = null
        val sources = state?.optJSONArray("sources")
        if (sources == null || sources.length() == 0) {
            list.addView(text("尚未添加影视来源。", 17f, SECONDARY).apply { setPadding(0, dp(8), 0, dp(12)) })
        } else {
            for (index in 0 until sources.length()) {
                val source = sources.optJSONObject(index) ?: continue
                val id = source.optString("id")
                val name = source.optString("name").ifBlank { "未命名来源" }
                val searchable = source.optBoolean("searchable")
                val imported = source.optInt("importedEpisodeCount")
                val subtitle = when {
                    imported > 0 -> "M3U8 · $imported 集"
                    searchable -> "可搜索"
                    else -> "此来源不可原生浏览"
                }
                val row = row(name, subtitle) { confirmRemove(id, name) }
                row.tag = "sources-row-$index"
                addRow(row)
            }
        }
        addRow(row("添加西瓜资源", "使用预置的西瓜 M3U8 JSON 采集地址") { addSource(mapOf("preset" to "xigua")) }
            .apply { tag = "sources-add-xigua" })
        addRow(row("添加自定义来源", "填写名称与 MacCMS JSON 采集地址") { showAddSource() })
        addRow(row("导入 M3U8", "添加单集地址或粘贴多集列表") { showM3u8Import() }
            .apply { tag = "settings-import-m3u8" })
        addRow(row("一键清理流媒体缓存", "删除所有已缓存剧集和未完成的分片") { confirmClearCache() }
            .apply { tag = "settings-clear-vod-cache" })
        addRow(row("清理最近观看记录", "清空搜索面板中的影视观看历史") { confirmClearRecentHistory() }
            .apply { tag = "settings-clear-recent-vod" })
        linkRows()
        if (dialog?.isShowing != true) rows.firstOrNull()?.requestFocus()
    }

    private fun linkRows() {
        val width = columns
        rows.forEachIndexed { index, card ->
            val column = index % width
            card.nextFocusUpId = rows.getOrNull(index - width)?.id ?: card.id
            val nextRow = index - column + width
            card.nextFocusDownId = if (nextRow < rows.size) rows[(nextRow + column).coerceAtMost(rows.lastIndex)].id else card.id
            card.nextFocusLeftId = if (column > 0) rows[index - 1].id else card.id
            card.nextFocusRightId = if (column + 1 < width) rows.getOrNull(index + 1)?.id ?: card.id else card.id
        }
    }

    private fun addRow(view: View) {
        val width = columns
        if (rows.size % width == 0) {
            cardLane = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = width.toFloat()
                list.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        val hasRightGap = rows.size % width < width - 1
        cardLane!!.addView(view, LinearLayout.LayoutParams(0, dp(102), 1f).apply {
            if (hasRightGap) marginEnd = dp(14)
            bottomMargin = dp(14)
        })
        rows.add(view)
    }

    private fun row(title: String, subtitle: String, action: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            id = View.generateViewId(); orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16)); minimumHeight = dp(102)
            isFocusable = true; isFocusableInTouchMode = true; isClickable = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            background = focusBackground()
            setOnClickListener { action() }
        }
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val titleView = text(title, 21f, INK)
        val subtitleView = text(subtitle, 14f, SECONDARY).apply { setPadding(0, dp(5), 0, 0) }
        labels.addView(titleView); labels.addView(subtitleView)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            titleView.setTextColor(if (focused) BASE else INK)
            titleView.typeface = android.graphics.Typeface.create("sans-serif", if (focused) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            subtitleView.setTextColor(if (focused) 0xFF3B404A.toInt() else SECONDARY)
        }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = "$title。$subtitle"; info.className = "android.widget.Button"
            }
        }
        return row
    }

    private fun focusBackground() = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
            setColor(INK); cornerRadius = dp(16).toFloat()
        })
        addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF171B24.toInt()); cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFF2B303B.toInt())
        })
    }

    private fun showAddSource() {
        val name = input("来源名称", 120).apply { tag = "sources-name" }
        val url = input("MacCMS JSON 采集地址", 8192).apply {
            tag = "sources-url"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(name); addView(url, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val status = text("", 14f, SECONDARY).apply { setPadding(dp(24), dp(8), dp(24), 0) }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(content); addView(status) }
        val popup = TvDialogs.builder(this).setTitle("添加影视来源").setView(holder)
            .setNegativeButton("取消", null)
            .setPositiveButton("添加", null)
            .create()
        popup.setOnShowListener {
            popup.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isBlank() || url.text.isBlank()) { status.text = "请填写来源名称与采集地址"; return@setOnClickListener }
                popup.dismiss()
                addSource(mapOf("name" to name.text.toString().trim(), "url" to url.text.toString().trim()))
            }
        }
        popup.show()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        popup.getButton(AlertDialog.BUTTON_POSITIVE)?.let { positive ->
            name.nextFocusDownId = url.id; url.nextFocusUpId = name.id; url.nextFocusDownId = positive.id
            positive.nextFocusUpId = url.id
        }
        name.requestFocus()
    }

    private fun showM3u8Import() {
        if (busy) { message.text = "请等待当前操作完成"; return }
        val name = input("影视名称", 120)
        val episodes = input("每行一个地址，或「集名 | 地址」", 512 * 1024).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
        }
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
            addView(name)
            addView(episodes)
        }
        val popup = TvDialogs.builder(this).setTitle("导入 M3U8").setView(fields)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .show()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        val positive = popup.getButton(AlertDialog.BUTTON_POSITIVE)
        name.nextFocusDownId = episodes.id
        episodes.nextFocusUpId = name.id
        episodes.nextFocusDownId = positive.id
        positive.nextFocusUpId = episodes.id
        positive.setOnClickListener {
            if (name.text.isBlank()) { name.error = "请填写影视名称"; return@setOnClickListener }
            if (episodes.text.isBlank()) { episodes.error = "请填写至少一条 M3U8 地址"; return@setOnClickListener }
            popup.dismiss()
            addSource(mapOf("name" to name.text.toString(), "episodes" to episodes.text.toString()), importing = true)
        }
        name.requestFocus()
    }

    private fun confirmClearRecentHistory() {
        val popup = TvDialogs.builder(this).setTitle("清理最近观看记录")
            .setMessage("清空影视最近观看记录？播放进度和剧集缓存会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                RecentVodHistory(this).clear()
                message.text = "最近观看记录已清理"
            }.show()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun confirmClearCache() {
        if (busy) { message.text = "请等待当前操作完成"; return }
        val popup = TvDialogs.builder(this).setTitle("清理流媒体缓存")
            .setMessage("将停止正在进行的缓存，并删除全部剧集缓存。播放记录和进度会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清理") { _, _ ->
                busy = true
                message.text = "正在清理流媒体缓存…"
                worker.execute {
                    val remaining = runCatching { VodCacheManager.get(this).clearEpisodes() }.getOrNull()
                    main.post {
                        busy = false
                        if (isDestroyed) return@post
                        message.text = when (remaining) {
                            null -> "缓存清理失败，请重试"
                            0 -> "流媒体缓存已清理"
                            else -> "还有 ${remaining} 集缓存未能清理，请重试"
                        }
                    }
                }
            }.show()
        dialog = popup
        popup.setOnDismissListener { if (dialog === popup) dialog = null }
        popup.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
    }

    private fun confirmRemove(id: String, name: String) {
        if (id.isBlank()) return
        if (busy) { message.text = "请等待当前操作完成"; return }
        dialog = TvDialogs.builder(this).setTitle("移除影视来源")
            .setMessage("移除“$name”？此操作无法撤销。已有缓存不会在此删除。")
            .setNegativeButton("保留", null)
            .setPositiveButton("移除") { _, _ ->
                val token = ++generation
                busy = true; message.text = "正在移除…"
                worker.execute {
                    val result = try { repository.command("vod_remove_source", mapOf("id" to id)) }
                    catch (_: Exception) { JSONObject().put("ok", false).put("error", "移除失败，请重试") }
                    val state = try { repository.state() } catch (_: Exception) { null }
                    main.post {
                        if (!active) return@post
                        busy = false; message.text = if (result.optBoolean("ok")) "已移除来源" else result.optString("error", "移除失败")
                        if (token == generation) render(state)
                    }
                }
            }.show().also { popup ->
                popup.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
    }

    private fun addSource(form: Map<String, String>, importing: Boolean = false) {
        if (busy) { message.text = "请等待当前操作完成"; return }
        busy = true; message.text = if (importing) "正在导入…" else "正在添加…"
        val token = ++generation
        worker.execute {
            val result = try { repository.command(if (importing) "vod_import_m3u8" else "vod_add_source", form) }
            catch (_: Exception) { JSONObject().put("ok", false).put("error", "无法保存来源，请重试") }
            val state = try { repository.state() } catch (_: Exception) { null }
            main.post {
                if (!active) return@post
                busy = false
                message.text = if (result.optBoolean("ok")) {
                    if (importing) "已导入影视选集，可在流媒体中搜索" else "已添加来源"
                } else result.optString("error", "无法添加此来源")
                if (token == generation) render(state)
            }
        }
    }

    private fun input(hint: String, limit: Int) = TvDialogs.searchInput(this, hint).apply {
        id = View.generateViewId()
        filters = arrayOf(InputFilter.LengthFilter(limit))
        textSize = 17f
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    companion object {
        private val BASE = Color.rgb(8, 10, 14)
        private val INK = Color.rgb(245, 245, 247)
        private val SECONDARY = Color.rgb(184, 187, 194)
    }
}
