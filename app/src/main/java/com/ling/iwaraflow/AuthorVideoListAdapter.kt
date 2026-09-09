package com.ling.iwaraflow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AuthorVideoListAdapter(
    private val items: List<VideoItem>,
    private val onClick: (VideoItem) -> Unit
) : RecyclerView.Adapter<AuthorVideoListAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_author_video, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.videoTitle)
        private val meta = view.findViewById<TextView>(R.id.videoMeta)
        private val tags = view.findViewById<TextView>(R.id.videoTags)
        private val issue = view.findViewById<TextView>(R.id.playbackIssue)
        private val icon = view.findViewById<TextView>(R.id.videoIcon)

        fun bind(item: VideoItem) {
            title.text = item.title
            meta.text = "${formatCount(item.views)} 播放  ·  ${formatCount(item.likes)} 赞"
            tags.text = item.tags.take(5).joinToString("  ") { "#$it" }
            val reason = item.playbackIssue
            if (reason.isNullOrBlank()) {
                issue.visibility = View.GONE
                icon.alpha = 1f
                itemView.alpha = 1f
                itemView.setOnClickListener { onClick(item) }
            } else {
                issue.text = reason
                issue.visibility = View.VISIBLE
                icon.alpha = 0.35f
                itemView.alpha = 0.9f
                itemView.setOnClickListener(null)
            }
        }

        private fun formatCount(value: Int): String = when {
            value >= 10_000 -> "%.1fw".format(value / 10_000.0)
            value >= 1_000 -> "%.1fk".format(value / 1_000.0)
            else -> value.toString()
        }
    }
}
