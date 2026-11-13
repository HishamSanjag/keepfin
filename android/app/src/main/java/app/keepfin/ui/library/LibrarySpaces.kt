package app.keepfin.ui.library

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Counts
import app.keepfin.HubLogic
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.RuleText
import app.keepfin.SmartContext
import app.keepfin.SmartLogic
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spaceTone
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

internal fun Space.countLabel(count: Int): String = when (kind) {
    SpaceKind.MUSIC -> if (count == 1) "1 song" else "$count songs"
    SpaceKind.VIDEO -> if (count == 1) "1 video" else "$count videos"
    SpaceKind.MIXED -> if (count == 1) "1 item" else "$count items"
}

/** What a space card says under its name: "240 songs", "Smart · 58 songs" and, when a followed collection found news, "3 new · 31 songs". */
internal fun spaceLine(space: Space, count: Int, news: Int, smart: Counts? = null): String {
    if (space.device) return "Only on this phone"
    val base = if (space.smart) "Smart · " + RuleText.countLabel(smart ?: Counts()) else space.countLabel(count)
    return if (news > 0) "$news new · $base" else base
}

// ---- home grid ---------------------------------------------------------------------------------------

/**
 * The Spaces of Library home: compact 56dp cards in two columns, in hub order. It shows the pinned spaces (at most five) and an
 * "All spaces · N more" card when more exist. There is no "New space" here; that lives in edit mode.
 */
