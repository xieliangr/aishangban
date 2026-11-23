package com.aishangban.auto

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** 时间段列表适配器。 */
class TimeAdapter(
    private val items: MutableList<TimeRange>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<TimeAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeText: TextView = view.findViewById(R.id.timeText)
        val deleteBtn: Button = view.findViewById(R.id.deleteBtn)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_time, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val range = items[position]
        holder.timeText.text = range.displayText
        holder.deleteBtn.setOnClickListener {
            val adapterPosition = holder.bindingAdapterPosition
            if (adapterPosition != RecyclerView.NO_POSITION) onDelete(adapterPosition)
        }
    }

    override fun getItemCount(): Int = items.size
}
