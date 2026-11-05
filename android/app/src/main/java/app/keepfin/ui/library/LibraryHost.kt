package app.keepfin.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import app.keepfin.ui.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The sheet or dialog the library surfaces (Home, Search, Library) raised; one host shows it, so every surface behaves the same. */
@Stable
class LibrarySheetState {
    var sheet by mutableStateOf<LibrarySheet?>(null)
}

/**
 * One [LibraryUi] for every library surface: Home, Search and the Library share the selection, the document picker and the sheets,
 * so an item's action sheet is the same wherever it is opened.
 */
@Composable
fun rememberLibraryUi(vm: StudioViewModel, state: LibrarySheetState): LibraryUi {
    val context = LocalContext.current
    val selection = remember(vm) {
        Selection().also { s ->
            s.resolveIds = { query, limit -> vm.lib.browse.ids(query, limit) }
            s.background = { work, done ->
                if (vm.lib.browse.cheap) { work(); done() }
                else vm.viewModelScope.launch { withContext(Dispatchers.IO) { work() }; done() }
            }
        }
    }
    var pickerSpace by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> pickerSpace?.let { vm.spaces.importFiles(uris, it) } }
    return remember(vm, context) {
        LibraryUi(vm, context, { state.sheet = it }, selection) { space -> pickerSpace = space; picker.launch(arrayOf("audio/*", "video/*", "image/*")) }
    }
}
