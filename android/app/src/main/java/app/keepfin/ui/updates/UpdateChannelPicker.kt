package app.keepfin.ui.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.UpdateChannel
import app.keepfin.UpdateRelease
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags

/** Settings > Updates > "Update channel": Stable (default) or Beta app releases. The download engine has its own channel. */
@Composable
fun UpdateChannelPicker(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val channel = vm.prefs.appChannel
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.setting("app-channel"))) {
        Text("Update channel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(6.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(UpdateChannel.STABLE to "Stable", UpdateChannel.BETA to "Beta").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = channel == value, onClick = { vm.updatesFlow.chooseAppChannel(value) },
                    shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.setting("app-channel-${value.wire}")),
                ) { Text(label, style = MaterialTheme.typography.labelMedium) }
            }
        }
        Text(
            if (channel == UpdateChannel.BETA) "Try new features early. Betas can have bugs." else "Tested releases. Recommended.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp).testTag(UiTags.setting("app-channel-note")),
        )
    }
}

/** Small "Beta" pill next to the version when this build is a prerelease. */
@Composable
fun BetaBadge(version: String, modifier: Modifier = Modifier) {
    if (!UpdateRelease.isPrerelease(version)) return
    Text(
        "Beta", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp).testTag("beta-badge"),
    )
}
