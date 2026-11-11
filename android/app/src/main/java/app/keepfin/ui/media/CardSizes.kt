package app.keepfin.ui.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.CardPrefs
import app.keepfin.core.layout.CardSizes

/** The person's Card size and Text under cards, provided once by the app shell. */
val LocalCardPrefs = compositionLocalOf { CardPrefs() }

/** The gap between cards in a row and in a grid. */
internal val CardGap = 12.dp

/** A card's width in dp on this screen (a photo's is its height): the core's size rule times the person's Card size. */
@Composable
fun rememberCardWidth(kind: CardKind): Dp {
    val page = LocalConfiguration.current.screenWidthDp
    val scale = LocalCardPrefs.current.scale
    return remember(kind, page, scale) { CardSizes.size(kind, page.toDouble(), scale).dp }
}

/** How many cards of [cardDp] fit a row [availableDp] wide with [gapDp] between them; at least one. */
internal fun cardsPerRow(availableDp: Float, cardDp: Float, gapDp: Float): Int =
    ((availableDp + gapDp) / (cardDp + gapDp)).toInt().coerceAtLeast(1)

/** A photo card's width at row height [heightDp]: its own shape, kept within a calm range; square when its size is unknown. */
internal fun photoWidth(heightDp: Float, pixelWidth: Int?, pixelHeight: Int?): Float {
    if (pixelWidth == null || pixelHeight == null || pixelWidth <= 0 || pixelHeight <= 0) return heightDp
    return heightDp * (pixelWidth.toFloat() / pixelHeight).coerceIn(0.6f, 1.8f)
}
