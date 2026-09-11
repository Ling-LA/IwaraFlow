package com.ling.iwaraflow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/**
 * 评论列表。顶层评论一行一条；点开「共 N 条回复」后，回复直接接在那条评论下面
 * 缩进显示，再点一次收起。这样不用跳页，也和哔哩哔哩的习惯一致。
 */
class CommentListAdapter(
    private val onReply: (IwaraComment) -> Unit,
    private val onLoadReplies: (IwaraComment, (Result<List<IwaraComment>>) -> Unit) -> Unit,
    /** 点了头像或名字：进这个用户的主页。 */
    private val onOpenAuthor: ((IwaraAuthor) -> Unit)? = null
) : RecyclerView.Adapter<CommentListAdapter.Holder>() {

    /** 列表里的一行：顶层评论或者展开出来的回复。 */
    internal class Row(val comment: IwaraComment, val depth: Int)

    private val rows = ArrayList<Row>()
    /** 已展开回复的顶层评论 id → 回复条数，用来收起时知道删几行。 */
    private val expanded = HashMap<String, Int>()
    private val loading = HashSet<String>()

    fun replaceAll(comments: List<IwaraComment>) {
        rows.clear(); expanded.clear(); loading.clear()
        comments.forEach { rows += Row(it, 0) }
        notifyDataSetChanged()
    }

    fun append(comments: List<IwaraComment>) {
        val existing = rows.asSequence().map { it.comment.id }.filter { it.isNotBlank() }.toHashSet()
        val fresh = comments.filter { it.id.isBlank() || existing.add(it.id) }
        if (fresh.isEmpty()) return
        val start = rows.size
        fresh.forEach { rows += Row(it, 0) }
        notifyItemRangeInserted(start, fresh.size)
    }

    /** 自己刚发的评论插到最前面，不用等重新拉列表。 */
    fun prepend(comment: IwaraComment) {
        rows.add(0, Row(comment, 0))
        notifyItemInserted(0)
    }

    /** 自己刚发的回复接在对应评论的回复末尾；那条评论没展开就先当作展开了一条。 */
    fun addReply(parentId: String, reply: IwaraComment) {
        val parentIndex = rows.indexOfFirst { it.depth == 0 && it.comment.id == parentId }
        if (parentIndex < 0) return
        val shown = expanded[parentId] ?: 0
        val insertAt = parentIndex + 1 + shown
        rows.add(insertAt, Row(reply, 1))
        expanded[parentId] = shown + 1
        notifyItemInserted(insertAt)
        notifyItemChanged(parentIndex)
    }

    fun topLevelCount(): Int = rows.count { it.depth == 0 }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

    private fun toggleReplies(parent: IwaraComment) {
        val id = parent.id
        if (id.isBlank() || id in loading) return
        val parentIndex = rows.indexOfFirst { it.depth == 0 && it.comment.id == id }
        if (parentIndex < 0) return
        val shown = expanded[id]
        if (shown != null) {
            repeat(shown) { rows.removeAt(parentIndex + 1) }
            expanded.remove(id)
            notifyItemRangeRemoved(parentIndex + 1, shown)
            notifyItemChanged(parentIndex)
            return
        }
        loading += id
        notifyItemChanged(parentIndex)
        onLoadReplies(parent) { result ->
            loading -= id
            val index = rows.indexOfFirst { it.depth == 0 && it.comment.id == id }
            if (index < 0) return@onLoadReplies
            result.onSuccess { replies ->
                rows.addAll(index + 1, replies.map { Row(it, 1) })
                expanded[id] = replies.size
                notifyItemRangeInserted(index + 1, replies.size)
            }
            notifyItemChanged(index)
        }
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.commentAvatar)
        private val author = view.findViewById<TextView>(R.id.commentAuthor)
        private val body = view.findViewById<TextView>(R.id.commentBody)
        private val time = view.findViewById<TextView>(R.id.commentTime)
        private val reply = view.findViewById<TextView>(R.id.commentReply)
        private val replies = view.findViewById<TextView>(R.id.commentReplies)
        private val density = view.resources.displayMetrics.density

        internal fun bind(row: Row) {
            val c = row.comment
            // 回复整体往右缩进，看得出是挂在上一条下面的。
            val indent = (if (row.depth > 0) 48 else 16) * density
            itemView.setPadding(indent.toInt(), itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            avatar.setImageDrawable(null)
            if (c.author.avatarUrl.isNotBlank()) {
                avatar.load(c.author.avatarUrl) { crossfade(true); transformations(CircleCropTransformation()) }
            } else {
                avatar.setImageResource(R.drawable.bg_reply_toggle)
            }
            author.text = c.author.name.ifBlank { c.author.username }.ifBlank { "匿名" }
            val openAuthor = onOpenAuthor?.takeIf { c.author.id.isNotBlank() || c.author.username.isNotBlank() }
            val authorClick: ((View) -> Unit)? = openAuthor?.let { open -> { open(c.author) } }
            avatar.setOnClickListener(authorClick)
            author.setOnClickListener(authorClick)
            avatar.isClickable = authorClick != null
            author.isClickable = authorClick != null
            body.text = c.body
            time.text = relativeTime(c.createdAt)
            reply.setOnClickListener { onReply(c) }

            val id = c.id
            when {
                row.depth > 0 || (c.replyCount <= 0 && expanded[id] == null) -> replies.visibility = View.GONE
                else -> {
                    replies.visibility = View.VISIBLE
                    replies.text = when {
                        id in loading -> "加载中…"
                        expanded[id] != null -> "收起回复"
                        else -> "共 ${c.replyCount} 条回复 ›"
                    }
                    replies.setOnClickListener { toggleReplies(c) }
                }
            }
        }
    }

    companion object {
        /** “3 分钟前”这类相对时间；超过一个月直接给日期。 */
        fun relativeTime(epochMs: Long, now: Long = System.currentTimeMillis()): String {
            if (epochMs <= 0L) return ""
            val diff = now - epochMs
            val minute = 60_000L; val hour = 60 * minute; val day = 24 * hour
            return when {
                diff < minute -> "刚刚"
                diff < hour -> "${diff / minute} 分钟前"
                diff < day -> "${diff / hour} 小时前"
                diff < 30 * day -> "${diff / day} 天前"
                else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(epochMs))
            }
        }
    }
}
