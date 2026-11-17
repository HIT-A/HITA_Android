package cn.limpu.hita.ui.main.homework

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentContainerView
import cn.limpu.hita.R
import dagger.hilt.android.AndroidEntryPoint

/** Reuses the existing homework page when its optional main tab is hidden. */
@AndroidEntryPoint
class HomeworkActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FragmentContainerView(this).apply { id = R.id.today_homework_container }
        val toolbar = Toolbar(this).apply {
            setTitle(R.string.today_homework_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationContentDescription = getString(R.string.today_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            addView(toolbar, LinearLayout.LayoutParams(-1, -2))
            addView(container, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(container.id, HomeworkFragment()).commit()
    }
}
