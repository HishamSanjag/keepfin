package app.keepfin.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.keepfin.LibraryChapter
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import app.keepfin.LibraryItem
import app.keepfin.SubtitleFile
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.LibraryViews
import app.keepfin.ui.library.VideoRequest
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private enum class Aspect(val label: String, val ratio: Float?) { FIT("Fit", null), FILL("Fill", null), ZOOM("Zoom", null), R16_9("16:9", 16f / 9f), R4_3("4:3", 4f / 3f) }

private tailrec fun Context.findActivity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.findActivity(); else -> null }

/**
 * Full-screen video. The Media3 controller from [StudioViewModel.player] drives a Compose surface; the controls are Keepfin's own:
 * tap to show or hide, double-tap to skip 10 s, brightness on the left edge, volume on the right, captions (files next to the video
 * with delay and size, or tracks inside it), audio tracks, aspect modes, A-B loop, rotation, picture-in-picture and Up next.
 * The video pauses when this screen leaves, unless picture-in-picture is on.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoPlayerScreen(vm: StudioViewModel, request: VideoRequest) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val item: LibraryItem? = app.keepfin.ui.library.rememberItem(vm.lib.browse, request.id).value
    val port = vm.player
    val ui by port.ui.collectAsStateWithLifecycle()
    val tracks by port.tracks.collectAsStateWithLifecycle()
    val current = ui.current
    val title = item?.title ?: current?.title.orEmpty()

    var controls by rememberSaveable { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var aspect by rememberSaveable { mutableStateOf(Aspect.FIT) }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var rotationLocked by rememberSaveable { mutableStateOf(false) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    var skip by remember { mutableStateOf<SkipRun?>(null) }
    var skipTick by remember { mutableIntStateOf(0) }
    var holding by remember { mutableStateOf(false) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var lockHint by remember { mutableStateOf(false) }
    var lockTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var announce by remember { mutableStateOf("") }
    var pendingAnnounce by remember { mutableStateOf("") }
    var announceTick by remember { mutableIntStateOf(0) }
    val lastSpeed = remember { floatArrayOf(-1f) }
    val heldFrom = remember { floatArrayOf(1f) }
    var loopA by rememberSaveable { mutableLongStateOf(-1L) }
    var loopB by rememberSaveable { mutableLongStateOf(-1L) }
    var resumePrompt by rememberSaveable(request.id) { mutableStateOf(request.resumed) }
    var captionChoice by rememberSaveable(request.id) { mutableStateOf<String?>(null) }
    var captionDelay by rememberSaveable { mutableLongStateOf(0L) }
    var cancelledUpNext by rememberSaveable(request.id) { mutableStateOf(false) }
    // the music video was asked for but the service made the song (the file is not on this phone)
    LaunchedEffect(request.musicVideo, ui.current?.resolved, ui.current?.musicVideo) {
        // a moment's grace: right after a switch the controller still shows the song until the service has made the video item
        if (!request.musicVideo) return@LaunchedEffect
        delay(1_000)
        val now = port.ui.value.current
        if (now != null && now.resolved && !now.musicVideo) vm.musicVideoMissing()
    }
    val autoplay = vm.prefs.videoAutoplay
    val ctx = request.context

    // One polite announcement per change, once it has settled (a drag or a run of taps says its last value only).
    fun show(h: Hud, spoken: String) { hud = h; hudTick++; pendingAnnounce = spoken; announceTick++ }
    fun showNote(text: String) = show(Hud.Note(text), text)
    LaunchedEffect(announceTick) { if (announceTick > 0) { delay(600); announce = pendingAnnounce } }

    // Immersive, sensor orientation and brightness are this screen's alone; leaving restores them and pauses the video.
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            // a switch to the song continues playing there; leaving any other way pauses
            if (activity?.isInPictureInPictureMode != true && !vm.sideSwitching) port.pause()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { port.pause() }

    // Picture-in-picture: on demand, and automatically when the user leaves to the home screen while playing.
    fun pipParams(auto: Boolean): PictureInPictureParams {
        val w = tracks.videoWidth.takeIf { it > 0 } ?: 16
        val h = tracks.videoHeight.takeIf { it > 0 } ?: 9
        val ratio = (w.toFloat() / h).coerceIn(0.5f, 2.2f)
        return PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).apply { if (Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(auto) }.build()
    }
    val pipSupported = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    fun enterPip() { if (pipSupported) try { activity?.enterPictureInPictureMode(pipParams(false)) } catch (_: Exception) { vm.say("Picture-in-picture isn't available right now.") } }
    DisposableEffect(ui.playing, tracks.videoWidth, tracks.videoHeight) {
        val component = activity as? ComponentActivity
        val leave = Runnable { if (ui.playing) enterPip() }
        // a window that refuses picture-in-picture (some multi-window modes, a host without it) throws: the player goes on without it
        if (pipSupported && Build.VERSION.SDK_INT >= 31) runCatching { activity?.setPictureInPictureParams(pipParams(ui.playing)) }
        else if (pipSupported) component?.addOnUserLeaveHintListener(leave)
        val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        component?.addOnPictureInPictureModeChangedListener(pipListener)
        onDispose {
            if (pipSupported && Build.VERSION.SDK_INT >= 31) runCatching { activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()) }
            else component?.removeOnUserLeaveHintListener(leave)
            component?.removeOnPictureInPictureModeChangedListener(pipListener)
        }
    }

    LaunchedEffect(controls, ui.playing, poke, sheet, scrubbing) { if (controls && ui.playing && sheet == null && !scrubbing) { delay(3500); controls = false } }
    LaunchedEffect(hudTick, holding) { if (hud != null && !holding) { delay(900); hud = null } }
    LaunchedEffect(skipTick) { if (skip != null) { delay(VideoLogic.SKIP_WINDOW_MS); skip = null } }
    LaunchedEffect(lockTick) { if (lockHint) { delay(3000); lockHint = false } }
    // a speed chosen in the sheet says so; the hold and its release stay quiet (they set lastSpeed first)
    LaunchedEffect(ui.speed) {
        if (lastSpeed[0] >= 0f && ui.speed != lastSpeed[0] && !holding) showNote(VideoLogic.speedPill(ui.speed))
        lastSpeed[0] = ui.speed
    }
    // leaving while the 2x hold is down puts the speed back
    DisposableEffect(Unit) { onDispose { if (holding) port.setSpeed(heldFrom[0]) } }
    LaunchedEffect(resumePrompt) { if (resumePrompt) { delay(7000); resumePrompt = false } }
    LaunchedEffect(loopA, loopB) {
        if (loopA >= 0 && loopB > loopA) port.position.collect { if (it.positionMs >= loopB) port.seekTo(loopA) }
    }

    // Chapters: read from the library when the screen opens (the lists do not carry them), cleaned the way the Windows player does.
    val chapters by produceState(emptyList<LibraryChapter>(), request.id) {
        value = withContext(Dispatchers.IO) { runCatching { vm.lib.chapters(request.id) }.getOrDefault(emptyList()) }
            .filter { it.start.isFinite() && it.start >= 0.0 }.sortedBy { it.start }.distinctBy { it.start }
    }

    // Take screenshot: the frame comes from the video surface; it is announced only after the picture was written.
    fun takeScreenshot() {
        sheet = null
        val surface = FrameCapture.findSurface(view)
        val at = port.position.value.positionMs
        scope.launch {
            val bitmap = surface?.let { runCatching { FrameCapture.capture(it) }.getOrNull() }
            if (bitmap == null) { vm.say("Couldn't capture this frame."); return@launch }
            val saved = withContext(Dispatchers.IO) { runCatching { FrameCapture.save(context, bitmap, FrameCapture.fileName(title, at)) } }
            bitmap.recycle()
            val uri = saved.getOrNull()
            if (uri == null) vm.say("Couldn't save this frame to ${FrameCapture.FOLDER}.")
            else vm.say("Frame saved to ${FrameCapture.FOLDER}", "Copy image") { if (FrameCapture.copy(context, uri)) vm.say("Image copied") else vm.say("Couldn't copy the image.") }
        }
    }

    // Captions.
    val files by produceState(emptyList<SubtitleFile>(), request.id) { value = withContext(Dispatchers.IO) { runCatching { vm.lib.subtitles(request.id) }.getOrDefault(emptyList()) } }
    val fileLabels = remember(files) { files.map { it.label }.toSet() }
    val embedded = remember(tracks.text, fileLabels) { tracks.text.filter { it.label !in fileLabels } }
    LaunchedEffect(files, vm.prefs.captionsOn) {
        if (captionChoice == null) captionChoice = if (vm.prefs.captionsOn && files.isNotEmpty()) "file:${files.first().uri}" else "off"
    }
    val chosenFile = files.firstOrNull { "file:${it.uri}" == captionChoice }
    val cues by produceState(emptyList<SubtitleCue>(), chosenFile?.uri) {
        value = if (chosenFile == null) emptyList() else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(chosenFile.uri))?.use { input -> SubtitleCues.parse(input.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8), chosenFile.mimeType + chosenFile.label) }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(captionChoice, embedded) {
        // Only an embedded choice lets the player render text itself; files are drawn by Keepfin so they can be delayed.
        val choice = captionChoice
        if (choice != null && choice.startsWith("track:")) port.selectText(choice.removePrefix("track:")) else if (choice != null) port.selectText(null)
    }

    // Previous and next: the list this video was opened from; a video opened alone falls back to its folder in natural title order.
    val inContext = ctx.size > 1
    val nextCtx = app.keepfin.ui.library.rememberItem(vm.lib.browse, if (inContext) LibraryViews.neighbourInContext(ctx, request.id, 1) else null).value
    val prevCtx = app.keepfin.ui.library.rememberItem(vm.lib.browse, if (inContext) LibraryViews.neighbourInContext(ctx, request.id, -1) else null).value
    val folder = app.keepfin.ui.library.rememberBrowsed<Pair<LibraryItem?, LibraryItem?>>(vm.lib.browse, item?.id to inContext, null to null) { browse ->
        val now = item
        if (now == null || inContext) null to null else {
            val siblings = browse.top(app.keepfin.ItemQuery(spaces = listOf(now.space), kinds = setOf(now.kind), folder = now.folder.trim('/')), 3000)
            LibraryViews.previousInFolder(now, siblings) to LibraryViews.nextInFolder(now, siblings)
        }
    }.value
    // a song's music video is the other side of the song: the queue moves on, not the folder (no Up next card, no countdown)
    // (a video kept as a song opened from the full player's picture does the same: the playing queue moves on, not the folder)
    val queueNav = request.musicVideo || request.fromPlayer
    val up: LibraryItem? = if (queueNav) null else if (inContext) nextCtx else folder.second
    val before: LibraryItem? = if (queueNav) null else if (inContext) prevCtx else folder.first
    // "End of video" in the sleep timer also stops Autoplay: remembered from when it was set, since it may clear as the video ends
    var sleepAtEnd by remember(request.id) { mutableStateOf(false) }
    LaunchedEffect(request.id) { port.sleepTimer.collect { if (it.active && it.endOfTrack) sleepAtEnd = true else if (!port.ui.value.ended) sleepAtEnd = false } }
    val counting = autoplay && !sleepAtEnd
    var countdown by remember(request.id, ui.ended) { mutableIntStateOf(VideoLogic.COUNTDOWN_SECONDS) }
    LaunchedEffect(ui.ended, cancelledUpNext, up, counting) {
        if (ui.ended && !cancelledUpNext && up != null && counting) {
            while (countdown > 0) { delay(1000); countdown-- }
            vm.playVideo(up, context = ctx)
        }
    }

    // Preview frames for the seek bar: from the file on this phone only, opened on the first scrub, freed when the screen leaves.
    val frameAspect = item?.takeIf { !request.musicVideo }?.let { i -> val w = i.width; val h = i.height; if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else null } ?: (16f / 9f)
    val musicVideoUri by produceState<String?>(null, request.id, request.musicVideo) { value = if (request.musicVideo) withContext(Dispatchers.IO) { runCatching { vm.lib.musicVideoUri(request.id) }.getOrNull() } else null }
    val thumbSource = if (request.musicVideo) musicVideoUri else item?.takeIf { !it.remote }?.uri
    val thumbs = remember(thumbSource, frameAspect) {
        VideoThumbnails(context, thumbSource?.let(Uri::parse)?.takeIf { it.scheme == "content" || it.scheme == "file" }, frameAspect)
    }
    DisposableEffect(thumbs) { onDispose { thumbs.release() } }

    fun skipBy(forward: Boolean) {
        port.seekBy(if (forward) VideoLogic.SKIP_STEP_MS else -VideoLogic.SKIP_STEP_MS)
        val run = VideoLogic.accumulate(skip, forward, System.currentTimeMillis())
        skip = run; skipTick++
        pendingAnnounce = VideoLogic.skipAnnouncement(run); announceTick++
    }
    fun pinch(out: Boolean) {
        if (out && aspect != Aspect.ZOOM) { aspect = Aspect.ZOOM; showNote("Zoomed to fill") }
        else if (!out && aspect != Aspect.FIT) { aspect = Aspect.FIT; showNote("Original") }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).testTag(UiTags.VIDEO_PLAYER)) {
        val player = port.player
        val scale = when (aspect) { Aspect.FIT -> ContentScale.Fit; Aspect.ZOOM -> ContentScale.Crop; else -> ContentScale.FillBounds }
        if (player != null) {
            val frame = if (aspect.ratio != null) Modifier.align(Alignment.Center).aspectRatio(aspect.ratio!!) else Modifier.fillMaxSize()
            ContentFrame(player, frame, SURFACE_TYPE_SURFACE_VIEW, scale, false) { Box(Modifier.fillMaxSize().background(Color.Black)) }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Preview only: this item has no video file.", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodyMedium)
        }

        // Captions: embedded cues from the player, and Keepfin's own file captions.
        if (player != null && captionChoice?.startsWith("track:") == true) EmbeddedCaptions(player, vm.prefs.subtitleScale, Modifier.fillMaxSize())
        if (chosenFile != null) FileCaptions(vm, cues, captionDelay, vm.prefs.subtitleScale, controls && !locked && !inPip)

        // Gestures: tap toggles the controls (and keeps skipping within a second of a double-tap), double-tap skips 10 s, hold plays at 2x,
        // two fingers zoom, the outer thirds change brightness and volume. Locked, only a tap is heard: it shows the unlock button.
        val gestures = Modifier.fillMaxSize()
        if (!inPip) Box(
            if (locked) gestures
                .pointerInput(Unit) { detectTapGestures(onTap = { lockHint = true; lockTick++ }) }
                .semantics { contentDescription = "Screen locked"; onClick("Show the unlock button") { lockHint = true; lockTick++; true } }
            else gestures
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var zoom = 1f
                        var decided = false
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                zoom *= event.calculateZoom()
                                event.changes.forEach { it.consume() }
                                if (!decided && zoom > 1.1f) { decided = true; pinch(true) } else if (!decided && zoom < 0.9f) { decided = true; pinch(false) }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            tryAwaitRelease()
                            if (holding) { holding = false; hud = null; lastSpeed[0] = heldFrom[0]; port.setSpeed(heldFrom[0]) }
                        },
                        onLongPress = {
                            heldFrom[0] = port.ui.value.speed; lastSpeed[0] = 2f
                            port.setSpeed(2f); holding = true
                            show(Hud.Note("2×"), "Speed 2×")
                        },
                        onTap = { offset ->
                            val forward = offset.x > size.width / 2
                            if (VideoLogic.continues(skip, forward, System.currentTimeMillis())) skipBy(forward) else { controls = !controls; poke++ }
                        },
                        // inside a run every tap counts (YouTube), so a quick tap-tap there adds two skips, not one
                        onDoubleTap = { offset ->
                            val forward = offset.x > size.width / 2
                            if (VideoLogic.continues(skip, forward, System.currentTimeMillis())) skipBy(forward)
                            skipBy(forward)
                        },
                    )
                }
                .pointerInput(Unit) {
                    var side = 0
                    var level = 0f
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            side = if (offset.x < size.width * .33f) -1 else if (offset.x > size.width * .67f) 1 else 0
                            val window = activity?.window
                            level = if (side < 0) (window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: systemBrightness(context)) else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        },
                        onDragEnd = { side = 0 }, onDragCancel = { side = 0 },
                        onVerticalDrag = { change, dy ->
                            if (side == 0) return@detectVerticalDragGestures
                            change.consume()
                            level = (level - dy / (size.height * .8f)).coerceIn(0f, 1f)
                            if (side < 0) {
                                activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = level.coerceAtLeast(0.02f) } }
                                val label = "Brightness ${(level * 100).toInt()}%"
                                show(Hud.Level(label, level, false), label)
                            } else {
                                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).toInt(), 0)
                                val label = "Volume ${(level * 100).toInt()}%"
                                show(Hud.Level(label, level, true), label)
                            }
                        },
                    )
                },
        )

        if (ui.buffering || (current == null && ui.error == null)) CircularProgressIndicator(Modifier.align(Alignment.Center).size(40.dp), color = Color.White, strokeWidth = 3.dp)

        if (!inPip) {
            hud?.let { HudView(it, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp)) }
            skip?.let { SkipBubble(it, Modifier.align(if (it.forward) Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 24.dp)) }
            AnnounceRegion(announce, Modifier.align(Alignment.TopCenter))
            if (locked && lockHint) LockedPill({ locked = false; lockHint = false; controls = true; poke++ }, Modifier.align(Alignment.Center))
            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Controls(
                    vm = vm, title = title, ui = ui, chapters = chapters, pipSupported = pipSupported, captionsOn = captionChoice != null && captionChoice != "off", landscape = landscape,
                    thumbs = thumbs, thumbAspect = frameAspect, autoplay = autoplay, several = queueNav || inContext || up != null || before != null,
                    hasPrevious = if (queueNav) ui.hasPrevious else before != null, hasNext = if (queueNav) ui.hasNext else up != null, musicVideo = request.musicVideo,
                    onBack = vm::closeVideo, onPip = { enterPip() }, onMore = { sheet = "more" }, onSpeed = { sheet = "speed" },
                    onSong = item?.takeIf { !request.musicVideo && vm.isVideoSong(it) }?.let { song -> { vm.listenToVideoSong(song) } },
                    onCaptions = { sheet = "captions" }, onChapters = { sheet = "chapters" }, onScrub = { scrubbing = it },
                    onAutoplay = vm.prefs::chooseVideoAutoplay,
                    onPrevious = { if (queueNav) port.previousItem() else before?.let { vm.playVideo(it, context = ctx) } },
                    onNext = { if (queueNav) port.next() else up?.let { vm.playVideo(it, context = ctx) } },
                    onRotate = {
                        landscape = !landscape
                        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    },
                    onPoke = { poke++ },
                )
            }
            if (resumePrompt && item != null) ResumeCard(request.startMs / 1000.0, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (controls && !locked) 150.dp else 24.dp), onDismiss = { resumePrompt = false }) {
                resumePrompt = false; vm.playVideo(item, startOver = true, context = ctx)
            }
            if (ui.ended && up != null && !cancelledUpNext) UpNextCard(up.title, if (counting) countdown else null, { cancelledUpNext = true }, { vm.playVideo(up, context = ctx) }, Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).padding(bottom = 96.dp))
            ui.error?.let { message -> ErrorCard(message, item, vm, Modifier.align(Alignment.Center)) }
        }
    }

    when (sheet) {
        "speed" -> SpeedSheet(port, ui.speed, VideoLogic.SPEEDS) { sheet = null }
        "chapters" -> ChaptersSheet(vm, chapters) { sheet = null }
        "sleep" -> SleepSheet(port, "End of video", "Playback pauses when this video ends.") { sheet = null }
        "captions" -> CaptionsSheet(
            files = files, embedded = embedded, choice = captionChoice ?: "off", delay = captionDelay, scale = vm.prefs.subtitleScale,
            onChoice = { captionChoice = it; vm.prefs.chooseCaptions(it != "off") }, onDelay = { captionDelay = it }, onScale = vm.prefs::chooseSubtitleScale, onDismiss = { sheet = null },
        )
        "more" -> MoreSheet(
            vm = vm, ui = ui, tracks = tracks, item = item, onScreenshot = ::takeScreenshot, aspect = aspect, onAspect = { aspect = it }, loopA = loopA, loopB = loopB,
            onLoop = { a, b -> loopA = a; loopB = b }, rotationLocked = rotationLocked,
            onLock = { rotationLocked = it; activity?.requestedOrientation = if (it) ActivityInfo.SCREEN_ORIENTATION_LOCKED else if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR },
            onLockScreen = { sheet = null; locked = true; controls = false; lockHint = false },
            onSleep = { sheet = "sleep" },
            onDismiss = { sheet = null },
        )
    }
}

private fun systemBrightness(context: Context): Float = try {
    android.provider.Settings.System.getInt(context.contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
} catch (_: Exception) { 0.5f }

@Composable
private fun EmbeddedCaptions(player: Player, scale: Float, modifier: Modifier) {
    val holder = remember { arrayOfNulls<SubtitleView>(1) }
    AndroidView(
        factory = { context -> SubtitleView(context).also { holder[0] = it; it.setCues(player.currentCues.cues) } },
        update = { it.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale) },
        modifier = modifier,
    )
    DisposableEffect(player) {
        val listener = object : Player.Listener { override fun onCues(cueGroup: CueGroup) { holder[0]?.setCues(cueGroup.cues) } }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
}

@Composable
private fun FileCaptions(vm: StudioViewModel, cues: List<SubtitleCue>, delayMs: Long, scale: Float, controlsShown: Boolean) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    val text = SubtitleCues.at(cues, position.positionMs, delayMs) ?: return
    Box(Modifier.fillMaxSize().padding(bottom = if (controlsShown) 120.dp else 36.dp, start = 24.dp, end = 24.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text, color = Color.White, textAlign = TextAlign.Center, fontSize = (18f * scale).sp, lineHeight = (24f * scale).sp,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .62f)).padding(horizontal = 10.dp, vertical = 4.dp).testTag("video-caption"),
        )
    }
}

@Composable
private fun Controls(
    vm: StudioViewModel,
    title: String,
    ui: PlayerUi,
    chapters: List<LibraryChapter>,
    pipSupported: Boolean,
    captionsOn: Boolean,
    landscape: Boolean,
    thumbs: VideoThumbnails,
    thumbAspect: Float,
    autoplay: Boolean,
    several: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    musicVideo: Boolean,
    onBack: () -> Unit,
    onPip: () -> Unit,
    /** A video kept as a song: Song goes back to the music player with the same file (null for any other video). */
    onSong: (() -> Unit)? = null,
    onMore: () -> Unit,
    onSpeed: () -> Unit,
    onCaptions: () -> Unit,
    onChapters: () -> Unit,
    onScrub: (Boolean) -> Unit,
    onAutoplay: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRotate: () -> Unit,
    onPoke: () -> Unit,
) {
    val port = vm.player
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.3f).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .7f), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.38f).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .75f)))))
        Row(Modifier.align(Alignment.TopStart).fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag("video-back")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close the video", tint = Color.White) }
            Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            if (musicVideo) SongVideoSwitch(video = true, onChange = { vm.chooseSide(it) }, onPicture = true)
            else if (onSong != null) SongVideoSwitch(video = true, onChange = { if (!it) onSong() }, onPicture = true)
            else AutoplaySwitch(autoplay, onAutoplay)
            if (pipSupported) IconButton(onClick = onPip, modifier = Modifier.size(48.dp).testTag("video-pip")) { Icon(Icons.Outlined.PictureInPictureAlt, contentDescription = "Picture in picture", tint = Color.White) }
            IconButton(onClick = onMore, modifier = Modifier.size(48.dp).testTag(UiTags.VIDEO_MORE)) { Icon(Icons.Outlined.MoreVert, contentDescription = "More player options", tint = Color.White) }
        }
        // previous and next video when there is a list or a folder to move through (as on Windows); the row tightens so the five fit a phone
        Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(if (several) 12.dp else 28.dp), verticalAlignment = Alignment.CenterVertically) {
            if (several) IconButton(onClick = { onPrevious(); onPoke() }, enabled = hasPrevious, modifier = Modifier.size(48.dp).testTag("video-previous")) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous video", tint = if (hasPrevious) Color.White else Color.White.copy(alpha = .38f), modifier = Modifier.size(30.dp))
            }
            IconButton(onClick = { port.seekBy(-10_000); onPoke() }, modifier = Modifier.size(56.dp).testTag("video-back10")) { Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = { port.toggle(); onPoke() }, modifier = Modifier.size(72.dp).testTag(UiTags.VIDEO_PLAY),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White.copy(alpha = .2f), contentColor = Color.White),
            ) { Icon(if (ui.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (ui.playing) "Pause" else "Play", modifier = Modifier.size(44.dp)) }
            IconButton(onClick = { port.seekBy(10_000); onPoke() }, modifier = Modifier.size(56.dp).testTag("video-forward10")) { Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp)) }
            if (several) IconButton(onClick = { onNext(); onPoke() }, enabled = hasNext, modifier = Modifier.size(48.dp).testTag("video-next")) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next video", tint = if (hasNext) Color.White else Color.White.copy(alpha = .38f), modifier = Modifier.size(30.dp))
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp)) {
            VideoSeek(vm, chapters, thumbs, thumbAspect, onChapters, onScrub, onPoke)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSpeed, modifier = Modifier.heightIn(min = 48.dp).testTag("video-speed")) {
                    Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp), tint = Color.White); Spacer(Modifier.width(6.dp)); Text(speedLabel(ui.speed), color = Color.White, style = MaterialTheme.typography.labelLarge.tabular())
                }
                IconButton(onClick = onCaptions, modifier = Modifier.size(48.dp).testTag("video-captions")) {
                    Icon(if (captionsOn) Icons.Outlined.ClosedCaption else Icons.Outlined.ClosedCaptionDisabled, contentDescription = if (captionsOn) "Captions on" else "Captions off", tint = Color.White)
                }
                IconButton(onClick = onRotate, modifier = Modifier.size(48.dp).testTag("video-rotate")) {
                    Icon(Icons.Outlined.ScreenRotation, contentDescription = if (landscape) "Back to portrait" else "Landscape", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AutoplaySwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange).testTag(UiTags.VIDEO_AUTOPLAY)
            .semantics { contentDescription = "Autoplay"; stateDescription = if (on) "On" else "Off" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 20.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color.White else Color.White.copy(alpha = .35f)), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(2.dp).size(16.dp).clip(CircleShape).background(if (on) Color.Black else Color.White), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(11.dp), tint = if (on) Color.White else Color.Black)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // the Slider with our own thumb and track
