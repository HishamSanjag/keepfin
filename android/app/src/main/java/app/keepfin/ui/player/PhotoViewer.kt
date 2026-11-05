package app.keepfin.ui.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItem
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.studioImageLoader
import app.keepfin.ui.library.ExternalOpen
import app.keepfin.ui.library.ImageRequest
import app.keepfin.ui.theme.tabular
import coil3.compose.AsyncImage
import coil3.request.ImageRequest as CoilRequest

/** Full-screen photos: swipe between a folder's pictures, pinch or double-tap to zoom, drag to pan once zoomed. */
@Composable
fun PhotoViewer(vm: StudioViewModel, request: ImageRequest) {
    val context = LocalContext.current
    // the photos are read off the main thread; until they arrive nothing is shown (and nothing is closed)
    val read: List<LibraryItem>? by app.keepfin.ui.library.rememberBrowsed<List<LibraryItem>?>(vm.lib.browse, request.ids, null) { it.byIds(request.ids) }
    val items = read ?: return
    if (items.isEmpty()) { vm.closePhotos(); return }
    val pager = rememberPagerState(initialPage = request.index.coerceIn(0, items.lastIndex)) { items.size }
    var zoomed by remember { mutableStateOf(false) }
    val shown = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    Box(Modifier.fillMaxSize().background(Color.Black).testTag(UiTags.PHOTO_VIEWER)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it.coerceIn(0, items.lastIndex)].id }, beyondViewportPageCount = 1) { page ->
            ZoomableImage(items[page], active = page == pager.currentPage) { zoomed = it }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().background(Color.Black.copy(alpha = .45f)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closePhotos, modifier = Modifier.size(48.dp).testTag("photo-close")) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close the photo", tint = Color.White) }
            Text(shown.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text("${pager.currentPage + 1} of ${items.size}", color = Color.White, style = MaterialTheme.typography.labelLarge.tabular(), modifier = Modifier.padding(end = 4.dp))
            IconButton(onClick = { ExternalOpen.share(context, shown)?.let(vm::say) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Share, contentDescription = "Share", tint = Color.White) }
            IconButton(onClick = { vm.openExternally(shown) }, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open in another app", tint = Color.White) }
        }
        Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun ZoomableImage(item: LibraryItem, active: Boolean, onZoom: (Boolean) -> Unit) {
    val context = LocalContext.current
    val loader = studioImageLoader()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var failed by remember { mutableStateOf(false) }
    if (!active && scale != 1f) { scale = 1f; offset = Offset.Zero }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
        onZoom(scale > 1f)
    }
    val request = remember(item.id) { CoilRequest.Builder(context).data(Uri.parse(item.uri)).build() }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f; onZoom(scale > 1f) }) }
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = request, imageLoader = loader, contentDescription = item.title, contentScale = ContentScale.Fit, onError = { failed = true },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
        )
        if (failed) Box(Modifier.semantics { contentDescription = "This photo can't be shown" }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(48.dp))
        }
    }
}
