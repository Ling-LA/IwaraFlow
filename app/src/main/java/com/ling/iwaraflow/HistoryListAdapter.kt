package com.ling.iwaraflow

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class HistoryListAdapter(context: Context, items: List<VideoItem>) :
    ArrayAdapter<VideoItem>(context, 0, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_history, parent, false)
        val item = getItem(position) ?: return view
        view.findViewById<TextView>(R.id.historyTitle).text = item.title
        view.findViewById<TextView>(R.id.historyMeta).text = "@${item.author}"
        view.findViewById<TextView>(R.id.historyTags).text = item.tags.take(5).joinToString("  ") { "#$it" }
        return view
    }
}
