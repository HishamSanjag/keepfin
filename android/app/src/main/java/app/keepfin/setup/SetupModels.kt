package app.keepfin.setup

/** What the app shows instead of itself: nothing, the first start, or the setup of an earlier library. */
internal enum class Gate {
    NONE, FIRST_START, SETUP;

    companion object {
        /**
         * The rule, like the PC's storage phases: the start pages show only while no Keepfin folder is chosen yet and no earlier
         * library waits to be brought over. An unfinished setup resumes first; a chosen folder always means the app itself, so an
         * update, its What's new or Settings never bring the first run back.
         */
        fun of(unfinishedRun: Boolean, folderChosen: Boolean, earlierLibrary: Boolean): Gate = when {
            unfinishedRun -> SETUP
            folderChosen -> NONE
            earlierLibrary -> SETUP
            else -> FIRST_START
        }
    }
}

/**
 * The screens of the flow. First start: WELCOME, WHERE. Setup of an earlier library: WHAT_YOU_HAVE, WHERE, MOVE_OR_LEAVE, REVIEW,
 * RUNNING (with CONSENT and STOPPED as its states), REPORT.
 */
internal enum class SetupStep { WELCOME, WHERE, WHAT_YOU_HAVE, MOVE_OR_LEAVE, REVIEW, RUNNING, CONSENT, STOPPED, REPORT, EARLIER }

internal enum class FolderKind { NEW, EXISTING, NEWER }

/** Which button opened the picker. */
internal enum class PickMode { RECOMMENDED, ANOTHER, EXISTING }

/** The folder the user picked: what it turned out to be. */
internal data class PickedFolder(
    val uri: String,
    /** A readable place, for example "Internal storage > Keepfin". */
    val label: String,
    val kind: FolderKind,
    /** Things already in a folder that is not a Keepfin folder (the setup adds its own folders inside). */
    val existingEntries: Int = 0,
    val freeBytes: Long? = null,
    /** Which button led here. */
    val mode: PickMode = PickMode.ANOTHER,
    /** "I already have a Keepfin folder": the document id of a Keepfin folder inside the chosen one, when there is one. */
    val inner: String? = null,
)

/** Something the screen has to launch for the flow (the system picker or a permission); the screen answers with [SetupActions.requestHandled]. */
internal sealed class SetupRequest {
    /** Open the system folder picker inside [documentId] (for example `primary:Documents/Keepfin`). */
    data class Picker(val documentId: String) : SetupRequest()

    /** Ask for the storage permission that Android 9 and older need to make a folder. */
    data object WritePermission : SetupRequest()
}

/** The step that looks for files an earlier Keepfin saved (they survive an uninstall in Music, Movies and Download). */
internal data class EarlierUi(
    /** True until the user has allowed reading music and videos (or said no). */
    val needsPermission: Boolean,
    val permissions: List<String> = emptyList(),
    /** Found files; zero until a search ran. */
    val files: Int = 0,
    val bytes: Long = 0,
    /** Per place: name and file count. */
    val places: List<Pair<String, Int>> = emptyList(),
    /** Something went wrong, or nothing was found (Settings > Storage shows this). */
    val message: String? = null,
)

internal data class SpaceRow(
    val id: String,
    val name: String,
    val items: Int,
    val bytes: Long,
    /** Files from before a reinstall: removing the originals needs the user's yes. */
    val foreign: Int,
    val choice: Choice,
)

internal data class InventoryUi(
    val spaces: List<SpaceRow>,
    val items: Int,
    val bytes: Long,
    val playlists: Int,
    val downloaded: Int,
    val favourites: Int,
    /** Files an earlier Keepfin saved in Music, Movies and Download that the earlier library did not track. */
    val others: Int = 0,
    val otherBytes: Long = 0,
    /** The other files come along in the plan (the user can switch them off). */
    val includeOthers: Boolean = true,
    /** Looking for the other files needs the music and video permissions first. */
    val otherPermissions: List<String> = emptyList(),
)

internal data class PlanUi(
    val moveCount: Int,
    val moveBytes: Long,
    val leaveCount: Int,
    val foreignMoves: Int,
    val fits: Boolean,
    val freeBytes: Long?,
    /** Permissions the earlier files need before they can be read. Empty when none are missing. */
    val permissions: List<String>,
)

internal data class ProgressUi(val phase: String, val done: Int, val total: Int, val bytesDone: Long, val bytesTotal: Long, val current: String?)

/** What the system shows for the originals that wait for a yes: how many, and the batch number. */
internal data class ConsentUi(val files: Int, val batch: Int, val ofBatches: Int)

internal data class SetupUi(
    val gate: Gate = Gate.NONE,
    val step: SetupStep = SetupStep.WELCOME,
    val busy: Boolean = false,
    val error: String? = null,
    val folder: PickedFolder? = null,
    val inventory: InventoryUi? = null,
    val plan: PlanUi? = null,
    val progress: ProgressUi? = null,
    val consent: ConsentUi? = null,
    /** Why a run stopped before it finished: it carries on from where it stopped. */
    val stopped: String? = null,
    val report: MigrationReport? = null,
    /** True while the work runs in the foreground service. */
    val running: Boolean = false,
    /** The run was picked up again after an interruption and continues by itself ("Continuing…"). */
    val resumed: Boolean = false,
    /** The picker was closed without a choice (first start keeps today's behaviour: downloads go to the public folders). */
    val pickCancelled: Boolean = false,
    /** What the screen must launch next, or null. */
    val request: SetupRequest? = null,
    /** Files from an earlier Keepfin (first start, or Settings > Storage); null when this is not that flow. */
    val earlier: EarlierUi? = null,
    /** First start (the start pages): the Keepfin folder is made, so the start goes on to its last page; the app opens when Start is tapped. */
    val startReady: Boolean = false,
)

