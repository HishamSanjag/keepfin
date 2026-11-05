package app.keepfin.ui.jobs

import android.net.Uri
import app.keepfin.ui.components.MediaCover
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Link
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.MediaKind
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.ArtworkBadge
import app.keepfin.ui.components.LocalRowObserver
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import java.io.File

/** Callbacks shared by every row. Created once per screen so rows can skip recomposition. */
@Stable
class JobActions(
    /** The primary action of a finished row: plays it in Keepfin when [inApp], else opens it in another app. */
    val open: (Job) -> Unit,
    val share: (Job) -> Unit,
    val saveCopy: (Job) -> Unit,
    val showSource: (Job) -> Unit,
    val pause: (String) -> Unit,
    val resume: (String) -> Unit,
    val cancel: (String) -> Unit,
    val retry: (String) -> Unit,
    val retrySave: (String) -> Unit,
    val remove: (String) -> Unit,
    val delete: (Job) -> Unit,
    /** Takes one item of a collection off the list and out of history (a waiting retry is cancelled first). Never deletes media. */
    val removeMember: (String) -> Unit = remove,
    /** Plays the library item a job matched instead of downloading. */
    val playExisting: (Job) -> Unit = {},
    /** "Download anyway" for a job that completed as already in a space. */
    val downloadAnyway: (String) -> Unit = {},
    /** Display name of a space id, for "Saved to Music" and "Already in Music". */
    val spaceName: (String?) -> String? = { null },
    /** True when [open] plays in Keepfin's own player (library on); the menu then also offers [openExternal]. */
    val inApp: Boolean = false,
    val openExternal: (Job) -> Unit = open,
)

/**
 * One transfer. Takes the immutable [Job] snapshot, so Compose skips rows whose job is structurally equal
 * when another row's progress changes. Artwork receives only its own stable fields.
 */
