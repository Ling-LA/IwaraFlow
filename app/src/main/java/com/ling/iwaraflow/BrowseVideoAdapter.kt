package com.ling.iwaraflow

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load

/** Metadata-only cards: browsing a category never starts a player or records watch feedback. */
internal class BrowseVideoAdapter(private val onOpen: (VideoItem) -> Unit) : RecyclerView.Adapter<BrowseVideoAdapter.Holder>() {
    private val items = ArrayList<VideoItem>()

    fun submit(videos: List<VideoItem>) { items.clear(); items.addAll(videos); notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(parent)
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])
    override fun onViewRecycled(holder: Holder) { holder.cover.dispose(); super.onViewRecycled(holder) }

    inner class Holder(parent: ViewGroup) : RecyclerView.ViewHolder(LinearLayout(parent.context)) {
        private val context = parent.context
        private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
        val cover = ImageView(context)
        private val title = TextView(context)
        private val author = TextView(context)
        private val likes = TextView(context)
        private val date = TextView(context)

        init {
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(4), dp(4), dp(6)) }
                background = GradientDrawable().apply { setColor(UiPalette.resolve(context, -1)); cornerRadius = dp(14).toFloat() }
                clipToOutline = true
                isFocusable = true
                addView(FrameLayout(context).apply {
                    // A uniform cover ratio keeps the two-column grid predictable while scrolling.
                    addView(cover.apply { scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                    addView(TextView(context).apply {
                        text = "▶"; textSize = 13f; setTextColor(-1); gravity = Gravity.CENTER
                        background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(12).toFloat() }
                    }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(8) })
                }, LinearLayout.LayoutParams(-1, dp(150)))
                addView(title.apply {
                    textSize = 15f; setTextColor(UiPalette.resolve(context, 0xFF17324A.toInt()))
                    setTypeface(null, Typeface.BOLD); maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END
                    setPadding(dp(10), dp(9), dp(10), dp(4))
                }, LinearLayout.LayoutParams(-1, -2))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), 0, dp(10), dp(6))
                    addView(author.apply {
                        textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                        setTextColor(UiPalette.resolve(context, 0xFF607D93.toInt()))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(likes.apply { textSize = 12f; setPadding(dp(5), 0, 0, 0) }, LinearLayout.LayoutParams(-2, -2))
                })
                addView(date.apply {
                    textSize = 11f; maxLines = 1; setPadding(dp(10), 0, dp(10), dp(10))
                    setTextColor(UiPalette.resolve(context, 0xFF607D93.toInt()))
                }, LinearLayout.LayoutParams(-1, -2))
            }
        }

        fun bind(item: VideoItem) {
            title.text = item.title
            author.text = "@${item.author}"
            likes.text = "${if (item.liked) "♥" else "♡"} ${count(item.likes)}"
            likes.setTextColor(if (item.liked) 0xFFE94C77.toInt() else UiPalette.resolve(context, 0xFF607D93.toInt()))
            date.text = if (item.createdAt > 0) java.time.Instant.ofEpochMilli(item.createdAt)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() else "上传日期未知"
            cover.load(item.thumbnailUrl.takeIf { it.isNotBlank() }) {
                crossfade(false); placeholder(R.drawable.bg_avatar_placeholder); error(R.drawable.bg_avatar_placeholder)
            }
            itemView.contentDescription = "${item.title}，${item.author}，${item.likes} 赞"
            itemView.setOnClickListener { onOpen(item) }
        }
    }

    private fun count(value: Int): String = when {
        value >= 10000 -> "%.1f万".format(value / 10000.0)
        value >= 1000 -> "%.1fk".format(value / 1000.0)
        else -> value.toString()
    }
}