@Composable
private fun VideoSeek(vm: StudioViewModel, chapters: List<LibraryChapter>, thumbs: VideoThumbnails, thumbAspect: Float, onChapters: () -> Unit, onScrub: (Boolean) -> Unit, onPoke: () -> Unit) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = position.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (position.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shown = (fraction * duration).toLong()
    val starts = remember(chapters) { chapters.map { (it.start * 1000).toLong() } }
    val chapterAt = VideoLogic.chapterIndexAt(starts, shown).coerceAtLeast(0)
    val chapterTitle = chapters.getOrNull(chapterAt)?.let { it.title.ifBlank { LibraryViews.mmss(it.start) } }
    // where chapters split the bar (a chapter at 0 is just the start)
    val cuts = remember(chapters, position.durationMs) {
        if (position.durationMs <= 0) emptyList() else chapters.map { it.start }.filter { it > 1.0 && it * 1000 < position.durationMs }.map { (it * 1000 / position.durationMs).toFloat() }
    }
    val played = Studio.colors.action
    Column {
        dragging?.let { ScrubBubble(it, shown, chapterTitle, thumbs, thumbAspect) }
        // YouTube's bar: chapters as segments with gaps, played chapters in colour; thicker with a bigger thumb while dragged
        Slider(
            value = fraction,
            onValueChange = { v ->
                val from = dragging ?: fraction
                if (dragging == null) onScrub(true)
                // a tick when the drag crosses into another chapter
                if (VideoLogic.crossedChapter(starts, (from * duration).toLong(), (v * duration).toLong())) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                dragging = v; thumbs.request((v * duration).toLong()); onPoke()
            },
            onValueChangeFinished = { dragging?.let { vm.player.seekTo((it * duration).toLong()) }; dragging = null; onScrub(false) },
            enabled = position.durationMs > 0,
            thumb = {
                Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(if (dragging != null) 20.dp else 13.dp).clip(CircleShape).background(played))
                }
            },
            track = { SegmentedTrack(fraction, cuts, thick = dragging != null, played = played) },
            modifier = Modifier.fillMaxWidth().testTag(UiTags.VIDEO_SEEK).semantics { stateDescription = "${LibraryViews.mmss(shown / 1000.0)} of ${LibraryViews.mmss(position.durationMs / 1000.0)}" },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(LibraryViews.mmss(shown / 1000.0), color = Color.White, style = MaterialTheme.typography.labelMedium.tabular())
            if (chapterTitle != null) TextButton(
                onClick = onChapters,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.VIDEO_CHAPTER_BUTTON).semantics { contentDescription = "Chapter: $chapterTitle. Open the chapter list." },
            ) {
                Text(chapterTitle, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Icon(Icons.Outlined.ChevronRight, null, Modifier.size(16.dp), tint = Color.White)
            } else Spacer(Modifier.weight(1f))
            Text(LibraryViews.mmss(position.durationMs / 1000.0), color = Color.White, style = MaterialTheme.typography.labelMedium.tabular())
        }
    }
}

