package app.keepfin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.RowSeparator

/** Settings search for a phone: the view model decides what exists on this build and what choosing a result does. */
@Composable
internal fun SettingsSearchScreen(vm: StudioViewModel, contentPadding: PaddingValues) {
    SettingsSearchContent(
        visibility = vm.settingsVisibility(), contentPadding = contentPadding,
        onBack = { vm.searchSettings(false) },
        onOpen = { entry -> vm.searchSettings(false); vm.openSettings(entry.target) },
    )
}

/** Typing filters the static index; choosing a result opens its category, scrolled to the setting and highlighted. */
@Composable
internal fun SettingsSearchContent(visibility: SettingsVisibility, contentPadding: PaddingValues, onBack: () -> Unit, onOpen: (SettingsEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(query, visibility) { SettingsIndex.search(query, visibility) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag(UiTags.SETTINGS_PAGE_BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Settings")
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Search settings") },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { results.firstOrNull()?.let(onOpen) }),
                modifier = Modifier.weight(1f).focusRequester(focus).testTag(UiTags.SETTINGS_SEARCH_FIELD),
            )
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
            if (query.isNotBlank() && results.isEmpty()) item("none") {
                Text(
                    "No setting matches “${query.trim()}”. Try a shorter word, like sync, theme or speed.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp).testTag(UiTags.SETTINGS_SEARCH_EMPTY),
                )
            }
            itemsIndexed(results, key = { _, e -> e.category.name + e.title }) { index, entry ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onOpen(entry) }.testTag(UiTags.settingsSearchResult(index))
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                        Text(entry.category.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                }
                if (index < results.lastIndex) RowSeparator(Modifier.padding(start = 20.dp))
            }
        }
    }
}
