package eu.rafareborn.biometricbypass.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import eu.rafareborn.biometricbypass.R
import eu.rafareborn.biometricbypass.data.AppInfo

class AppAdapter(
    private val onSelectionChanged: () -> Unit,
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_USER = 1
        const val FILTER_SYSTEM = 2
    }

    private var allApps: List<AppInfo> = emptyList()
    private var displayedApps: List<AppInfo> = emptyList()

    private var currentQuery: String = ""
    private var currentFilter: Int = FILTER_USER

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(apps: List<AppInfo>) {
        allApps = apps
        applyFilter()
    }

    fun setFilter(filter: Int) {
        if (currentFilter != filter) {
            currentFilter = filter
            applyFilter()
        }
    }

    fun setQuery(query: String) {
        val trimmed = query.trim()
        if (currentQuery != trimmed) {
            currentQuery = trimmed
            applyFilter()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun selectAllDisplayed(select: Boolean) {
        displayedApps.forEach { it.isSelected = select }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        val queryLower = currentQuery.lowercase()
        displayedApps = allApps.filter { app ->
            val matchesCategory = when (currentFilter) {
                FILTER_USER -> !app.isSystem
                FILTER_SYSTEM -> app.isSystem
                else -> true
            }
            val matchesQuery = if (queryLower.isEmpty()) {
                true
            } else {
                app.appName.lowercase().contains(queryLower) ||
                    app.packageName.lowercase().contains(queryLower)
            }
            matchesCategory && matchesQuery
        }
        notifyDataSetChanged()
    }

    fun getDisplayedCount(): Int = displayedApps.size
    fun getTotalSelectedCount(): Int = allApps.count { it.isSelected }
    fun getTotalCount(): Int = allApps.size
    fun getAllApps(): List<AppInfo> = allApps

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(displayedApps[position])
    }

    override fun getItemCount(): Int = displayedApps.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.card_view)
        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_name)
        private val tvPackage: TextView = itemView.findViewById(R.id.tv_package)
        private val tvSystemTag: TextView = itemView.findViewById(R.id.tv_system_tag)
        private val cbSelected: MaterialCheckBox = itemView.findViewById(R.id.cb_selected)

        fun bind(app: AppInfo) {
            tvName.text = app.appName
            tvPackage.text = app.packageName
            tvSystemTag.visibility = if (app.isSystem) View.VISIBLE else View.GONE
            cbSelected.isChecked = app.isSelected

            if (app.icon != null) {
                ivIcon.setImageDrawable(app.icon)
            } else {
                ivIcon.setImageResource(R.mipmap.ic_launcher)
            }

            cardView.setOnClickListener {
                app.isSelected = !app.isSelected
                cbSelected.isChecked = app.isSelected
                onSelectionChanged()
            }
        }
    }
}