/** The pieces of the flow the screens call. A fake implements it in tests. */
internal interface SetupActions {
    fun start()
    fun back()
    /** "Use the recommended place": makes Documents/Keepfin, then asks for the screen to open the picker inside it. */
    fun chooseRecommended() {}
    /** "Choose another place": the picker at the top of the storage. */
    fun chooseAnother() {}
    /** "I already have a Keepfin folder": the picker at the top of the storage; the choice is checked as a Keepfin folder. */
    fun chooseExisting() {}
    /** A Keepfin folder was found inside the chosen one: open the picker inside it. */
    fun chooseInner() {}
    /** The screen launched the request in [SetupUi.request]. */
    fun requestHandled() {}
    /** Android 9 and older: the answer of the storage permission asked for the recommended place. */
    fun writePermissionResult(granted: Boolean) {}
    /** The system picker answered: a tree address, or null when it was closed without a choice. */
    fun folderPicked(uri: String?)
    fun openFolder()
    fun createFolder()
    fun setChoice(spaceId: String, choice: Choice)
    fun review()
    fun begin()
    fun resume()
    fun finish()
    fun permissionsChanged()
    /** The request the system shows for the originals that wait for a yes (null when none can be shown). */
    fun consentRequest(): ConsentRequest?
    fun consentResult(granted: Boolean)
    /** "N other files Keepfin saved before" (upgrade setup): bring them along, or not. */
    fun includeOthers(include: Boolean) {}
    /** The earlier files step: look for them (the screen asks for the media permissions first when they are missing). */
    fun findEarlier() {}
    /** The earlier files step: bring them in (opens move-or-leave with the found files). */
    fun bringEarlier() {}
    /** The earlier files step: not now. First start carries on to the folder; Settings goes back to the app. */
    fun skipEarlier() {}
    /**
     * The last start page: Start was tapped. With Keepfin Complete and the music and video permission held, the files an earlier Keepfin
     * saved are looked for (the step shows only when some are found); otherwise the app opens. The mode itself is saved by the screen.
     */
    fun finishStart(complete: Boolean) {}
}

/** Texts that appear more than once (and are tested). */
internal object SetupTexts {
    const val WELCOME_TITLE = "Welcome to Keepfin"
    const val WELCOME_HINT = "Your music, videos and photos, kept together in one folder on this phone."
    const val WELCOME_START = "Create my Keepfin folder"
    const val WELCOME_CONFIRM = "It goes in Documents › Keepfin. Android then asks you to confirm: tap Use this folder, then Allow."
    const val WELCOME_MORE = "Other options"
    const val NEW_HERE = "I am new here"
    const val NEW_HERE_HINT = "Start fresh. Next you choose where Keepfin keeps its folder."
    const val HAVE_FOLDER = "I have a Keepfin folder"
    const val HAVE_FOLDER_HINT = "From another phone, a computer or a backup: your library, playlists and history come back."
    const val CREATE = "Create Keepfin folder"
    const val RECOMMENDED = "Use the recommended place"
    const val RECOMMENDED_HINT = "Keepfin makes a folder called Keepfin inside Documents and keeps everything there."
    const val CONFIRM_LEAD = "Android will ask you to confirm."
    const val CONFIRM_STEP_1 = "Use this folder"
    const val CONFIRM_STEP_2 = "Allow"
    const val ANOTHER = "Choose another place"
    const val ANOTHER_HINT = "Pick any folder on this phone or its memory card."
    const val WHERE_TITLE = "Where should Keepfin keep everything?"
    const val WHERE_HINT = "Pick a place with room to grow. Your music, videos and library live here."
    const val FORBIDDEN = "Android does not let apps use the top of the phone's storage, the Download folder itself, or Android/data. Documents itself is too wide as well. Choose a folder of its own, for example Keepfin."
    const val INNER_FOUND = "Found a Keepfin folder inside"
    const val EARLIER_TITLE = "Files Keepfin saved before"
    const val EARLIER_ASK = "Keepfin can look in Music, Movies and Download for files an earlier Keepfin saved there. Android asks to let Keepfin read your music and videos, only to find them."
    const val EARLIER_NONE = "Keepfin did not find files from an earlier Keepfin. Nothing was changed."
    const val EARLIER_READ_FAILED = "Keepfin could not look through your music and videos just now. Nothing was changed. Try again from Settings > Storage."
    const val EARLIER_ENTRY = "Bring in files from an earlier Keepfin"
    const val UPGRADE_TITLE = "Keepfin now keeps everything in one folder"
    const val UPGRADE_HINT = "Your downloads were spread over Music, Movies and Download. Now everything lives together in one folder."
    val UPGRADE_GAINS = listOf(
        "One folder for everything" to "Music, videos, playlists and history in one place.",
        "Copies where you want them" to "On a memory card, a USB drive or in the cloud, kept up to date.",
        "Your devices share it" to "Your phone and PC sync and play from each other.",
    )
    const val PICK_CANCELLED = "Choose a place for your Keepfin folder to go on."
    const val NOT_NOW = "Not now"
    const val PICK_CANCELLED_HINT = "Keepfin keeps your library in a folder you own. Choose one to go on."
}
