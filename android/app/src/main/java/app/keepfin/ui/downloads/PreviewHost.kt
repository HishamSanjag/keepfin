package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.MediaInfo
import app.keepfin.MusicVideoLookup
import app.keepfin.ui.Capture
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.FormatChoice
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.devices.DownloadTarget
import app.keepfin.ui.devices.SendJob
import app.keepfin.ui.devices.rememberDownloadTargets
import app.keepfin.ui.devices.sendDownloads
import app.keepfin.ui.isAudio
import app.keepfin.ui.player.MusicVideoLogic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The download preview (dev-docs/plans/DOWNLOAD-PREVIEW.md), hosted over Downloads: one full-height sheet that reads a link, shows its result
 * and carries the choices, in every case (one link, a song you have, a collection, several links, a link that could not be read). It is
 * hosted here, outside the list, so scrolling the list never closes it. Closing it (swipe, Back, Close) only hides it: the link and the
 * choices are kept and a summary row reopens it; Clear is the only thing that discards it.
 */
@Composable
fun PreviewHost(vm: StudioViewModel) {
    val capture = vm.capture
    val review = vm.collection?.takeIf { vm.collectionOpen }
    val showing = review != null || (vm.reviewOpen && capture !is Capture.Empty)
    if (!showing) return
    val targets = rememberDownloadTargets()
    val scope = rememberCoroutineScope()
    // Decided when called: the sheet keeps the first lambda it gets for swipe and Back, and it opens while the link is still read
    PreviewSheet(onClose = { if (vm.collectionOpen) vm.collectionOpen = false else if (vm.capture is Capture.Inspecting) vm.cancelInspection() else vm.closeReview() }) { close ->
        when {
            review != null -> Column(Modifier.fillMaxSize().testTag(UiTags.COLLECTION)) { ListPreview(vm, review, targets, close) }
            capture is Capture.Single && capture.media.gallery != null -> PhotoPreview(
                capture.media, vm.choice, { vm.choice = it }, vm.saveToFor(capture.media.webpageUrl ?: capture.media.url, capture.media.uploader), vm.adding, { vm.download() }, close,
                targets, { target -> scope.sendTo(vm, target, listOf(SendJob(capture.media.webpageUrl ?: capture.media.url, vm.choice.request(capture.media))), vm.options, capture.media.title) { vm.clearDraft() } },
            )
            capture is Capture.Single -> SinglePreview(vm, capture.media, targets, close)
            capture is Capture.Batch -> LinksPreview(vm, capture, targets, close)
            capture is Capture.Inspecting -> ReadingMoment(ReviewReading(capture.urls, null, vm::cancelInspection, vm::inspect) {}, close)
            capture is Capture.Failed -> ReadingMoment(
                ReviewReading(listOf(capture.url), capture.message, vm::closeReview, vm::inspect, capture.signInSite) { vm.signInTo(capture.signInKey) }, close,
            )
        }
    }
}

/** Sends [jobs] to another device and says what came of it; [done] runs once they are on their way. */
private fun CoroutineScope.sendTo(vm: StudioViewModel, target: DownloadTarget, jobs: List<SendJob>, options: app.keepfin.DownloadOptions, what: String, done: () -> Unit) {
    launch {
        try {
            withContext(Dispatchers.IO) { sendDownloads(target, jobs, options) }
            done()
            vm.say("Sent to ${target.name} · $what")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            vm.say(StudioViewModel.friendly(e, "Could not send this download."))
        }
    }
}

