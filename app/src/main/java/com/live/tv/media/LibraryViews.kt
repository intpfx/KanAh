package com.live.tv.media

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.live.tv.TvDialogs

internal object LibraryStyle {
    val INK = Color.rgb(245, 245, 247)
    val BASE = Color.rgb(8, 10, 14)
    val SECONDARY = Color.rgb(155, 160, 172)
    val SURFACE = Color.rgb(35, 39, 47)
    val CACHED = Color.rgb(35, 85, 68)
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun text(context: Context, value: String, size: Float, color: Int = INK) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    fun button(context: Context, label: String, name: String, action: () -> Unit) = text(context, label, 16f).apply {
        id = View.generateViewId(); tag = name
        TvDialogs.styleAction(this)
        isClickable = true
        setOnClickListener { action() }
    }
    fun round(context: Context, color: Int, radius: Int = 14) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(context, radius).toFloat()
    }
    fun mode(context: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(context, INK))
        addState(intArrayOf(android.R.attr.state_pressed), round(context, INK))
        addState(intArrayOf(android.R.attr.state_selected), round(context, Color.rgb(73, 78, 88)))
        addState(intArrayOf(), round(context, Color.TRANSPARENT))
    }
    fun episode(context: Context, cached: Boolean) = StateListDrawable().apply {
        val focus = if (cached) Color.rgb(61, 117, 94) else INK
        for (state in listOf(android.R.attr.state_selected, android.R.attr.state_pressed)) {
            addState(intArrayOf(state), round(context, focus, 12).apply {
                if (cached) setStroke(dp(context, 2), INK)
            })
        }
        addState(intArrayOf(), round(context, if (cached) CACHED else SURFACE, 12))
    }
    fun episodeText(cached: Boolean) = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
        intArrayOf(if (cached) INK else BASE, if (cached) INK else BASE, INK)
    )
}

/** A title remains visible while artwork is loading, missing or unavailable from every source. */
internal class LibraryPosterView(context: Context) : FrameLayout(context) {
    val image = ImageView(context).apply {
        tag = "library-poster-image"; scaleType = ImageView.ScaleType.CENTER_CROP
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val placeholder = LibraryStyle.text(context, "", 22f).apply {
        tag = "library-poster-placeholder"; gravity = Gravity.CENTER; maxLines = 5
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(LibraryStyle.dp(context, 18), LibraryStyle.dp(context, 24), LibraryStyle.dp(context, 18), LibraryStyle.dp(context, 24))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(43, 49, 62), Color.rgb(17, 21, 30)))
        addView(placeholder, LayoutParams(-1, -1)); addView(image, LayoutParams(-1, -1))
    }
    fun hideTitle() { placeholder.visibility = View.GONE }

    fun bindTitle(title: String) { placeholder.text = title.ifBlank { "未命名影片" } }
}

