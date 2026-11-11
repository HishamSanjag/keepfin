package app.keepfin.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.LibraryItemKind
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.CardText
import app.keepfin.ui.components.ArtworkBadge
import app.keepfin.ui.components.SelectBox
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.theme.tabular

private val CardShape = RoundedCornerShape(12.dp)

/** What a card says about itself over its picture. */
data class CardMarks(val progress: Float = 0f, val badge: String? = null, val tag: String? = null, val watched: Boolean = false)

/** What a tap and a long press do. */
class CardActions(
    val onClick: () -> Unit,
    val clickLabel: String? = null,
    val onLongClick: (() -> Unit)? = null,
    val longClickLabel: String? = null,
)

/**
 * The one media card of every page: a video (16:9), a square cover, a round artist, a mix, a photo at one height or a tile. Every kind has
 * the same corners and the same text under it (a two-line title, then one line of info), shown as the person chose in Text. [art] fills the picture.
 */
@Composable
fun MediaCard(
    kind: CardKind,
    title: String,
    info: String?,
    actions: CardActions,
    modifier: Modifier = Modifier,
    width: Dp = rememberCardWidth(kind),
    aspect: Float = 1f,
    marks: CardMarks = CardMarks(),
    selected: Boolean? = null,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    art: @Composable BoxScope.() -> Unit,
) {
    if (kind == CardKind.Tile) {
        TileCard(title, actions, modifier, width, art)
        return
    }
    val text = LocalCardPrefs.current.text
    val round = kind == CardKind.Round
    val photo = kind == CardKind.Photo
    val artSize = when {
        photo -> Modifier.fillMaxWidth().height(width)
        kind == CardKind.Video -> Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        else -> Modifier.fillMaxWidth().aspectRatio(1f)
    }
    Column(
        modifier.width(if (photo) width * aspect else width).clip(CardShape).cardClick(actions)
            .semantics { if (text == CardText.None) contentDescription = listOfNotNull(title, info).joinToString(", ") },
    ) {
        Box(artSize.clip(if (round) CircleShape else CardShape)) {
            art()
            CardMarksOverlay(marks)
            if (selected != null) SelectBox(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
            overlay?.invoke(this)
        }
        if (text != CardText.None) Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(
                    title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = if (round) TextAlign.Center else null, modifier = Modifier.fillMaxWidth(),
                )
                if (text == CardText.Full && info != null) Text(
                    info, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = if (round) TextAlign.Center else null, modifier = Modifier.fillMaxWidth(),
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun BoxScope.CardMarksOverlay(marks: CardMarks) {
    marks.tag?.let {
        Text(
            it, style = MaterialTheme.typography.labelSmall, color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = .6f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }
    marks.badge?.let { ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
    if (marks.watched) Icon(Icons.Outlined.CheckCircle, contentDescription = "Watched", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
    else if (marks.progress > 0f) LinearProgressIndicator(
        progress = { marks.progress },
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).semantics { contentDescription = "Watched ${(marks.progress * 100).toInt()} percent" },
        drawStopIndicator = {}, trackColor = Color.Black.copy(alpha = .3f),
    )
}

/** A small art square beside a title on a tonal plate: Home's "Jump back in". The title always shows, as the art is too small to stand alone. */
@Composable
private fun TileCard(title: String, actions: CardActions, modifier: Modifier, width: Dp, art: @Composable BoxScope.() -> Unit) {
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.width(width).heightIn(min = 56.dp)) {
        Row(Modifier.heightIn(min = 56.dp).cardClick(actions), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(56.dp).clip(CardShape)) { art() }
            Text(
                title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.cardClick(actions: CardActions): Modifier = combinedClickable(
    role = Role.Button, onClickLabel = actions.clickLabel, onClick = actions.onClick,
    onLongClickLabel = actions.longClickLabel, onLongClick = actions.onLongClick,
)

/** A mix's picture: its first four covers as a 2 x 2 mosaic, or the one cover it has. */
@Composable
fun BoxScope.MixArt(covers: List<Any?>, seed: String) {
    if (covers.size < 4) {
        ArtTile(covers.firstOrNull(), seed, LibraryItemKind.AUDIO, Modifier.fillMaxSize(), RectangleShape, iconSize = 36.dp)
        return
    }
    Column(Modifier.fillMaxSize()) {
        for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth()) {
            for (column in 0..1) ArtTile(covers[row * 2 + column], "$seed-${row * 2 + column}", LibraryItemKind.AUDIO, Modifier.weight(1f).fillMaxSize(), RectangleShape, iconSize = 20.dp)
        }
    }
}
