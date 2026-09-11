package com.ling.iwaraflow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

class SavedVideoListAdapter(
    private val items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit,
    /** 角标上的一行小字，返回 null 就不显示。“已下载”那一页用它标下载状态。 */
    private val note: (VideoItem) -> String? = { null }
) : RecyclerView.Adapter<SavedVideoListAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_author_video, parent, false))

    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.videoTitle)
        private val meta = view.findViewById<TextView>(R.id.videoMeta)
        private val tags = view.findViewById<TextView>(R.id.videoTags)
        private val issue = view.findViewById<TextView>(R.id.playbackIssue)
        private val icon = view.findViewById<TextView>(R.id.videoIcon)
        private val thumbnail = view.findViewById<ImageView>(R.id.videoThumbnail)

        fun bind(item: VideoItem) {
            title.text = item.title
            meta.text = buildString {
                append("@${item.author}")
                if (item.views > 0 || item.likes > 0) append("  ·  ${formatCount(item.views)} 播放  ·  ${formatCount(item.likes)} 赞")
            }
            tags.text = item.tags.take(5).joinToString("  ") { "#$it" }
            val label = note(item)
            issue.text = label.orEmpty()
            issue.visibility = if (label == null) View.GONE else View.VISIBLE
            itemView.alpha = 1f
            itemView.setOnClickListener { onClick(item) }

            icon.visibility = View.VISIBLE
            thumbnail.setImageDrawable(null)
            if (item.thumbnailUrl.isNotBlank()) {
                thumbnail.load(item.thumbnailUrl) {
                    crossfade(true)
                    listener(
                        onSuccess = { _, _ -> icon.visibility = View.GONE },
                        onError = { _, _ -> icon.visibility = View.VISIBLE }
                    )
                }
            }
        }

        private fun formatCount(value: Int): String = when {
            value >= 10_000 -> "%.1fw".format(value / 10_000.0)
            value >= 1_000 -> "%.1fk".format(value / 1_000.0)
            else -> value.toString()
        }
    }
}
