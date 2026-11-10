package app.keepfin.ui.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The service's own mark for Dropbox and Google Drive (drawn in its colours, so shown untinted); null for the others. */
internal fun providerMark(provider: String?): ImageVector? = when (provider) {
    "dropbox" -> dropbox
    "drive" -> drive
    else -> null
}

private fun mark(name: String, vararg paths: Pair<Long, String>): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        for ((color, d) in paths) addPath(addPathNodes(d), fill = SolidColor(Color(color)))
    }.build()

private val dropbox by lazy {
    mark("Dropbox", 0xFF0061FF to "M6 1.8 0 5.6l6 3.9 6-3.9-6-3.8Zm12 0-6 3.8 6 3.9 6-3.9-6-3.8ZM0 13.3l6 3.8 6-3.8-6-3.8-6 3.8Zm18-3.8-6 3.8 6 3.8 6-3.8-6-3.8ZM6 18.4l6 3.8 6-3.8-6-3.9-6 3.9Z")
}

private val drive by lazy {
    mark("Google Drive", 0xFFFBBC04 to "M8 2.5h8l7.5 13h-8Z", 0xFF34A853 to "M8 2.5 12 9.4 4.5 22.5l-4-7Z", 0xFF4285F4 to "M8.5 15.5h15l-4 7h-15Z")
}
