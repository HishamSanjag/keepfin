package app.keepfin.ui.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.Guide
import app.keepfin.GuideFeature
import app.keepfin.GuideLogic
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.SearchField
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** Reads the bundled guide (content/guide.json, copied into the APK at build time) and keeps the Android features. */
fun loadGuide(context: Context): Result<Guide> = runCatching {
    GuideLogic.forAndroid(GuideLogic.parse(context.assets.open(GuideLogic.ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }))
}

/**
 * Features & how-to: search, sections as headings, each feature an expandable card (summary, numbered steps, the Android note) with a
 * "Try it" button for the places that exist in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuideScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded = remember(context) { loadGuide(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(loaded.getOrNull()?.title ?: "Features & how-to", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() }) },
                navigationIcon = { IconButton(onClick = onBack, modifier = Modifier.testTag(UiTags.SETTINGS_PAGE_BACK)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val guide = loaded.getOrNull()
        if (guide == null) {
            Text(
                "The guide could not be loaded. Reinstalling Keepfin restores it.", style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(padding).padding(24.dp).testTag(UiTags.GUIDE),
            )
            return@Scaffold
        }
        val shown = remember(guide, query) { GuideLogic.search(guide, query) }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag(UiTags.GUIDE),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item("intro") {
                if (guide.intro.isNotBlank()) Text(guide.intro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                SearchField(query, { query = it }, "Search features and steps", Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.GUIDE_SEARCH))
            }
            if (shown.sections.isEmpty()) item("none") {
                Text("Nothing matches “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp).testTag("guide-empty"))
            }
            shown.sections.forEach { section ->
                item("head-${section.id}") {
                    Text(
                        section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp).semantics { heading() },
                    )
                }
                items(section.features, key = { it.id }) { feature ->
                    val open = feature.id in expanded
                    FeatureCard(feature, open, { expanded = if (open) expanded - feature.id else expanded + feature.id }, vm::guideTry)
                }
            }
        }
    }
}

@Composable
private fun FeatureCard(feature: GuideFeature, open: Boolean, onToggle: () -> Unit, onTry: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp), color = Studio.colors.raised,
        border = if (Studio.colors.dark) null else BorderStroke(1.dp, Studio.colors.separator),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.guideCard(feature.id)),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .clickable(role = Role.Button, onClickLabel = if (open) "Collapse" else "Expand", onClick = onToggle)
                    .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(feature.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (!open) Text(feature.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp).size(24.dp))
            }
            if (open) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(feature.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                feature.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium.tabular(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                        Text(step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }
                feature.androidNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val tryId = feature.tryId?.takeIf { it in GuideLogic.TRY_IDS }
                if (tryId != null) {
                    Spacer(Modifier.size(4.dp))
                    Button(
                        onClick = { onTry(tryId) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.guideTry(feature.id)),
                        colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                    ) { Text("Try it", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}
