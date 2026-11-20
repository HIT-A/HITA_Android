package cn.limpu.hita.data.repository.homework

import cn.limpu.hita.data.model.homework.HomeworkItem
import cn.limpu.hita.data.model.homework.HomeworkSubmissionFormat
import cn.limpu.hita.data.model.homework.HomeworkSubmissionMethod
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-02：新增的四个作业字段必须对旧快照向后兼容，且非法枚举值不能让整份快照读不出来。
 */
class HomeworkItemCompatTest {

    @Test
    fun `legacy snapshot without new fields still loads`() {
        // 旧版本写下的最小快照：只有 id / title / createdAt。
        val legacy = """[{"id":"a","title":"实验报告","createdAt":1}]"""
        val store = HomeworkStore({ legacy }, { true })

        val items = store.load()

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("a", item.id)
        assertEquals("", item.subject)
        assertEquals(HomeworkSubmissionMethod.UNKNOWN, item.submissionMethod)
        assertEquals("", item.submissionDetail)
        assertEquals(HomeworkSubmissionFormat.UNKNOWN, item.submissionFormat)
        assertFalse(item.hasSubmissionInfo)
    }

    @Test
    fun `new fields survive a save and load round trip`() {
        var disk: String? = null
        val gson = Gson()
        val item = HomeworkItem(
            id = "b",
            title = "大作业",
            createdAt = 2,
            subject = "编译原理",
            submissionMethod = HomeworkSubmissionMethod.EMAIL,
            submissionDetail = "teacher@example.edu",
            submissionFormat = HomeworkSubmissionFormat.PDF,
        )
        disk = gson.toJson(arrayOf(item))
        val store = HomeworkStore({ disk }, { true })

        val restored = store.load().single()

        assertEquals("编译原理", restored.subject)
        assertEquals(HomeworkSubmissionMethod.EMAIL, restored.submissionMethod)
        assertEquals("teacher@example.edu", restored.submissionDetail)
        assertEquals(HomeworkSubmissionFormat.PDF, restored.submissionFormat)
        assertTrue(restored.hasSubmissionInfo)
    }

    @Test
    fun `unknown enum names fall back to UNKNOWN instead of failing`() {
        // 模拟将来版本删掉某个枚举值后回退安装旧版本的情形。
        val dirty = """[{"id":"c","title":"作业","createdAt":3,"submissionMethod":"TELEPATHY","submissionFormat":"HOLOGRAM"}]"""
        val store = HomeworkStore({ dirty }, { true })

        val restored = store.load().single()

        assertEquals(HomeworkSubmissionMethod.UNKNOWN, restored.submissionMethod)
        assertEquals(HomeworkSubmissionFormat.UNKNOWN, restored.submissionFormat)
    }
}
