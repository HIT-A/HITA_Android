package cn.limpu.hita.ui.subject

import cn.limpu.hita.data.model.resource.CourseResourceItem
import cn.limpu.hita.data.model.resource.ExternalResourceEntry
import cn.limpu.hita.data.model.resource.ResourceSource
import org.junit.Assert.*
import org.junit.Test

/** Expected strings and routes captured from the Activity before request ownership changes. */
class SubjectResourceCompatibilityTest {
    private val subject = SubjectMatchSnapshot("CS100", "程序设计")

    @Test fun tomlMetadataWinsOverEarlierPlainHeadingAndKeepsNestedSection() {
        val input = "# 资源\n## 程序设计\n旧章节\n## 教学材料\n<!-- TOML-COURSE: code=\"CS100\" name=\"程序设计\" -->\n正文\n### 讲义\n链接\n## 其他\n忽略"
        assertEquals("## 教学材料\n正文\n### 讲义\n链接", SubjectReadmeFormatter.filter(input, null, subject))
    }

    @Test fun unmatchedReadmeIsPreservedByteForByte() {
        val input = "# 目录\n\n## 其他\n正文  \n"
        assertEquals(input, SubjectReadmeFormatter.filter(input, null, subject))
    }

    @Test fun aliasesMatchAndCrossspecialtyHeadingIsExcluded() {
        val input = "## crossspecialty CS100\n不选\n## 别名\n选择\n### 子标题\n保留\n## 其他\n忽略"
        assertEquals("## 别名\n选择\n### 子标题\n保留", SubjectReadmeFormatter.filter(input, CourseResourceItem(aliases = listOf("别名")), subject))
    }

    @Test fun htmlTablesAndDetailsUseExplicitFallbackTitle() {
        val input = "<table><tr><th>A</th><th>B</th></tr><tr><td>C</td></tr></table>\n{{% details closed=\"true\" %}}内容{{% /details %}}"
        assertEquals("A | B\n--- | ---\nC | \n<details><summary>打开资料</summary>内容</details>", SubjectReadmeFormatter.preprocess(input, "打开资料"))
    }

    @Test fun relativeLinksUseOnlyTheirOwnSource() {
        assertEquals("https://host/a/file.pdf", SubjectReadmeFormatter.resolveLink("file.pdf", "https://host/a/README.md"))
        assertEquals("file.pdf", SubjectReadmeFormatter.resolveLink("file.pdf", ""))
        assertEquals("https://other/file", SubjectReadmeFormatter.resolveLink("https://other/file", "https://host/a/README.md"))
    }

    @Test fun fireworksSiteAlwaysOpensInBrowser() {
        val entry = ExternalResourceEntry(name = "a.md", path = "ignored", downloadUrl = "https://fireworks.jwyihao.top/notes")
        assertEquals(SubjectResourceDestination.Browser(entry.downloadUrl), SubjectResourceOpenPolicy.destination(entry))
    }

    @Test fun markdownPathEncodesSegmentsAndPreservesRepoProxyPolicy() {
        val entry = ExternalResourceEntry(name = "README.MD", path = "资料/a b+#.MD", source = ResourceSource.FIREWORKS)
        assertEquals(SubjectResourceDestination.Markdown("https://ghproxy.net/https://raw.githubusercontent.com/HIT-Fireworks/fireworks-notes-society/main/%E8%B5%84%E6%96%99/a%20b%2B%23.MD", "README.MD"), SubjectResourceOpenPolicy.destination(entry))
    }

    @Test fun downloadUsesHitcsRepoAndEmptyRoutesStayEmpty() {
        assertEquals(SubjectResourceDestination.Download("https://ghproxy.net/https://raw.githubusercontent.com/HITLittleZheng/HITCS/main/a.pdf", "a.pdf"), SubjectResourceOpenPolicy.destination(ExternalResourceEntry(name = "a.pdf", path = "a.pdf")))
        assertNull(SubjectResourceOpenPolicy.destination(ExternalResourceEntry()))
        assertNull(SubjectResourceOpenPolicy.destination(ExternalResourceEntry(downloadUrl = "https://fireworks.other/a")))
    }
}
