package app.keepfin.ui.library

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Counts
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Providers
import app.keepfin.RuleText
import app.keepfin.SmartContext
import app.keepfin.SmartField
import app.keepfin.SmartLogic
import app.keepfin.SmartMatch
import app.keepfin.SmartRule
import app.keepfin.SmartRules
import app.keepfin.Space
import app.keepfin.SpaceLogic
import app.keepfin.SpaceType
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spaceTone
import app.keepfin.ui.theme.tabular
import org.json.JSONObject

private val rulesSaver = listSaver<SmartRules, String>(
    save = { listOf(SpaceLogic.encodeRules(it).toString()) },
    restore = { SpaceLogic.decodeRules(JSONObject(it[0])) ?: SmartRules() },
)

private val fieldOrder = listOf(SmartField.TAG, SmartField.COLLECTION, SmartField.SPACE, SmartField.SOURCE, SmartField.ARTIST, SmartField.ALBUM, SmartField.KIND)
private val daysChoices = listOf(7, 30, 90, 365)

// ---- smart space page -------------------------------------------------------------------------------

/** Opening a smart space lists what its rules gather from every space, live, and plays it. */
@Composable
fun SmartSpaceScreen(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val query = remember(space.rules) { ItemQuery(smart = space.rules ?: SmartRules(), order = ItemOrder.ADDED) }
    val counts by rememberCounts(ui.browse, query)
    val matchCount = counts.total
    val names = rememberRuleNames(ui.vm)
    val rulesText = remember(space.rules, names) { space.rules?.let { RuleText.describe(it, names) } ?: "No rules yet" }
    val tone = spaceTone(space)
    var menu by remember { mutableStateOf(false) }
    // Quick filters: only the kinds the space gathers, shown when there are two or more (as in folder spaces); the rules stay the rules
    var filter by rememberSaveable(space.id) { mutableStateOf(ContentFilter.ALL) }
    val kinds = remember(counts) { ContentFilter.entries.filter { it != ContentFilter.ALL && counts.of(it.kind()!!) > 0 } }
    val effectiveFilter = if (filter != ContentFilter.ALL && filter !in kinds) ContentFilter.ALL else filter
    val listed = remember(query, effectiveFilter) { effectiveFilter.kind()?.let { query.copy(kinds = setOf(it)) } ?: query }
    val listedCount by rememberCount(ui.browse, listed)
    val playable = if (effectiveFilter == ContentFilter.ALL) counts.audio + counts.video else listedCount.takeIf { effectiveFilter.kind() != LibraryItemKind.IMAGE } ?: 0
    SideEffect { ui.selection.publishQuery(listed, listedCount) }
    Column(Modifier.fillMaxSize().testTag(UiTags.SMART_SPACE)) {
        DetailHeader(ui, space.name, "Smart · ${RuleText.countLabel(counts)}") {
            IconButton(onClick = { ui.addTo(space) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_ADD)) { Icon(Icons.Outlined.Add, contentDescription = "Add to ${space.name}") }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(UiTags.LIB_OVERFLOW)) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Edit rules") }, leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null) },
                        onClick = { menu = false; ui.vm.openRules(space, false) }, modifier = Modifier.testTag(UiTags.SMART_EDIT_RULES),
                    )
                    DropdownMenuItem(
                        text = { Text("Edit space") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { menu = false; ui.sheet(LibrarySheet.EditSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_EDIT),
                    )
                    if (!space.kept) DropdownMenuItem(
                        text = { Text("Remove space", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; ui.sheet(LibrarySheet.RemoveSpace(space)) }, modifier = Modifier.testTag(UiTags.LIB_SPACE_MENU_REMOVE),
                    )
                }
            }
        }
        if (matchCount == 0) {
            val noRules = space.rules?.rules.isNullOrEmpty()
            val favorites = space.id == app.keepfin.core.spaces.Spaces.FAVORITES_ID && !noRules
            EmptyState(
                art = EmptyArt.COLLECTION, title = if (favorites) "No favorites yet" else if (noRules) "No rules yet" else "Nothing matches yet",
                body = if (favorites) "Tap the heart on any song or video to add it here." else if (noRules) "A smart space gathers items from every space by rules. Add a rule to fill it." else "$rulesText. Items appear here by themselves as your library grows.",
                modifier = Modifier.weight(1f),
                action = { OutlinedButton(onClick = { ui.vm.openRules(space, false) }, modifier = Modifier.heightIn(min = 48.dp).testTag("smart-empty-rules")) { Text("Edit rules") } },
            )
            return@Column
        }
        if (kinds.size > 1 && !ui.selection.mode) KindChips(effectiveFilter, kinds) { filter = it }
        PagedItemsColumn(ui, listed, padding, publishSelection = false, header = { _ ->
            item("rules-line", contentType = "head") {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit rules") { ui.vm.openRules(space, false) }.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(28.dp).background(tone.accent, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = tone.onAccent, modifier = Modifier.size(16.dp))
                    }
                    Text(rulesText, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            if (playable > 0 && !ui.selection.mode) item("play", contentType = "buttons") { PlayButtons(ui, Songs.Rows(listed, listedCount)) }
        })
    }
}

