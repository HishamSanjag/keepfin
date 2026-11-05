package app.keepfin.ui.settings

/**
 * The nine Settings categories, in the order of the Settings home list (dev-docs/plans/SETTINGS-REDESIGN.md). ABOUT keeps its name from
 * when it was "About & help", so saved state still opens it.
 */
enum class SettingsCategory(val title: String) {
    DOWNLOADS("Downloads"),
    LIBRARY("Library & playback"),
    STORAGE("Storage & copies"),
    DEVICES("Devices"),
    APPEARANCE("Appearance"),
    NOTIFICATIONS("Notifications"),
    HELP("Help"),
    /** Opens the Support Keepfin screen directly (apart from Help, as on Windows). */
    SUPPORT("Support Keepfin"),
    ABOUT("About Keepfin"),
}

/** Named places inside a category that something else can send you to (the keys of the category's list items). */
object SettingsGroups {
    const val MODE = "mode"
    const val DOWNLOADS = "downloads"
    const val SIGN_IN = "signin"
    const val SPEED = "speed"
    const val LIBRARY = "library"
    const val STORAGE = "storage"
    const val TRASH = "trash"
    const val DEVICES = "devices"
    const val DEVICES_ADVANCED = "devices-advanced"
    const val APPEARANCE = "appearance"
    const val START = "start"
    const val NOTIFICATIONS = "notifications"
    const val UPDATES = "updates"
    const val HELP = "help"
    const val ABOUT_APP = "about-app"
    const val SUPPORT = "support"
    const val START_OVER = "start-over"
}

/** Where a link into Settings lands: a category, optionally a group in it, and optionally the one row to highlight. */
data class SettingsTarget(val category: SettingsCategory, val group: String? = null, val tag: String? = null)

/** A pending "scroll here and highlight it". [nonce] makes the same target fire again when asked twice. */
data class SettingsAnchor(val target: SettingsTarget, val nonce: Long)

/**
 * The redirect map: every destination id the older Settings used, and where it lives now. Saved state and links from elsewhere in the
 * app may still carry the old names (General, Performance, Network, Accounts and sign-in, Updates, Support and about).
 */
object SettingsRedirect {
    private val map: Map<String, SettingsTarget> = mapOf(
        "GENERAL" to SettingsTarget(SettingsCategory.APPEARANCE),
        "DOWNLOADS" to SettingsTarget(SettingsCategory.DOWNLOADS),
        "LIBRARY" to SettingsTarget(SettingsCategory.LIBRARY),
        "STORAGE" to SettingsTarget(SettingsCategory.STORAGE),
        "DEVICES" to SettingsTarget(SettingsCategory.DEVICES),
        "PERFORMANCE" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SPEED),
        "NETWORK" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SPEED),
        "ACCOUNTS" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SIGN_IN),
        "UPDATES" to SettingsTarget(SettingsCategory.ABOUT, SettingsGroups.UPDATES),
        "SUPPORT" to SettingsTarget(SettingsCategory.SUPPORT),
        "HELP" to SettingsTarget(SettingsCategory.HELP),
        "GUIDE" to SettingsTarget(SettingsCategory.HELP, SettingsGroups.HELP),
        "APPEARANCE" to SettingsTarget(SettingsCategory.APPEARANCE),
        "NOTIFICATIONS" to SettingsTarget(SettingsCategory.NOTIFICATIONS),
        "ABOUT" to SettingsTarget(SettingsCategory.ABOUT),
    )

    /** The new place for an old (or current) destination id, or null when the id is unknown. Case does not matter. */
    fun resolve(id: String?): SettingsTarget? = id?.let { map[it.trim().uppercase()] }

    val legacyIds: Set<String> get() = map.keys
}

/** One searchable setting. [group] is the list item to scroll to, [tag] the row to highlight when it is a single control. */
data class SettingsEntry(
    val title: String,
    val category: SettingsCategory,
    val group: String,
    val keywords: String = "",
    val tag: String? = null,
) {
    val target get() = SettingsTarget(category, group, tag)
}

