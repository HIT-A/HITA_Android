package cn.limpu.hita.ui.main.homework

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentContainerView
import cn.limpu.hita.R
import cn.limpu.hita.ui.design.HitaTheme
import cn.limpu.hita.ui.main.MainThemeBackground
import dagger.hilt.android.AndroidEntryPoint

/** Reuses the existing homework page when its optional main tab is hidden. */
@AndroidEntryPoint
class HomeworkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        @Suppress("DEPRECATION") // Needed on older APIs to dispatch IME insets.
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val container = FragmentContainerView(this).apply { id = R.id.today_homework_container }
        setContentView(container)
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(container.id, HomeworkFragment()).commit()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun HomeworkPage(content: @Composable () -> Unit) {
        val dark = HitaTheme.isDark
        LaunchedEffect(dark) {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        MainThemeBackground(page = {
            Column(
                Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.title_homework)) },
                    navigationIcon = {
                        IconButton(onClick = onBackPressedDispatcher::onBackPressed) {
                            Icon(
                                painter = painterResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material),
                                contentDescription = stringResource(R.string.today_back),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                content()
            }
        })
    }
}