// ---- rules editor -----------------------------------------------------------------------------------

@Composable
internal fun rememberRuleNames(vm: app.keepfin.ui.StudioViewModel): (SmartField, String) -> String? {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    return remember(snapshot.collections, settings.library.spaces) {
        { field: SmartField, value: String ->
            when (field) {
                SmartField.SPACE -> settings.library.spaces.firstOrNull { it.id == value }?.name
                SmartField.COLLECTION -> snapshot.collections.firstOrNull { it.id == value }?.name
                else -> null
            }
        }
    }
}

private fun fieldName(rule: SmartRule): String = when (rule) {
    is SmartRule.Values -> RuleText.fieldLabel(rule.field)
    is SmartRule.AddedWithin -> "Added in the last"
    SmartRule.Favorite -> "Favorite"
    SmartRule.Unplayed -> "Not played yet"
}

/**
 * The smart rules editor: Match any / all, rule rows (tag, collection, space, source, artist, album, kind, added within, favorite,
 * not played yet) with value pickers fed from the library, Only songs and Leave out spaces, and a live match count. Save keeps the space.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RulesEditorScreen(ui: LibraryUi, route: LibraryRoute.Rules, spaces: List<Space>, padding: PaddingValues) {
    val vm = ui.vm
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    var rules by rememberSaveable(route.draft.id, stateSaver = rulesSaver) { mutableStateOf(route.draft.rules ?: SmartRules()) }
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickingExclude by rememberSaveable { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val names = rememberRuleNames(ui.vm)
    val cleaned = remember(rules) { SpaceLogic.cleanRules(rules) }
    // the live match count: how many items the rules gather, and from which spaces (read in the index, off the main thread)
    val matchQuery = remember(cleaned) { ItemQuery(smart = cleaned) }
    val matchCounts by rememberBrowsed(ui.browse, matchQuery, Pair(Counts(), emptyMap<String, Int>())) { b ->
        if (cleaned.rules.isEmpty()) Pair(Counts(), emptyMap()) else b.spaceCounts(matchQuery).let { by -> Pair(b.counts(matchQuery), by.mapValues { it.value.total }) }
    }
    val matches = matchCounts.first
    val folderSpaces = remember(spaces) { spaces.filter { it.type == SpaceType.FOLDER } }
    val spaceNames = remember(spaces) { spaces.associate { it.id to it.name } }
    val draftName = route.draft.name.ifBlank { "New smart space" }

    fun setRule(index: Int, rule: SmartRule) { rules = rules.copy(rules = rules.rules.toMutableList().also { it[index] = rule }) }
    fun removeRule(index: Int) { rules = rules.copy(rules = rules.rules.filterIndexed { i, _ -> i != index }) }

    Column(Modifier.fillMaxSize().testTag(UiTags.RULES_EDITOR)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.back() }, modifier = Modifier.testTag("library-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("$draftName · rules", fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            Button(
                onClick = {
                    saving = true
                    val saved = route.draft.copy(rules = cleaned, type = SpaceType.SMART)
                    vm.spaces.saveSpace(saved, false) { problem ->
                        saving = false
                        if (problem == null) {
                            vm.say(if (route.creating) "Added the smart space “${saved.name}”" else "Saved the rules of “${saved.name}”")
                            if (route.creating) vm.replaceLibraryRoute(LibraryRoute.Space(saved.id)) else vm.back()
                        } else error = problem
                    }
                },
                enabled = cleaned.rules.isNotEmpty() && !saving, contentPadding = PaddingValues(horizontal = 20.dp), modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.RULES_SAVE),
                colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
            ) { Text("Save", fontWeight = FontWeight.SemiBold) }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("match") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    listOf(SmartMatch.ANY to "Match any", SmartMatch.ALL to "Match all").forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = rules.match == value, onClick = { rules = rules.copy(match = value) }, shape = SegmentedButtonDefaults.itemShape(index, 2),
                            modifier = Modifier.heightIn(min = 48.dp).testTag(if (value == SmartMatch.ANY) UiTags.RULES_MATCH_ANY else UiTags.RULES_MATCH_ALL),
                        ) { Text(label) }
                    }
                }
            }
            if (rules.rules.isEmpty()) item("hint") {
                Text("Add a rule to say what this space gathers. Values inside one rule are ORed: “Tag is lofi, chill” takes either.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(rules.rules, key = { index, rule -> "rule-$index-${fieldName(rule)}" }) { index, rule ->
                RuleCard(rule, index, names, onEdit = { picking = index }, onDays = { days -> setRule(index, SmartRule.AddedWithin(days)) }, onRemove = { removeRule(index) })
            }
            item("add") {
                Box {
                    OutlinedButton(onClick = { addMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.RULES_ADD)) { Text("+ Add rule") }
                    DropdownMenu(addMenu, { addMenu = false }) {
                        fieldOrder.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(RuleText.fieldLabel(field)) },
                                onClick = { addMenu = false; rules = rules.copy(rules = rules.rules + SmartRule.Values(field, emptyList())); picking = rules.rules.lastIndex },
                                modifier = Modifier.testTag(UiTags.ruleType(field.wire)),
                            )
                        }
                        DropdownMenuItem(text = { Text("Added in the last…") }, onClick = { addMenu = false; rules = rules.copy(rules = rules.rules + SmartRule.AddedWithin(30)) }, modifier = Modifier.testTag(UiTags.ruleType("addedWithin")))
                        DropdownMenuItem(text = { Text("Favorite") }, onClick = { addMenu = false; if (SmartRule.Favorite !in rules.rules) rules = rules.copy(rules = rules.rules + SmartRule.Favorite) }, modifier = Modifier.testTag(UiTags.ruleType("favorite")))
                        DropdownMenuItem(text = { Text("Not played yet") }, onClick = { addMenu = false; if (SmartRule.Unplayed !in rules.rules) rules = rules.copy(rules = rules.rules + SmartRule.Unplayed) }, modifier = Modifier.testTag(UiTags.ruleType("unplayed")))
                    }
                }
            }
            item("also") {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text("Also", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).semantics { heading() })
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(rules.onlyKind == LibraryItemKind.AUDIO, role = Role.Switch) { on ->
                                rules = rules.copy(onlyKind = if (on) LibraryItemKind.AUDIO else null)
                            }.testTag(UiTags.RULES_ONLY_SONGS),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Only songs", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(checked = rules.onlyKind == LibraryItemKind.AUDIO, onCheckedChange = null)
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "Choose spaces to leave out") { pickingExclude = true }.testTag(UiTags.RULES_EXCLUDE),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Leave out spaces", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    rules.excludeSpaces.map { spaceNames[it] ?: it }.joinToString(", ").ifEmpty { "None" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text("Choose", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = app.keepfin.ui.theme.Studio.colors.accentText)
                        }
                    }
                }
            }
            item("count") {
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(UiTags.RULES_COUNT)) {
                    Text(
                        if (cleaned.rules.isEmpty()) "No rules yet" else "${RuleText.countLabel(matches)} match",
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium.tabular(),
                    )
                    Text(
                        if (cleaned.rules.isEmpty() || matches.total == 0) "Updates by itself as you download more."
                        else RuleText.origin(matchCounts.second) { spaceNames[it] } + ". Updates by itself as you download more.",
                        fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp).testTag("rules-error")) }
                }
            }
        }
    }

    picking?.let { index ->
        val rule = rules.rules.getOrNull(index)
        if (rule is SmartRule.Values) {
            val options by rememberBrowsed(ui.browse, rule.field to snapshot.collections.size, emptyList<PickOption>()) { b -> optionsFor(rule.field, b, snapshot.collections, folderSpaces) }
            ValuePickerSheet(
                title = RuleText.fieldLabel(rule.field), options = options, selected = rule.values, allowCustom = rule.field == SmartField.TAG,
                onDone = { values ->
                    picking = null
                    if (values.isEmpty()) removeRule(index) else setRule(index, SmartRule.Values(rule.field, values))
                },
                onDismiss = { picking = null; if (rule.values.isEmpty()) removeRule(index) },
            )
        } else picking = null
    }
    if (pickingExclude) ValuePickerSheet(
        title = "Leave out spaces", options = folderSpaces.map { PickOption(it.id, it.name, it.folder) }, selected = rules.excludeSpaces, allowCustom = false,
        onDone = { values -> rules = rules.copy(excludeSpaces = values); pickingExclude = false }, onDismiss = { pickingExclude = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleCard(rule: SmartRule, index: Int, names: (SmartField, String) -> String?, onEdit: () -> Unit, onDays: (Int) -> Unit, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag(UiTags.rulesRow(index))) {
        Column(Modifier.padding(start = 14.dp, end = 2.dp, top = 2.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(fieldName(rule), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).semantics { heading() })
                IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag("rules-remove-$index")) { Icon(Icons.Outlined.Close, contentDescription = "Remove the ${fieldName(rule)} rule") }
            }
            when (rule) {
                is SmartRule.Values -> FlowRow(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    rule.values.forEach { value -> ValueChip(RuleText.valueLabel(rule.field, value, names), false, onEdit) }
                    ValueChip(if (rule.values.isEmpty()) "Choose…" else "Edit", true, onEdit, "rules-edit-$index")
                }
                is SmartRule.AddedWithin -> Row(Modifier.padding(end = 12.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    daysChoices.forEach { d ->
                        FilterPill(if (d == 365) "1 year" else "$d days", rule.days == d, { onDays(d) }, Modifier.testTag("rules-days-$d"))
                    }
                }
                SmartRule.Favorite -> Text("Songs and videos you marked as favorites.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                SmartRule.Unplayed -> Text("Items you have never played.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
            }
        }
    }
}

@Composable
private fun ValueChip(label: String, action: Boolean, onClick: () -> Unit, tag: String? = null) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).then(if (tag != null) Modifier.testTag(tag) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp), color = if (action) androidx.compose.ui.graphics.Color.Transparent else colors.primaryContainer,
            border = if (action) androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant) else null, modifier = Modifier.heightIn(min = 32.dp),
        ) {
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(label, fontSize = 13.sp, color = if (action) colors.primary else colors.onPrimaryContainer, fontWeight = if (action) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
            }
        }
    }
}

/** Picker options for a rule field, fed from what the library really holds. */
internal fun optionsFor(field: SmartField, browse: app.keepfin.LibraryBrowse, collections: List<app.keepfin.LibraryCollection>, folderSpaces: List<Space>): List<PickOption> = when (field) {
    SmartField.TAG, SmartField.SOURCE, SmartField.ARTIST, SmartField.ALBUM -> browse.facets(field, 300).map { PickOption(it.value, it.value, "${it.count} items") }
    SmartField.COLLECTION -> collections.sortedBy { LibraryViews.fold(it.name) }.map { PickOption(it.id, it.name, "${it.itemIds.size} items") }
    SmartField.SPACE -> folderSpaces.map { PickOption(it.id, it.name, it.folder) }
    SmartField.KIND -> browse.counts(app.keepfin.ItemQuery()).let { c -> listOf("audio", "video", "image", "other").map { wire -> PickOption(wire, RuleText.kindLabel(wire), "${c.of(app.keepfin.LibraryItemKind.entries.first { it.wire == wire })} items") } }
}