/** The seek bar's track: one rounded piece per chapter with a small gap between them, coloured up to [fraction]. Decorative. */
@Composable
private fun SegmentedTrack(fraction: Float, cuts: List<Float>, thick: Boolean, played: Color) {
    val height = if (thick) 8.dp else 4.dp
    Canvas(Modifier.fillMaxWidth().height(height).testTag(if (cuts.isEmpty()) "video-seek-track" else "video-chapter-segments").clearAndSetSemantics { }) {
        val gap = if (cuts.isEmpty()) 0f else 3.dp.toPx()
        val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
        val bounds = listOf(0f) + cuts + 1f
        for (i in 0 until bounds.lastIndex) {
            val from = size.width * bounds[i] + if (i > 0) gap / 2 else 0f
            val to = size.width * bounds[i + 1] - if (i < bounds.lastIndex - 1) gap / 2 else 0f
            if (to <= from) continue
            drawRoundRect(Color.White.copy(alpha = .3f), Offset(from, 0f), androidx.compose.ui.geometry.Size(to - from, size.height), radius)
            val filled = (size.width * fraction).coerceIn(from, to)
            if (filled > from) drawRoundRect(played, Offset(from, 0f), androidx.compose.ui.geometry.Size(filled - from, size.height), radius)
        }
    }
}