@Composable
fun JobRow(job: Job, actions: JobActions, modifier: Modifier = Modifier, highlighted: Boolean = false, attention: Boolean = false) {
    LocalRowObserver.current?.let { observer -> SideEffect { observer(job.id) } }
    var showDetails by remember { mutableStateOf(false) }
    if (showDetails) ErrorDetailsDialog(job) { showDetails = false }
    val completed = job.status == JobStatus.COMPLETED
    val reduced = Studio.reducedMotion
    val highlight by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent,
        animationSpec = tween(if (reduced) 0 else 220), label = "highlight",
    )
    Column(
        modifier.fillMaxWidth().background(highlight)
            .clickable(enabled = completed && !job.filesRemoved, onClickLabel = if (job.existing != null || actions.inApp) "Play" else "Open") { if (job.existing != null) actions.playExisting(job) else actions.open(job) }
            .testTag(UiTags.job(job.id))
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f)) {
                JobArtwork(job.id, job.thumbnail, completed, job.publicUri, job.file, job.kind == MediaKind.AUDIO)
                Format.duration(job.duration)?.let { ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(job.provider ?: Format.host(job.url), job.label.takeIf { it.isNotBlank() })
                Text(
                    meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                job.match?.let { match ->
                    Text(app.keepfin.Restriction.matchCaption(match), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                Spacer(Modifier.height(2.dp))
                StatusLine(job, actions.spaceName(job.space))
            }
            // At most two visible actions: a finished row has Play/Open and the menu; a running row has Pause/Resume and the
            // menu (see TransferBlock). Everything else lives in the menu.
            if (!(job.status.active || job.status == JobStatus.PAUSED)) {
                if (completed && !job.filesRemoved && job.existing == null) {
                    val label = if (actions.inApp) "Play" else "Open"
                    IconButton(onClick = { actions.open(job) }, modifier = Modifier.testTag(UiTags.jobOpen(job.id))) {
                        Icon(if (actions.inApp) Icons.Outlined.PlayArrow else Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "$label ${job.title}")
                    }
                }
                JobMenu(job, actions)
            }
        }
        when {
            job.status.active || job.status == JobStatus.PAUSED -> TransferBlock(job, actions)
            job.status == JobStatus.FAILED -> ProblemBlock(
                job.error ?: "The download failed.", "Retry", Icons.Outlined.Refresh, retryTag = UiTags.jobRetry(job.id),
                details = { showDetails = true }, detailsTag = UiTags.jobDetails(job.id),
            ) { actions.retry(job.id) }
            attention && job.status == JobStatus.CANCELLED -> ProblemBlock(
                "Cancelled before it finished.", "Retry", Icons.Outlined.Refresh, warning = true, retryTag = UiTags.jobRetry(job.id),
            ) { actions.retry(job.id) }
            completed && job.filesRemoved -> ProblemBlock(
                "The saved file was deleted from your phone. Your download history stays.", "Remove from history", Icons.Outlined.DeleteOutline, warning = true,
                retryTag = UiTags.jobRetry(job.id),
            ) { actions.remove(job.id) }
            completed && job.existing != null -> ExistingBlock(job, actions)
            completed && job.saveError != null -> ProblemBlock(
                "Downloaded, but not saved to ${Format.savedFolder(job)}. ${job.saveError}", "Save again", Icons.Outlined.SaveAlt, warning = true,
                retryTag = UiTags.jobRetry(job.id), details = { showDetails = true }, detailsTag = UiTags.jobDetails(job.id),
            ) { actions.retrySave(job.id) }
        }
    }
}

@Composable
private fun JobArtwork(id: String, thumbnail: String?, completed: Boolean, publicUri: String?, file: String?, audio: Boolean) {
    // Saved videos show their own cover first (works offline): the one inside the file, else a frame; provider artwork is the fallback.
    val local: MediaCover? = remember(completed, publicUri, file, audio) {
        if (!completed || audio) null else (publicUri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: file?.let { Uri.fromFile(File(it)) })?.let(::MediaCover)
    }
    MediaThumb(
        primary = local ?: thumbnail, fallback = if (local != null) thumbnail else null, audio = audio,
        modifier = Modifier.testTag(UiTags.jobThumb(id)).fillMaxWidth().aspectRatio(16f / 9f),
    )
}

@Composable
private fun StatusLine(job: Job, spaceName: String?) {
    val colors = MaterialTheme.colorScheme
    when (job.status) {
        JobStatus.COMPLETED -> {
            val ok = job.saveError == null
            if (job.existing != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Studio.colors.success, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Already in ${spaceName ?: "your library"}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.testTag(UiTags.jobExisting(job.id)))
                }
                return
            }
            if (job.filesRemoved) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Studio.colors.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("File removed", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, modifier = Modifier.testTag("job-removed-${job.id}"))
                }
                return
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, contentDescription = null,
                    tint = if (ok) Studio.colors.success else Studio.colors.warning, modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                val size = Format.bytes(job.fileSize ?: job.totalBytes).takeIf { (job.fileSize ?: job.totalBytes) != null }
                val text = if (ok) listOfNotNull(size, "Saved to ${spaceName ?: Format.savedFolder(job)}").joinToString(" · ")
                else listOfNotNull(size, "Not saved yet").joinToString(" · ")
                Text(text, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        JobStatus.FAILED -> Text("Failed", style = MaterialTheme.typography.labelMedium, color = colors.error)
        JobStatus.CANCELLED -> Text("Cancelled", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        else -> Unit
    }
}

@Composable
private fun TransferBlock(job: Job, actions: JobActions) {
    val colors = MaterialTheme.colorScheme
    val paused = job.status == JobStatus.PAUSED
    val determinate = job.percent > 0f && (job.status == JobStatus.DOWNLOADING || paused)
    val percent = job.percent.coerceIn(0f, 100f)
    val retrying = retryStatus(job)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val barModifier = Modifier.fillMaxWidth().height(4.dp).semantics {
                stateDescription = if (determinate) "${percent.toInt()} percent" else Format.phase(job)
            }
            val track = colors.surfaceContainerHighest
            val tint = if (paused) colors.outline else colors.primary
            when {
                job.status == JobStatus.QUEUED -> LinearProgressIndicator(progress = { 0f }, modifier = barModifier, color = tint, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                determinate -> LinearProgressIndicator(progress = { percent / 100f }, modifier = barModifier, color = tint, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                paused -> LinearProgressIndicator(progress = { 0f }, modifier = barModifier, color = tint, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {})
                else -> LinearProgressIndicator(modifier = barModifier, color = tint, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp)
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                val left = buildList {
                    val done = job.downloadedBytes
                    val total = job.totalBytes ?: job.expectedSize
                    if (determinate && job.status == JobStatus.DOWNLOADING && job.phase == null) add("${percent.toInt()}%")
                    else if (retrying != null) add(retrying)
                    else { add(Format.phase(job)); if (determinate) add("${percent.toInt()}%") }
                    if (done != null && total != null && total > 0 && job.status != JobStatus.PROCESSING) add(Format.progressBytes(done, total))
                    else if (total != null && job.status == JobStatus.QUEUED) add("≈" + Format.bytes(total))
                }.joinToString(" · ")
                Text(left, style = MaterialTheme.typography.bodySmall.tabular(), color = if (retrying != null) colors.tertiary else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(UiTags.jobStatus(job.id)))
                if (job.status == JobStatus.DOWNLOADING) {
                    val right = listOfNotNull(Format.speed(job.speed), Format.eta(job.eta)).joinToString(" · ")
                    if (right.isNotEmpty()) Text(right, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (paused) {
            IconButton(onClick = { actions.resume(job.id) }) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume ${job.title}") }
        } else if (job.status == JobStatus.DOWNLOADING || job.status == JobStatus.QUEUED || job.status == JobStatus.PREPARING) {
            IconButton(onClick = { actions.pause(job.id) }) { Icon(Icons.Outlined.Pause, contentDescription = "Pause ${job.title}") }
        }
        // Cancel and the rest are in the menu: two visible actions at most.
        JobMenu(job, actions)
    }
}

/** The recording was already in the space, so nothing was downloaded. Play it, or download a second copy on purpose. */
@Composable
private fun ExistingBlock(job: Job, actions: JobActions) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { actions.playExisting(job) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
        }
        androidx.compose.material3.OutlinedButton(onClick = { actions.downloadAnyway(job.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.jobAnyway(job.id))) { Text("Download anyway") }
    }
}

@Composable
private fun ProblemBlock(
    message: String,
    action: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    warning: Boolean = false,
    retryTag: String? = null,
    details: (() -> Unit)? = null,
    detailsTag: String? = null,
    onAction: () -> Unit,
) {
    val tint = if (warning) Studio.colors.warning else MaterialTheme.colorScheme.error
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, end = 12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(if (warning) Icons.Outlined.WarningAmber else Icons.Outlined.ErrorOutline, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 1.dp).size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (details != null) TextButton(onClick = details, modifier = Modifier.heightIn(min = 48.dp).then(if (detailsTag != null) Modifier.testTag(detailsTag) else Modifier)) { Text("Error details") }
            FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).then(if (retryTag != null) Modifier.testTag(retryTag) else Modifier)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(action)
            }
        }
    }
}

