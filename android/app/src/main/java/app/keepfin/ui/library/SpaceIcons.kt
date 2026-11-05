package app.keepfin.ui.library

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.ui.graphics.vector.ImageVector
import app.keepfin.SpaceIcon

/** The glyph of a space icon choice. */
fun SpaceIcon.vector(): ImageVector = when (this) {
    SpaceIcon.MUSIC -> Icons.Outlined.MusicNote
    SpaceIcon.VIDEO -> Icons.Outlined.Movie
    SpaceIcon.FOLDER -> Icons.Outlined.Folder
    SpaceIcon.STAR -> Icons.Outlined.StarBorder
    SpaceIcon.HEART -> Icons.Outlined.FavoriteBorder
    SpaceIcon.MIC -> Icons.Outlined.Mic
    SpaceIcon.BOOK -> Icons.Outlined.AutoStories
    SpaceIcon.DUMBBELL -> Icons.Outlined.FitnessCenter
    SpaceIcon.MOON -> Icons.Outlined.DarkMode
    SpaceIcon.CHILD -> Icons.Outlined.ChildCare
}
