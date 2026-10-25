package cn.limpu.hita.data.source.web.service

import androidx.lifecycle.LiveData
import com.limpu.component.data.DataState
import cn.limpu.hita.data.model.teacher.TeacherSearched

interface TeacherService {

    /**
     * 获取教师信息
     */
    fun getTeacherProfile(
        teacherId: String,
        teacherUrl: String
    ): LiveData<DataState<Map<String, String>>>


    /**
     * 获取教师介绍页信息
     */
    fun getTeacherPages(
        teacherId: String
    ): LiveData<DataState<Map<String, String>>>


    /**
     * 搜索教师
     */
    fun searchTeachers(text:String):LiveData<DataState<List<TeacherSearched>>>
}