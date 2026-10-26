package cn.limpu.hita.ui.eas

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.EasQuerySession
import com.limpu.component.data.DataState

/** Keep remaining metadata/score streams owned by the session that selected their term. */
internal fun <T> EASRepository.sessionBoundData(
    session: EasQuerySession = captureQuerySession(),
    create: (EasQuerySession) -> LiveData<DataState<T>>,
): LiveData<DataState<T>> {
    if (!session.isCurrent()) return MutableLiveData(DataState(DataState.STATE.NOTHING))
    val source = create(session)
    return MediatorLiveData<DataState<T>>().apply {
        addSource(querySessions.asLiveData()) {
            if (!session.isCurrent()) {
                removeSource(source)
                value = DataState(DataState.STATE.NOTHING)
            }
        }
        addSource(source) { if (session.isCurrent()) value = it }
    }
}
