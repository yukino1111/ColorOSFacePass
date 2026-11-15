package io.github.yukino1111.colorosfacepass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.loadingindicator.LoadingIndicator
import com.google.android.material.textfield.TextInputEditText
import io.github.yukino1111.colorosfacepass.BuildConfig
import io.github.yukino1111.colorosfacepass.R
import io.github.yukino1111.colorosfacepass.data.AppInfo
import io.github.yukino1111.colorosfacepass.data.ConfigManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var appBar: AppBarLayout
    private lateinit var toolbar: MaterialToolbar
    private lateinit var btnLanguage: MaterialButton
    private lateinit var etSearch: TextInputEditText
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var chipGroupFilter: ChipGroup
    private lateinit var tvCount: TextView
    private lateinit var rvApps: RecyclerView
    private lateinit var loadingIndicator: LoadingIndicator
    private lateinit var llEmpty: View

    private lateinit var adapter: AppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ConfigManager.init(this)

        initViews()
        applyEdgeToEdgeInsets()
        setupListeners()
        loadApps()
    }

    private fun initViews() {
        appBar = findViewById(R.id.app_bar)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        btnLanguage = findViewById(R.id.btn_language)
        etSearch = findViewById(R.id.et_search)
        toggleMode = findViewById(R.id.toggle_mode)
        chipGroupFilter = findViewById(R.id.chip_group_filter)
        tvCount = findViewById(R.id.tv_count)
        rvApps = findViewById(R.id.rv_apps)
        loadingIndicator = findViewById(R.id.progress_bar)
        llEmpty = findViewById(R.id.ll_empty)

        adapter =
            AppAdapter {
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

    private fun applyEdgeToEdgeInsets() {
        val baseAppBarLeft = appBar.paddingLeft
        val baseAppBarTop = appBar.paddingTop
        val baseAppBarRight = appBar.paddingRight
        val baseListLeft = rvApps.paddingLeft
        val baseListRight = rvApps.paddingRight
        val baseListBottom = rvApps.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_coordinator)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            appBar.updatePadding(
                left = baseAppBarLeft + bars.left,
                top = baseAppBarTop + bars.top,
                right = baseAppBarRight + bars.right,
            )
            rvApps.updatePadding(
                left = baseListLeft + bars.left,
                right = baseListRight + bars.right,
                bottom = baseListBottom + bars.bottom,
            )
            insets
        }
    }

    private fun setupListeners() {
        btnLanguage.setOnClickListener {
            toggleLanguage()
        }

        etSearch.addTextChangedListener { text ->
            adapter.setQuery(text?.toString() ?: "")
            updateCountText()
            checkEmptyState()
        }

        toggleMode.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) {
                saveCurrentSelection()
                updateCountText()
            }
        }

        chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter =
                when (checkedIds.firstOrNull()) {
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
        loadingIndicator.visibility = View.VISIBLE
        rvApps.visibility = View.GONE
        llEmpty.visibility = View.GONE

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
                    ),
                )
            }

            // Sort: Selected first, then non-system first, then by name
            appList.sortWith(
                compareByDescending<AppInfo> { it.isSelected }
                    .thenBy { it.isSystem }
                    .thenBy { it.appName.lowercase() },
            )

            withContext(Dispatchers.Main) {
                loadingIndicator.visibility = View.GONE
                rvApps.visibility = View.VISIBLE
                adapter.submitList(appList)
                updateCountText()
                checkEmptyState()
            }
        }
    }

    private fun checkEmptyState() {
        llEmpty.visibility =
            if (adapter.getDisplayedCount() == 0 && loadingIndicator.visibility != View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun saveCurrentSelection() {
        val mode =
            if (toggleMode.checkedButtonId == R.id.btn_blacklist) {
                ConfigManager.MODE_BLACKLIST
            } else {
                ConfigManager.MODE_WHITELIST
            }

        val selectedPackages =
            adapter
                .getAllApps()
                .filter { it.isSelected }
                .map { it.packageName }
                .toSet()

        ConfigManager.saveConfig(this, mode, selectedPackages)
    }

    private fun updateCountText() {
        val selected = adapter.getDisplayedSelectedCount()
        val total = adapter.getDisplayedCount()
        tvCount.text = getString(R.string.selected_count_format, selected, total)
        tvCount.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
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

            R.id.action_export -> {
                exportConfig()
                true
            }

            R.id.action_import -> {
                showImportDialog()
                true
            }

            R.id.action_about -> {
                showAboutDialog()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }

    private fun exportConfig() {
        val json = ConfigManager.exportToJson(this)
        copyToClipboard("BiometricBypass_Config", json)
        Toast.makeText(this, R.string.export_success, Toast.LENGTH_LONG).show()
    }

    private fun showImportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import, null)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.et_import)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipText =
            clipboard.primaryClip
                ?.getItemAt(0)
                ?.text
                ?.toString()
        if (clipText != null && clipText.trim().startsWith("{")) {
            editText.setText(clipText.trim())
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_import)
            .setView(dialogView)
            .setPositiveButton(R.string.dialog_import_confirm) { _, _ ->
                val text =
                    editText.text
                        ?.toString()
                        ?.trim()
                        .orEmpty()
                if (ConfigManager.importFromJson(this, text)) {
                    Toast.makeText(this, R.string.import_success, Toast.LENGTH_SHORT).show()
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
            }.setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_about_version)
        val cardRepo = dialogView.findViewById<MaterialCardView>(R.id.card_about_repo)

        tvVersion.text = getString(R.string.about_version_format, BuildConfig.VERSION_NAME)
        val repoUrl = getString(R.string.about_repo_url)

        val dialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.about_title)
                .setView(dialogView)
                .create()

        cardRepo.setOnClickListener {
            dialog.dismiss()
            openRepoUrl(repoUrl)
        }
        cardRepo.setOnLongClickListener {
            copyToClipboard("ColorOSFacePass_Repo", repoUrl)
            Toast.makeText(this, R.string.about_link_copied, Toast.LENGTH_SHORT).show()
            true
        }

        dialog.show()
    }

    private fun toggleLanguage() {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val currentLang =
            if (!appLocales.isEmpty) {
                appLocales[0]?.language
            } else {
                resources.configuration.locales[0]?.language
            }
        val targetTag = if (currentLang == "en") "zh" else "en"
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(targetTag))
    }

    private fun openRepoUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val opened = runCatching { startActivity(intent) }.isSuccess
        if (!opened) {
            copyToClipboard("ColorOSFacePass_Repo", url)
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(
        label: String,
        text: String,
    ) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}
