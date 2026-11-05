package app.keepfin.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.keepfin.R
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.Studio

/** The only destinations this screen can open. No URL ever comes from user input. */
object SupportLinks {
    const val PROFILE = "https://github.com/HishamSanjag"
    const val SPONSORS = "https://github.com/sponsors/HishamSanjag"
    const val KOFI = "https://ko-fi.com/hishamsanjag"
    const val PAYPAL = "https://paypal.me/hishamsanjagpaypal"
}

/** Opens a fixed https link; false when no app can handle it. Tests replace this to observe the URL. */
val LocalExternalLinks = compositionLocalOf<(Context, String) -> Boolean> { ::openExternalLink }

fun openExternalLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) { false }

private data class SupportOption(val tag: String, val title: String, val subtitle: String, val url: String, val icon: ImageVector)

private val options = listOf(
    SupportOption(UiTags.SUPPORT_SPONSORS, "GitHub Sponsors", "Monthly or one-time, through GitHub", SupportLinks.SPONSORS, Icons.Filled.Favorite),
    SupportOption(UiTags.SUPPORT_KOFI, "Ko-fi", "Buy me a coffee", SupportLinks.KOFI, Icons.Filled.LocalCafe),
    SupportOption(UiTags.SUPPORT_PAYPAL, "PayPal", "Send a tip with PayPal", SupportLinks.PAYPAL, Icons.Filled.AccountBalanceWallet),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val opener = LocalExternalLinks.current
    fun open(url: String) { if (!opener(context, url)) vm.say("No browser is available to open this link.") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { SettingsHeading("Support Keepfin") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag(UiTags.SUPPORT_SCREEN),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item("profile") {
                Surface(
                    shape = RoundedCornerShape(24.dp), color = Studio.colors.raised,
                    border = if (Studio.colors.dark) null else androidx.compose.foundation.BorderStroke(1.dp, Studio.colors.separator),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painterResource(R.drawable.developer_avatar), contentDescription = "Photo of Hisham Sanjag",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(104.dp).clip(CircleShape).border(2.dp, Studio.colors.action, CircleShape),
                        )
                        Spacer(Modifier.height(14.dp))
                        Text("Hisham Sanjag", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        Text(
                            "@HishamSanjag", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Open GitHub profile") { open(SupportLinks.PROFILE) }
                                .padding(horizontal = 12.dp, vertical = 12.dp).testTag(UiTags.SUPPORT_PROFILE),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "I build Keepfin in my spare time. If it saves you time, you can support its development.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
            options.forEach { option ->
                item(option.tag) {
                    Surface(
                        shape = RoundedCornerShape(20.dp), color = Studio.colors.raised,
                        border = if (Studio.colors.dark) null else androidx.compose.foundation.BorderStroke(1.dp, Studio.colors.separator),
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                            .clickable(role = Role.Button, onClickLabel = "Open ${option.title}") { open(option.url) }.testTag(option.tag),
                    ) {
                        Row(Modifier.heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(CircleShape).then(Modifier), contentAlignment = Alignment.Center) {
                                Surface(color = Studio.colors.action, shape = CircleShape, modifier = Modifier.size(44.dp)) {
                                    Box(contentAlignment = Alignment.Center) { Icon(option.icon, contentDescription = null, tint = Studio.colors.onAction) }
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(option.title, style = MaterialTheme.typography.titleMedium)
                                Text(option.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item("note") {
                Text(
                    "Keepfin stays free and has no ads or tracking. Support is always optional.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}
