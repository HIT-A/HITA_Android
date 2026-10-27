package cn.limpu.hita.data.repository

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import cn.limpu.hita.data.AppDatabase
import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.timetable.Timetable
import cn.limpu.hita.data.source.preference.BenbuStartDatePreferenceSource
import cn.limpu.hita.data.source.preference.EasCredentialStore
import cn.limpu.hita.data.source.preference.EasPreferenceSource
import cn.limpu.hita.data.source.preference.TimetablePreferenceSource
import cn.limpu.hita.data.source.web.service.EASService
import cn.limpu.hita.ui.eas.imp.ImportTimetableViewModel
import com.limpu.component.data.DataState
import java.io.Closeable
import java.lang.reflect.Proxy
import java.util.Calendar
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CompletableDeferred

/** The same harness runs unchanged against old LiveData and new suspend production entry points. */
internal class ImportMetadataHarness : Closeable {
    val application = BackgroundTermConsumerHarness.IsolatedApplication(ApplicationProvider.getApplicationContext())
    val preferences = EasPreferenceSource(application).also { it.saveEasToken(BackgroundTermConsumerHarness.account()) }
    val timetablePreferences = TimetablePreferenceSource(application, preferences)
    val calibration = BenbuStartDatePreferenceSource(application)
    val database = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java).build()
    val dates = LinkedBlockingQueue<Request<Calendar>>()
    val schedules = LinkedBlockingQueue<Request<MutableList<TimePeriodInDay>>>()
    val store = ViewModelStore()

    @Suppress("UNCHECKED_CAST")
    private val service = Proxy.newProxyInstance(EASService::class.java.classLoader, arrayOf(EASService::class.java)) { proxy, method, args ->
        when (method.name) {
            "getStartDate", "getStartDateOnce" -> {
                val request = Request<Calendar>(args!![1] as TermItem)
                dates.offer(request)
                if (method.name.endsWith("Once")) (suspend { request.result.await() })
                    .startCoroutineUninterceptedOrReturn(args.last() as Continuation<DataState<Calendar>>)
                else request.live
            }
            "getScheduleStructure", "getScheduleStructureOnce" -> {
                val request = Request<MutableList<TimePeriodInDay>>(args!![0] as TermItem)
                schedules.offer(request)
                if (method.name.endsWith("Once")) (suspend { request.result.await() })
                    .startCoroutineUninterceptedOrReturn(args.last() as Continuation<DataState<MutableList<TimePeriodInDay>>>)
                else request.live
            }
            "toString" -> "ImportMetadataService"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.single()
            else -> error("Unexpected metadata method ${method.name}")
        }
    } as EASService

    val repository = EASRepository(application, preferences, EasCredentialStore(application), timetablePreferences,
        TimetableChangeStore(application), TimetableMutationLock(), database,
        EasRepositoryServiceFactories(benbu = { service }, weihai = { service }))
    lateinit var model: ImportTimetableViewModel

    init { main { model = ImportTimetableViewModel(repository, calibration, timetablePreferences); store.put("import", model) } }

    override fun close() {
        main { store.clear() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        database.close()
        application.close()
    }

    class Request<T>(val term: TermItem) {
        val live = MutableLiveData<DataState<T>>()
        val result = CompletableDeferred<DataState<T>>()
        fun complete(data: T) = main { val state = DataState(data); live.value = state; result.complete(state) }
    }

    companion object {
        fun main(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
        fun term(code: String = "1") = BackgroundTermConsumerHarness.term(code)
        fun date(day: Int) = Calendar.getInstance().apply { set(2026, Calendar.MARCH, day, 0, 0, 0); set(Calendar.MILLISECOND, 0) }
        fun <T> LinkedBlockingQueue<T>.next(): T = requireNotNull(poll(10, TimeUnit.SECONDS)) { "Expected actual metadata request" }
    }
}
