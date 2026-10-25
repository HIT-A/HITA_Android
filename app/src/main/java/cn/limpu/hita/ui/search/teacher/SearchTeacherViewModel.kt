package cn.limpu.hita.ui.search.teacher

import cn.limpu.hita.data.model.teacher.TeacherSearched
import androidx.lifecycle.LiveData
import androidx.lifecycle.map
import com.limpu.component.data.DataState
import cn.limpu.hita.data.repository.EASRepository
import cn.limpu.hita.data.repository.HoaRepository
import cn.limpu.hita.ui.search.BaseSearchResultViewModel
import cn.limpu.hita.ui.search.SearchTrigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SearchTeacherViewModel @Inject constructor(
    private val hoaRepository: HoaRepository,
    private val easRepository: EASRepository
) : BaseSearchResultViewModel<TeacherSearched>() {
    private val hoaCampus = easRepository.getHoaCampus()
    override fun doSearch(trigger: SearchTrigger): LiveData<DataState<List<TeacherSearched>>> {
        val query = trigger.text.trim()
        return hoaRepository.searchCourses(query, hoaCampus).map { state ->
            if (state.state != DataState.STATE.SUCCESS) {
                return@map DataState<List<TeacherSearched>>(state.state, state.message)
            }
            val queryLower = query.lowercase()
            val mapped = mutableListOf<TeacherSearched>()
            val seen = hashSetOf<String>()
            for (course in state.data ?: emptyList()) {
                val matchedTeachers = course.teachers.filter {
                    it.isNotBlank() && it.lowercase().contains(queryLower)
                }
                for (teacher in matchedTeachers) {
                    val key = "${teacher.trim()}|${course.repoName.trim()}"
                    if (!seen.add(key)) {
                        continue
                    }
                    mapped.add(
                        TeacherSearched().apply {
                            name = teacher.trim()
                            repoName = course.repoName
                            repoType = course.repoType
                            courseName = course.courseName
                            courseCode = course.courseCode
                            department = listOf(course.courseName, course.courseCode)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        }
                    )
                }
            }
            DataState(mapped, DataState.STATE.SUCCESS)
        }
    }
}