/** Which categories exist on this build: Storage and Devices show only in builds that have the Keepfin folder (and devices need a modern TLS stack). */
data class SettingsVisibility(val storage: Boolean = true, val devices: Boolean = true) {
    fun shows(category: SettingsCategory) = when (category) {
        SettingsCategory.STORAGE -> storage
        SettingsCategory.DEVICES -> devices
        else -> true
    }
}

/** The static index behind settings search: every setting, with a few words people may type for it. */
object SettingsIndex {
    val entries: List<SettingsEntry> = listOf(
        // Downloads
        SettingsEntry("Default quality and format", SettingsCategory.DOWNLOADS, SettingsGroups.DOWNLOADS, "preset best video audio resolution mp3", "setting-preset"),
        SettingsEntry("YouTube Music links", SettingsCategory.DOWNLOADS, SettingsGroups.DOWNLOADS, "audio video music"),
        SettingsEntry("Default download options", SettingsCategory.DOWNLOADS, SettingsGroups.DOWNLOADS, "subtitles sponsorblock thumbnail metadata chapters extras"),
        SettingsEntry("Working files", SettingsCategory.DOWNLOADS, SettingsGroups.DOWNLOADS, "folder per site one folder organise organize"),
        SettingsEntry("Add lyrics to songs", SettingsCategory.DOWNLOADS, SettingsGroups.DOWNLOADS, "lyrics lrc words", "setting-lyrics"),
        SettingsEntry("Sign in to a site", SettingsCategory.DOWNLOADS, SettingsGroups.SIGN_IN, "account login cookies private age members"),
        SettingsEntry("Import cookies.txt", SettingsCategory.DOWNLOADS, SettingsGroups.SIGN_IN, "cookies netscape browser"),
        SettingsEntry("Clear sign-in", SettingsCategory.DOWNLOADS, SettingsGroups.SIGN_IN, "log out remove cookies"),
        SettingsEntry("Parallel downloads", SettingsCategory.DOWNLOADS, SettingsGroups.SPEED, "speed concurrency at once simultaneous"),
        SettingsEntry("Fragments per download", SettingsCategory.DOWNLOADS, SettingsGroups.SPEED, "speed pieces hls dash"),
        SettingsEntry("Turbo transfers", SettingsCategory.DOWNLOADS, SettingsGroups.SPEED, "speed multi-connection connections faster", "setting-turbo"),
        SettingsEntry("Download on Wi-Fi only", SettingsCategory.DOWNLOADS, SettingsGroups.SPEED, "mobile data network cellular", "setting-wifi"),
        // Library & playback
        SettingsEntry("Keepfin mode", SettingsCategory.LIBRARY, SettingsGroups.MODE, "downloader only library player switch"),
        SettingsEntry("Open media with", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "player keepfin player another app"),
        SettingsEntry("Where downloads go", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "route music space save to"),
        SettingsEntry("Spaces", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "add a space folder music videos podcasts"),
        SettingsEntry("Volume normalisation", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "playback loudness volume normalization", "setting-normalize"),
        SettingsEntry("Import tags from sources", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "genre tags categories", "setting-import-tags"),
        SettingsEntry("Find my earlier Keepfin files", SettingsCategory.LIBRARY, SettingsGroups.LIBRARY, "reinstall adopt old files"),
        // Storage & copies
        SettingsEntry("Keepfin folder", SettingsCategory.STORAGE, SettingsGroups.STORAGE, "storage free space path check files memory card"),
        SettingsEntry("Copies", SettingsCategory.STORAGE, SettingsGroups.STORAGE, "backup mirror sync add a copy usb card"),
        SettingsEntry("Cloud accounts", SettingsCategory.STORAGE, SettingsGroups.STORAGE, "google drive onedrive dropbox copy"),
        SettingsEntry("Needs review", SettingsCategory.STORAGE, SettingsGroups.STORAGE, "conflicts copies review"),
        SettingsEntry("Start over", SettingsCategory.STORAGE, SettingsGroups.START_OVER, "reset delete everything erase wipe clean"),
        // Devices
        SettingsEntry("Reachable when Keepfin is closed", SettingsCategory.DEVICES, SettingsGroups.DEVICES, "keep this phone reachable background notification battery", "devices-keep-reachable"),
        SettingsEntry("Add a device", SettingsCategory.DEVICES, SettingsGroups.DEVICES, "pair pairing qr code scan other phone pc"),
        SettingsEntry("Paired devices", SettingsCategory.DEVICES, SettingsGroups.DEVICES, "permissions remove share library mobile data"),
        SettingsEntry("Name of this phone", SettingsCategory.DEVICES, SettingsGroups.DEVICES_ADVANCED, "rename fingerprint port advanced"),
        SettingsEntry("Turn off devices", SettingsCategory.DEVICES, SettingsGroups.DEVICES_ADVANCED, "disable sync advanced"),
        // Appearance
        SettingsEntry("Theme", SettingsCategory.APPEARANCE, SettingsGroups.APPEARANCE, "light dark system"),
        SettingsEntry("Pure black", SettingsCategory.APPEARANCE, SettingsGroups.APPEARANCE, "oled dark amoled", "setting-pure-black"),
        SettingsEntry("Material You colors", SettingsCategory.APPEARANCE, SettingsGroups.APPEARANCE, "wallpaper dynamic color", "setting-dynamic-color"),
        SettingsEntry("App icon", SettingsCategory.APPEARANCE, SettingsGroups.APPEARANCE, "launcher logo"),
        SettingsEntry("Open Keepfin on", SettingsCategory.APPEARANCE, SettingsGroups.START, "start page home library downloads"),
        SettingsEntry("Offer copied links", SettingsCategory.APPEARANCE, SettingsGroups.START, "clipboard paste link", "setting-offer-copied-links"),
        // Notifications
        SettingsEntry("Download notifications", SettingsCategory.NOTIFICATIONS, SettingsGroups.NOTIFICATIONS, "alerts progress completed failures sound lock screen", "notification-enabled"),
        SettingsEntry("Play alert sounds", SettingsCategory.NOTIFICATIONS, SettingsGroups.NOTIFICATIONS, "sound", "notification-sound"),
        SettingsEntry("Hide details on lock screen", SettingsCategory.NOTIFICATIONS, SettingsGroups.NOTIFICATIONS, "private privacy", "notification-private"),
        SettingsEntry("Send a test notification", SettingsCategory.NOTIFICATIONS, SettingsGroups.NOTIFICATIONS, "test system settings allow"),
        // Help & support
        SettingsEntry("Features & how-to", SettingsCategory.HELP, SettingsGroups.HELP, "guide help steps"),
        SettingsEntry("Support Keepfin", SettingsCategory.SUPPORT, SettingsGroups.SUPPORT, "donate help sponsor tip"),
        // About Keepfin
        SettingsEntry("About Keepfin", SettingsCategory.ABOUT, SettingsGroups.ABOUT_APP, "version licenses credits"),
        SettingsEntry("Check for updates", SettingsCategory.ABOUT, SettingsGroups.UPDATES, "version new release app update"),
        SettingsEntry("Update automatically", SettingsCategory.ABOUT, SettingsGroups.UPDATES, "download engine automatic daily", "setting-auto-update"),
        SettingsEntry("Engine channel", SettingsCategory.ABOUT, SettingsGroups.UPDATES, "stable early access nightly"),
        SettingsEntry("What's new", SettingsCategory.ABOUT, SettingsGroups.UPDATES, "release notes changes"),
        SettingsEntry("Start over", SettingsCategory.ABOUT, SettingsGroups.START_OVER, "reset delete everything erase clean wipe"),
    )

    /** Entries whose title or keywords contain every word typed, on the categories this build has. Title matches come first. */
    fun search(query: String, visibility: SettingsVisibility = SettingsVisibility()): List<SettingsEntry> {
        val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { visibility.shows(it.category) }
            .filter { e -> val hay = (e.title + " " + e.keywords + " " + e.category.title).lowercase(); words.all { it in hay } }
            .sortedBy { e -> if (words.all { it in e.title.lowercase() }) 0 else 1 }
    }
}
