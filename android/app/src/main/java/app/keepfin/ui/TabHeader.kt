package app.keepfin.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.ui.components.ScreenTitle
import app.keepfin.ui.home.BigBox
import app.keepfin.ui.theme.Studio

/**
 * The top of a tab, pinned while the page scrolls: the title, the bell and the gear. A small search button opens the paste-or-search
 * field in the bar itself (a link starts a download, words open Search); on a page with its own box at the top the button appears
 * once that box has scrolled away. A hairline under the bar shows that the page is scrolled.
 *
 * [searchAfter]: the index of the page's own box in [list] (-1 when the page has none, so the button always shows); [search] false
 * leaves the button out (the Search tab).
 */
@Composable
fun TabHeader(
    vm: StudioViewModel,
    title: String,
    list: LazyListState?,
    modifier: Modifier = Modifier,
    searchAfter: Int = -1,
    search: Boolean = true,
    extra: @Composable RowScope.() -> Unit = {},
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val scrolled by remember(list) { derivedStateOf { list != null && (list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 0) } }
    val offer by remember(list, searchAfter) { derivedStateOf { searchAfter < 0 || (list != null && list.firstVisibleItemIndex > searchAfter) } }
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth()) {
            if (open) Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).testTag(UiTags.TAB_SEARCH_FIELD),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { open = false }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Close search") }
                BigBox(vm, Modifier.weight(1f), autoFocus = true, tag = UiTags.TAB_SEARCH_BOX, hint = "Search or paste a link", onRouted = { open = false })
            } else ScreenTitle(title, modifier) {
                if (search) AnimatedVisibility(offer, enter = fadeIn() + scaleIn(initialScale = .8f), exit = fadeOut() + scaleOut(targetScale = .8f)) {
                    IconButton(onClick = { open = true }, modifier = Modifier.testTag(UiTags.TAB_SEARCH)) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search or paste a link")
                    }
                }
                extra()
                TabActions(vm)
            }
            if (scrolled) HorizontalDivider(color = Studio.colors.separator)
        }
    }
}
