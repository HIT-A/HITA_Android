package cn.limpu.hita.ui.search

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaComposeTheme
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.search.teacher.FragmentSearchTeacher
import com.limpu.style.ThemeTools
import com.limpu.style.base.BaseTabAdapter
import com.limpu.style.base.FragmentSearchResult
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SearchActivity : AppCompatActivity(), SearchRoot {
    private var searchEditText: EditText? = null
    private var committedQuery = ""
    private var pagerAdapter: SearchPagerAdapter? = null
    private var openKeyboardOnEnter = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val nightMode = when (ThemeTools.getThemeMode(this)) {
            ThemeTools.MODE.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeTools.MODE.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        super.onCreate(savedInstanceState)
        val keyword = intent.getStringExtra("keyword").orEmpty()
        val draft = savedInstanceState?.getString("searchDraft") ?: keyword
        committedQuery = savedInstanceState?.getString("searchQuery") ?: keyword
        openKeyboardOnEnter = savedInstanceState == null && !isSearchForPurpose()

        setContent {
            HitaComposeTheme() {
                SearchScreen(
                    activity = this,
                    initialText = draft,
                    onBack = { onBackPressedDispatcher.onBackPressed() },
                    onSearchViewReady = { editText ->
                        searchEditText = editText
                        val start = savedInstanceState?.getInt("searchSelectionStart", draft.length) ?: draft.length
                        val end = savedInstanceState?.getInt("searchSelectionEnd", draft.length) ?: draft.length
                        editText.setSelection(start.coerceIn(0, draft.length), end.coerceIn(0, draft.length))
                        editText.setOnEditorActionListener { textView, actionId, _: KeyEvent? ->
                            if (textView.text.toString().isBlank()) return@setOnEditorActionListener false
                            if (
                                actionId == EditorInfo.IME_ACTION_GO ||
                                actionId == EditorInfo.IME_ACTION_SEARCH
                            ) {
                                hideKeyboard()
                                setSearchText(textView.text.toString())
                                true
                            } else {
                                false
                            }
                        }
                    },
                    onPagerReady = { viewPager, tabs ->
                        if (pagerAdapter == null) {
                            pagerAdapter = SearchPagerAdapter(supportFragmentManager, this)
                        }
                        viewPager.adapter = pagerAdapter
                        tabs.setupWithViewPager(viewPager)
                    }
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("searchQuery", committedQuery)
        searchEditText?.let {
            outState.putString("searchDraft", it.text.toString())
            outState.putInt("searchSelectionStart", it.selectionStart)
            outState.putInt("searchSelectionEnd", it.selectionEnd)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        if (openKeyboardOnEnter) {
            openKeyboardOnEnter = false
            popUpKeyboard()
        }
    }

    private fun isSearchForPurpose(): Boolean {
        val text = intent.getStringExtra("keyword")
        val purpose = intent.getStringExtra("type")
        return text?.isNotEmpty() == true && purpose?.isNotEmpty() == true
    }

    @Suppress("DEPRECATION")
    private fun popUpKeyboard() {
        searchEditText?.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.toggleSoftInput(0, InputMethodManager.HIDE_NOT_ALWAYS)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    override fun getSearchText(): String {
        return committedQuery
    }

    fun setSearchText(text: String) {
        committedQuery = text
        for (fragment in supportFragmentManager.fragments) {
            if (fragment is FragmentSearchResult) {
                fragment.setSearchText(text)
            }
        }
    }

    class SearchPagerAdapter(fm: FragmentManager, val context: Context) : BaseTabAdapter(fm, 1) {
        private val titles: IntArray = intArrayOf(R.string.tab_search_teacher)

        override fun getPageTitle(position: Int): CharSequence {
            return context.getString(titles[position])
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            mFragments[position] = null
        }

        override fun initItem(position: Int): Fragment {
            return FragmentSearchTeacher()
        }
    }
}

@Composable
private fun SearchScreen(
    activity: FragmentActivity,
    initialText: String,
    onBack: () -> Unit,
    onSearchViewReady: (EditText) -> Unit,
    onPagerReady: (ViewPager, TabLayout) -> Unit,
) {
    val tokens = HitaTheme.tokens
    val density = LocalDensity.current
    val containerId = rememberSaveable { android.view.View.generateViewId() }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val tabHeightPx = with(density) { 32.dp.toPx().toInt() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = tokens.spacing.lg)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                    contentDescription = stringResource(R.string.today_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.rotate(180f)
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_baseline_search_24),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = tokens.spacing.sm)
                            .size(24.dp)
                    )
                    AndroidView(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .padding(start = tokens.spacing.xs),
                        factory = {
                            android.widget.EditText(it).apply {
                                setSingleLine(true)
                                imeOptions = EditorInfo.IME_ACTION_SEARCH
                                inputType = android.text.InputType.TYPE_CLASS_TEXT
                                setText(initialText)
                                setTextColor(textColor)
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                onSearchViewReady(this)
                            }
                        },
                        update = { it.setTextColor(textColor) }
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    android.widget.LinearLayout(it).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        val tabs = TabLayout(it).apply {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            tabMode = TabLayout.MODE_SCROLLABLE
                            isTabIndicatorFullWidth = false
                        }
                        val viewPager = ViewPager(it).apply {
                            id = containerId
                        }
                        addView(tabs, android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            tabHeightPx
                        ))
                        addView(viewPager, android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                        ))
                        onPagerReady(viewPager, tabs)
                    }
                },
                update = {
                    activity.supportFragmentManager.executePendingTransactions()
                }
            )
        }
    }
}
