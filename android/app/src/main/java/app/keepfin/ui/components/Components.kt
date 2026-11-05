package app.keepfin.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.ui.theme.Studio
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import coil3.video.videoFramePercent

/** One image loader for the app: memory + disk cache, video frames for saved media. */
object StudioImages {
    @Volatile private var instance: ImageLoader? = null
    fun loader(context: Context): ImageLoader = instance ?: synchronized(this) {
        instance ?: ImageLoader.Builder(context.applicationContext)
            .components { addTo(this, context) }
            .crossfade(180)
            .build().also { instance = it }
    }

    /** What the app reads beyond plain images: covers inside saved files and video frames. Test loaders add the same. */
    fun addTo(components: ComponentRegistry.Builder, context: Context) {
        components.add(MediaCoverFetcher.Factory(context.applicationContext))
        components.add(MediaCoverKeyer())
        components.add(VideoFrameDecoder.Factory())
    }
}

/** Tests replace the loader to count requests; production uses [StudioImages]. */
val LocalStudioImageLoader = staticCompositionLocalOf<ImageLoader?> { null }

/** Test hook: called after every successful recomposition of a queue/library row. */
val LocalRowObserver = staticCompositionLocalOf<((String) -> Unit)?> { null }

@Composable
fun studioImageLoader(): ImageLoader = LocalStudioImageLoader.current ?: StudioImages.loader(LocalContext.current)

/**
 * Media artwork that never reloads while its row recomposes: the request is remembered by model, so
 * progress ticks reuse the same request and Coil's memory cache. Falls back once if the primary fails.
 */
@Composable
fun MediaThumb(
    primary: Any?,
    modifier: Modifier = Modifier,
    fallback: Any? = null,
    audio: Boolean = false,
    shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.small,
) {
    val context = LocalContext.current
    val loader = studioImageLoader()
    var failedPrimary by remember(primary, fallback) { mutableStateOf(primary == null) }
    val model = if (failedPrimary) fallback else primary
    val request = remember(model) {
        model?.let { ImageRequest.Builder(context).data(it).videoFramePercent(0.1).build() }
    }
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(
            if (audio) Icons.Outlined.MusicNote else Icons.Outlined.Movie, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f), modifier = Modifier.size(22.dp),
        )
        if (request != null) {
            AsyncImage(
                model = request, contentDescription = null, imageLoader = loader,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                onError = { if (!failedPrimary && fallback != null) failedPrimary = true },
            )
        }
    }
}

/** Raised pane: the only container on a screen; never nested. */
@Composable
fun Pane(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Studio.colors.raised,
        shape = MaterialTheme.shapes.medium,
        // Surfaces separate by tone (white on porcelain, slate on ink), not by a border around every panel.
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, detail: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (detail != null) {
            Spacer(Modifier.width(8.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** Compact spec badge: resolution, fps, HDR, codec, provider. */
@Composable
fun SpecBadge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false, color: Color? = null) {
    val tint = color ?: if (emphasized) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, tint.copy(alpha = if (emphasized) .7f else .35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** Overlay badge on artwork (duration, LIVE). */
@Composable
fun ArtworkBadge(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    Box(
        modifier.clip(RoundedCornerShape(4.dp))
            .background(if (live) Color(0xFFD93025) else Color(0xCC000000))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) { Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1) }
}

/** The row tag that settings search just sent the person to: that row is tinted for a moment. Null when nothing is highlighted. */
val LocalSettingsHighlightTag = staticCompositionLocalOf<String?> { null }

/** A soft tint that fades after about two seconds; used for the row (or group) settings search opened. */
@Composable
fun highlightColor(active: Boolean): Color {
    val tint = MaterialTheme.colorScheme.primaryContainer
    return androidx.compose.animation.animateColorAsState(if (active) tint else tint.copy(alpha = 0f), androidx.compose.animation.core.tween(600), label = "highlight").value
}

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    val lit = highlightColor(tag != null && LocalSettingsHighlightTag.current == tag)
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).background(lit)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun NavRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
    onClick: () -> Unit,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

@Composable
fun RowSeparator(modifier: Modifier = Modifier, inset: Int = 0) {
    HorizontalDivider(modifier.padding(start = inset.dp), thickness = 1.dp, color = Studio.colors.separator)
}

@Composable
fun SettingsGroup(title: String, modifier: Modifier = Modifier, tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        // A page that holds a single group is already titled by its own header, so the group heading is left out.
        if (title.isNotEmpty()) Text(
            title, style = MaterialTheme.typography.titleSmall, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
        ) else Spacer(Modifier.size(12.dp))
        Pane(contentPadding = PaddingValues(vertical = 4.dp), content = content)
    }
}

/**
 * A group that starts collapsed and holds the less-used options (Speed and connections, Devices > Advanced). One level only: the
 * header shows the title and a one-line summary of what is inside, so you rarely need to open it.
 */
@Composable
fun SettingsExpander(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Spacer(Modifier.size(12.dp))
        Pane(contentPadding = PaddingValues(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onToggle)
                    .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = Studio.colors.accentText, modifier = Modifier.semantics { heading() })
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                RowSeparator()
                content()
            }
        }
    }
}

@Composable
fun SupportingText(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}