internal class LibraryPosterAdapter(private val context: Context, private val images: LibraryImages,
    private val loadMusicArtwork: ((ImageView, com.live.tv.music.catalog.CatalogTrack) -> Unit)? = null) : BaseAdapter() {
    var items: List<LibraryWork> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].key.hashCode().toLong()
    override fun hasStableIds() = false
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val card = convertView as? PosterCard ?: PosterCard(context)
        val work = getItem(position)
        card.title.text = work.title
        card.artwork.bindTitle(work.title)
        val music = work.music
        if (music != null) {
            // Music results use the same poster card language as films, with a square album cover
            // centred inside the portrait frame instead of a cropped full-bleed poster.
            card.artwork.image.scaleType = ImageView.ScaleType.CENTER_INSIDE
            card.subtitle.text = "音乐 · " + music.artists.joinToString(" / ")
            card.contentDescription = "${work.title}，歌手 ${music.artists.joinToString(" / ")}，播放"
            card.updateFocus(parent.hasFocus())
            loadMusicArtwork?.invoke(card.artwork.image, music) ?: card.artwork.image.setImageDrawable(null)
        } else {
            card.artwork.image.scaleType = ImageView.ScaleType.CENTER_CROP
            card.subtitle.text = when {
                !work.current -> "来源已改变"
                work.completed.isNotEmpty() -> "本地 ${work.completed.size} 集"
                work.caches.any { it.state in listOf("queued", "resolving", "downloading") } -> "正在缓存"
                else -> ""
            }
            card.contentDescription = listOf(work.title, card.subtitle.text.toString()).filter { it.isNotBlank() }.joinToString("，")
            card.updateFocus(parent.hasFocus())
            images.load(card.artwork.image, work)
        }
        return card
    }

    fun refreshFocus(parent: ViewGroup) {
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index) as? PosterCard ?: continue
            child.updateFocus(parent.hasFocus() || child.hasFocus())
        }
    }

    private class PosterCard(context: Context) : FrameLayout(context) {
        val artwork = LibraryPosterView(context)
        val title = LibraryStyle.text(context, "", 18f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif", Typeface.BOLD) }
        val subtitle = LibraryStyle.text(context, "", 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, LibraryStyle.dp(context, 6), 0, 0) }
        private val shade = View(context).apply { setBackgroundColor(Color.argb(22, 0, 0, 0)) }
        private val caption = LinearLayout(context).apply {
            tag = "library-poster-caption"; orientation = LinearLayout.VERTICAL
            setPadding(LibraryStyle.dp(context, 12), LibraryStyle.dp(context, 36), LibraryStyle.dp(context, 12), LibraryStyle.dp(context, 14))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, Color.argb(235, 0, 0, 0)))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private var focusedPoster = false
        init {
            tag = "library-poster-card"; isFocusable = false
            layoutParams = AbsListView.LayoutParams(-1, -2)
            setBackgroundColor(Color.rgb(19, 22, 29))
            addView(artwork, LayoutParams(-1, -1)); addView(shade, LayoutParams(-1, -1))
            caption.addView(title, LinearLayout.LayoutParams(-1, -2)); caption.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
            addView(caption, LayoutParams(-1, -2, Gravity.BOTTOM))
            updateFocus(false)
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((width * 1.5f).toInt(), MeasureSpec.EXACTLY))
        }
        override fun setSelected(selected: Boolean) { super.setSelected(selected); updateFocus((parent as? View)?.hasFocus() == true) }
        override fun setPressed(pressed: Boolean) { super.setPressed(pressed); updateFocus((parent as? View)?.hasFocus() == true) }
        fun updateFocus(parentFocused: Boolean) {
            val focused = isSelected && parentFocused || isPressed
            caption.visibility = if (focused && artwork.image.drawable != null) VISIBLE else INVISIBLE
            shade.visibility = if (focused) VISIBLE else INVISIBLE
            subtitle.visibility = if (subtitle.text.isNotEmpty()) VISIBLE else GONE
            if (focusedPoster != focused) { focusedPoster = focused; invalidate() }
        }
        override fun dispatchDraw(canvas: Canvas) {
            // GridView can restore selected state after its focus callback. Read the final
            // native state before drawing so returning from a floating control reveals it.
            updateFocus((parent as? View)?.hasFocus() == true)
            super.dispatchDraw(canvas)
            if (!focusedPoster) return
            val inset = LibraryStyle.dp(context, 3).toFloat()
            outline.color = Color.argb(170, 0, 0, 0); outline.strokeWidth = inset * 2
            canvas.drawRect(inset, inset, width - inset, height - inset, outline)
            outline.color = LibraryStyle.INK; outline.strokeWidth = LibraryStyle.dp(context, 2).toFloat()
            canvas.drawRect(inset, inset, width - inset, height - inset, outline)
        }
    }
}

internal class LibraryEpisodeAdapter(private val context: Context) : BaseAdapter() {
    var cacheMode = false
        set(value) { field = value; notifyDataSetChanged() }
    var marked: Set<String> = emptySet()
        set(value) { field = value; notifyDataSetChanged() }
    var items: List<LibraryEpisode> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }
    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = position.toLong()
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        return (convertView as? EpisodeTile ?: EpisodeTile(context).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(LibraryStyle.dp(context, 8), 0, LibraryStyle.dp(context, 8), 0)
            textSize = 16f; includeFontPadding = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = LibraryStyle.dp(context, 56)
        }).apply {
            owner = parent
            val episode = items[position]
            val state = when (episode.cacheState) {
                "complete" -> "已缓存"
                "queued" -> "排队中"
                "resolving" -> "准备下载"
                "downloading" -> if (episode.percent >= 0) "下载 ${episode.percent}%" else "下载中"
                "removing" -> "删除中"
                "failed" -> "失败，可重试"
                else -> ""
            }
            val checked = LibraryReference.key(episode.reference) in marked
            val visibleStatus = if (episode.cached) "" else state
            background = LibraryStyle.episode(context, episode.cached)
            setTextColor(LibraryStyle.episodeText(episode.cached))
            text = (if (cacheMode) (if (checked) "✓ " else "□ ") else "") + episode.label + if (visibleStatus.isNotEmpty()) "\n$visibleStatus" else ""
            contentDescription = "${episode.label}${if (state.isNotEmpty()) "，$state" else ""}，" +
                if (cacheMode) (if (checked) "已勾选，确认取消选择" else "未勾选，确认选择") else "确认播放"
        }
    }

    fun refreshFocus(parent: ViewGroup) {
        for (index in 0 until parent.childCount) parent.getChildAt(index).refreshDrawableState()
    }

    // GridView remembers selection after focus leaves. Only actual remote focus highlights a tile.
    @android.annotation.SuppressLint("AppCompatCustomView")
    private class EpisodeTile(context: Context) : TextView(context) {
        var owner: ViewGroup? = null
        override fun onCreateDrawableState(extraSpace: Int): IntArray {
            val states = super.onCreateDrawableState(extraSpace)
            if (owner?.hasFocus() != false) return states
            return states.clone().apply {
                var write = 0
                for (state in states) if (state != android.R.attr.state_selected) this[write++] = state
                while (write < size) this[write++] = 0
            }
        }
    }
}
