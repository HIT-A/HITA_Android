package cn.limpu.hita.feature.timetable.display

import android.app.Application
import cn.limpu.hita.data.model.timetable.DisplayScope
import cn.limpu.hita.data.repository.DisplayScopeResolver
import cn.limpu.hita.data.repository.DisplayScopeSelector
import cn.limpu.hita.feature.livecourse.scheduler.LiveCourseScheduler
import cn.limpu.hita.ui.widgets.WidgetUtils
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps selection persistence ahead of the platform refresh requests, on the caller's thread. */
@Singleton
class DisplayScopeCoordinator internal constructor(
    private val persistSelection: (DisplayScope) -> Unit,
    private val refreshWidgets: () -> Unit,
    private val scheduleLiveCourse: () -> Unit,
) : DisplayScopeSelector {
    @Inject constructor(application: Application, resolver: DisplayScopeResolver) : this(
        persistSelection = resolver::persistSelection,
        refreshWidgets = { WidgetUtils.sendRefreshToAll(application) },
        scheduleLiveCourse = { LiveCourseScheduler.autoSchedule(application) },
    )

    override fun select(scope: DisplayScope) {
        persistSelection(scope)
        refreshWidgets()
        scheduleLiveCourse()
    }
}
