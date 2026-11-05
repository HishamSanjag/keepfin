package app.keepfin.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import app.keepfin.ui.theme.Studio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.merge.CombineState
import app.keepfin.merge.SeparatePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The screens of combining two libraries (dev-docs/plans/LIBRARY-MERGE.md): the Combine step and its review in the setup after pairing, the
// progress on a device's card, and Separate again. The same flow and sentences as the Windows screens, with "this phone".

object CombineTags {
    const val STEP = "combine-step"
    const val WAIT = "combine-wait"
    const val WAIT_NOTE = "combine-wait-note"
    const val UNDER_WAY = "combine-under-way"
    const val QUESTION = "combine-question"
    const val KEEP_THEIRS = "combine-keep-theirs"
    const val KEEP_MINE = "combine-keep-mine"
    const val LATER = "combine-later"
    const val REVIEW = "combine-review"
    const val BACK = "combine-back"
    const val COMBINE = "combine-go"
    const val PROMISE = "combine-promise"
    const val FLOW = "combine-flow"
    const val LIB_MINE = "combine-lib-mine"
    const val LIB_OTHER = "combine-lib-other"
    const val LIB_RESULT = "combine-lib-result"
    const val TILES = "combine-tiles"
    const val ALONG = "combine-along"
    const val CHECK = "combine-check"
    fun tile(key: String) = "combine-tile-$key"
    const val DETAILS_TOGGLE = "combine-details-toggle"
    const val DETAILS = "combine-details"
    const val MORE = "combine-more"
    const val DAYS = "combine-days"
    const val ERROR = "combine-error"
    const val TRY_AGAIN = "combine-try-again"
    const val SEPARATE = "combine-separate"
    const val SEP_BACK = "combine-sep-back"
    const val SEP_ADDED = "combine-sep-added"
    const val SEP_HELD = "combine-sep-held"
    const val SEP_KEEP = "combine-sep-keep"
    const val SEP_REMOVE = "combine-sep-remove"
    const val SEP_NOTE = "combine-sep-note"
    const val SEP_FOLDER = "combine-sep-folder"
    const val SEP_UNREACHABLE = "combine-sep-unreachable"
    const val SEP_GO = "combine-sep-go"
    const val SEP_CANCEL = "combine-sep-cancel"
    const val SEP_CHECKING = "combine-sep-checking"
    fun card(id: String) = "combine-card-$id"
    fun cardLine(id: String) = "combine-card-line-$id"
    fun cardProgress(id: String) = "combine-card-progress-$id"
    fun cardPause(id: String) = "combine-card-pause-$id"
    fun cardResume(id: String) = "combine-card-resume-$id"
    fun cardCancel(id: String) = "combine-card-cancel-$id"
    fun cardReview(id: String) = "combine-card-review-$id"
    fun cardSeparate(id: String) = "combine-card-separate-$id"
    fun cardKeep(id: String) = "combine-card-keep-$id"
    fun cardError(id: String) = "combine-card-error-$id"
}

private fun background(work: () -> Unit) { CoroutineScope(Dispatchers.IO).launch { runCatching(work) } }

// ---- the Combine step ------------------------------------------------------------------------------------------------------------

/**
 * The first step of the setup when the two devices hold different libraries: "Combine libraries". It asks for the review when it opens
 * (nothing changes yet), then offers the choice and the review. Combine starts the work and closes; the device card shows how it goes.
 * [onClose] runs when the person leaves or has pressed Combine.
 */
