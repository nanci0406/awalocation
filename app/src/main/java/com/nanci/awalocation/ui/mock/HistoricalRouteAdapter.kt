package com.nanci.awalocation.ui.mock

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textview.MaterialTextView
import com.nanci.awalocation.R

class HistoricalRouteAdapter(
    private val dataSet: MutableList<HistoricalRoute>,
    private val onItemClicked: (HistoricalRoute, isLongClicked: Boolean) -> Unit
) : RecyclerView.Adapter<HistoricalRouteAdapter.ViewHolder>() {

    class ViewHolder(
        val root: View
    ) : RecyclerView.ViewHolder(root) {
        val name: MaterialTextView = root.findViewById(R.id.name)
        val route: MaterialTextView = root.findViewById(R.id.desc)
    }

    operator fun get(position: Int): HistoricalRoute {
        return dataSet[position]
    }

    fun removeItem(position: Int): HistoricalRoute {
        val removed = dataSet.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, itemCount - position)
        return removed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_route_item, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = dataSet[position]
        holder.name.text = item.name
        val first = item.route.firstOrNull()
        holder.route.text = if (first != null) {
            "共 ${item.route.size} 个路径点 · 起点 ${"%.4f".format(first.first)}, ${"%.4f".format(first.second)}"
        } else {
            "共 ${item.route.size} 个路径点"
        }
        holder.root.setOnClickListener {
            onItemClicked(item, false)
        }
        holder.root.setOnLongClickListener {
            onItemClicked(item, true)
            true
        }
    }
}