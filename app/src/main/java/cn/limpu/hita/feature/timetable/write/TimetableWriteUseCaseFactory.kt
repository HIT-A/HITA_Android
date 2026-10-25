package cn.limpu.hita.feature.timetable.write

import android.app.Application
import android.os.Handler
import android.os.Looper
import cn.limpu.hita.data.repository.TimetableWriteStore
import cn.limpu.hita.ui.widgets.WidgetUtils
import cn.limpu.hita.utils.LogUtils
import javax.inject.Inject

/** Bind platform effects while retaining the queue used by the caller's related repository work. */
class TimetableWriteUseCaseFactory @Inject constructor(private val application: Application) {
    private val handler = Handler(Looper.getMainLooper())

    fun create(
        store: TimetableWriteStore,
        enqueueWrite: (() -> Unit) -> Unit = store::enqueueWrite,
    ): TimetableWriteUseCase = TimetableWriteUseCase(
        store = store,
        refreshWidgets = { WidgetUtils.sendRefreshToAll(application) },
        deliverResult = { handler.post(it) },
        onWriteFailure = { LogUtils.e("Failed to write timetable events", it) },
        onRefreshFailure = { LogUtils.e("Timetable write succeeded but widget refresh failed", it) },
        enqueueWrite = enqueueWrite,
    )
}