@Composable
fun SpacesGrid(ui: LibraryUi, spaces: List<Space>) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val shown = remember(spaces) { SpaceLogic.homeSpaces(spaces) }
    val more = remember(spaces) { SpaceLogic.moreSpaces(spaces) }
    val counts by rememberSpaceCounts(ui.browse)
    val smartCounts by rememberSmartCounts(ui, shown)
    val finding = (ui.vm.lib.deviceMusic.collectAsStateWithLifecycle().value as? app.keepfin.DeviceMusicState.Scanning)?.found
    val cells =remember(shown, more) { shown.map<Space, Any> { it } + listOfNotNull(if (more > 0) more else null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.LIB_SPACES_GRID), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cells.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { cell ->
                    if (cell is Space) {
                        HubCard(
                            cell, if (cell.device && finding != null) "Finding your music… $finding songs" else spaceLine(cell, counts[cell.id]?.total ?: 0, HubLogic.newInSpace(snapshot.collections, cell.id), smartCounts[cell.id]), ui,
                            Modifier.weight(1f),
                        )
                    } else AllSpacesCard(cell as Int, ui, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One space on the hub: a 56dp tonal card with its icon tile, name and a count line. Tap opens it; long-press (or the TalkBack
 * actions) offers Edit, Add and, for spaces you added, Remove.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HubCard(space: Space, line: String, ui: LibraryUi, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val tone = spaceTone(space)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(tone.container)
                .combinedClickable(
                    role = Role.Button, onClickLabel = "Open ${space.name}", onClick = { ui.vm.openLibraryRoot(LibraryRoute.Space(space.id)) },
                    onLongClickLabel = "More actions", onLongClick = { menu = true },
                )
                .semantics {
                    contentDescription = "${space.name}, $line"
                    customActions = spaceActions(ui, space)
                }
                .testTag(UiTags.spaceCard(space.id))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tone.accent), contentAlignment = Alignment.Center) {
                Icon(space.icon(), contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(space.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(menu && !space.device, { menu = false }) {
            DropdownMenuItem(
                text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                onClick = { menu = false; ui.sheet(LibrarySheet.EditSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_EDIT),
            )
            DropdownMenuItem(
                text = { Text(if (space.smart) "Edit rules" else "Add") }, leadingIcon = { Icon(if (space.smart) Icons.Outlined.AutoAwesome else Icons.Outlined.Add, contentDescription = null) },
                onClick = { menu = false; ui.addTo(space) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_ADD),
            )
            if (!space.kept) DropdownMenuItem(
                text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; ui.sheet(LibrarySheet.RemoveSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_REMOVE),
            )
        }
    }
}

private fun spaceActions(ui: LibraryUi, space: Space) = buildList {
    if (space.device) return@buildList
    add(CustomAccessibilityAction("Edit space") { ui.sheet(LibrarySheet.EditSpace(space)); true })
    add(CustomAccessibilityAction(if (space.smart) "Edit rules" else "Add to this space") { ui.addTo(space); true })
    if (!space.kept) add(CustomAccessibilityAction("Remove space") { ui.sheet(LibrarySheet.RemoveSpace(space)); true })
}

/** The last home card: "All spaces" with how many are not shown. */
@Composable
private fun AllSpacesCard(more: Int, ui: LibraryUi, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(role = Role.Button, onClickLabel = "Show all spaces") { ui.vm.openLibraryDetail(LibraryRoute.AllSpaces) }
            .semantics { contentDescription = "All spaces, $more more" }
            .testTag(UiTags.LIB_ALL_SPACES)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.GridView, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("All spaces", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$more more", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ---- edit in place -----------------------------------------------------------------------------------

/**
 * Edit mode of the hub: the same two-column grid as home, with every space (built-ins and smart spaces included) draggable and
 * a pin toggle on each card. "+ New space" appears only here, with "Where downloads go" below the grid. Done leaves edit mode.
 * TalkBack gets Move up / Move down actions on every card.
 */
@Composable
fun HubEditScreen(ui: LibraryUi, spaces: List<Space>, padding: PaddingValues) {
    val vm = ui.vm
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val library = settings.library
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val ids = spaces.map { it.id }
    val order = remember(ids) { mutableStateListOf<String>().also { it.addAll(ids) } }
    val byId = remember(spaces) { spaces.associateBy { it.id } }
    val counts by rememberSpaceCounts(ui.browse)
    val smartCounts by rememberSmartCounts(ui, spaces)
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index in order.indices && to.index in order.indices) order.add(to.index, order.removeAt(from.index))
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reduced = Studio.reducedMotion
    fun commit() { if (order.toList() != ids) vm.spaces.reorderSpaces(order.toList()) }
    Column(Modifier.fillMaxSize().testTag(UiTags.LIB_MANAGE)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
            Button(
                onClick = { vm.back() }, contentPadding = PaddingValues(horizontal = 20.dp), modifier = Modifier.heightIn(min = 48.dp).testTag("edit-spaces-done"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
            ) { Text("Done", fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
        Text(
            "Spaces · drag to reorder", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp).semantics { heading() },
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2), state = gridState, modifier = Modifier.fillMaxSize().testTag(UiTags.LIB_SPACES_GRID),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(order.toList(), key = { it }) { id ->
                val space = byId[id] ?: return@items
                ReorderableItem(reorderState, key = id) { dragging ->
                    val lift by animateDpAsState(if (dragging) 8.dp else 0.dp, tween(if (reduced) 0 else 120), label = "lift")
                    EditCard(
                        space, spaceLine(space, counts[space.id]?.total ?: 0, 0, smartCounts[space.id]), ui, order, lift.value,
                        dragHandle = Modifier.longPressDraggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                            onDragStopped = { commit() },
                        ),
                        onMove = { forward -> val moved = HubLogic.move(order.toList(), id, forward); if (moved != order.toList()) { order.clear(); order.addAll(moved); vm.spaces.reorderSpaces(moved) } },
                    )
                }
            }
            item(key = "new-space") { NewSpaceTile({ ui.sheet(LibrarySheet.EditSpace(null)) }, Modifier.fillMaxWidth().height(56.dp).testTag(UiTags.LIB_SPACE_NEW)) }
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Tap a card to rename it or change its color, folder or rules. Long-press a card to drag it. Pinned spaces show on home, in this order. With no pins, the first five show.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
            item(key = "routes", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("Where downloads go", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                    RouteChoice("Music links", library.musicRoute, library.folderSpaces, UiTags.SETTINGS_ROUTE_MUSIC) { id -> vm.updateSettings { it.copy(library = it.library.copy(musicRoute = id)) } }
                    RouteChoice("Everything else", library.otherRoute, library.folderSpaces, UiTags.SETTINGS_ROUTE_OTHER) { id -> vm.updateSettings { it.copy(library = it.library.copy(otherRoute = id)) } }
                    Text("Save to on any download overrides this once.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            item(key = "tools", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = vm.libraryActions::rescan, modifier = Modifier.heightIn(min = 48.dp).testTag("library-rescan")) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (snapshot.scanning) "Scanning…" else "Rescan library")
                    }
                    TextButton(onClick = { vm.openLibrarySettings() }, modifier = Modifier.heightIn(min = 48.dp).testTag("library-settings-row")) {
                        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Library settings")
                    }
                }
            }
        }
    }
}

/**
 * One card in edit mode: exactly the home card (56dp, same tile, name and count line) with the pin toggle added on the card at its end.
 * The whole card drags after a long press and a tap edits it.
 */
@Composable
private fun EditCard(
    space: Space,
    line: String,
    ui: LibraryUi,
    order: List<String>,
    lift: Float,
    dragHandle: Modifier,
    onMove: (forward: Boolean) -> Unit,
) {
    val tone = spaceTone(space)
    val pinned = space.isPinned
    val index = order.indexOf(space.id)
    Row(
        Modifier.fillMaxWidth().height(56.dp).graphicsLayer { shadowElevation = lift; shape = RoundedCornerShape(14.dp); clip = false }
            .clip(RoundedCornerShape(14.dp)).background(tone.container)
            .then(dragHandle)
            .clickable(role = Role.Button, onClickLabel = "Edit ${space.name}") { ui.sheet(LibrarySheet.EditSpace(space)) }
            .semantics {
                contentDescription = "${space.name}, $line, ${if (pinned) "pinned to home" else "not pinned"}"
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction("Move up") { onMove(false); true })
                    if (index in 0 until order.lastIndex) add(CustomAccessibilityAction("Move down") { onMove(true); true })
                    add(CustomAccessibilityAction(if (pinned) "Unpin from home" else "Pin to home") { ui.vm.spaces.pinSpace(space.id, !pinned); true })
                    if (!space.kept) add(CustomAccessibilityAction("Remove space") { ui.sheet(LibrarySheet.RemoveSpace(space)); true })
                }
            }
            .testTag(UiTags.spaceCard(space.id))
            .padding(start = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(tone.accent), contentAlignment = Alignment.Center) {
            Icon(space.icon(), contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(space.name, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (pinned) "Pinned" else "Not pinned", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = { ui.vm.spaces.pinSpace(space.id, !pinned) },
            modifier = Modifier.size(48.dp).testTag(UiTags.spacePin(space.id)).semantics { stateDescription = if (pinned) "Pinned" else "Not pinned" },
        ) {
            Icon(
                Icons.Outlined.PushPin, contentDescription = if (pinned) "Unpin ${space.name} from home" else "Pin ${space.name} to home",
                tint = if (pinned) tone.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = if (pinned) 0f else 45f },
            )
        }
    }
}

@Composable
private fun NewSpaceTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.outline
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(role = Role.Button, onClick = onClick).drawBehind {
            val inset = 1.dp.toPx()
            drawRoundRect(
                line, topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset), cornerRadius = CornerRadius(13.dp.toPx()),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) { Text("+ New space", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = app.keepfin.ui.theme.Studio.colors.accentText) }
}

@Composable
private fun RouteChoice(label: String, current: String, spaces: List<Space>, tag: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Change") { open = true }.testTag(UiTags.setting(tag)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text("${spaces.firstOrNull { it.id == current }?.name ?: "Not set"} ▾", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = app.keepfin.ui.theme.Studio.colors.accentText)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            spaces.forEach { space -> DropdownMenuItem(text = { Text(space.name) }, onClick = { open = false; onPick(space.id) }, modifier = Modifier.testTag(UiTags.setting("$tag-${space.id}"))) }
        }
    }
}

// ---- all spaces --------------------------------------------------------------------------------------

/** Every space as a plain list that scales to any number: folders first, then smart spaces, with search. */
@Composable
fun AllSpacesScreen(ui: LibraryUi, spaces: List<Space>, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val ordered = remember(spaces) { SpaceLogic.allSpaces(spaces) }
    val terms = remember(query) { LibraryViews.terms(query) }
    val shown = remember(ordered, terms) { if (terms.isEmpty()) ordered else ordered.filter { s -> terms.all { s.name.lowercase().contains(it) || s.folder.lowercase().contains(it) } } }
    val folders = shown.filter { !it.smart }
    val smart = shown.filter { it.smart }
    val counts by rememberSpaceCounts(ui.browse)
    val smartCounts by rememberSmartCounts(ui, smart)
    val names = remember(spaces, snapshot.collections) { { field: app.keepfin.SmartField, value: String ->
        when (field) {
            app.keepfin.SmartField.SPACE -> spaces.firstOrNull { it.id == value }?.name
            app.keepfin.SmartField.COLLECTION -> snapshot.collections.firstOrNull { it.id == value }?.name
            else -> null
        }
    } }
    Column(Modifier.fillMaxSize().testTag(UiTags.LIB_ALL_SPACES_LIST)) {
        DetailHeader(ui, "All spaces", "${spaces.size} ${if (spaces.size == 1) "space" else "spaces"}") {
            IconButton(onClick = { ui.sheet(LibrarySheet.EditSpace(null)) }, modifier = Modifier.size(48.dp).testTag("all-spaces-new")) { Icon(Icons.Outlined.Add, contentDescription = "New space") }
            IconButton(onClick = { ui.vm.openLibraryRoot(LibraryRoute.Manage) }, modifier = Modifier.size(48.dp).testTag("all-spaces-edit")) { Icon(Icons.Outlined.Edit, contentDescription = "Edit spaces") }
        }
        SearchField(query, { query = it }, "Filter spaces", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.LIB_SEARCH_FIELD))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            if (shown.isEmpty()) item("none") {
                Text("No space matches “${query.trim()}”.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            if (folders.isNotEmpty()) {
                item("folders-head") { SectionLabel("Folders · ${folders.size}") }
                items(folders, key = { "f-" + it.id }) { space ->
                    SpaceListRow(space, "${space.countLabel(counts[space.id]?.total ?: 0)} · ${space.folderLine()}") { ui.vm.openLibraryDetail(LibraryRoute.Space(space.id)) }
                }
            }
            if (smart.isNotEmpty()) {
                item("smart-head") { SectionLabel("Smart · ${smart.size}") }
                items(smart, key = { "s-" + it.id }) { space ->
                    val rules = space.rules?.let { RuleText.describe(it, names) } ?: "No rules yet"
                    SpaceListRow(space, "$rules · ${RuleText.countLabel(smartCounts[space.id] ?: Counts())}") { ui.vm.openLibraryDetail(LibraryRoute.Space(space.id)) }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun SpaceListRow(space: Space, line: String, onClick: () -> Unit) {
    val tone = spaceTone(space)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.spaceRow(space.id)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(tone.accent), contentAlignment = Alignment.Center) {
            Icon(space.icon(), contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(space.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun Space.folderLine(): String = when {
    smart -> "Smart space"
    id == "downloads" -> "Keepfin folders"
    kind == SpaceKind.MUSIC -> "Music/$folder"
    kind == SpaceKind.VIDEO -> "Movies/$folder"
    else -> "Download/$folder"
}