@Composable
fun CombineStepDialog(deviceId: String, name: String, otherIsPc: Boolean, combine: DeviceCombine, onClose: () -> Unit) {
    val state by combine.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableStateOf("choose") }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) }
    val mine = combineFor(state, deviceId)
    val phase = mine?.let(::phaseOf)
    val preview = if (phase == CombinePhase.Ready) mine?.preview else null
    val keep = picked?.let { if (it == "mine") Keep.Mine else Keep.Theirs } ?: defaultKeep(preview, otherIsPc)

    // ask for the review once; one that is already there (after a restart) is used as it is
    LaunchedEffect(deviceId) {
        if (combineFor(combine.state.value, deviceId) != null) return@LaunchedEffect
        error = withContext(Dispatchers.IO) { combine.review(deviceId) }
    }

    // leaving without Combine puts the libraries back as they were (the review is dropped)
    fun leave() {
        val s = combineFor(combine.state.value, deviceId)
        if (!started && s != null && !s.started) background { combine.cancel() }
        onClose()
    }
    BackHandler { if (step == "review" && !busy) step = "choose" else if (!busy) leave() }

    val underWay = mine != null && mine.started
    val failed = mine != null && !mine.started && phase == CombinePhase.Failed
    val problem = error ?: if (failed) mine?.error else null
    val ready = preview != null && !failed

    Dialog(onDismissRequest = { if (!busy) leave() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag(CombineTags.STEP), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Combine libraries", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(if (step == "review" && ready) "Review · $name" else name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when {
                        underWay -> Text(
                            "Combining with $name is already under way or waiting. You can follow it, or change it, on $name’s card in Settings › Devices.",
                            fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag(CombineTags.UNDER_WAY),
                        )
                        !ready && problem != null -> Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag(CombineTags.ERROR))
                        !ready -> Waiting(mine)
                        step == "choose" -> ChooseBody(preview!!, name, keep) { picked = if (it == Keep.Mine) "mine" else "theirs" }
                        else -> ReviewBody(preview!!, name, expanded) { expanded = !expanded }
                    }
                    if (ready && problem != null) Text(problem, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag(CombineTags.ERROR))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        underWay -> Button(onClick = { onClose() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.LATER)) { Text("Close") }
                        !ready && problem != null -> {
                            OutlinedButton(onClick = { leave() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.LATER)) { Text("Later") }
                            Button(
                                onClick = {
                                    error = null
                                    scope.launch { error = withContext(Dispatchers.IO) { combine.review(deviceId) } }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.TRY_AGAIN),
                            ) { Text("Try again") }
                        }
                        !ready -> OutlinedButton(onClick = { leave() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.LATER)) { Text("Later") }
                        step == "choose" -> {
                            OutlinedButton(onClick = { leave() }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.LATER)) { Text("Later") }
                            Button(onClick = { step = "review" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.REVIEW)) { Text("Review") }
                        }
                        else -> {
                            OutlinedButton(onClick = { step = "choose" }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.BACK)) { Text("Back") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    error = null
                                    scope.launch {
                                        val e = withContext(Dispatchers.IO) { combine.start(deviceId, keep == Keep.Mine) }
                                        busy = false
                                        if (e != null) error = e else { started = true; onClose() }
                                    }
                                },
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.COMBINE),
                            ) { Text("Combine") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting(s: CombineState?) {
    Column(Modifier.testTag(CombineTags.WAIT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Looking at both libraries…", fontSize = 15.sp)
        }
        if (s != null && s.total > 0) Text("${count(s.done)} of ${count(s.total)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Nothing changes yet. This library stays usable.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CombineTags.WAIT_NOTE))
    }
}

@Composable
private fun ChooseBody(preview: app.keepfin.merge.CombineDryRun, name: String, keep: Keep, onPick: (Keep) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(combineQuestion(preview, name), fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.testTag(CombineTags.QUESTION))
        Text("Keep the name, spaces and settings of", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
        KeepRow("$name’s library", "The library on $name.", keep == Keep.Theirs, CombineTags.KEEP_THEIRS) { onPick(Keep.Theirs) }
        KeepRow("This phone’s library", "The library on this phone.", keep == Keep.Mine, CombineTags.KEEP_MINE) { onPick(Keep.Mine) }
    }
}

@Composable
private fun KeepRow(title: String, line: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column {
            Text(title, fontSize = 15.sp)
            if (line.isNotEmpty()) Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewBody(p: app.keepfin.merge.CombineDryRun, name: String, expanded: Boolean, onToggle: () -> Unit) {
    val picture = reviewPicture(p, name)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Your files stay where they are. Nothing is deleted.", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(CombineTags.PROMISE))
        ReviewFlow(p, name, picture.total)
        if (picture.tiles.isNotEmpty()) {
            FlowRow(Modifier.fillMaxWidth().testTag(CombineTags.TILES), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (tile in picture.tiles) ReviewTileView(tile)
            }
        }
        Column(Modifier.testTag(CombineTags.ALONG), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Comes along", fontSize = 13.sp, color = muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (chip in picture.along) ReviewChipView(chip)
            }
        }
        if (picture.check.isNotEmpty() || p.reviewTotal > 0) {
            Surface(shape = RoundedCornerShape(12.dp), color = Studio.colors.warning.copy(alpha = 0.14f), modifier = Modifier.fillMaxWidth().testTag(CombineTags.CHECK)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("To check · ${count(p.reviewTotal)}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    for (line in picture.check) Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = muted)
                    if (p.reviewTotal > 0) {
                        TextButton(
                            onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.DETAILS_TOGGLE)
                                .semantics { contentDescription = if (expanded) "Hide the details" else "Show the details (${count(p.reviewTotal)})" },
                        ) { Text(if (expanded) "Hide the details" else "Show the details (${count(p.reviewTotal)})") }
                        if (expanded) {
                            Column(Modifier.testTag(CombineTags.DETAILS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (row in p.review) Text(reviewEntry(row, name), fontSize = 13.sp, lineHeight = 18.sp, color = muted)
                                val more = (p.reviewTotal - p.review.size).coerceAtLeast(0)
                                if (more > 0) Text("and ${count(more)} more", fontSize = 13.sp, color = muted, modifier = Modifier.testTag(CombineTags.MORE))
                            }
                        }
                    }
                }
            }
        }
        Text("You can separate them again for ${daysPhrase(p.separateDays)}.", fontSize = 14.sp, color = muted, modifier = Modifier.testTag(CombineTags.DAYS))
    }
}

/** Before and after: this phone and the other library, flowing into one library. Narrow (a phone, large text) puts the result on its own row. */
@Composable
private fun ReviewFlow(p: app.keepfin.merge.CombineDryRun, name: String, total: Long?) {
    val wide = androidx.compose.ui.platform.LocalDensity.current.fontScale <= 1.15f
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().testTag(CombineTags.FLOW)) {
        val sideBySide = wide && maxWidth >= 420.dp
        val mine = @Composable { ReviewLib("This phone", p.items, CombineTags.LIB_MINE, Modifier) }
        val theirs = @Composable { ReviewLib(name, p.otherItems.takeIf { it >= 0 }, CombineTags.LIB_OTHER, Modifier) }
        val result = @Composable { ReviewLib("One library", total, CombineTags.LIB_RESULT, Modifier, result = true) }
        val op = @Composable { text: String -> Text(text, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "" }) }
        if (sideBySide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { mine() }; op("+"); Box(Modifier.weight(1f)) { theirs() }; op("→"); Box(Modifier.weight(1f)) { result() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { mine() }; op("+"); Box(Modifier.weight(1f)) { theirs() }
                }
                op("↓")
                Box(Modifier.fillMaxWidth()) { result() }
            }
        }
    }
}

