package app.keepfin.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.keepfin.ui.theme.ThemeMode

/**
 * Where Keepfin opens ("Open Keepfin on" in Settings): where the user left off (default), Downloads, Home or Library.
 * A presentation choice like the theme, so it lives here with the other UI preferences rather than in the engine's settings.
 */
enum class StartPage(val wire: String) {
    DOWNLOADS("downloads"), HOME("home"), LIBRARY("library"), LAST("last");

    /** The tab to open: [last] is the tab the user was on when they left. Home, Search and Library need the library on; otherwise Downloads opens. */
    fun resolve(last: String?, libraryEnabled: Boolean): String = when {
        this == DOWNLOADS -> "downloads"
        this == HOME -> if (libraryEnabled) "home" else "downloads"
        this == LIBRARY -> if (libraryEnabled) "library" else "downloads"
        else -> last?.takeIf { it in setOf("downloads", "home", "search", "library", "settings") && (it !in setOf("home", "search", "library") || libraryEnabled) } ?: "downloads"
    }

    companion object { fun of(value: String?) = entries.firstOrNull { it.wire == value } ?: LAST }
}

/** Presentation preferences only. Download behaviour lives in the engine's settings. */
class UiPreferences(context: Context, name: String = "studio-ui") {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    var theme by mutableStateOf(ThemeMode.of(prefs.getString("theme", null) ?: legacyTheme(context)))
        private set
    var dynamicColor by mutableStateOf(prefs.getBoolean("dynamicColor", false))
        private set
    var libraryGrid by mutableStateOf(prefs.getBoolean("libraryGrid", false))
        private set

    /** Which tab the app opens on. */
    var startPage by mutableStateOf(StartPage.of(prefs.getString("startPage", null)))
        private set
    fun chooseStartPage(value: StartPage) { startPage = value; prefs.edit().putString("startPage", value.wire).apply() }
    /** The tab the user was last on, for [StartPage.LAST]. */
    var lastDestination: String?
        get() = prefs.getString("lastDestination", null)
        set(value) { prefs.edit().putString("lastDestination", value).apply() }

    /** Collections downloaded as one grouped card (true) or as independent items (false). */
    var keepCollectionsTogether by mutableStateOf(prefs.getBoolean("keepCollectionsTogether", true))
        private set
    fun chooseKeepTogether(value: Boolean) { keepCollectionsTogether = value; prefs.edit().putBoolean("keepCollectionsTogether", value).apply() }

    /** Hash of the last clipboard link the "Download the copied link?" notice was shown for: the same link is never offered twice. */
    var lastOfferedClip: String?
        get() = prefs.getString("lastOfferedClip", null)
        set(value) { prefs.edit().putString("lastOfferedClip", value).apply() }

    /** "Download the copied link?" when Keepfin opens with a new link on the clipboard. On by default; Settings can turn it off. */
    var offerCopiedLinks by mutableStateOf(prefs.getBoolean("offerCopiedLinks", true))
        private set
    fun chooseOfferCopiedLinks(value: Boolean) { offerCopiedLinks = value; prefs.edit().putBoolean("offerCopiedLinks", value).apply() }

    /** Home's All / Videos / Music / Photos chip, remembered. */
    var homeFilter by mutableStateOf(prefs.getString("homeFilter", null) ?: "all")
        private set
    fun chooseHomeFilter(value: String) { homeFilter = value; prefs.edit().putString("homeFilter", value).apply() }

    /** Devices > Available devices as a circle or a list ("circle" / "list"); null until the person chooses, when the page picks by how many were found. */
    var devicesView by mutableStateOf<String?>(prefs.getString("devicesView", null)?.takeIf { it == "circle" || it == "list" })
        private set
    fun chooseDevicesView(value: String) {
        if (value != "circle" && value != "list") return
        devicesView = value
        prefs.edit().putString("devicesView", value).apply()
    }

    /** Pure black dark theme (saves power on OLED screens). Only has an effect in the dark theme. */
    var pureBlack by mutableStateOf(prefs.getBoolean("pureBlack", false))
        private set
    fun choosePureBlack(value: Boolean) { pureBlack = value; prefs.edit().putBoolean("pureBlack", value).apply() }


    // Per-space library presentation, remembered by space id ("home" for the cross-space search).
    private val grids = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    private val sorts = androidx.compose.runtime.mutableStateMapOf<String, String>()
    fun libraryGrid(space: String, default: Boolean): Boolean = grids[space] ?: if (prefs.contains("grid:$space")) prefs.getBoolean("grid:$space", default) else default
    fun chooseLibraryGrid(space: String, value: Boolean) { grids[space] = value; prefs.edit().putBoolean("grid:$space", value).apply() }
    fun librarySort(space: String): String? = sorts[space] ?: prefs.getString("sort:$space", null)
    fun chooseLibrarySort(space: String, value: String) { sorts[space] = value; prefs.edit().putString("sort:$space", value).apply() }

    /** Compact (default) or Comfortable library density, remembered per space (and "home" for the cross-space screens). */
    private val compacts = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()
    fun libraryCompact(space: String): Boolean = compacts[space] ?: prefs.getBoolean("compact:$space", true)
    fun chooseLibraryCompact(space: String, value: Boolean) { compacts[space] = value; prefs.edit().putBoolean("compact:$space", value).apply() }

