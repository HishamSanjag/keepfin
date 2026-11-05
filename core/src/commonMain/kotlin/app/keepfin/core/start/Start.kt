package app.keepfin.core.start

// The start pages (dev-docs/plans/START-PAGES.md), the same on both apps: which pages show and in what order, where Skip
// intro goes, when the folder page lets you on, which permission rows the phone shows, and every word on them.

enum class StartPage { WELCOME, HOW, MODE, FOLDER, PERMISSIONS }

/** A phone permission row on the last page. MEDIA finds what an earlier Keepfin saved, so it only shows with the library. */
enum class StartPermission { MEDIA, NOTIFICATIONS }

/** One feature, step or row: a short bold title and a line under it. */
data class StartLine(val title: String, val body: String)

/** Every word on the start pages of one app. */
data class StartWords(
    val welcomeEyebrow: String,
    val welcomeTitle: String,
    val welcomeBody: String,
    val features: List<StartLine>,
    val sitesTitle: String,
    val sites: List<String>,
    val sitesMore: String,
    val musicLinks: String,
    val howEyebrow: String,
    val howTitle: String,
    val steps: List<StartLine>,
    val signIn: StartLine,
    val modeEyebrow: String,
    val modeTitle: String,
    val complete: StartLine,
    val downloads: StartLine,
    val recommended: String,
    val modeLater: String,
    val folderEyebrow: String,
    val folderTitle: String,
    val folderHint: String,
    val haveFolder: String,
    val advanced: String,
    val advancedHint: String,
    val folderLater: String,
    val permissionsEyebrow: String,
    val permissionsTitle: String,
    val media: StartLine,
    val notifications: StartLine,
    val grant: String,
    val granted: String,
    val permissionsLater: String,
    val next: String,
    val back: String,
    val skipIntro: String,
    val start: String,
)

object Start {
    /** The names of the two ways to use Keepfin, also used in Settings. */
    const val COMPLETE = "Keepfin Complete"
    const val DOWNLOADS = "Just downloads"

    /** Windows ends on the folder page; the phone goes on to its permissions. */
    fun pages(phone: Boolean): List<StartPage> =
        if (phone) StartPage.entries.toList() else StartPage.entries.filter { it != StartPage.PERMISSIONS }

    /** Skip intro jumps to the first page that sets something up. */
    val skipTo: StartPage = StartPage.MODE

    /** The pages that only introduce the app, and so offer Skip intro. */
    fun introduces(page: StartPage): Boolean = page == StartPage.WELCOME || page == StartPage.HOW

    /** Next (or a swipe) never passes the folder page before a folder is chosen; every other page lets you on. */
    fun canGoOn(page: StartPage, folderReady: Boolean): Boolean = page != StartPage.FOLDER || folderReady

    /** The page after [page], or null on the last one. */
    fun next(page: StartPage, phone: Boolean): StartPage? = pages(phone).let { it.getOrNull(it.indexOf(page) + 1) }

    /** The page before [page], or null on the first one. */
    fun previous(page: StartPage, phone: Boolean): StartPage? = pages(phone).let { it.getOrNull(it.indexOf(page) - 1) }

    /** The phone's permission rows: bringing in earlier files is a library feature, so Just downloads only gets notifications. */
    fun permissions(complete: Boolean): List<StartPermission> =
        if (complete) listOf(StartPermission.MEDIA, StartPermission.NOTIFICATIONS) else listOf(StartPermission.NOTIFICATIONS)

    fun words(phone: Boolean): StartWords {
        val device = if (phone) "phone" else "computer"
        return StartWords(
            welcomeEyebrow = "Welcome to Keepfin",
            welcomeTitle = "Keep the videos and music you love",
            welcomeBody = "Download from a link and keep everything in one library on your $device. Downloads run on your $device, with no account.",
            features = listOf(
                StartLine("Download", "videos, songs, photo posts and whole playlists"),
                StartLine("Play", if (phone) "with lyrics, in the background too" else "here, with synced lyrics, a queue and resume"),
                StartLine("Follow", "playlists and channels: new ones arrive by themselves"),
                StartLine("One library", if (phone) "on your phone and PC" else "on your PC and phone, with a copy on another drive"),
            ),
            // sites the providers list knows by name (core providers); the music services are matched, never downloaded from
            sitesTitle = "Works with",
            sites = listOf("YouTube", "YouTube Music", "Facebook", "Instagram", "TikTok", "X", "Vimeo", "SoundCloud", "Reddit"),
            sitesMore = "and many more",
            musicLinks = "Spotify, Apple Music and Deezer links find the same song on YouTube Music.",
            howEyebrow = "How it works",
            howTitle = "Three steps, and it's yours",
            steps = if (phone) listOf(
                StartLine("Share a link to Keepfin", "In YouTube or any app: Share › Keepfin. Or paste it on Home."),
                StartLine("Choose song or video", "Quality, lyrics, or every item in a playlist."),
                StartLine("Tap Download", "It lands in your library. Nothing downloads until you tap."),
            ) else listOf(
                StartLine("Copy a link", "From YouTube, a music service or a photo post. A playlist or a channel works too."),
                StartLine("Paste it on Home", "Keepfin shows what's there: the video, the song or every item in the playlist."),
                StartLine("Choose, then Download", "Song or video, quality, lyrics. Nothing downloads until you press Download."),
            ),
            signIn = if (phone) StartLine(
                "Need to sign in?",
                "Private, age-restricted or members-only videos need your account. Sign in inside Keepfin: Settings › Downloads › Sign in to a site.",
            ) else StartLine(
                "Need to sign in?",
                "Private, age-restricted or members-only videos need your account. Keepfin can use the sign-in from a browser you choose: Settings › Downloads › Website sign-in.",
            ),
            modeEyebrow = "Set up",
            modeTitle = "How will you use Keepfin?",
            complete = StartLine(COMPLETE, if (phone) "Download, then browse and play it all here." else "Download, then browse and play it all here: spaces, Search, synced lyrics and a queue."),
            downloads = StartLine(DOWNLOADS, if (phone) "Files go to your folder; open them in other apps." else "Paste a link, pick a quality, and the file lands in your folder."),
            recommended = "Recommended",
            modeLater = "Change it any time in Settings.",
            folderEyebrow = if (phone) "Set up · needed" else "Last step · needed",
            folderTitle = "Where should your files live?",
            folderHint = if (phone) "Everything goes into one Keepfin folder. Android asks you to allow it once." else "Everything goes into one Keepfin folder. Pick a drive with room to grow.",
            haveFolder = "I already have a Keepfin folder",
            advanced = "Advanced setup",
            advancedHint = "Every storage step: a new or existing folder, all the folder details${if (phone) "" else ", and extra copies"}.",
            folderLater = "You can move it later in Settings › Storage.",
            permissionsEyebrow = "Last step · optional",
            permissionsTitle = "Let Keepfin do a bit more",
            media = StartLine("Music and videos", "Find what Keepfin saved before and add it to your library"),
            notifications = StartLine("Notifications", "Download progress and play controls"),
            grant = "Grant",
            granted = "Granted",
            permissionsLater = "Keepfin works without them. You can grant them later in Settings.",
            next = "Next",
            back = "Back",
            skipIntro = "Skip intro",
            start = "Start using Keepfin",
        )
    }
}
