package app.keepfin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The page title of a top-level screen (Library, Downloads, Settings). [display] uses the large display size of the
 * Cobalt Atelier scale (Your library, Downloads); [subtitle] adds one quiet line under it. [trailing] sits at the end of the row.
 */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null, display: Boolean = false, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, style = if (display) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
/**
 * Filter chip: 32dp tall, 8dp corners, tonal when selected and outlined otherwise. The chip sits inside a 48dp touch
 * target, so a row of chips stays compact without shrinking what can be tapped.
 */
@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.heightIn(min = 48.dp).sizeIn(minWidth = 48.dp)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = if (selected) colors.primaryContainer else Color.Transparent,
            border = if (selected) null else BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.height(32.dp),
        ) {
            Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text(
                    label, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
            }
        }
    }
}

/** The 22dp rounded checkbox of selection mode. Purely visual: the row around it is the control. */
@Composable
fun SelectBox(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp), color = if (checked) colors.primary else Color.Transparent,
        border = if (checked) null else BorderStroke(2.dp, colors.outline), modifier = modifier.size(22.dp),
    ) {
        if (checked) Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(14.dp)) }
    }
}

/** A text-only action tucked into a row: 48dp tall, label at 13sp semibold in the primary colour. */
@Composable
fun RowTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = app.keepfin.ui.theme.Studio.colors.accentText) {
    Box(
        modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1) }
}