@Composable
private fun ResumeCard(seconds: Double, modifier: Modifier, onDismiss: () -> Unit, onStartOver: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .78f)).padding(start = 18.dp, end = 6.dp).testTag(UiTags.VIDEO_RESUME),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Resumed from ${LibraryViews.mmss(seconds)}", color = Color.White, style = MaterialTheme.typography.bodyMedium.tabular())
        TextButton(onClick = onStartOver, modifier = Modifier.heightIn(min = 48.dp)) { Text("Start over", color = Color(0xFFA7C3FF)) }
        // keep watching from here without waiting for the card to go
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("video-resume-dismiss")) {
            Icon(Icons.Outlined.Close, contentDescription = "Dismiss", tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun UpNextCard(title: String, seconds: Int?, onCancel: () -> Unit, onPlay: () -> Unit, modifier: Modifier) {
    Column(modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .85f)).padding(16.dp).testTag(UiTags.VIDEO_UP_NEXT)) {
        Text(if (seconds != null) "Up next in $seconds s" else "Up next", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium.tabular())
        Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction)) { Text(if (seconds != null) "Play now" else "Play next") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("video-up-next-cancel")) { Text(if (seconds != null) "Cancel" else "Dismiss", color = Color.White) }
        }
    }
}

@Composable
private fun ErrorCard(message: String, item: LibraryItem?, vm: StudioViewModel, modifier: Modifier) {
    Column(modifier.padding(32.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = .85f)).padding(20.dp).testTag("video-error"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Can't play this video", color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(message, color = Color.White.copy(alpha = .8f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (item != null) Button(onClick = { vm.openExternally(item) }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open in another app")
            }
            OutlinedButton(onClick = vm::closeVideo, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close", color = Color.White) }
        }
    }
}

