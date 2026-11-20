package cn.limpu.hita.data.source.web.campus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 智慧墙信封解析。
 *
 * 覆盖正常信封、数字被服务端序列化成字符串、业务失败、缺失 model、aTrust 502 页面、
 * 以及空响应。所有样本都是脱敏的固定文本，不包含任何真实 Cookie/账号。
 */
class LibrarySmartWallParserTest {

    @Test
    fun `peoples parses integers and numeric strings`() {
        val peoples = LibrarySmartWallParser.parsePeoples(
            """{"success":true,"errorCode":null,"errorMsg":"","model":{"curInNum":312,"todayInNum":9284}}"""
        )
        assertEquals(312, peoples.currentInLibrary)
        assertEquals(9284, peoples.todayVisitors)
    }

    @Test
    fun `peoples accepts numbers returned as strings`() {
        val peoples = LibrarySmartWallParser.parsePeoples(
            """{"success":true,"model":{"curInNum":"312","todayInNum":"9284"}}"""
        )
        assertEquals(312, peoples.currentInLibrary)
        assertEquals(9284, peoples.todayVisitors)
    }

    @Test
    fun `peoples accepts float shaped strings by truncating`() {
        val peoples = LibrarySmartWallParser.parsePeoples(
            """{"success":true,"model":{"curInNum":"312.0","todayInNum":" 9284 "}}"""
        )
        assertEquals(312, peoples.currentInLibrary)
        assertEquals(9284, peoples.todayVisitors)
    }

    @Test
    fun `peoples keeps zero instead of treating it as missing`() {
        val peoples = LibrarySmartWallParser.parsePeoples(
            """{"success":true,"model":{"curInNum":0,"todayInNum":"0"}}"""
        )
        assertEquals(0, peoples.currentInLibrary)
        assertEquals(0, peoples.todayVisitors)
    }

    @Test
    fun `room nums parses haveUse and useAble`() {
        val rooms = LibrarySmartWallParser.parseRoomNums(
            """{"success":true,"model":{"haveUse":7,"useAble":24,"haveUseProportion":0.23}}"""
        )
        assertEquals(7, rooms.occupied)
        assertEquals(24, rooms.available)
    }

    @Test
    fun `room nums accepts string numbers`() {
        val rooms = LibrarySmartWallParser.parseRoomNums(
            """{"success":true,"model":{"haveUse":"7","useAble":"24"}}"""
        )
        assertEquals(7, rooms.occupied)
        assertEquals(24, rooms.available)
    }

    @Test
    fun `business failure surfaces server message`() {
        val error = runCatching {
            LibrarySmartWallParser.parsePeoples(
                """{"success":false,"errorCode":"SIGN_INVALID","errorMsg":"签名校验失败","model":null}"""
            )
        }.exceptionOrNull()
        assertTrue(error is LibrarySmartWallException)
        assertEquals("签名校验失败", error?.message)
    }

    @Test
    fun `business failure without message falls back to error code`() {
        val error = runCatching {
            LibrarySmartWallParser.parseRoomNums(
                """{"success":false,"errorCode":"E4001","errorMsg":"","model":null}"""
            )
        }.exceptionOrNull()
        assertEquals("图书馆服务返回错误码 E4001", error?.message)
    }

    @Test
    fun `missing model is an error not empty data`() {
        val error = runCatching {
            LibrarySmartWallParser.parsePeoples("""{"success":true,"model":null}""")
        }.exceptionOrNull()
        assertTrue(error is LibrarySmartWallException)
    }

    @Test
    fun `missing numeric field is an error`() {
        val error = runCatching {
            LibrarySmartWallParser.parsePeoples("""{"success":true,"model":{"curInNum":1}}""")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("todayInNum"))
    }

    @Test
    fun `non numeric field is an error`() {
        val error = runCatching {
            LibrarySmartWallParser.parseRoomNums("""{"success":true,"model":{"haveUse":"-","useAble":1}}""")
        }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("haveUse"))
    }

    @Test
    fun `atrust 502 html page is reported as gateway failure`() {
        // 脱敏样本：aTrust 网关在应用服务器不可达时返回的 HTML 错误页。
        val html = """
            <!DOCTYPE html>
            <html><head><title>aTrust</title></head>
            <body><p>应用服务器连接建立失败</p></body></html>
        """.trimIndent()
        assertTrue(LibrarySmartWallParser.isGatewayErrorPage(html))
        val error = runCatching { LibrarySmartWallParser.parsePeoples(html) }.exceptionOrNull()
        assertTrue(error is LibrarySmartWallException)
        assertTrue(error?.message.orEmpty().contains("502"))
    }

    @Test
    fun `empty body is an error`() {
        val error = runCatching { LibrarySmartWallParser.parsePeoples("   ") }.exceptionOrNull()
        assertTrue(error is LibrarySmartWallException)
    }

    @Test
    fun `non json body is an error`() {
        val error = runCatching {
            LibrarySmartWallParser.parseRoomNums("<html><body>login</body></html>")
        }.exceptionOrNull()
        assertTrue(error is LibrarySmartWallException)
        assertFalse(error?.message.orEmpty().contains("502"))
    }
}
