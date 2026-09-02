package cn.limpu.hita.ui.subject

/** Normalizes Markdown syntax that is common in HOA course notes but not enabled by default. */
internal object MarkdownPreprocessor {
    /** Converts single-dollar inline formulas to the delimiters supported by JLatexMath. */
    fun normalizeInlineLatex(markdown: String): String {
        val output = StringBuilder(markdown.length)
        var index = 0
        var inFence = false
        var inInlineCode = false

        while (index < markdown.length) {
            if (markdown.startsWith("```", index)) {
                inFence = !inFence
                output.append("```")
                index += 3
                continue
            }
            if (markdown.startsWith("$$", index) && !inFence && !inInlineCode) {
                val end = markdown.indexOf("$$", index + 2)
                if (end >= 0) {
                    output.append(markdown, index, end + 2)
                    index = end + 2
                    continue
                }
            }
            val char = markdown[index]
            if (char == '`' && !inFence) {
                inInlineCode = !inInlineCode
                output.append(char)
                index++
                continue
            }
            if (char != '$' || inFence || inInlineCode || markdown.getOrNull(index - 1) == '\\' ||
                markdown.getOrNull(index + 1) == '$'
            ) {
                output.append(char)
                index++
                continue
            }

            val end = findInlineLatexEnd(markdown, index + 1)
            if (end == null) {
                output.append(char)
                index++
                continue
            }

            val body = markdown.substring(index + 1, end)
            if (body.isBlank() || body.any { it == '\n' || it == '\r' }) {
                output.append(char)
                index++
                continue
            }
            output.append("\\(").append(body).append("\\)")
            index = end + 1
        }
        return output.toString()
    }

    private fun findInlineLatexEnd(markdown: String, start: Int): Int? {
        var index = start
        while (index < markdown.length) {
            if (markdown[index] == '$' && markdown.getOrNull(index + 1) != '$' &&
                markdown.getOrNull(index - 1) != '\\'
            ) {
                return index
            }
            index++
        }
        return null
    }
}