// ---- sheets -------------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CaptionsSheet(
    files: List<SubtitleFile>, embedded: List<TrackChoice>, choice: String, delay: Long, scale: Float,
    onChoice: (String) -> Unit, onDelay: (Long) -> Unit, onScale: (Float) -> Unit, onDismiss: () -> Unit,
) {
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("captions-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Captions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(choice == "off", { onChoice("off") }, label = { Text("Off") }, modifier = Modifier.heightIn(min = 48.dp).testTag("captions-off"))
                files.forEach { file -> FilterChip(choice == "file:${file.uri}", { onChoice("file:${file.uri}") }, label = { Text(file.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                embedded.forEach { track -> FilterChip(choice == "track:${track.id}", { onChoice("track:${track.id}") }, label = { Text(track.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
            if (files.isEmpty() && embedded.isEmpty()) Text("This video has no captions. Download it with subtitles, or put a .srt or .vtt file next to it with the same name.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Size ${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            Slider(value = scale, onValueChange = { onScale((Math.round(it * 10) / 10f)) }, valueRange = 0.6f..2f, steps = 13, modifier = Modifier.semantics { stateDescription = "${(scale * 100).toInt()} percent" })
            val fileMode = choice.startsWith("file:")
            Text("Delay ${if (delay > 0) "+" else ""}${delay} ms", style = MaterialTheme.typography.labelLarge.tabular())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onDelay((delay - 50).coerceAtLeast(-10_000)) }, enabled = fileMode, modifier = Modifier.heightIn(min = 48.dp).testTag("delay-minus")) { Text("− 50 ms") }
                OutlinedButton(onClick = { onDelay((delay + 50).coerceAtMost(10_000)) }, enabled = fileMode, modifier = Modifier.heightIn(min = 48.dp).testTag("delay-plus")) { Text("+ 50 ms") }
                TextButton(onClick = { onDelay(0) }, enabled = fileMode && delay != 0L, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reset") }
            }
            Text("Delay applies to caption files next to the video. Captions inside the video follow its own timing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChaptersSheet(vm: StudioViewModel, chapters: List<LibraryChapter>, onDismiss: () -> Unit) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("chapters-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chapters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            val currentIndex = chapters.indexOfLast { it.start * 1000 <= position.positionMs }.coerceAtLeast(0)
            chapters.forEachIndexed { index, chapter ->
                val label = chapter.title.ifBlank { LibraryViews.mmss(chapter.start) }
                val clock = LibraryViews.mmss(chapter.start)
                val here = index == currentIndex
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp))
                        .selectable(selected = here, role = Role.RadioButton, onClick = { vm.player.seekTo((chapter.start * 1000).toLong()); onDismiss() })
                        .semantics(mergeDescendants = true) { contentDescription = "Chapter ${index + 1}, $label, $clock" }
                        .testTag("chapter-$index"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { if (here) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                    Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(clock, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MoreSheet(
    vm: StudioViewModel, ui: PlayerUi, tracks: MediaTracks, item: LibraryItem?, onScreenshot: () -> Unit, aspect: Aspect, onAspect: (Aspect) -> Unit,
    loopA: Long, loopB: Long, onLoop: (Long, Long) -> Unit, rotationLocked: Boolean, onLock: (Boolean) -> Unit, onLockScreen: () -> Unit, onSleep: () -> Unit, onDismiss: () -> Unit,
) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    val sleep by vm.player.sleepTimer.collectAsStateWithLifecycle()
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("video-more-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Player options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            TextButton(onClick = onScreenshot, modifier = Modifier.heightIn(min = 48.dp).testTag("video-screenshot")) {
                Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Take screenshot")
            }
            Text("Picture", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Aspect.entries.forEach { option -> FilterChip(aspect == option, { onAspect(option) }, label = { Text(option.label) }, modifier = Modifier.heightIn(min = 48.dp).testTag("aspect-${option.name.lowercase()}")) }
            }
            if (tracks.audio.size > 1) {
                Text("Audio track", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tracks.audio.forEach { track -> FilterChip(track.selected, { vm.player.selectAudio(track.id) }, label = { Text(track.label) }, modifier = Modifier.heightIn(min = 48.dp)) }
                }
            }
            Text("A-B loop", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { onLoop(position.positionMs, -1L) }, modifier = Modifier.heightIn(min = 48.dp).testTag("loop-a")) { Text(if (loopA >= 0) "A ${LibraryViews.mmss(loopA / 1000.0)}" else "Set A") }
                OutlinedButton(onClick = { if (loopA >= 0 && position.positionMs > loopA) onLoop(loopA, position.positionMs) }, enabled = loopA >= 0, modifier = Modifier.heightIn(min = 48.dp).testTag("loop-b")) {
                    Text(if (loopB >= 0) "B ${LibraryViews.mmss(loopB / 1000.0)}" else "Set B")
                }
                TextButton(onClick = { onLoop(-1L, -1L) }, enabled = loopA >= 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear") }
            }
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Lock rotation", style = MaterialTheme.typography.bodyLarge)
                    Text("Keep the screen in its current orientation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(rotationLocked, onLock, modifier = Modifier.testTag("rotation-lock"))
            }
            TextButton(onClick = onLockScreen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.VIDEO_LOCK)) {
                Icon(Icons.Outlined.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Lock screen")
                    Text("Ignore touches until you unlock. Back still works.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onSleep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.VIDEO_SLEEP)) {
                Icon(Icons.Outlined.Bedtime, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text("Sleep timer", Modifier.weight(1f))
                Text(VideoLogic.sleepSummary(sleep.active, sleep.endOfTrack, sleep.remainingMs), style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item != null) TextButton(onClick = { onDismiss(); vm.openExternally(item) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open in another app")
            }
        }
    }
}
