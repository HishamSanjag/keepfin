package app.keepfin.ui.library

import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.SpaceLogic
import app.keepfin.SpaceKind
import app.keepfin.ui.Destination
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library tab when the library is on. There is no chip row: Home is the Spaces hub, a space is its own page with a back
 * arrow, and Edit spaces / Playlists are pages of their own. A selection swaps the page header for the selection bar and
 * the bottom navigation for the selection actions.
 */
@Composable
fun LibraryTab(vm: StudioViewModel, ui: LibraryUi, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val version by ui.browse.changes.collectAsStateWithLifecycle()
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    // The hub order the user chose (built-ins included), with every space's color, icon and pin filled in.
    // "This device" is added here, after the saved spaces; it is never part of the settings
    val onPhone by vm.lib.deviceMusic.collectAsStateWithLifecycle()
    val showDevice = onPhone is app.keepfin.DeviceMusicState.Ready || onPhone is app.keepfin.DeviceMusicState.Scanning
    val spaces = remember(settings.library.spaces, showDevice) { SpaceLogic.withDefaults(settings.library.spaces).let { if (showDevice) it + app.keepfin.deviceSpace() else it } }
    val selection = ui.selection
    val route = vm.libraryRoute
    LaunchedEffect(route) { selection.clear() }
    // a selected item that left the library (deleted, moved out of reach) leaves the selection
    LaunchedEffect(version) {
        if (selection.ids.isNotEmpty()) {
            val ids = selection.ids.toList()
            val alive = if (ui.browse.cheap) ui.browse.byIds(ids).mapTo(HashSet()) { it.id } else withContext(Dispatchers.IO) { ui.browse.byIds(ids).mapTo(HashSet()) { it.id } }
            selection.prune(alive)
        }
    }
    LaunchedEffect(selection.mode) { vm.librarySelecting = selection.mode }
    DisposableEffect(vm) { onDispose { vm.librarySelecting = false; selection.clear() } }
    val compact = vm.prefs.libraryCompact(densityKey(route))

    CompositionLocalProvider(LocalCompact provides compact) {
        Column(modifier.fillMaxSize().testTag(UiTags.LIB_HOME.takeIf { route == LibraryRoute.Home } ?: UiTags.LIB_SPACE)) {
            if (snapshot.problems.isNotEmpty()) ProblemBanner(snapshot.problems.first(), vm.libraryActions::rescan)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val loading = snapshot.scanning && snapshot.scannedAt == null
                when {
                    loading -> LoadingLibrary()
                    else -> RouteBody(ui, route, spaces, contentPadding)
                }
            }
            if (selection.mode) SelectionActionBar(ui)
        }
    }
}

/** Density is remembered per space; every page that is not inside a space shares "home". */
private fun densityKey(route: LibraryRoute): String = when (route) {
    is LibraryRoute.Space -> route.id
    is LibraryRoute.Album -> route.space
    is LibraryRoute.Artist -> route.space
    is LibraryRoute.Folder -> route.space
    is LibraryRoute.Collection -> route.space
    is LibraryRoute.SmartList -> route.space ?: "home"
    is LibraryRoute.Rules -> route.draft.id
    else -> "home"
}

fun Space.icon() = (icon ?: SpaceLogic.defaultIcon(this)).vector()

