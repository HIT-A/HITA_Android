package cn.limpu.hita.ui.eas

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.repository.EasQuerySession
import cn.limpu.hita.data.repository.TermQueryRequest
import com.limpu.component.data.DataState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/** Test-only bridge retains the old controlled source while exercising production Flow collection. */
internal fun termPageTestRequest(
    session: EasQuerySession,
    source: LiveData<DataState<List<TermItem>>>,
): TermQueryRequest = TermQueryRequest(session, callbackFlow {
    val observer = Observer<DataState<List<TermItem>>> { trySend(it) }
    withContext(Dispatchers.Main.immediate) { source.observeForever(observer) }
    awaitClose {
        if (Looper.myLooper() == Looper.getMainLooper()) source.removeObserver(observer)
        else Handler(Looper.getMainLooper()).post { source.removeObserver(observer) }
    }
}.buffer(Channel.UNLIMITED))
