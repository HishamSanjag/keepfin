package app.keepfin.ui.settings

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.NavRow
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SettingsGroup
import app.keepfin.ui.components.SupportingText

/** Sites whose cookies the engine exports after sign-in (see EngineApi.importSignInSession). */
enum class SignInSite(val label: String, val url: String, val desktop: Boolean = false) {
    YOUTUBE("YouTube · Google", "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F", desktop = true),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/"),
    X("X", "https://x.com/i/flow/login"),
    FACEBOOK("Facebook", "https://www.facebook.com/login/"),
    TIKTOK("TikTok", "https://www.tiktok.com/login"),
    REDDIT("Reddit", "https://www.reddit.com/login/"),
    VIMEO("Vimeo", "https://vimeo.com/log_in"),
    SOUNDCLOUD("SoundCloud", "https://soundcloud.com/signin"),
}

/** Google refuses embedded browsers that identify as WebView; it gets a desktop-class Chrome UA. */
private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(vm: StudioViewModel, onClose: () -> Unit) {
    var site by rememberSaveable { mutableStateOf<SignInSite?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    BackHandler(enabled = site != null && canGoBack) { web?.goBack() }
    BackHandler(enabled = site != null && !canGoBack) { site = null }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { SettingsHeading(site?.let { "Sign in · ${it.label}" } ?: "Sign in to a site") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Settings") } },
                actions = {
                    Button(
                        onClick = { vm.finishSignIn { count -> if (count > 0) onClose() } },
                        enabled = !vm.signingIn, modifier = Modifier.padding(end = 8.dp).testTag(UiTags.SIGN_IN_DONE),
                    ) {
                        if (vm.signingIn) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Done")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            val current = site
            if (current == null) {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                    item {
                        SupportingText(
                            "Sign in on the site's own page. When you tap Done, Keepfin saves that site's cookies on this phone so downloads can use your account. " +
                                "Nothing is sent anywhere else. Clear sign-in in Settings removes them.",
                            Modifier.padding(top = 8.dp),
                        )
                    }
                    item {
                        SettingsGroup("Choose a site") {
                            SignInSite.entries.forEachIndexed { index, option ->
                                if (index > 0) RowSeparator()
                                NavRow(option.label, icon = Icons.Outlined.Lock, modifier = Modifier.testTag("sign-in-${option.name.lowercase()}"),
                                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }) { site = option }
                            }
                        }
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SignInSite.entries.forEach { option ->
                        FilterChip(selected = option == current, onClick = { site = option }, label = { Text(option.label) })
                    }
                }
                if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
                SignInWebView(current, onProgress = { progress = it }, onHistory = { canGoBack = it }, onView = { web = it }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SignInWebView(site: SignInSite, onProgress: (Int) -> Unit, onHistory: (Boolean) -> Unit, onView: (WebView?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { onProgress(newProgress) }
            }
            webViewClient = object : WebViewClient() {
                // Only web pages load here; app intents and other schemes are refused.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme?.lowercase() != "https"
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { onHistory(view.canGoBack()) }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) { onHistory(view.canGoBack()) }
            }
        }
    }
    val defaultAgent = remember { WebSettings.getDefaultUserAgent(context) }
    DisposableEffect(view) {
        onView(view)
        onDispose { onView(null); view.stopLoading(); view.destroy() }
    }
    DisposableEffect(site) {
        view.settings.userAgentString = if (site.desktop) DESKTOP_UA else defaultAgent.replace("; wv", "").replace(Regex("Version/\\S+ "), "")
        view.loadUrl(site.url)
        onDispose { }
    }
    AndroidView(factory = { view }, modifier = modifier)
}