    // App update reminders, remembered per version so a newer release is announced again.
    /** Which app releases to offer: "stable" (default, also when missing) or "beta". */
    var appChannel by mutableStateOf(app.keepfin.UpdateChannel.of(prefs.getString("appChannel", null)))
        private set
    fun chooseAppChannel(value: app.keepfin.UpdateChannel) { appChannel = value; prefs.edit().putString("appChannel", value.wire).apply() }
    /** Channel a skip or hide was made under; old entries have none and were made on stable. */
    val skippedUpdateChannel: String get() = prefs.getString("skippedUpdateChannel", null) ?: "stable"
    val hiddenBannerChannel: String get() = prefs.getString("hiddenBannerChannel", null) ?: "stable"
    /** The version the user chose "Skip this version" for: the one-time dialog never appears for it. */
    var skippedUpdateVersion by mutableStateOf(prefs.getString("skippedUpdateVersion", null))
        private set
    fun skipUpdateVersion(version: String) { skippedUpdateVersion = version; prefs.edit().putString("skippedUpdateVersion", version).putString("skippedUpdateChannel", appChannel.wire).apply() }
    /** The version whose Downloads banner was dismissed with "Hide for this version". */
    var hiddenBannerVersion by mutableStateOf(prefs.getString("hiddenBannerVersion", null))
        private set
    fun hideUpdateBanner(version: String) { hiddenBannerVersion = version; prefs.edit().putString("hiddenBannerVersion", version).putString("hiddenBannerChannel", appChannel.wire).apply() }

    // Video player preferences.
    var subtitleScale by mutableStateOf(prefs.getFloat("subtitleScale", 1f))
        private set
    fun chooseSubtitleScale(value: Float) { subtitleScale = value; prefs.edit().putFloat("subtitleScale", value).apply() }
    var captionsOn by mutableStateOf(prefs.getBoolean("captionsOn", true))
        private set
    fun chooseCaptions(value: Boolean) { captionsOn = value; prefs.edit().putBoolean("captionsOn", value).apply() }
    /** Whether the next video starts by itself when one ends (the switch in the video player's top bar). */
    var videoAutoplay by mutableStateOf(prefs.getBoolean("videoAutoplay", true))
        private set
    fun chooseVideoAutoplay(value: Boolean) { videoAutoplay = value; prefs.edit().putBoolean("videoAutoplay", value).apply() }
    /** The Song | Video choice of the full player (dev-docs/plans/MUSIC-VIDEOS.md), remembered: while Video is on, a song that has a music video starts as that video. */
    var musicVideo by mutableStateOf(prefs.getBoolean("musicVideo", false))
        private set
    fun chooseMusicVideo(value: Boolean) { musicVideo = value; prefs.edit().putBoolean("musicVideo", value).apply() }

    /** The lyrics card under the full player's controls. Hidden from the card itself, shown again from the player's menu. */
    var lyricsPreview by mutableStateOf(prefs.getBoolean("lyricsPreview", true))
        private set
    fun chooseLyricsPreview(value: Boolean) { lyricsPreview = value; prefs.edit().putBoolean("lyricsPreview", value).apply() }

    /** The full player's style (Stage, Cinema or Afterglow), remembered; the same choice as on Windows. An unknown saved id is the default. */
    var playerStyle by mutableStateOf(app.keepfin.ui.player.PlayerStyles.byId(prefs.getString("playerStyle", null)).id)
        private set
    fun choosePlayerStyle(id: String) { val style = app.keepfin.ui.player.PlayerStyles.byId(id).id; playerStyle = style; prefs.edit().putString("playerStyle", style).apply() }

    fun chooseTheme(value: ThemeMode) { theme = value; prefs.edit().putString("theme", value.wire).apply() }
    fun chooseDynamicColor(value: Boolean) { dynamicColor = value; prefs.edit().putBoolean("dynamicColor", value).apply() }
    fun chooseLibraryGrid(value: Boolean) { libraryGrid = value; prefs.edit().putBoolean("libraryGrid", value).apply() }

    /** Last app version whose "What's new" was handled; null on a fresh install. */
    var lastSeenVersion: String?
        get() = prefs.getString("lastSeenVersion", null)
        set(value) { prefs.edit().putString("lastSeenVersion", value).apply() }

    /** Epoch millis of the last automatic update check. */
    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(value) { prefs.edit().putLong("lastUpdateCheck", value).apply() }

    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean("notificationPermissionAsked", false) || legacyAsked
        set(value) { prefs.edit().putBoolean("notificationPermissionAsked", value).apply() }

    private val legacyAsked = context.getSharedPreferences("native-ui", Context.MODE_PRIVATE).getBoolean("notificationPermissionAsked", false)

    private companion object {
        /** The Views UI stored an explicit dark/light choice; keep honouring it after the upgrade. */
        fun legacyTheme(context: Context): String {
            val legacy = context.getSharedPreferences("native-ui", Context.MODE_PRIVATE)
            if (!legacy.contains("dark")) return ThemeMode.SYSTEM.wire
            return if (legacy.getBoolean("dark", true)) ThemeMode.DARK.wire else ThemeMode.LIGHT.wire
        }
    }
}