@Composable
private fun ReviewLib(title: String, items: Long?, tag: String, modifier: Modifier, result: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val ink = if (result) colors.onPrimaryContainer else colors.onSurface
    Surface(
        modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) { contentDescription = "$title: " + (items?.let { plural(it, "item") } ?: "not known yet") }, shape = RoundedCornerShape(12.dp), color = if (result) colors.primaryContainer else colors.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(if (result) 1.5.dp else 1.dp, if (result) colors.primary else colors.outlineVariant),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, fontSize = 13.sp, color = if (result) colors.onPrimaryContainer else colors.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(items?.let { count(it) } ?: "…", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = ink, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
            Text(if (items == 1L) "item" else "items", fontSize = 12.sp, color = if (result) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewTileView(tile: ReviewTile) {
    val colors = MaterialTheme.colorScheme
    val icon = when (tile.key) { TileKey.Added -> Icons.Outlined.Add; TileKey.Linked -> Icons.Outlined.Check; TileKey.Twice -> Icons.Outlined.ContentCopy }
    Surface(shape = RoundedCornerShape(10.dp), color = colors.surfaceContainerHigh, modifier = Modifier.widthIn(min = 104.dp).testTag(CombineTags.tile(tile.key.name.lowercase())).semantics(mergeDescendants = true) { contentDescription = tile.sentence }) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = Studio.colors.accentText, modifier = Modifier.size(18.dp))
            Column {
                Text(count(tile.count), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"))
                Text(tile.label, fontSize = 12.sp, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReviewChipView(chip: ReviewChip) {
    val colors = MaterialTheme.colorScheme
    val icon = when (chip.icon) { "list" -> Icons.AutoMirrored.Outlined.QueueMusic; "layers" -> Icons.Outlined.Layers; "heart" -> Icons.Outlined.FavoriteBorder; else -> Icons.Outlined.PlayArrow }
    Surface(shape = RoundedCornerShape(50), color = colors.surfaceContainerHigh, border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(chip.text, fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
    }
}
// ---- Separate again --------------------------------------------------------------------------------------------------------------

/**
 * Separate libraries…: what goes back, whether the other device keeps copies of what this phone brought, and the unreachable case.
 * Nothing is deleted from this phone's folder.
 */
@Composable
fun SeparateDialog(name: String, combine: DeviceCombine, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SeparatePreview?>(null) }
    var checked by remember { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        preview = withContext(Dispatchers.IO) { combine.separatePreview() }
        checked = true
    }
    val p = preview
    val facts = p?.let { separateFacts(it, name) }
    BackHandler(enabled = !busy) { onClose() }
    Dialog(onDismissRequest = { if (!busy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag(CombineTags.SEPARATE), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Separate libraries", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag(CombineTags.ERROR)) }
                    if (facts == null) {
                        if (checked) Text("Keepfin could not check what goes back. Try again in a moment.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CombineTags.ERROR))
                        else Row(Modifier.testTag(CombineTags.SEP_CHECKING), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text("Checking what goes back…", fontSize = 15.sp)
                        }
                    } else {
                        facts.unreachable?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CombineTags.SEP_UNREACHABLE)) }
                        Text(facts.goesBack, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.testTag(CombineTags.SEP_BACK))
                        facts.addedSince?.let { Text(it, fontSize = 15.sp, lineHeight = 22.sp, modifier = Modifier.testTag(CombineTags.SEP_ADDED)) }
                        if (facts.held != null && facts.unreachable == null) {
                            Text(facts.held, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() }.testTag(CombineTags.SEP_HELD))
                            KeepRow(facts.keepLabel, "", !remove, CombineTags.SEP_KEEP) { remove = false }
                            KeepRow(facts.removeLabel, "", remove, CombineTags.SEP_REMOVE) { remove = true }
                        }
                        Text("Changes you made since combining are not brought back.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CombineTags.SEP_NOTE))
                        Text("Nothing is deleted from this phone's folder.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CombineTags.SEP_FOLDER))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onClose, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.SEP_CANCEL)) { Text("Cancel") }
                    Button(
                        enabled = facts != null && !busy,
                        onClick = {
                            busy = true
                            error = null
                            val anyway = p?.peerReachable == false
                            scope.launch {
                                val e = withContext(Dispatchers.IO) { combine.separate(keepOnPeer = !remove, anyway = anyway) }
                                busy = false
                                if (e != null) error = e else onClose()
                            }
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(CombineTags.SEP_GO),
                    ) { Text(if (facts?.unreachable != null) "Separate anyway" else "Separate") }
                }
            }
        }
    }
}

// ---- the card --------------------------------------------------------------------------------------------------------------------

/**
 * The combine on a device's card: a line with a progress bar while it runs (Pause, Cancel), a paused or stopped note (Resume, Cancel), and
 * when done "Combined · Separate again until <date>" with Separate libraries… and Keep combined on the opened card. Shown on a collapsed card
 * too, and after the app restarted (the state comes from the journal).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CombineCardBlock(
    deviceId: String, name: String, state: CombineState, expanded: Boolean, combine: DeviceCombine,
    onSeparate: () -> Unit, onReview: () -> Unit, modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf("") }
    val phase = phaseOf(state)
    if (phase == CombinePhase.Idle) return
    fun run(label: String, work: () -> String?) {
        busy = label
        error = null
        scope.launch {
            error = withContext(Dispatchers.IO) { runCatching(work).getOrElse { it.message ?: "That did not work." } }
            busy = ""
        }
    }
    val running = phase == CombinePhase.Running
    val looking = phase == CombinePhase.Looking
    val stopped = phase == CombinePhase.Paused || phase == CombinePhase.Failed
    val line = combineLine(state, name)
    Column(modifier.fillMaxWidth().testTag(CombineTags.card(deviceId)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(line, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().testTag(CombineTags.cardLine(deviceId)))
        if (running || phase == CombinePhase.Paused || phase == CombinePhase.Separating) {
            val fraction = if (state.total > 0) (state.done.toFloat() / state.total.toFloat()).coerceIn(0f, 1f) else null
            val description = "Combining libraries with $name"
            if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(CombineTags.cardProgress(deviceId)))
            else if (phase != CombinePhase.Paused) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(CombineTags.cardProgress(deviceId)))
            else LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = description }.testTag(CombineTags.cardProgress(deviceId)))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            if (phase == CombinePhase.Ready) {
                OutlinedButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardReview(deviceId)).semantics { contentDescription = "Review: combining with $name" }) { Text("Review") }
            }
            if (running) {
                OutlinedButton(
                    onClick = { run("pause") { combine.pause(); null } }, enabled = busy.isEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardPause(deviceId)).semantics { contentDescription = "Pause combining with $name" },
                ) { Text("Pause") }
            }
            if (stopped) {
                Button(
                    onClick = { run("resume") { combine.resume(); null } }, enabled = busy.isEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardResume(deviceId))
                        .semantics { contentDescription = "${if (phase == CombinePhase.Failed) "Try again" else "Resume"}: combining with $name" },
                ) { Text(if (phase == CombinePhase.Failed) "Try again" else "Resume") }
            }
            if (looking || phase == CombinePhase.Ready || running || stopped) {
                TextButton(
                    onClick = { run("cancel") { combine.cancel() } }, enabled = busy.isEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardCancel(deviceId)).semantics { contentDescription = "Cancel combining with $name" },
                ) { Text("Cancel") }
            }
            if (canSeparate(state) && expanded) {
                OutlinedButton(onClick = onSeparate, modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardSeparate(deviceId))) { Text("Separate libraries…") }
                TextButton(
                    onClick = { run("keep") { combine.keepCombined() } }, enabled = busy.isEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CombineTags.cardKeep(deviceId)),
                ) { Text("Keep combined") }
            }
        }
        error?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(CombineTags.cardError(deviceId))) }
    }
}
