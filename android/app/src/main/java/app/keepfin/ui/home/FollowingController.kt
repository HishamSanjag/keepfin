package app.keepfin.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import app.keepfin.ArtistRelease
import app.keepfin.FollowingState
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.MusicTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the screens do with followed artists: follow and unfollow, check now, "Keep it" (one release, one tap, the person's defaults) and
 * the card's seen / hide choices. The engine does the work; this holds only what the screens show while it runs (the Review sheet, the
 * artist list's filter, the releases being kept).
 */
class FollowingController(private val vm: StudioViewModel) {
    val state: StateFlow<FollowingState> get() = vm.engine.following

    /** The Review sheet of Home's card is open. */
    var reviewing by mutableStateOf(false)

    /** The artist list shows only the artists you follow. */
    var onlyFollowing by mutableStateOf(false)

    /** Releases whose Keep it is running: the button shows "Keeping…" and ignores a second tap. */
    val keeping = mutableStateListOf<String>()

    fun isFollowing(name: String?) = state.value.isFollowing(name)

    fun follow(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || isFollowing(clean)) return
        vm.engine.followArtist(clean)
        vm.say("Following $clean.")
    }

    fun unfollow(id: String) {
        val artist = state.value.artists.firstOrNull { it.id == id } ?: return
        vm.engine.unfollowArtist(id)
        vm.say("Stopped following ${artist.name}.", "Undo") { vm.engine.followArtist(artist.name) }
    }

    /** Follow when not followed, else unfollow. */
    fun toggle(name: String) {
        state.value.artistOf(name)?.let { unfollow(it.id) } ?: follow(name)
    }

    fun check(id: String?) {
        vm.engine.checkFollowing(id)
        vm.say(if (id == null) "Checking for new releases." else "Checking for new releases from ${state.value.artists.firstOrNull { it.id == id }?.name ?: "this artist"}.")
    }

    /** The x on Home's card and "Mark all seen": the card returns only for newer releases. */
    fun markSeen() = vm.engine.markReleasesSeen()

    /** "Don't show on Home": the card is off for good (Settings, Library turns it back on). */
    fun hideOnHome() {
        vm.updateSettings { it.copy(newReleasesOnHome = false) }
        vm.say("New releases are off on Home. You can turn them back on in Settings, under Library.")
    }

    /** "Manage who you follow": the artist list of the first music space, on Following. */
    fun manage() {
        reviewing = false
        val space = vm.musicSpaceIds().firstOrNull() ?: return
        onlyFollowing = true
        vm.chooseMusicTab(space, MusicTab.ARTISTS)
        vm.openLibraryDeep(LibraryRoute.Space(space))
    }

    fun keep(release: ArtistRelease) {
        if (release.id in keeping) return
        keeping += release.id
        vm.viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { StudioEngine.withServiceWork(vm.getApplication()) { vm.engine.keepRelease(release.id) } }
                vm.say("Added $count ${if (count == 1) "download" else "downloads"} to the queue")
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                vm.say(app.keepfin.ui.StudioViewModel.friendly(e, "Could not keep ${release.title}."))
            } finally { keeping -= release.id }
        }
    }
}
