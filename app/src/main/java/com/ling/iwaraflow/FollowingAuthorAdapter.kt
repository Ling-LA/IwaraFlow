package com.ling.iwaraflow

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

class FollowingAuthorAdapter(
    private val items: List<IwaraAuthor>,
    private val onClick: (IwaraAuthor) -> Unit
) : RecyclerView.Adapter<FollowingAuthorAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_following_author, parent, false))

    override fun getItemCount(): Int = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val avatar = view.findViewById<ImageView>(R.id.followingAvatar)
        private val name = view.findViewById<TextView>(R.id.followingName)
        private val username = view.findViewById<TextView>(R.id.followingUsername)
        private val description = view.findViewById<TextView>(R.id.followingDescription)

        fun bind(item: IwaraAuthor) {
            name.text = item.name.ifBlank { item.username }
            username.text = if (item.username.isBlank()) "" else "@${item.username}"
            description.text = item.description.replace('\n', ' ').trim().ifBlank { "已关注" }
            avatar.setImageDrawable(null)
            if (item.avatarUrl.isNotBlank()) {
                avatar.load(item.avatarUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                }
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }
}
