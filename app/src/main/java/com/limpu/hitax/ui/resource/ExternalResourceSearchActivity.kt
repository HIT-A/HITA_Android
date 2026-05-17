package com.limpu.hitax.ui.resource

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.google.android.material.snackbar.Snackbar
import com.limpu.component.data.DataState
import com.limpu.hitax.R
import com.limpu.hitax.data.model.resource.ExternalCourseItem
import com.limpu.hitax.data.model.resource.ExternalResourceEntry
import com.limpu.hitax.data.model.resource.ResourceSource
import com.limpu.hitax.databinding.ActivityExternalResourceSearchBinding
import com.limpu.hitax.databinding.ItemExternalCourseBinding
import com.limpu.hitax.databinding.ItemExternalResourceEntryBinding
import com.limpu.hitax.ui.base.HiltBaseActivity
import com.limpu.hitax.utils.LogUtils
import com.limpu.style.base.BaseListAdapter
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ExternalResourceSearchActivity :
    HiltBaseActivity<ActivityExternalResourceSearchBinding>() {

    private val viewModel: ExternalResourceSearchViewModel by viewModels()
    private lateinit var courseAdapter: CourseAdapter
    private lateinit var entryAdapter: EntryAdapter
    private var isBrowseMode = false
    private val browseStack = ArrayDeque<BrowseState>()

    private data class BrowseState(
        val path: String,
        val source: ResourceSource,
        val breadcrumb: String,
    )

    override fun initViewBinding(): ActivityExternalResourceSearchBinding =
        ActivityExternalResourceSearchBinding.inflate(layoutInflater)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setToolbarActionBack(binding.toolbar)
        applyStatusBarInsets()
    }

    override fun initViews() {
        binding.toolbar.title = getString(R.string.external_resource_title)

        courseAdapter = CourseAdapter(mutableListOf())
        entryAdapter = EntryAdapter(mutableListOf())
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = courseAdapter

        binding.searchInput.setOnEditorActionListener(object : TextView.OnEditorActionListener {
            override fun onEditorAction(v: TextView?, actionId: Int, event: KeyEvent?): Boolean {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                    startSearch()
                    return true
                }
                return false
            }
        })
        binding.searchButton.setOnClickListener { startSearch() }
        binding.swipeRefresh.setColorSchemeColors(getColorPrimary())
        binding.swipeRefresh.setOnRefreshListener {
            if (isBrowseMode) {
                val state = browseStack.lastOrNull() ?: return@setOnRefreshListener
                viewModel.browse(state.path, state.source)
            } else {
                startSearch()
            }
        }

        viewModel.searchResults.observe(this) { state ->
            if (isBrowseMode) return@observe
            binding.swipeRefresh.isRefreshing = false
            if (state.state == DataState.STATE.SUCCESS) {
                val items = state.data ?: emptyList()
                courseAdapter.notifyItemChangedSmooth(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.setText(R.string.external_resource_empty)
            } else {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.setText(R.string.external_resource_failed)
                state.message?.takeIf { it.isNotBlank() }?.let {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                }
            }
        }

        viewModel.browseResults.observe(this) { state ->
            if (!isBrowseMode) return@observe
            binding.swipeRefresh.isRefreshing = false
            if (state.state == DataState.STATE.SUCCESS) {
                val items = state.data ?: emptyList()
                entryAdapter.notifyItemChangedSmooth(items)
                binding.emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                binding.emptyText.setText(R.string.external_resource_empty)
            } else {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.setText(R.string.external_resource_failed)
                state.message?.takeIf { it.isNotBlank() }?.let {
                    Snackbar.make(binding.root, it, Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startSearch() {
        val input = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (input.isBlank()) return
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)

        if (isBrowseMode) {
            exitBrowseMode()
        }

        binding.swipeRefresh.isRefreshing = true
        viewModel.search(input)
    }

    private fun enterBrowseMode(item: ExternalCourseItem) {
        isBrowseMode = true
        browseStack.clear()
        val state = BrowseState(item.path, item.source, item.courseName)
        browseStack.addLast(state)

        binding.searchBar.visibility = View.GONE
        binding.breadcrumb.visibility = View.VISIBLE
        binding.breadcrumb.text = item.courseName
        binding.list.adapter = entryAdapter
        binding.toolbar.title = getString(R.string.external_resource_browse)

        binding.swipeRefresh.isRefreshing = true
        viewModel.browse(item.path, item.source)
    }

    private fun navigateInto(entry: ExternalResourceEntry) {
        if (!entry.isDir) {
            handleFileClick(entry)
            return
        }

        val currentState = browseStack.lastOrNull() ?: return
        val newState = BrowseState(
            path = entry.path,
            source = currentState.source,
            breadcrumb = "${currentState.breadcrumb} / ${entry.name}",
        )
        browseStack.addLast(newState)

        binding.breadcrumb.text = newState.breadcrumb
        binding.swipeRefresh.isRefreshing = true
        viewModel.browse(entry.path, entry.source)
    }

    private fun exitBrowseMode() {
        isBrowseMode = false
        browseStack.clear()

        binding.searchBar.visibility = View.VISIBLE
        binding.breadcrumb.visibility = View.GONE
        binding.list.adapter = courseAdapter
        binding.toolbar.title = getString(R.string.external_resource_title)
    }

    @Deprecated("Use OnBackPressedCallback in production")
    override fun onBackPressed() {
        if (isBrowseMode && browseStack.size > 1) {
            browseStack.removeLast()
            val previous = browseStack.last()
            binding.breadcrumb.text = previous.breadcrumb
            binding.swipeRefresh.isRefreshing = true
            viewModel.browse(previous.path, previous.source)
        } else if (isBrowseMode) {
            exitBrowseMode()
            binding.emptyText.visibility = View.GONE
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    /**
     * Handle file click: download actual files, open website links in browser.
     */
    private fun handleFileClick(entry: ExternalResourceEntry) {
        val url = entry.downloadUrl

        // Fireworks website links → open in browser (not a downloadable file)
        if (url.startsWith("https://fireworks.jwyihao.top")) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return
        }

        // Build download URL via GitHub proxy (raw.githubusercontent.com blocked on CN mobile)
        val rawUrl = if (entry.path.isNotBlank()) {
            val repo = when (entry.source) {
                ResourceSource.HITCS -> "HITLittleZheng/HITCS"
                ResourceSource.FIREWORKS -> "HIT-Fireworks/fireworks-notes-society"
            }
            val encodedPath = entry.path.split("/").joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
            "https://raw.githubusercontent.com/$repo/main/$encodedPath"
        } else if (url.isNotBlank() && !url.startsWith("https://fireworks.")) {
            url
        } else {
            return
        }
        // Wrap with proxy for reliable access on Chinese mobile networks
        val downloadUrl = "https://ghproxy.net/$rawUrl"

        // Download with correct filename
        val fileName = entry.name // e.g. "总结.pdf"
        try {
            val dm = getSystemService(DOWNLOAD_SERVICE) as android.app.DownloadManager
            val request = android.app.DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle(fileName)
                .setDescription("正在下载 $fileName")
                .setNotificationVisibility(
                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
            dm.enqueue(request)
            com.google.android.material.snackbar.Snackbar.make(
                binding.root,
                "开始下载: $fileName",
                com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            LogUtils.e("Download failed: ${e.message}")
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                startActivity(intent)
            } catch (e2: Exception) {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    "下载失败: ${e.message}",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun applyStatusBarInsets() {
        val target = binding.root
        val originalTop = target.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = originalTop + bars.top)
            insets
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    inner class CourseAdapter(mBeans: MutableList<ExternalCourseItem>) :
        BaseListAdapter<ExternalCourseItem, CourseAdapter.Holder>(this, mBeans) {

        inner class Holder(val binding: ItemExternalCourseBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding {
            return ItemExternalCourseBinding.inflate(layoutInflater, parent, false)
        }

        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int): Holder {
            return Holder(viewBinding as ItemExternalCourseBinding)
        }

        override fun bindHolder(holder: Holder, data: ExternalCourseItem?, position: Int) {
            data ?: return
            holder.binding.title.text = data.courseName
            holder.binding.subtitle.text = data.category
            holder.binding.sourceTag.text = when (data.source) {
                ResourceSource.HITCS -> getString(R.string.external_resource_source_hitcs)
                ResourceSource.FIREWORKS -> getString(R.string.external_resource_source_fireworks)
            }
            holder.binding.sourceTag.visibility = View.VISIBLE
            holder.binding.card.setOnClickListener {
                enterBrowseMode(data)
            }
        }
    }

    inner class EntryAdapter(mBeans: MutableList<ExternalResourceEntry>) :
        BaseListAdapter<ExternalResourceEntry, EntryAdapter.Holder>(this, mBeans) {

        inner class Holder(val binding: ItemExternalResourceEntryBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun getViewBinding(parent: ViewGroup, viewType: Int): ViewBinding {
            return ItemExternalResourceEntryBinding.inflate(layoutInflater, parent, false)
        }

        override fun createViewHolder(viewBinding: ViewBinding, viewType: Int): Holder {
            return Holder(viewBinding as ItemExternalResourceEntryBinding)
        }

        override fun bindHolder(holder: Holder, data: ExternalResourceEntry?, position: Int) {
            data ?: return
            holder.binding.name.text = data.name
            if (data.isDir) {
                holder.binding.icon.setImageResource(R.drawable.ic_baseline_menu_24)
                holder.binding.size.visibility = View.GONE
            } else {
                holder.binding.icon.setImageResource(R.drawable.ic_baseline_search_24)
                if (data.size > 0) {
                    holder.binding.size.text = formatFileSize(data.size)
                    holder.binding.size.visibility = View.VISIBLE
                } else {
                    holder.binding.size.visibility = View.GONE
                }
            }
            holder.binding.card.setOnClickListener {
                navigateInto(data)
            }
        }
    }
}
