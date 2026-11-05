package app.keepfin.ui.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.keepfin.AppIconChoice
import app.keepfin.AppIconController
import app.keepfin.AppIcons
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SupportingText

const val APP_ICON_HELP = "Changes the icon on your home screen."
const val APP_ICON_NOTE = "Your home screen may take a few seconds to update, and Keepfin shortcuts pinned to it may be removed."

object AppIconTags {
    const val PICKER = "app-icon-picker"
    fun option(id: String) = "app-icon-$id"
}

/** Settings, Appearance, App icon: every design from the generated list, one selected; picking one switches the launcher icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppIconPicker(controller: AppIconController? = null) {
    val context = LocalContext.current
    val ctl = controller ?: remember { AppIcons.load(context) } ?: return
    var selected by remember { mutableStateOf(ctl.current()) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag(UiTags.setting(AppIconTags.PICKER))) {
        Text("App icon", style = MaterialTheme.typography.bodyLarge)
        Text(APP_ICON_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(8.dp))
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (variant in ctl.catalog.variants) {
                val isSelected = variant.id == selected.id
                val preview = variant.accents.firstOrNull { it.hex.equals(selected.accent, true) } ?: variant.accents.first()
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.width(88.dp).defaultMinSize(minHeight = 48.dp).testTag(AppIconTags.option(variant.id))
                        .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                            if (!isSelected) {
                                val choice = AppIconChoice(variant.id, selected.accent)
                                if (ctl.choose(choice)) { selected = ctl.current(); failed = false } else failed = true
                            }
                        })
                        .semantics { contentDescription = variant.name },
                ) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconPreview(preview.preview)
                        Spacer(Modifier.size(4.dp))
                        Text(variant.name, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        if (failed) Text("The icon could not be changed on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
    }
    SupportingText(APP_ICON_NOTE)
}

/** The launcher icon as the system draws it (adaptive layers in the launcher's mask), at 56dp. */
@Composable
private fun IconPreview(resourceName: String) {
    val context = LocalContext.current
    val drawable: Drawable? = remember(resourceName) {
        val id = context.resources.getIdentifier(resourceName, "mipmap", context.packageName)
        if (id == 0) null else ResourcesCompat.getDrawable(context.resources, id, context.theme)?.mutate()
    }
    Canvas(Modifier.size(56.dp)) {
        val d = drawable ?: return@Canvas
        d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    }
}
