package cn.limpu.hita.data.source.web.eas

import cn.limpu.hita.data.model.eas.EASToken
import cn.limpu.hita.data.model.eas.TermItem
import cn.limpu.hita.data.source.web.eas.graduate.GraduateHttpTransport
import com.limpu.component.data.DataState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** 用记录型传输驱动生产数据源，核对真实请求，而不只是解析器。 */
class WeihaiGraduateEASWebSourceContractTest {
    private data class Call(
        val kind: String,
        val path: String,
        val fields: Map<String, String> = emptyMap(),
        val referer: String = "",
    )

    private class RecordingTransport(val answer: (Call) -> String) : GraduateHttpTransport {
        val calls = mutableListOf<Call>()

        override fun form(path: String, fields: Map<String, String>, referer: String): String =
            Call("form", path, fields, referer).also { calls += it }.let(answer)

        override fun json(path: String, body: String, referer: String): String =
            Call("json", path, referer = referer).also { calls += it }.let(answer)

        override fun get(path: String, referer: String): String =
            Call("get", path, referer = referer).also { calls += it }.let(answer)
    }

    private val term = TermItem("2026-2027", "2026-2027", "1", "秋季")

    private fun token() = EASToken().apply {
        campus = EASToken.Campus.WEIHAI
        stutype = EASToken.TYPE.GRAD
        username = "synthetic-grad"
        cookies[WeihaiGraduateSessionPolicy.TICKET_COOKIE] = "synthetic-ticket"
    }

    @Test fun termsUseGraduateEndpointsAndMapCurrentFlag() = runBlocking {
        val transport = RecordingTransport {
            when (it.path) {
                "/component/querydangqianxnxq" -> """{"XN":"2026-2027","XQ":"1","XNXQ":"2026秋季"}"""
                "/component/queryxnxqdata" ->
                    """[{"XN":"2026-2027","XQ":"1","XNMC":"2026-2027","XQMC":"秋季","SFDQXQ":"1"}]"""
                else -> error("unexpected ${it.path}")
            }
        }
        val state = WeihaiGraduateEASWebSource { transport }.getAllTermsOnce(token())
        assertEquals(DataState.STATE.SUCCESS, state.state)
        assertEquals(listOf("2026-2027-1"), state.data?.map { it.id })
        assertEquals(listOf(true), state.data?.map { it.isCurrent })
        assertEquals(listOf("form", "form"), transport.calls.map { it.kind })
    }

    @Test fun examsSendGraduateFieldsAndKeepUnknownClock() = runBlocking {
        val transport = RecordingTransport {
            """{"list":[{"KCMC":"合成","KCDM":"C1","PYLX":"2","KSRQ2":"2026-12-01","KSJC":3,"JSJC":4}],
                "total":1,"pageNum":1,"pageSize":20}"""
        }
        val state = WeihaiGraduateEASWebSource { transport }.getExamItemsOnce(token(), term)
        assertEquals(DataState.STATE.SUCCESS, state.state)
        assertEquals("第3-4节", state.data?.single()?.examTime)
        assertEquals("威海校区", state.data?.single()?.campusName)
        val call = transport.calls.single()
        assertEquals("/kscxtj/queryXsksByxhList", call.path)
        assertEquals("2", call.fields["ppylx"])
        assertEquals("2026-2027", call.fields["pxn"])
        assertEquals("/kscxtj/queryXskscxByXh", call.referer)
    }

    @Test fun scoresUseJsonBodyAndGradeEnvelope() = runBlocking {
        val transport = RecordingTransport {
            """{"code":200,"content":{"list":[{"kcmc":"合成","kcdm":"C1","xnxqmc":"2026-2027秋季",
                "xscj":"优秀","xszscj":"95","xf":"3"}],"total":1,"pageNum":1,"pageSize":20}}"""
        }
        val state = WeihaiGraduateEASWebSource { transport }.getPersonalScoresOnce(
            term, token(), cn.limpu.hita.data.source.web.service.EASService.TestType.ALL,
        )
        assertEquals(DataState.STATE.SUCCESS, state.state)
        val score = state.data?.single()
        assertEquals("优秀", score?.finalScoresText)
        assertEquals(95.0, score?.numericScore)
        val call = transport.calls.single()
        assertEquals("json", call.kind)
        assertEquals("/cjgl/grcjcx/grcjcx", call.path)
        assertEquals("/cjgl/grcjcx/go/2", call.referer)
    }
}
