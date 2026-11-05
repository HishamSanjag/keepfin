package app.keepfin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.URLSpan
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import app.keepfin.ui.UiTags
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.core.MarkwonTheme
import org.commonmark.node.Link

/** Native Markdown for release notes: no browser, HTML plugin, remote images or scripts. */
object ReleaseNotes {
    fun bundledNotes(context: Context): String = context.assets.open("release-notes.md")
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun safeLink(destination: String): Boolean {
        if (destination.any { it.isISOControl() || it.isWhitespace() }) return false
        val uri = Uri.parse(destination)
        return uri.scheme?.lowercase() == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null
    }

    fun render(context: Context, notes: String, linkColor: Int, codeColor: Int, codeBackground: Int): Spanned {
        val density = context.resources.displayMetrics.density
        return Markwon.builder(context).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(linkColor).blockMargin((20 * density).toInt())
                    .headingTextSizeMultipliers(floatArrayOf(1.35f, 1.2f, 1.1f, 1f, 1f, 1f))
                    .headingBreakHeight(0).codeTextColor(codeColor)
                    .codeBackgroundColor(codeBackground).codeBlockBackgroundColor(codeBackground)
            }
            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                builder.setFactory(Link::class.java) { _, props ->
                    val destination = io.noties.markwon.core.CoreProps.LINK_DESTINATION.require(props)
                    if (safeLink(destination)) object : URLSpan(destination) {
                        override fun onClick(widget: View) {
                            // Validate again at the external-navigation boundary.
                            if (!safeLink(url)) return
                            try {
                                widget.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(widget.context, "No browser is available to open this link.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else null
                }
            }
        }).build().toMarkdown(notes)
    }
}

/** Bounded, dismissible release-notes dialog; Back and outside taps close it. */
@Composable
fun ReleaseNotesDialog(title: String, notes: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val screenHeight = LocalConfiguration.current.screenHeightDp
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = colors.surfaceContainerHigh, modifier = Modifier.widthIn(max = 600.dp)) {
            Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Column(Modifier.padding(top = 16.dp).heightIn(max = (screenHeight * 0.62f).dp).verticalScroll(rememberScrollState())) {
                    ReleaseNotesBody(notes)
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp).testTag("release-notes-close")) { Text("Done") }
            }
        }
    }
}

/** Native Markdown body shared by the dialog, the update sheet and the what's-new sheet. */
@Composable
fun ReleaseNotesBody(notes: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val text = remember(notes, colors.primary, colors.onSurface) {
        ReleaseNotes.render(context, notes, colors.primary.toArgb(), colors.onSurface.toArgb(), colors.surfaceContainerHighest.toArgb())
    }
    val bodyColor = colors.onSurface.toArgb()
    val linkColor = colors.primary.toArgb()
    AndroidView(
        factory = { ctx ->
            TextView(ctx).apply {
                tag = UiTags.RELEASE_NOTES_BODY
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setLineSpacing(3 * ctx.resources.displayMetrics.density, 1f)
                movementMethod = LinkMovementMethod.getInstance()
            }
        },
        update = { view -> view.text = text; view.setTextColor(bodyColor); view.setLinkTextColor(linkColor) },
        modifier = modifier.fillMaxWidth().testTag(UiTags.RELEASE_NOTES_BODY),
    )
}
