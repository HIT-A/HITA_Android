package cn.limpu.hita.ui.subject

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPreprocessorTest {
    @Test
    fun `single dollar formulas become inline latex`() {
        assertEquals(
            "\\(\\LaTeX\\)",
            MarkdownPreprocessor.normalizeInlineLatex("\$\\LaTeX\$")
        )
    }

    @Test
    fun `code and display formulas are left unchanged`() {
        val markdown = "`\$x\$` and \$\$x^2\$\$\n\n```\n\$y\$\n```"
        assertEquals(markdown, MarkdownPreprocessor.normalizeInlineLatex(markdown))
    }
}
