package cn.limpu.hita.ui.subject

import android.content.Context
import android.graphics.Typeface
import android.text.Layout
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import cn.limpu.hita.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlin.math.roundToInt

/** Activity-scoped renderer. Each TextView supplies its card's source when a link is clicked. */
internal class SubjectMarkdownRenderer(context: Context, private val openLink: (String) -> Unit) {
    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
    private val markwon = Markwon.builder(context)
        .usePlugin(LinkifyPlugin.create())
        .usePlugin(HtmlPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(JLatexMathPlugin.create(15f))
        .usePlugin(GlideImagesPlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(ContextCompat.getColor(context, R.color.primary))
                    .isLinkUnderlined(true)
                    .blockMargin(dp(context, 8))
                    .blockQuoteWidth(dp(context, 3))
                    .blockQuoteColor(ContextCompat.getColor(context, R.color.primary))
                    .bulletWidth(dp(context, 5))
                    .headingBreakHeight(0)
                    .headingTypeface(Typeface.create("sans-serif", Typeface.BOLD))
                    .headingTextSizeMultipliers(floatArrayOf(1.6f, 1.38f, 1.22f, 1.12f, 1.05f, 1f))
            }
        })
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view: View, link: String ->
                    openLink(SubjectReadmeFormatter.resolveLink(link, (view.tag as? String).orEmpty()))
                }
            }
        })
        .build()

    fun createView(context: Context, color: Int) = TextView(context).apply {
        textSize = 15f
        includeFontPadding = false
        setLineSpacing(0f, 1.28f)
        breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        setTextColor(color)
        movementMethod = LinkMovementMethod.getInstance()
        linksClickable = true
    }

    fun render(view: TextView, markdown: String, source: String, color: Int) {
        view.setTextColor(color)
        view.tag = source
        markwon.setMarkdown(view, markdown)
        view.movementMethod = LinkMovementMethod.getInstance()
        view.setOnClickListener {
            val link = (it.tag as? String).orEmpty()
            if (link.startsWith("http://") || link.startsWith("https://")) openLink(link)
        }
    }
}
