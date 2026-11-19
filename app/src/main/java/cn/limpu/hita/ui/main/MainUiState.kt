package cn.limpu.hita.ui.main

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.repository.DisplayScopeResolver

internal data class MainUiState(
    val navigation: MainNavigationState = MainNavigationState.restore(MainTab.visibleFor(null)),
    val drawerOpen: Boolean = false,
    val drawer: DrawerUserState = DrawerUserState(),
    val todayTitle: String = "",
    /** 顶栏次级信息：教学周与星期，主标题是日期。 */
    val todaySubtitle: String = "",
    val timetableTitle: String = "",
    val timetableName: String = "",
    val showTimetableName: Boolean = false,
    val scopeOptions: List<DisplayScopeResolver.ScopeOption> = emptyList(),
    val currentScope: DisplayScope? = null,
    val blogDotVisible: Boolean = false,
    /** 首页迷你课程条；null 表示暂无需要展示的课程。 */
    val miniCourse: MiniCourseState? = null,
)

/** A bitmap belongs to the Activity's image loader, never the retained ViewModel. */
internal data class MainWallpaperState(
    val bitmap: Bitmap? = null,
    val visible: Boolean = false,
    val scrimOpacity: Int = 0,
    val titleColor: Int = android.graphics.Color.WHITE,
    val labelColor: Int = android.graphics.Color.WHITE,
)

internal data class MainActions(
    val selectTab: (Int) -> Unit,
    val openDrawer: () -> Unit,
    val closeDrawer: () -> Unit,
    val selectTimetable: (DisplayScope) -> Unit,
    val timetableTitleClick: () -> Unit = {},
    /** 顶栏「按周跳转」的候选周次；按需读取，避免每次重组都查课表。 */
    val weekOptions: () -> List<cn.limpu.hita.ui.main.timetable.WeekOption> = { emptyList() },
    val pickWeek: (Long) -> Unit = {},
    val theme: () -> Unit,
    val wallpaper: () -> Unit,
    val wallpaperLongPress: () -> Unit,
    val timetableSetting: () -> Unit,
    val timetableSocial: () -> Unit,
    val timetableHistory: () -> Unit = {},
    val addEvent: () -> Unit,
    val drawerHeader: () -> Unit,
    val drawerAvatar: () -> Unit,
    val themeStyle: () -> Unit,
    val agreement: () -> Unit,
    val userFeedback: () -> Unit,
    val about: () -> Unit,
    val githubUser: () -> Unit,
    val githubProject: () -> Unit,
    val githubMingyu: () -> Unit,
    val githubRuannuo: () -> Unit,
    val githubJixing: () -> Unit,
    val githubRadiant: () -> Unit,
    val systemBars: (wallpaper: Boolean, darkTheme: Boolean) -> Unit,
)