@Composable
private fun ColumnScope.SinglePreview(vm: StudioViewModel, media: MediaInfo, targets: List<DownloadTarget>, close: () -> Unit) {
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val choice = vm.choice
    val link = media.webpageUrl ?: media.url
    // Both is judged as if the song were kept as audio, so it can be offered (or explained) while Video is chosen
    val asAudio = choice.copy(audioMode = true).request(media)
    val lookOffered = vm.offersMusicVideo(media, asAudio)
    val linkOffered = vm.songVideo.offersLinkVideo(media, asAudio)
    LaunchedEffect(media.url, lookOffered) { if (lookOffered) vm.lookForCaptureVideo(media) }
    val look = vm.captureVideo
    val both = when {
        lookOffered -> when (val found = look?.lookup) {
            null -> BothOffer.Looking
            is MusicVideoLookup.Found -> found.video.let { video ->
                BothOffer.Found(video.title, video.thumbnail, listOfNotNull(video.channel.takeIf { it.isNotBlank() }, MusicVideoLogic.views(video.views), MusicVideoLogic.length(video.durationSec)).joinToString(" · "), own = false)
            }
            else -> BothOffer.Missing
        }
        linkOffered -> BothOffer.Found(media.title, media.thumbnail, null, own = true)
        else -> BothOffer.Off
    }
    val bothOn = if (lookOffered) look?.keep == true && look.lookup is MusicVideoLookup.Found else linkOffered && vm.songVideo.keepLink
    // a video of a song you have joins that song: it is not "already in" the library
    val joinsSong = !choice.audioMode && vm.songVideo.captureSong != null
    val anyway = vm.captureExisting != null && !joinsSong
    val ui = SingleUi(
        media, choice, { vm.choice = it }, both, bothOn, { on -> if (lookOffered) vm.keepCaptureVideo(on) else if (linkOffered) vm.songVideo.chooseKeepLink(on) },
        songOfLink = vm.songVideo.captureSong, existingIn = vm.captureExisting?.takeIf { !joinsSong }?.let { vm.spaceName(it.space) ?: "your library" },
        onPlayExisting = { vm.captureExisting?.let(vm::playExisting) }, keptAsSongIn = vm.keptAsSongIn(media), onOpenPlaylist = vm.collections::openPlaylist,
    )
    PreviewLayout(
        header = PreviewHeader(cover = { SingleCover(media) }, title = media.title, facts = factsOf(media)),
        onClose = close, mainLabel = "What to save",
        extras = PreviewExtras(ExtrasModel.changed(vm.options, settings.options)) {
            ExtrasGroups(vm.options, { vm.options = it }, { vm.updateDefaultOptions { _ -> it }; vm.say("Saved as default options") }, media, video = media.video.isNotEmpty() && !choice.audioMode, cut = true)
        },
        footer = {
            PreviewFooter(
                saveTo = vm.saveToFor(link, media.uploader), playlist = rememberPlaylistUi(vm, vm.playlistTo) { vm.playlistTo = it }, targets = targets,
                onDownloadOn = { scope.sendTo(vm, it, listOf(SendJob(link, choice.request(media), bothOn)), vm.options, media.title) { vm.clearDraft() } },
                label = (if (anyway) "Download anyway" else "Download") + " · " + (choiceSize(media, choice) ?: "size unknown"),
                onDownload = { vm.download(anyway = anyway) }, adding = vm.adding,
            )
        },
    ) { SingleMain(ui) }
}

@Composable
private fun ColumnScope.ListPreview(vm: StudioViewModel, review: CollectionReview, targets: List<DownloadTarget>, close: () -> Unit) {
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val targetName = if (settings.library.enabled) vm.spaceName(vm.collections.collectionSpace(review)) else null
    val count = review.selectedCount
    PreviewLayout(
        header = listHeader(vm, review), onClose = close,
        mainLabel = (if (review.music) "Songs" else "Videos") + if (review.entries.isNotEmpty()) " · ${review.entries.size}" else "",
        extras = PreviewExtras(ExtrasModel.changed(review.options, settings.options, queueChanged = !review.keepTogether)) {
            ExtrasGroups(
                review.options, { review.options = it }, { vm.updateDefaultOptions { _ -> it }; vm.say("Saved as default options") },
                video = !review.preset.isAudio(), queue = QueueChoice(review.keepTogether, vm.collections::chooseKeepTogether),
            )
        },
        footer = {
            PreviewFooter(
                saveTo = if (vm.libraryEnabled) vm.saveToForCollection(review) else null, playlist = rememberPlaylistUi(vm, review.playlist) { review.playlist = it }, targets = targets,
                onDownloadOn = { target ->
                    val request = FormatChoice.forPreset(review.preset)
                    val withVideos = vm.collections.offersCollectionVideos(review) && review.preset.isAudio() && review.findVideo
                    scope.sendTo(vm, target, review.entries.filter { review.selected[it.url] == true }.map { SendJob(it.url, request, withVideos) }, review.options, "$count ${if (count == 1) "item" else "items"}") { vm.collections.closeCollection() }
                },
                label = if (count > 0) "Download $count" else "Select items to download", onDownload = vm.collections::downloadCollection,
                enabled = count > 0, adding = review.adding, tag = UiTags.COLLECTION_DOWNLOAD,
            )
        },
    ) { ListMain(vm, review, targetName) }
}

@Composable
private fun ColumnScope.LinksPreview(vm: StudioViewModel, state: Capture.Batch, targets: List<DownloadTarget>, close: () -> Unit) {
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val ready = state.items.mapNotNull { it.media }
    PreviewLayout(
        header = linksHeader(state), onClose = close, mainLabel = "Links · ${state.items.size}",
        extras = PreviewExtras(ExtrasModel.changed(vm.options, settings.options)) {
            ExtrasGroups(vm.options, { vm.options = it }, { vm.updateDefaultOptions { _ -> it }; vm.say("Saved as default options") }, video = !vm.batchPreset.isAudio())
        },
        footer = {
            PreviewFooter(
                saveTo = vm.saveToForBatch(ready), playlist = rememberPlaylistUi(vm, vm.playlistTo) { vm.playlistTo = it }, targets = targets,
                onDownloadOn = { target ->
                    val request = FormatChoice.forPreset(vm.batchPreset)
                    scope.sendTo(vm, target, ready.map { SendJob(it.webpageUrl ?: it.url, request) }, vm.options, "${ready.size} ${if (ready.size == 1) "link" else "links"}") { vm.clearDraft() }
                },
                label = if (ready.size == 1) "Download 1 link" else "Download ${ready.size} links", onDownload = vm::downloadBatch,
                enabled = ready.isNotEmpty(), adding = vm.adding,
            )
        },
    ) { LinksMain(vm, state) }
}
