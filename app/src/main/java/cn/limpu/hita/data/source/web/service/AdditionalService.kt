package cn.limpu.hita.data.source.web.service

import androidx.lifecycle.LiveData
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.eas.*
import cn.limpu.hita.data.model.timetable.TermSubject
import cn.limpu.hita.data.model.timetable.TimePeriodInDay
import cn.limpu.hita.data.model.classroom.BuildingItem
import cn.limpu.hita.data.model.classroom.ClassroomItem
import java.util.*

interface AdditionalService {


    fun getLectures(
        pageSize:Int,
        pageOffset:Int
    ):LiveData<DataState<List<Map<String,String>>>>


    fun getNewsMeta(
        link:String
    ):LiveData<DataState<Map<String,String>>>

}