@Composable
private fun ProblemBanner(message: String, onRescan: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Studio.colors.warning, modifier = Modifier.size(18.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        TextButton(onClick = onRescan, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rescan") }
    }
}

@Composable
private fun LoadingLibrary() {
    Column(Modifier.fillMaxSize().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Spacer(Modifier.size(16.dp))
        Text("Reading your library…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RouteBody(ui: LibraryUi, route: LibraryRoute, spaces: List<Space>, padding: PaddingValues) {
    when (route) {
        LibraryRoute.Home -> HomeScreen(ui, spaces, padding)
        is LibraryRoute.Section -> SectionScreen(ui, route.section, padding)
        is LibraryRoute.Duplicates -> DuplicatesScreen(ui, route, padding)
        is LibraryRoute.Space -> {
            val space = spaces.firstOrNull { it.id == route.id }
            if (space == null) MissingSpace(ui.vm)
            else if (space.smart) SmartSpaceScreen(ui, space, padding)
            else SpaceScreen(ui, space, padding)
        }
        LibraryRoute.Playlists -> CollectionsScreen(ui, spaces, padding)
        LibraryRoute.Manage -> HubEditScreen(ui, spaces, padding)
        LibraryRoute.Trash -> TrashScreen(ui, padding)
        LibraryRoute.AllSpaces -> AllSpacesScreen(ui, spaces, padding)
        LibraryRoute.Collections -> CollectionsScreen(ui, spaces, padding)
        LibraryRoute.MusicVideos -> MusicVideosScreen(ui, padding)
        is LibraryRoute.Rules -> RulesEditorScreen(ui, route, spaces, padding)
        is LibraryRoute.Album -> AlbumScreen(ui, route, padding)
        is LibraryRoute.Artist -> ArtistScreen(ui, route, padding)
        is LibraryRoute.UserPlaylist -> UserPlaylistScreen(ui, route.id, padding)
        is LibraryRoute.SmartList -> SmartScreen(ui, route, spaces, padding)
        is LibraryRoute.Collection -> CollectionDetailScreen(ui, route, padding)
        is LibraryRoute.Folder -> {
            val space = spaces.firstOrNull { it.id == route.space }
            if (space == null) MissingSpace(ui.vm) else FolderScreen(ui, space, route.path, padding)
        }
    }
}

@Composable
private fun MissingSpace(vm: StudioViewModel) {
    EmptyState(
        art = EmptyArt.LIBRARY, title = "This space is gone", body = "It was removed. Its files are still on your phone.",
        action = { OutlinedButton(onClick = { vm.openLibraryRoot(LibraryRoute.Home) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to Library") } },
    )
}

// ---- shared chrome -----------------------------------------------------------------------------------

/** Back arrow, title and a count; used by every detail page. A selection replaces it with the selection bar. */
@Composable
fun DetailHeader(ui: LibraryUi, title: String, subtitle: String?, trailing: @Composable () -> Unit = {}) {
    if (ui.selection.mode) { SelectionTopBar(ui); return }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BackButton(ui)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        trailing()
    }
}

@Composable
fun BackButton(ui: LibraryUi) {
    IconButton(onClick = { ui.vm.back() }, modifier = Modifier.testTag("library-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
}

/**
 * The header of a space: back arrow, name with "240 songs · 1.4 GB", search, sort and view, and the overflow menu (Add files,
 * Select, Edit space, Remove space, Compact / Comfortable). Search expands into a field under the bar. [title] is null for a
 * folder inside a space, which shows only the actions because the folder page has its own header.
 */
@Composable
fun SpaceToolbar(
    ui: LibraryUi,
    space: Space,
    title: String?,
    subtitle: String?,
    query: String,
    onQuery: (String) -> Unit,
    sort: LibraryViews.Sort?,
    onSort: ((LibraryViews.Sort) -> Unit)?,
    grid: Boolean?,
    onGrid: ((Boolean) -> Unit)?,
    canSelect: Boolean,
) {
    val selection = ui.selection
    if (selection.mode) { SelectionTopBar(ui); return }
    val vm = ui.vm
    var searching by rememberSaveable(space.id, title) { mutableStateOf(query.isNotEmpty()) }
    var sortMenu by remember { mutableStateOf(false) }
    var overflow by remember { mutableStateOf(false) }
    val compact = vm.prefs.libraryCompact(space.id)
    val focus = remember { FocusRequester() }
    Column(Modifier.fillMaxWidth().testTag(UiTags.LIB_TOOLBAR)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (title != null) {
                BackButton(ui)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            } else Spacer(Modifier.weight(1f))
            // The visible way in: "+" opens the library picker (folder space) or the rules (smart space).
            if (title != null && !space.device) IconButton(onClick = { ui.addTo(space) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_ADD)) {
                Icon(Icons.Outlined.Add, contentDescription = "Add to ${space.name}")
            }
            IconButton(onClick = { searching = !searching; if (!searching) onQuery("") }, modifier = Modifier.testTag(UiTags.LIB_SEARCH_TOGGLE)) {
                Icon(Icons.Outlined.Search, contentDescription = if (searching) "Close filter" else "Filter ${title ?: "this folder"}")
            }
            // The grid / list switch is one tap away (it also stays in the Sort and view menu).
            if (grid != null && onGrid != null) IconButton(onClick = { onGrid(!grid) }, modifier = Modifier.testTag(UiTags.LIB_VIEW_TOGGLE)) {
                Icon(if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, contentDescription = if (grid) "Show as list" else "Show as grid")
            }
            if ((sort != null && onSort != null) || (grid != null && onGrid != null)) Box {
                IconButton(onClick = { sortMenu = true }, modifier = Modifier.testTag(UiTags.LIB_SORT_VIEW)) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort and view")
                }
                DropdownMenu(sortMenu, { sortMenu = false }) {
                    if (sort != null && onSort != null) {
                        Text("Sort by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        LibraryViews.Sort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) }, leadingIcon = { RadioButton(option == sort, onClick = null) },
                                onClick = { sortMenu = false; onSort(option) }, modifier = Modifier.testTag("sort-${option.name}"),
                            )
                        }
                    }
                    if (grid != null && onGrid != null) {
                        if (sort != null) HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(if (grid) "Show as list" else "Show as grid") },
                            leadingIcon = { Icon(if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, contentDescription = null) },
                            onClick = { sortMenu = false; onGrid(!grid) }, modifier = Modifier.testTag(UiTags.LIB_VIEW),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { overflow = true }, modifier = Modifier.testTag(UiTags.LIB_OVERFLOW)) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(overflow, { overflow = false }) {
                    if (!space.device) DropdownMenuItem(
                        text = { Text(if (space.smart) "Edit rules" else "Add…") }, leadingIcon = { Icon(if (space.smart) Icons.Outlined.AutoAwesome else Icons.Outlined.Add, contentDescription = null) },
                        onClick = { overflow = false; ui.addTo(space) }, modifier = Modifier.testTag(UiTags.LIB_ADD_FILES),
                    )
                    if (canSelect) DropdownMenuItem(
                        text = { Text("Select") }, leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                        onClick = { overflow = false; selection.mode = true }, modifier = Modifier.testTag(UiTags.LIB_SELECT),
                    )
                    // the same file more than once in this space
                    if (!space.smart && !space.device) DropdownMenuItem(
                        text = { Text("Find duplicates") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        onClick = { overflow = false; vm.openLibraryDetail(LibraryRoute.Duplicates(space.name, app.keepfin.ItemQuery(spaces = listOf(space.id)))) },
                        modifier = Modifier.testTag(UiTags.LIB_DUPLICATES),
                    )
                    if (title != null && !space.device) {
                        DropdownMenuItem(
                            text = { Text("Edit space") }, leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = { overflow = false; ui.sheet(LibrarySheet.EditSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_EDIT),
                        )
                        if (!space.kept) DropdownMenuItem(
                            text = { Text("Remove space", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { overflow = false; ui.sheet(LibrarySheet.RemoveSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_REMOVE),
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (compact) "Comfortable view" else "Compact view") },
                        leadingIcon = { Icon(Icons.Outlined.ViewList, contentDescription = null) },
                        onClick = { overflow = false; vm.prefs.chooseLibraryCompact(space.id, !compact) },
                        modifier = Modifier.testTag(if (compact) UiTags.LIB_DENSITY_COMFORTABLE else UiTags.LIB_DENSITY_COMPACT),
                    )
                }
            }
        }
        if (searching) {
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            SearchField(query, onQuery, "Filter ${title ?: "this folder"}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus).testTag(UiTags.LIB_SEARCH_FIELD))
        }
    }
}

@Composable
fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = query, onValueChange = onQuery, singleLine = true, modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } },
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Studio.colors.raised, unfocusedContainerColor = Studio.colors.raised, unfocusedBorderColor = Studio.colors.separator),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    )
}

// ---- selection ---------------------------------------------------------------------------------------

/**
 * The tonal contextual bar of a selection: close, "N selected", and an outlined Select all that becomes Select none once
 * everything the page shows is selected. It covers exactly the visible, filtered items.
 */
@Composable
fun SelectionTopBar(ui: LibraryUi, modifier: Modifier = Modifier) {
    val selection = ui.selection
    val count = selection.ids.size
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier.fillMaxWidth().testTag(UiTags.LIB_SELECTION_BAR)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { selection.clear() }, modifier = Modifier.testTag(UiTags.LIB_SELECT_CLOSE)) { Icon(Icons.Outlined.Close, contentDescription = "Cancel selection") }
            Text(
                if (count == 0) "Select items" else "$count selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium.tabular().copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            OutlinedButton(
                onClick = selection::toggleAll, enabled = selection.hasScope,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 40.dp).testTag(UiTags.LIB_SELECT_ALL),
            ) { Text(if (selection.allSelected) "Select none" else "Select all", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        }
    }
}

/** The dark action bar of a selection: Move to…, Collection, Tags…, Play and Delete…. It takes the place of the bottom navigation. */
@Composable
private fun SelectionActionBar(ui: LibraryUi) {
    val selection = ui.selection
    val ids = selection.ids.toList()

    val dark = Studio.colors.dark
    val bar = if (dark) MaterialTheme.colorScheme.surfaceContainerHigh else Color(0xFF1F2533)
    val ink = if (dark) MaterialTheme.colorScheme.onSurface else Color.White
    val danger = Color(0xFFFFB4AB)
    val any = ids.isNotEmpty()
    val deleting = ui.vm.deleting
    var sawDeleting by remember { mutableStateOf(false) }
    // The selection stays (disabled, with progress) while a delete runs, and ends with it.
    LaunchedEffect(deleting) { if (deleting) sawDeleting = true else if (sawDeleting) { sawDeleting = false; selection.clear() } }
    Surface(color = bar, modifier = Modifier.fillMaxWidth().testTag(UiTags.LIB_SELECTION_ACTIONS)) {
        if (deleting) {
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 24.dp).testTag("selection-deleting").semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = ink)
                Text(if (ids.size == 1) "Deleting 1 item…" else "Deleting ${ids.size} items…", color = ink, fontWeight = FontWeight.SemiBold)
            }
            return@Surface
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp), verticalAlignment = Alignment.CenterVertically) {
            SelectionAction("Move to…", Icons.Outlined.DriveFileMove, ink, any, UiTags.LIB_SELECTION_MOVE) { ui.sheet(LibrarySheet.MoveTo(ids)) }
            SelectionAction("Playlist", Icons.AutoMirrored.Outlined.PlaylistAdd, ink, any, UiTags.LIB_SELECTION_PLAYLIST) { ui.sheet(LibrarySheet.AddToPlaylist(ids)) }
            // on a collection of yours: take the selected songs out of it (the files stay)
            (ui.vm.libraryRoute as? LibraryRoute.UserPlaylist)?.let { own ->
                SelectionAction("Remove", Icons.Outlined.PlaylistRemove, ink, any, UiTags.LIB_SELECTION_UNCOLLECT) { ui.vm.libraryActions.removeFromCollection(own.id, ids); selection.clear() }
            }
            SelectionAction("Tags…", Icons.Outlined.Sell, ink, any, UiTags.LIB_SELECTION_TAGS) { ui.sheet(LibrarySheet.Tags(ids)) }
            SelectionAction("Play", Icons.Outlined.PlayArrow, ink, any, UiTags.LIB_SELECTION_PLAY) { ui.playIds(ids); selection.clear() }
            SelectionAction("Delete…", Icons.Outlined.DeleteOutline, danger, any, UiTags.LIB_SELECTION_DELETE) { ui.sheet(LibrarySheet.Delete(ids, if (ids.size == 1) ui.item(ids[0])?.title else null)) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val color = if (enabled) tint else tint.copy(alpha = .38f)
    Column(
        Modifier.weight(1f).heightIn(min = 76.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Teaching empty states: what lands here and the next step. */
@Composable
fun SpaceEmpty(ui: LibraryUi, space: Space, modifier: Modifier = Modifier) {
    val (title, body) = when {
        !space.builtin -> "Choose what goes here" to "Send downloads to ${space.name} with Save to, or add files you already have."
        space.kind == SpaceKind.MUSIC -> "Your music lands here" to "Songs from YouTube Music, Spotify, Apple Music and Deezer land here."
        space.kind == SpaceKind.VIDEO -> "Your videos land here" to "Videos you save to ${space.name} appear as posters, with Continue watching."
        else -> "Everything else lands here" to "Downloads from other sites, and anything you add, show up here by folder."
    }
    EmptyState(
        art = EmptyArt.LIBRARY, title = title, body = body, modifier = modifier.testTag(UiTags.LIB_EMPTY),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { ui.vm.navigate(Destination.DOWNLOADS) }, modifier = Modifier.heightIn(min = 48.dp).testTag("empty-paste"),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Paste a link") }
                OutlinedButton(onClick = { ui.addFiles(space.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("empty-add-files")) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add files")
                }
            }
        },
    )
}

fun subtitleOf(item: LibraryItem): String = when (item.kind) {
    LibraryItemKind.AUDIO -> listOfNotNull(item.artist ?: item.albumArtist, item.album, item.duration?.let(LibraryViews::mmss)).joinToString(" · ")
    LibraryItemKind.VIDEO -> {
        val left = if (!item.watched && (item.position ?: 0.0) > 5.0 && item.duration != null) "${LibraryViews.mmss(item.duration - (item.position ?: 0.0))} left" else item.duration?.let(LibraryViews::mmss)
        listOfNotNull(left, item.height?.let { "${it}p" }, Format.bytes(item.size)).joinToString(" · ")
    }
    LibraryItemKind.IMAGE -> listOfNotNull(item.width?.let { w -> item.height?.let { h -> "$w × $h" } }, Format.bytes(item.size)).joinToString(" · ")
    LibraryItemKind.OTHER -> Format.bytes(item.size)
}
