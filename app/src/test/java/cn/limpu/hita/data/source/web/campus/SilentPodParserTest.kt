package cn.limpu.hita.data.source.web.campus

import cn.limpu.hita.data.model.campus.SilentPodCategory
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空间预约响应解析。fixture 取自接口文档的字段表并脱敏，
 * 只覆盖三个真实分类（40/42/41）与会出现的类型抖动（数字写成字符串）。
 */
class SilentPodParserTest {

    private val spacesFixture = """
        {
          "errorList": {},
          "warnList": [],
          "total": 3,
          "data": [
            {
              "id": 69, "name": "F01", "enName": "F01",
              "areaId": 3, "areaFullName": "二楼后区",
              "spaceTypeId": 40, "spaceTypeName": "4人静音舱（有门禁）",
              "capacity": 4, "teamCapacity": 2, "isAccessControl": true,
              "reservationType": "TEAM", "isNeedReserve": true, "isDisableSelect": false,
              "gtDurationMinutes": 30, "ltDurationMinutes": 120,
              "allowUseDurationList": ["08:00-09:30", "11:30-13:00"],
              "disabledDurationList": ["09:30-11:30"]
            },
            {
              "id": 88, "name": "F25",
              "areaFullName": "三楼后区",
              "spaceTypeId": 42, "spaceTypeName": "4人静音舱（无门禁）",
              "capacity": 4, "isAccessControl": false,
              "reservationType": "TEAM",
              "gtDurationMinutes": 30, "ltDurationMinutes": 120,
              "allowUseDurationList": [], "disabledDurationList": []
            },
            {
              "id": 101, "name": "S26",
              "areaFullName": "二楼前区",
              "spaceTypeId": 41, "spaceTypeName": "单人静音舱（无门禁）",
              "capacity": "1", "isAccessControl": "false",
              "reservationType": "PERSONAL",
              "gtDurationMinutes": "20", "ltDurationMinutes": "60",
              "allowUseDurationList": ["20:20-21:50"]
            },
            {
              "id": 200, "name": "R01",
              "spaceTypeId": 43, "spaceTypeName": "研讨室"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `spaces list parses mapped fields for the three pod categories`() {
        val spaces = SilentPodParser.parseSpaces(spacesFixture)
        assertEquals(3, spaces.size)

        val locked = spaces[0]
        assertEquals(69, locked.id)
        assertEquals("F01", locked.codeName)
        assertEquals("二楼后区", locked.areaFullName)
        assertEquals(SilentPodCategory.QUAD_LOCKED, locked.category)
        assertEquals(4, locked.capacity)
        assertTrue(locked.isAccessControl)
        assertEquals(listOf("08:00-09:30", "11:30-13:00"), locked.allowDurations)
        assertEquals(listOf("09:30-11:30"), locked.disabledDurations)
        assertEquals(30, locked.minDurationMinutes)
        assertEquals(120, locked.maxDurationMinutes)

        assertEquals(SilentPodCategory.QUAD, spaces[1].category)
        assertFalse(spaces[1].isAccessControl)
        assertTrue(spaces[1].allowDurations.isEmpty())
    }

    @Test
    fun `string typed numbers and booleans are accepted`() {
        val single = SilentPodParser.parseSpaces(spacesFixture)[2]
        assertEquals(SilentPodCategory.SINGLE, single.category)
        assertEquals(1, single.capacity)
        assertFalse(single.isAccessControl)
        assertEquals(20, single.minDurationMinutes)
        assertEquals(60, single.maxDurationMinutes)
        assertTrue(single.disabledDurations.isEmpty())
    }

    @Test
    fun `unmapped space type id is dropped instead of shown with a guessed category`() {
        assertTrue(SilentPodParser.parseSpaces(spacesFixture).none { it.codeName == "R01" })
    }

    @Test
    fun `space without id is dropped`() {
        val raw = """{"data":[{"name":"F99","spaceTypeId":42}]}"""
        assertTrue(SilentPodParser.parseSpaces(raw).isEmpty())
    }

    @Test
    fun `malformed json yields empty list rather than throwing`() {
        assertTrue(SilentPodParser.parseSpaces("<html>login</html>").isEmpty())
        assertTrue(SilentPodParser.parseReservations("{}").isEmpty())
    }

    @Test
    fun `invalid time range strings are filtered out`() {
        val item = JSONObject(
            """{"id":1,"name":"S21","spaceTypeId":41,"allowUseDurationList":["08:00-09:00","bad","08:00~09:00",""]}"""
        )
        assertEquals(listOf("08:00-09:00"), SilentPodParser.spaceOf(item)?.allowDurations)
    }

    @Test
    fun `spaces are grouped in fixed category order and sorted by code number`() {
        val spaces = SilentPodParser.parseSpaces(spacesFixture)
        val groups = SilentPodParser.group(spaces)
        assertEquals(
            listOf(SilentPodCategory.QUAD_LOCKED, SilentPodCategory.QUAD, SilentPodCategory.SINGLE),
            groups.map { it.category },
        )
        assertEquals(listOf("F01"), groups[0].spaces.map { it.codeName })
    }

    @Test
    fun `code names sort numerically not lexicographically`() {
        val raw = """
            {"data":[
              {"id":1,"name":"F10","spaceTypeId":40},
              {"id":2,"name":"F02","spaceTypeId":40},
              {"id":3,"name":"F21","spaceTypeId":40}
            ]}
        """.trimIndent()
        val groups = SilentPodParser.group(SilentPodParser.parseSpaces(raw))
        assertEquals(listOf("F02", "F10", "F21"), groups.single().spaces.map { it.codeName })
    }

    @Test
    fun `reservations parse documented fields`() {
        val raw = """
            {
              "errorList": {},
              "warnList": [],
              "total": 2,
              "data": [
                {
                  "id": 315, "spaceId": 69, "spaceName": "F01", "areaName": "二楼后区",
                  "spaceTypeName": "4人静音舱（有门禁）",
                  "reservationDate": "2026-11-15",
                  "startTime": "08:00:00", "endTime": "09:30:00",
                  "reservationType": "TEAM", "reservationTypeStr": "多人预约",
                  "status": "PENDING", "statusStr": "",
                  "cancelReason": null, "cancelTime": null, "selfSignDateTime": null,
                  "notes": "13800138000",
                  "isMain": true, "isNeedSign": true, "isAllowCancel": true,
                  "isAllowEarlyEnd": false, "isAllowOpenGate": false
                },
                {
                  "id": 316, "spaceId": 101, "spaceName": "S26", "areaName": "二楼前区",
                  "reservationDate": "2026-11-14",
                  "startTime": "20:20", "endTime": "21:50",
                  "reservationType": "PERSONAL",
                  "status": "CANCELED", "statusStr": "已取消",
                  "cancelReason": "个人行程调整",
                  "isMain": false
                }
              ]
            }
        """.trimIndent()
        val records = SilentPodParser.parseReservations(raw)
        assertEquals(2, records.size)

        val first = records[0]
        assertEquals(315, first.id)
        assertEquals("F01", first.spaceName)
        assertEquals("二楼后区", first.areaName)
        assertEquals("2026-11-15", first.date)
        assertEquals("08:00", first.startTime)
        assertEquals("09:30", first.endTime)
        assertEquals("多人预约", first.typeText)
        assertEquals("PENDING", first.status)
        // 服务端 statusStr 为空串时必须回落到本地文案，而不是显示空白。
        assertEquals("未开始", first.statusText)
        assertTrue(first.isMain)
        assertTrue(first.isNeedSign)
        assertTrue(first.isAllowCancel)
        assertNull(first.cancelReason)

        val second = records[1]
        assertEquals("已取消", second.statusText)
        assertEquals("个人行程调整", second.cancelReason)
        assertFalse(second.isMain)
        assertFalse(second.isAllowCancel)
    }

    @Test
    fun `status text falls back per documented enum`() {
        assertEquals("未开始", SilentPodParser.statusTextOf("PENDING"))
        assertEquals("进行中", SilentPodParser.statusTextOf("underway"))
        assertEquals("已结束", SilentPodParser.statusTextOf("ENDED"))
        assertEquals("已取消", SilentPodParser.statusTextOf("CANCELED"))
        assertEquals("已爽约", SilentPodParser.statusTextOf("OFF"))
        assertEquals("已预约", SilentPodParser.statusTextOf("SOMETHING_NEW"))
    }

    @Test
    fun `business error envelope exposes code and message`() {
        val error = SilentPodParser.errorMessageOf(
            """{"success":false,"errorCode":"NO_ALLOW_RESERVATION_TIME","errorMsg":"未到可预约开放时间"}"""
        )
        assertEquals("NO_ALLOW_RESERVATION_TIME：未到可预约开放时间", error)
        assertNull(SilentPodParser.errorMessageOf("""{"success":true,"model":{}}"""))
        assertNull(SilentPodParser.errorMessageOf("""{"data":[]}"""))
    }

    @Test
    fun `initial state login flag is read from html`() {
        val html = """
            <html><body><script>
            window.__INITIAL_STATE__ = {"i18n":{"locale":"zh_CN"},"loginStatus":{"loggedIn":true,"username":"<card-no>"}};
            </script></body></html>
        """.trimIndent()
        assertEquals(true, SilentPodParser.initialLoggedIn(html))

        val loggedOut = """<script>window.__INITIAL_STATE__ = {"loginStatus":{"loggedIn":false}};</script>"""
        assertEquals(false, SilentPodParser.initialLoggedIn(loggedOut))

        assertNull(SilentPodParser.initialLoggedIn("<html>no state</html>"))
        assertNull(SilentPodParser.initialLoggedIn("""window.__INITIAL_STATE__ = {"loginStatus":{}};"""))
    }

    @Test
    fun `initial state parsing tolerates braces inside strings`() {
        val html = """
            window.__INITIAL_STATE__ = {"nickname":"a}b{c","loginStatus":{"loggedIn":true}}
        """.trimIndent()
        assertEquals(true, SilentPodParser.initialLoggedIn(html))
    }
}
