package eu.rafareborn.biometricbypass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import eu.rafareborn.biometricbypass.R
import eu.rafareborn.biometricbypass.data.AppInfo
import eu.rafareborn.biometricbypass.data.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var etSearch: TextInputEditText
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var tvCount: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

    private lateinit var adapter: AppAdapter
    private var isHideDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.init(this)
        isHideDialog = ConfigManager.isHideDialog(this)

        initViews()
        setupListeners()
        loadApps()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        etSearch = findViewById(R.id.et_search)
        toggleMode = findViewById(R.id.toggle_mode)
        chipGroupFilter = findViewById(R.id.chip_group_filter)
        tvCount = findViewById(R.id.tv_count)
        rvApps = findViewById(R.id.rv_apps)
        progressBar = findViewById(R.id.progress_bar)
        tvEmpty = findViewById(R.id.tv_empty)

        adapter = AppAdapter {
            saveCurrentSelection()
            updateCountText()
        }

        rvApps.layoutManager = LinearLayoutManager(this)
        rvApps.adapter = adapter

        // Set initial mode toggle
        val currentMode = ConfigManager.getMode(this)
        if (currentMode == ConfigManager.MODE_BLACKLIST) {
            toggleMode.check(R.id.btn_blacklist)
        } else {
            toggleMode.check(R.id.btn_whitelist)
        }

        // Set initial filter chip
        chipGroupFilter.check(R.id.chip_all)
    }

    private fun setupListeners() {
        etSearch.addTextChangedListener { text ->
            adapter.setQuery(text?.toString() ?: "")
            updateCountText()
            checkEmptyState()
        }

        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                saveCurrentSelection()
                updateCountText()
            }
        }

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chip_user -> AppAdapter.FILTER_USER
                R.id.chip_system -> AppAdapter.FILTER_SYSTEM
                R.id.chip_all -> AppAdapter.FILTER_ALL
                else -> AppAdapter.FILTER_ALL
            }
            adapter.setFilter(filter)
            updateCountText()
            checkEmptyState()
        }
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE
        rvApps.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val savedPackages = ConfigManager.getPackages(this@MainActivity)
            val installedPackages = pm.getInstalledPackages(PackageManager.GET_META_DATA)

            val appList = mutableListOf<AppInfo>()
            for (pkg in installedPackages) {
                if (pkg.packageName == packageName) continue

                val appInfo = pkg.applicationInfo ?: continue
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon = runCatching { pm.getApplicationIcon(appInfo) }.getOrNull()
                val isSelected = pkg.packageName in savedPackages

                appList.add(
                    AppInfo(
                        packageName = pkg.packageName,
                        appName = appName,
                        icon = icon,
                        isSystem = isSystem,
                        isSelected = isSelected,
                    )
                )
            }

            // Sort: Selected first, then non-system first, then by name
            appList.sortWith(
                compareByDescending<AppInfo> { it.isSelected }
                    .thenBy { it.isSystem }
                    .thenBy { it.appName.lowercase() }
            )

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                adapter.submitList(appList)
                updateCountText()
                checkEmptyState()
            }
        }
    }

    private fun checkEmptyState() {
        tvEmpty.visibility = if (adapter.getDisplayedCount() == 0 && progressBar.visibility != View.VISIBLE) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun saveCurrentSelection() {
        val mode = if (toggleMode.checkedButtonId == R.id.btn_blacklist) {
            ConfigManager.MODE_BLACKLIST
        } else {
            ConfigManager.MODE_WHITELIST
        }

        val selectedPackages = adapter.getAllApps()
            .filter { it.isSelected }
            .map { it.packageName }
            .toSet()

        ConfigManager.saveConfig(this, mode, selectedPackages, isHideDialog)
    }

    private fun updateCountText() {
        val selected = adapter.getDisplayedSelectedCount()
        val total = adapter.getDisplayedCount()
        tvCount.text = getString(R.string.selected_count_format, selected, total)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val hideDialogItem = menu.findItem(R.id.action_hide_dialog)
        hideDialogItem?.isChecked = isHideDialog
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_select_all -> {
                adapter.selectAllDisplayed(true)
                saveCurrentSelection()
                updateCountText()
                true
            }
            R.id.action_deselect_all -> {
                adapter.selectAllDisplayed(false)
                saveCurrentSelection()
                updateCountText()
                true
            }
            R.id.action_hide_dialog -> {
                isHideDialog = !item.isChecked
                item.isChecked = isHideDialog
                saveCurrentSelection()
                val stateText = if (isHideDialog) "已开启：自动隐藏确认弹窗（完全无感）" else "已关闭：保持系统原生弹窗"
                Toast.makeText(this, stateText, Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_export -> {
                exportConfig()
                true
            }
            R.id.action_import -> {
                showImportDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportConfig() {
        val json = ConfigManager.exportToJson(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("BiometricBypass_Config", json)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show()
    }

    private fun showImportDialog() {
        val editText = EditText(this).apply {
            hint = "请在此粘贴导出的 JSON 配置字符串"
            minLines = 4
            maxLines = 8
            setPadding(40, 30, 40, 30)
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        if (clipText != null && clipText.trim().startsWith("{")) {
            editText.setText(clipText.trim())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_import)
            .setView(editText)
            .setPositiveButton("导入") { _, _ ->
                val text = editText.text.toString().trim()
                if (ConfigManager.importFromJson(this, text)) {
                    Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
                    isHideDialog = ConfigManager.isHideDialog(this)
                    invalidateOptionsMenu()
                    val mode = ConfigManager.getMode(this)
                    if (mode == ConfigManager.MODE_BLACKLIST) {
                        toggleMode.check(R.id.btn_blacklist)
                    } else {
                        toggleMode.check(R.id.btn_whitelist)
                    }
                    loadApps()
                } else {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
}