@Composable
private fun JobMenu(job: Job, actions: JobActions) {
    var open by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    if (showDetails) ErrorDetailsDialog(job) { showDetails = false }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More actions for ${job.title}" }.testTag(UiTags.jobMenu(job.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color? = null, tag: String? = null, action: () -> Unit): @Composable () -> Unit = {
                DropdownMenuItem(
                    text = { Text(label, color = color ?: Color.Unspecified) },
                    leadingIcon = { Icon(icon, contentDescription = null, tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = { open = false; action() },
                    modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
                )
            }
            val terminal = !job.status.active && job.status != JobStatus.PAUSED
            if (job.status == JobStatus.COMPLETED && job.existing != null) {
                item("Play", Icons.Outlined.PlayArrow) { actions.playExisting(job) }()
                item("Download anyway", Icons.Outlined.Refresh) { actions.downloadAnyway(job.id) }()
            } else if (job.status == JobStatus.COMPLETED) {
                if (actions.inApp) {
                    item("Play", Icons.Outlined.PlayArrow) { actions.open(job) }()
                    item("Open in another app", Icons.AutoMirrored.Outlined.OpenInNew) { actions.openExternal(job) }()
                } else item("Open", Icons.AutoMirrored.Outlined.OpenInNew) { actions.open(job) }()
                item("Share", Icons.Outlined.Share) { actions.share(job) }()
                item("Save a copy to…", Icons.Outlined.SaveAlt) { actions.saveCopy(job) }()
                if (job.saveError != null) item("Save again", Icons.Outlined.Refresh) { actions.retrySave(job.id) }()
            }
            if (job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED) item("Retry", Icons.Outlined.Refresh) { actions.retry(job.id) }()
            if (job.status == JobStatus.FAILED) item("Error details", Icons.Outlined.Info, tag = UiTags.jobMenuDetails(job.id)) { showDetails = true }()
            item("Show source", Icons.Outlined.Link) { actions.showSource(job) }()
            if (!terminal) item("Cancel download", Icons.Outlined.Close) { actions.cancel(job.id) }()
            if (terminal || job.retryAt != null) item("Remove from history", Icons.Outlined.ClearAll, tag = UiTags.jobRemove(job.id)) { actions.remove(job.id) }()
            if (job.status == JobStatus.COMPLETED && job.existing == null) item("Delete file…", Icons.Outlined.DeleteOutline, MaterialTheme.colorScheme.error) { actions.delete(job) }()
        }
    }
}

/** Full failure text (tool names already scrubbed by the engine) with a Copy button, for support and bug reports. */
@Composable
internal fun ErrorDetailsDialog(job: Job, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = listOfNotNull(job.error, job.saveError, job.errorDetail?.takeIf { it != job.error }).distinct().joinToString("\n\n").ifBlank { "No further details were recorded." }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Error details") },
        text = {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(UiTags.errorDetails).verticalScroll(rememberScrollState()))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("Keepfin error details", text))
                Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Shorter tile for the Library grid. */
@Composable
fun JobTile(job: Job, actions: JobActions, modifier: Modifier = Modifier) {
    LocalRowObserver.current?.let { observer -> SideEffect { observer(job.id) } }
    val completed = job.status == JobStatus.COMPLETED
    Column(modifier.clickable(enabled = completed, onClickLabel = if (actions.inApp) "Play" else "Open") { actions.open(job) }.testTag(UiTags.job(job.id)).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            JobArtwork(job.id, job.thumbnail, completed, job.publicUri, job.file, job.kind == MediaKind.AUDIO)
            Format.duration(job.duration)?.let { ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    when (job.status) {
                        JobStatus.COMPLETED -> listOfNotNull(job.provider, job.fileSize?.let(Format::bytes)).joinToString(" · ")
                        else -> Format.phase(job)
                    },
                    style = MaterialTheme.typography.bodySmall.tabular(), maxLines = 1,
                    color = if (job.status == JobStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            JobMenu(job, actions)
        }
    }
}
