package app.keepfin.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import app.keepfin.LibraryCodec
import app.keepfin.LibraryHost
import app.keepfin.LibrarySettings
import app.keepfin.EngineSettings
import app.keepfin.Job
import app.keepfin.SpaceLogic
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpenFolderResult
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.writeLocalAtomic
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.LibrarySelector
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/** What the setup needs from the engine (implemented by [app.keepfin.MediaEngine]). */
internal interface SetupEngine {
    /** The library settings of the MediaStore library (spaces, routes). */
    fun legacyLibrarySettings(): LibrarySettings

    /** The saved engine settings text, or null. Backed up before anything changes. */
    fun legacySettingsJson(): String?

    /** Switches the engine to the library on the Keepfin folder without an app restart. */
    fun attachFolderLibrary()

    /** A moved file is in the Keepfin folder: download-history rows that named the original follow it. */
    fun relocateFile(oldUri: String, newUri: String, path: String) {}

    /** Repair: a history row marked "file removed" by the move gets the new place of the file of its job. True when a row changed. */
    fun relocateJob(jobId: String, newUri: String, path: String): Boolean = false
}

/** A library host with nothing behind it: the setup's own repository only writes the migrated library. */
internal class QuietHost(settings: LibrarySettings) : LibraryHost {
    override val jobs = MutableStateFlow(emptyList<Job>())
    override val settings = MutableStateFlow(EngineSettings(library = settings))
    override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settings.value = transform(settings.value) }
    override fun jobThumbnail(jobId: String) = null
    override fun sidecarUris() = emptySet<String>()
    override fun repointFile(uri: String, publicPath: String?) {}
    override fun requestCheck(id: String?, review: Boolean) {}
    override fun followSettingsChanged() {}
}

/**
 * First start and setup of an earlier library (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md sections 5 and 6). Owns the state of the
 * flow ([ui]) and the files of a run (`filesDir/migration/<runId>/`: plan, backups, journal, report). The work runs on one worker
 * thread, or in [MigrationService] for the copy (a foreground service of type dataSync started from the screen).
 *
 * The engine keeps the MediaStore library until the run is finished and acknowledged; only then does [SetupEngine.attachFolderLibrary]
 * switch it. Cancelling the first start keeps today's behaviour: downloads go to the public folders and the library stays off.
 */
internal class StorageSetup(
    private val context: Context,
    private val engine: SetupEngine,
    private val now: () -> Long = System::currentTimeMillis,
) : SetupActions {
    private val files = context.filesDir
    private val profiles = ProfileStore(File(files, "profiles.json"))
    private val prefs = context.getSharedPreferences("storage-setup", Context.MODE_PRIVATE)
    private val migrationRoot = File(files, "migration")
    private val activeFile = File(migrationRoot, "active.json")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keepfin-setup").also { it.isDaemon = true } }
    private val stopFlag = AtomicBoolean(false)

    private val state = MutableStateFlow(SetupUi())
    val ui: StateFlow<SetupUi> = state.asStateFlow()

    @Volatile private var inventory: LegacyInventory? = null
    @Volatile private var choices: Map<String, Choice> = emptyMap()
    /** What the person chose themselves: defaults never replace these (a new folder or the other files keep a "Keep in place"). */
    @Volatile private var explicit: Map<String, Choice> = emptyMap()
    @Volatile private var plan: MigrationPlan? = null
    @Volatile private var pickedUri: Uri? = null
    @Volatile private var session: Session? = null
    @Volatile private var pendingBatches: List<ConsentBatch> = emptyList()
    @Volatile private var currentBatch = -1
    @Volatile private var pickMode = PickMode.ANOTHER
    /** The run brings in files from an earlier Keepfin (there is no library.json to back up). */
    @Volatile private var earlierMode = false
    @Volatile private var earlierRows: List<EarlierRow> = emptyList()
    private val maker by lazy { PlatformMaker(context) }

    /** [shared]: the repository belongs to the running app (Settings > Storage), so the run must not close it. */
    private class Session(
        val runId: String, val plan: MigrationPlan, val inventory: LegacyInventory, val repo: LibraryRepository,
        val journal: MigrationJournal, val runner: MigrationRunner, val shared: Boolean = false,
    )

    init {
        refresh()
        // a run of an earlier version left history rows pointing at the old places: they follow their files once
        worker.execute { repairHistory() }
    }

    /**
     * Download-history rows whose file the setup moved into the Keepfin folder follow it (matched by the old address, or by the job the
     * library item came from). Done once per run; safe to repeat. Runs on the worker.
     */
    internal fun repairHistory() {
        try {
            profiles.current() ?: return
            val running = activeRun()
            val dirs = migrationRoot.listFiles { f -> f.isDirectory }.orEmpty().filter { it.name != running }
            var access: SafFolderAccess? = null
            for (dir in dirs) {
                val mark = "history-repaired-${dir.name}"
                if (prefs.getBoolean(mark, false)) continue
                val planFile = File(dir, "plan.json")
                val library = File(dir, "backup/library.json")
                if (!planFile.isFile || !library.isFile) continue
                val plan = MigrationPlan.fromJson(planFile.readText())
                val recs = LibraryCodec.decodeFile(library.readText()).items.associateBy { it.item.id }
                val a = access ?: SafFolderAccess.open(context.contentResolver, Uri.parse(profiles.current()!!.folderTreeUri)).also { access = it }
                for (p in plan.items) {
                    if (p.choice != Choice.MOVE) continue
                    if (a.stat(p.dest) == null) continue
                    val uri = a.documentUriFor(p.dest).toString()
                    engine.relocateFile(p.oldId, uri, p.dest)
                    recs[p.oldId]?.item?.jobId?.let { engine.relocateJob(it, uri, p.dest) }
                }
                prefs.edit().putBoolean(mark, true).apply()
            }
        } catch (e: Exception) {
            // the history keeps what it has; the next start tries again
        }
    }

    // ------------------------------------------------------------------------------------------------ the gate

    private fun activeRun(): String? = try {
        if (activeFile.isFile) JSONObject(activeFile.readText()).optString("runId").takeIf { it.isNotEmpty() } else null
    } catch (e: Exception) {
        null
    }

    /** True when the earlier library holds something worth bringing over (an empty file is a new install). */
    private fun hasLegacyLibrary(): Boolean {
        val f = File(files, "library.json")
        return f.isFile && f.length() > 300
    }

    /** Works out what to show: the first start, the setup, or the app. */
    fun refresh() {
        val active = activeRun()
        // "Not now" on the folder steps is gone: an earlier skip no longer counts
        if (prefs.contains("skipped")) prefs.edit().remove("skipped").apply()
        val gate = Gate.of(unfinishedRun = active != null, folderChosen = profiles.current() != null, earlierLibrary = hasLegacyLibrary())
        if (gate == state.value.gate && gate == Gate.NONE) return
        when {
            gate == Gate.SETUP && active != null -> {
                state.value = SetupUi(gate = gate, step = SetupStep.RUNNING, busy = true)
                worker.execute { restoreRun(active) }
            }
            gate == Gate.SETUP -> {
                state.value = SetupUi(gate = gate, step = SetupStep.WHAT_YOU_HAVE, busy = true)
                worker.execute { loadInventory() }
            }
            else -> state.value = SetupUi(gate = gate, step = SetupStep.WELCOME)
        }
    }

    /** True when a Keepfin folder is in use. */
    fun hasFolder(): Boolean = profiles.current() != null && activeRun() == null

    fun currentProfile(): Profile? = profiles.current()

    @Volatile private var baseInventory: LegacyInventory? = null
    @Volatile private var otherRows: List<EarlierRow> = emptyList()
    @Volatile private var includeOther = true
    @Volatile private var otherPermissions: List<String> = emptyList()

    private fun loadInventory() {
        try {
            val text = File(files, "library.json").readText(Charsets.UTF_8)
            val inv = LegacyInventories.read(text, engine.legacyLibrarySettings())
            baseInventory = inv
            findOthers()
            applyOthers()
        } catch (e: Exception) {
            state.update { it.copy(busy = false, error = "Keepfin could not read your library. Nothing was changed.") }
        }
    }

    /** Files an earlier Keepfin saved in Music, Movies and Download that the earlier library does not hold. Skipped quietly when they cannot be read. */
    private fun findOthers() {
        val base = baseInventory ?: return
        val needed = EarlierFiles.missingPermissions(context)
        otherPermissions = if (needed.isNotEmpty() && EarlierFiles.hint()) needed else emptyList()
        otherRows = try { EarlierFiles.untracked(base, EarlierFiles.find(context).orEmpty()) } catch (e: Exception) { emptyList() }
    }

    /** The inventory in use: the earlier library, plus the other files when there are some and the user keeps them in. */
    private fun applyOthers() {
        val base = baseInventory ?: return
        val inv = if (includeOther && otherRows.isNotEmpty()) EarlierFiles.merge(base, EarlierFiles.inventory(otherRows)) else base
        inventory = inv
        choices = inv.spaces.associate { it.space.id to (explicit[it.space.id] ?: Choice.MOVE) }
        state.update { it.copy(busy = false, inventory = inventoryUi(inv, choices), error = null) }
    }

    override fun includeOthers(include: Boolean) {
        includeOther = include
        applyOthers()
        if (state.value.step == SetupStep.REVIEW) review()
    }

    private fun inventoryUi(inv: LegacyInventory, choices: Map<String, Choice>): InventoryUi {
        val extra = if (otherRows.isEmpty()) 0 else EarlierFiles.inventory(otherRows).let { it.itemCount }
        val extraBytes = if (otherRows.isEmpty()) 0L else EarlierFiles.inventory(otherRows).bytes
        return InventoryUi(
            spaces = inv.spaces.map { SpaceRow(it.space.id, it.space.name, it.count, it.bytes, it.foreign, choices[it.space.id] ?: Choice.MOVE) },
            items = inv.itemCount, bytes = inv.bytes, playlists = inv.playlists, downloaded = inv.downloaded, favourites = inv.favourites,
            others = extra, otherBytes = extraBytes, includeOthers = includeOther, otherPermissions = otherPermissions,
        )
    }

    // ------------------------------------------------------------------------------------------------ steps

    override fun start() {
        val s = state.value
        when (s.step) {
            SetupStep.WELCOME, SetupStep.WHAT_YOU_HAVE -> state.update { it.copy(step = SetupStep.WHERE, error = null) }
            SetupStep.WHERE -> if (s.gate == Gate.SETUP && s.folder != null && s.folder.kind != FolderKind.NEWER) moveOrLeave()
            SetupStep.MOVE_OR_LEAVE -> review()
            else -> {}
        }
    }

    override fun back() {
        state.update {
            when (it.step) {
                SetupStep.WHERE -> it.copy(step = if (it.gate == Gate.SETUP) SetupStep.WHAT_YOU_HAVE else SetupStep.WELCOME, error = null)
                SetupStep.MOVE_OR_LEAVE -> it.copy(step = if (earlierMode) SetupStep.EARLIER else SetupStep.WHERE, error = null)
                // the plan is one screen with the place and the choices: back is the step before it
                SetupStep.REVIEW -> it.copy(step = if (earlierMode) SetupStep.EARLIER else if (it.gate == Gate.SETUP) SetupStep.WHAT_YOU_HAVE else SetupStep.MOVE_OR_LEAVE, error = null)
                else -> it
            }
        }
    }

    private fun moveOrLeave() {
        val inv = inventory ?: return
        val free = state.value.folder?.freeBytes
        val ids = inv.spaces.mapTo(HashSet()) { it.space.id }
        choices = Planner.defaultChoices(inv, free) + explicit.filterKeys { it in ids }
        state.update { it.copy(step = SetupStep.MOVE_OR_LEAVE, inventory = inventoryUi(inv, choices), error = null) }
    }

    override fun setChoice(spaceId: String, choice: Choice) {
        val inv = inventory ?: return
        choices = choices + (spaceId to choice)
        explicit = explicit + (spaceId to choice)
        state.update { it.copy(inventory = inventoryUi(inv, choices)) }
        // on the one-screen plan a change shows its new totals right away
        if (state.value.step == SetupStep.REVIEW) review()
    }

    override fun review() {
        val inv = inventory ?: return
        val built = Planner.plan(inv, choices, UUID.randomUUID().toString(), now()) { UUID.randomUUID().toString() }
        plan = built
        val free = state.value.folder?.freeBytes
        state.update { it.copy(step = SetupStep.REVIEW, plan = planUi(built, free), error = null) }
    }

    private fun planUi(p: MigrationPlan, free: Long?) = PlanUi(
        moveCount = p.moveCount, moveBytes = p.bytesToCopy, leaveCount = p.leaveCount, foreignMoves = p.foreignMoves,
        fits = Planner.fits(p, free), freeBytes = free, permissions = if (p.foreignMoves > 0) missingReadPermissions() else emptyList(),
    )

    /** The permissions the earlier files from before a reinstall need before they can be read. */
    private fun missingReadPermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        return needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }

    override fun permissionsChanged() {
        if (state.value.step == SetupStep.EARLIER) { searchEarlier(); return }
        if (state.value.step == SetupStep.WHAT_YOU_HAVE) {
            state.update { it.copy(busy = true) }
            worker.execute { findOthers(); applyOthers() }
            return
        }
        val p = plan ?: return
        state.update { it.copy(plan = planUi(p, it.folder?.freeBytes)) }
    }

    // ------------------------------------------------------------------------------------------------ the folder

    private fun friendly(e: Throwable): String = when (e) {
        is FolderError.NeedsAccess -> "Keepfin needs access to this folder. Choose it again."
        is FolderError.Unavailable -> "That place is not available. If it is on a memory card, check that the card is in."
        is FolderError.UnsupportedProvider -> "Keepfin can keep its folder on this phone or its memory card for now. Pick a folder there."
        is FolderError.DiskFull -> "There is not enough space left in that place."
        is FolderError -> e.message ?: "Keepfin could not use that folder."
        else -> e.message ?: "Keepfin could not use that folder."
    }

    // ---- the three buttons

    /** "Use the recommended place": makes Documents/Keepfin (never blocks), then asks the screen to open the picker inside it. */
    override fun chooseRecommended() {
        android.util.Log.i("KeepfinSetup", "chooseRecommended")
        pickMode = PickMode.RECOMMENDED
        state.update { it.copy(busy = true, error = null, pickCancelled = false, folder = null) }
        worker.execute { handle(RecommendedPlace.prepare(Build.VERSION.SDK_INT, PlatformMaker.canWrite(context), maker)) }
    }

    override fun writePermissionResult(granted: Boolean) {
        state.update { it.copy(busy = true) }
        worker.execute { handle(RecommendedPlace.afterPermission(granted, maker)) }
    }

    private fun handle(outcome: RecommendedPlace.Outcome) {
        android.util.Log.i("KeepfinSetup", "recommended place: $outcome")
        val request = when (outcome) {
            is RecommendedPlace.Outcome.Open -> SetupRequest.Picker(outcome.documentId)
            RecommendedPlace.Outcome.AskPermission -> SetupRequest.WritePermission
        }
        state.update { it.copy(busy = false, request = request) }
    }

    /** "Choose another place": the picker at the top of the storage; memory cards are listed there too. */
    override fun chooseAnother() {
        pickMode = PickMode.ANOTHER
        state.update { it.copy(error = null, pickCancelled = false, request = SetupRequest.Picker(RecommendedPlace.STORAGE_ROOT_ID)) }
    }

    /** "I already have a Keepfin folder": the picker at the top of the storage; the folder is checked as a Keepfin folder. */
    override fun chooseExisting() {
        pickMode = PickMode.EXISTING
        state.update { it.copy(error = null, pickCancelled = false, request = SetupRequest.Picker(RecommendedPlace.STORAGE_ROOT_ID)) }
    }

    /** A Keepfin folder was found inside the chosen one: the picker opens in it. */
    override fun chooseInner() {
        val inner = state.value.folder?.inner ?: return
        pickMode = PickMode.EXISTING
        state.update { it.copy(error = null, pickCancelled = false, folder = null, request = SetupRequest.Picker(inner)) }
    }

    override fun requestHandled() {
        android.util.Log.i("KeepfinSetup", "requestHandled")
        state.update { if (it.request == null) it else it.copy(request = null) }
    }

    override fun folderPicked(uri: String?) {
        android.util.Log.i("KeepfinSetup", "picker answered: $uri")
        if (uri == null) {
            state.update { it.copy(pickCancelled = true, busy = false) }
            return
        }
        state.update { it.copy(busy = true, error = null, pickCancelled = false) }
        worker.execute { inspect(Uri.parse(uri)) }
    }

    private fun inspect(tree: Uri) {
        try {
            if (!FolderPlaces.supportedAuthority(tree.authority)) throw FolderError.UnsupportedProvider(tree.authority ?: "")
            val docId = DocumentsContract.getTreeDocumentId(tree)
            if (FolderPlaces.forbidden(docId)) {
                state.update { it.copy(busy = false, folder = null, error = SetupTexts.FORBIDDEN) }
                return
            }
            val cr = context.contentResolver
            try { cr.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: SecurityException) { /* the open below reports a missing grant */ }
            val access = SafFolderAccess.open(cr, tree)
            val label = FolderPlaces.label(docId)
            val free = access.freeBytes()
            val mode = pickMode
            val folder = when (val opened = KeepfinFolders.open(access)) {
                is OpenFolderResult.Ok -> PickedFolder(tree.toString(), label, if (opened.readOnly) FolderKind.NEWER else FolderKind.EXISTING, 0, free, mode)
                OpenFolderResult.Corrupt -> {
                    state.update { it.copy(busy = false, folder = null, error = "That folder holds Keepfin data that could not be read. Choose another folder.") }
                    return
                }
                else -> {
                    val top = access.list("").entries
                    // "I already have a Keepfin folder": the chosen folder may hold it in a folder called Keepfin
                    val inner = if (mode == PickMode.EXISTING) FolderPlaces.innerName(top.filter { it.isDirectory }.map { it.name })
                        ?.takeIf { access.stat("$it/${KeepfinFolders.FOLDER_JSON}") != null }?.let { "$docId/$it" } else null
                    PickedFolder(tree.toString(), label, FolderKind.NEW, top.count { it.name != ".keepfin" && !(it.name == "README.txt" && !it.isDirectory && it.size < 400) }, free, mode, inner)
                }
            }
            pickedUri = tree
            val cont = state.value.gate == Gate.FIRST_START && folder.kind != FolderKind.NEWER &&
                mode == PickMode.RECOMMENDED && docId == RecommendedPlace.DOCUMENT_ID
            // the recommended place was confirmed in the system dialog already: carry on without another tap
            state.update { it.copy(busy = cont, folder = folder, error = null, step = if (it.step == SetupStep.WELCOME && mode != PickMode.EXISTING) SetupStep.WHERE else it.step) }
            if (cont) confirmFolder()
            // the setup of an earlier library is one screen: with the place known, the plan is ready at once (Start is the only step left)
            else if (state.value.gate == Gate.SETUP && state.value.step == SetupStep.WHERE && folder.kind != FolderKind.NEWER) { moveOrLeave(); review() }
            // another place chosen on the plan: its free space is planned again, and a folder of a newer Keepfin cannot be started
            else if (state.value.gate == Gate.SETUP && state.value.step == SetupStep.REVIEW) {
                if (folder.kind == FolderKind.NEWER) {
                    plan = null
                    state.update { it.copy(step = SetupStep.WHERE, plan = null) }
                } else review()
            }
        } catch (e: Exception) {
            state.update { it.copy(busy = false, folder = null, error = friendly(e)) }
        }
    }

    override fun createFolder() { confirmFolder() }

    override fun openFolder() { confirmFolder() }

    private fun attach() {
        engine.attachFolderLibrary()
        state.value = SetupUi()
    }

    /**
     * The folder is chosen. On the first start the profile is made and the start pages go on to their permissions page (`startReady`);
     * the app opens when Start is tapped ([finishStart]). Anywhere else the app opens at once.
     */
    private fun confirmFolder() {
        if (state.value.startReady) return
        state.update { it.copy(busy = true, error = null) }
        worker.execute {
            try {
                val first = state.value.gate == Gate.FIRST_START
                createProfile()
                if (first) state.update { it.copy(busy = false, startReady = true) } else attach()
            } catch (e: Exception) {
                state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    override fun finishStart(complete: Boolean) {
        if (!state.value.startReady || state.value.busy) return
        state.update { it.copy(busy = true, error = null) }
        worker.execute {
            try {
                // an earlier Keepfin's files are a library matter, and only readable once music and videos were allowed on the last page
                if (complete && EarlierFiles.missingPermissions(context).isEmpty()) {
                    earlierMode = true
                    firstStartEarlier = true
                    searchEarlier(blocking = true)
                } else attach()
            } catch (e: Exception) {
                state.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    // ---- files an earlier Keepfin saved (Music/Keepfin, Movies/Keepfin, Download/Keepfin survive an uninstall)

    /** Settings > Storage: "Bring in files from an earlier Keepfin". Needs a Keepfin folder in use. */
    fun openEarlier() {
        val profile = profiles.current() ?: return
        earlierMode = true
        firstStartEarlier = false
        val needed = EarlierFiles.missingPermissions(context)
        state.value = SetupUi(gate = Gate.SETUP, step = SetupStep.EARLIER, busy = true, earlier = EarlierUi(needsPermission = needed.isNotEmpty(), permissions = needed))
        worker.execute {
            val tree = Uri.parse(profile.folderTreeUri)
            val folder = try {
                val access = SafFolderAccess.open(context.contentResolver, tree)
                PickedFolder(profile.folderTreeUri, FolderPlaces.label(DocumentsContract.getTreeDocumentId(tree)), FolderKind.EXISTING, 0, access.freeBytes())
            } catch (e: Exception) {
                null
            }
            state.update { it.copy(folder = folder, busy = needed.isEmpty()) }
            if (needed.isEmpty()) searchEarlier(blocking = true) else state.update { it.copy(busy = false) }
        }
    }

    override fun findEarlier() { searchEarlier() }

    private fun searchEarlier(blocking: Boolean = false) {
        val work = {
            val needed = EarlierFiles.missingPermissions(context)
            if (needed.isNotEmpty()) {
                // asked and refused: a first start skips silently (Settings > Storage keeps the entry); Settings says so
                if (firstStartEarlier) leaveEarlier()
                else state.update { it.copy(busy = false, step = SetupStep.EARLIER, earlier = EarlierUi(needsPermission = false, message = SetupTexts.EARLIER_NONE)) }
            } else {
                state.update { it.copy(busy = true) }
                val rows = try { EarlierFiles.find(context) } catch (e: Exception) { null }
                when {
                    rows == null -> state.update { it.copy(busy = false, earlier = EarlierUi(needsPermission = false, message = SetupTexts.EARLIER_READ_FAILED), step = SetupStep.EARLIER) }
                    EarlierFiles.inventory(rows).itemCount == 0 -> {
                        // nothing there: a first start just opens the app, Settings says so
                        if (firstStartEarlier) leaveEarlier()
                        else state.update { it.copy(busy = false, step = SetupStep.EARLIER, earlier = EarlierUi(needsPermission = false, message = SetupTexts.EARLIER_NONE)) }
                    }
                    else -> {
                        earlierRows = rows
                        val inv = EarlierFiles.inventory(rows)
                        inventory = inv
                        state.update { it.copy(busy = false, step = SetupStep.EARLIER, earlier = EarlierFiles.summary(inv)) }
                    }
                }
            }
        }
        if (blocking) work() else { state.update { it.copy(busy = true) }; worker.execute { work() } }
    }

    @Volatile private var firstStartEarlier = false

    /** First start: carry on to the app. Settings: close the step. */
    private fun leaveEarlier() {
        earlierMode = false
        firstStartEarlier = false
        if (engineAttached()) state.value = SetupUi() else attach()
    }

    private fun engineAttached(): Boolean = (engine as? app.keepfin.MediaEngine)?.folderLibrary != null

    override fun bringEarlier() {
        if (inventory == null) return
        earlierMode = true
        // the folder is chosen already: straight to the one-screen plan
        moveOrLeave()
        review()
    }

    override fun skipEarlier() { leaveEarlier() }

    /** Creates the folder (or opens the existing one) and records the profile. A new actor is made either way. */
    private fun createProfile(): Profile {
        val tree = pickedUri ?: throw IllegalStateException("Choose a folder first.")
        val access = SafFolderAccess.open(context.contentResolver, tree)
        val nowIso = Instant.ofEpochMilli(now()).toString()
        val folder = when (val opened = KeepfinFolders.open(access)) {
            is OpenFolderResult.Ok -> opened.folder
            OpenFolderResult.Corrupt -> throw FolderError.Io("That folder holds Keepfin data that could not be read.")
            else -> KeepfinFolders.create(access, UUID.randomUUID().toString(), UUID.randomUUID().toString(), nowIso)
        }
        val profile = Profile(UUID.randomUUID().toString(), Build.MODEL ?: "Android", tree.toString(), folder.libraryId, UUID.randomUUID().toString(), nowIso)
        profiles.add(profile)
        return profile
    }

    // ------------------------------------------------------------------------------------------------ the run

    override fun begin() {
        val p = plan ?: return
        val inv = inventory ?: return
        if (state.value.folder?.kind == FolderKind.NEWER) return
        state.update { it.copy(busy = true, error = null) }
        worker.execute {
            try {
                val dir = File(migrationRoot, p.runId)
                val backup = File(dir, "backup")
                backup.mkdirs()
                if (earlierMode) {
                    // files an earlier Keepfin saved have no library.json: the inventory built from MediaStore stands in for it
                    File(backup, "library.json").writeText(LibraryCodec.encodeFile(inv.items, emptyList()), Charsets.UTF_8)
                } else if (includeOther && otherRows.isNotEmpty()) {
                    // the other files are part of the run, so a resumed run must find them: the backup holds the library as it was plus them
                    File(backup, "library.json").writeText(LibraryCodec.encodeFile(inv.items, inv.collections), Charsets.UTF_8)
                } else File(files, "library.json").copyTo(File(backup, "library.json"), overwrite = true)
                engine.legacySettingsJson()?.let { File(backup, "engine-settings.json").writeText(it) }
                File(backup, "library-settings.json").writeText(SpaceLogic.encode(inv.settings).toString())
                writeLocalAtomic(File(dir, "plan.json"), p.toJson().toByteArray(Charsets.UTF_8))
                // the first start and Settings made the profile already; the setup of an earlier library makes it here
                if (profiles.current() == null) createProfile()
                writeLocalAtomic(activeFile, JSONObject().put("runId", p.runId).toString().toByteArray(Charsets.UTF_8))
                state.update { it.copy(step = SetupStep.RUNNING, busy = false, running = true, progress = null, stopped = null) }
                MigrationService.start(context)
            } catch (e: Exception) {
                state.update { it.copy(busy = false, error = "Setup could not start: ${friendly(e)} Nothing was changed.") }
            }
        }
    }

    override fun resume() {
        state.update { it.copy(running = true, stopped = null, error = null, step = SetupStep.RUNNING) }
        MigrationService.start(context)
    }

    private fun restoreRun(runId: String) {
        try {
            val dir = File(migrationRoot, runId)
            val plan = MigrationPlan.fromJson(File(dir, "plan.json").readText())
            val libraryText = File(dir, "backup/library.json").readText()
            val settings = SpaceLogic.decode(JSONObject(File(dir, "backup/library-settings.json").readText()))
            val inv = LegacyInventories.read(libraryText, settings)
            this.plan = plan
            inventory = inv
            // an interrupted run carries on by itself, once; if that fails the run shows its error and the Continue button
            state.update { it.copy(busy = false, step = SetupStep.RUNNING, running = true, resumed = true, plan = planUi(plan, null), inventory = inventoryUi(inv, plan.spaces.associate { s -> s.id to s.choice }),
                stopped = null) }
            try {
                MigrationService.start(context)
            } catch (e: Exception) {
                state.update { it.copy(running = false, stopped = "Setup was interrupted and could not carry on by itself: ${e.message ?: "Android did not start it"}. Press Continue.") }
            }
        } catch (e: Exception) {
            state.update { it.copy(busy = false, step = SetupStep.STOPPED, stopped = "Setup could not be picked up again: ${e.message}. Nothing was lost; your earlier library is still there.") }
        }
    }

    /** Android refused to start the service (the dataSync time limit passed in the background): the screen starts it again from the front. */
    fun serviceRefused() {
        state.update { it.copy(running = false, step = SetupStep.RUNNING, stopped = "Android did not let setup run in the background just now. Press Continue with Keepfin open.") }
    }

    /** The service asks the run to end within a second (the dataSync time limit). */
    fun requestStop() { stopFlag.set(true) }

    /** Blocks: runs the migration until it finishes, needs the user, or [requestStop]. Called from [MigrationService]. */
    fun runMigration() {
        stopFlag.set(false)
        try {
            val s = session ?: openSession().also { session = it }
            apply(s.runner.run(), s)
        } catch (e: Throwable) {
            state.update { it.copy(running = false, step = SetupStep.STOPPED, stopped = "Setup stopped: ${e.message ?: e.javaClass.simpleName}. Nothing was lost; it carries on from where it stopped.") }
        }
    }

    private fun apply(outcome: RunOutcome, s: Session) {
        when (outcome) {
            is RunOutcome.Completed -> {
                try { File(migrationRoot, "${s.runId}/report.json").writeText(outcome.report.toJson()) } catch (e: Exception) { /* the report is also on screen */ }
                state.update { it.copy(running = false, step = SetupStep.REPORT, report = outcome.report, consent = null, stopped = null) }
            }
            RunOutcome.Paused -> state.update { it.copy(running = false, step = SetupStep.RUNNING, stopped = "Setup is paused. It carries on where it stopped.") }
            is RunOutcome.Stopped -> state.update { it.copy(running = false, step = SetupStep.STOPPED, stopped = outcome.message) }
            is RunOutcome.NeedsConsent -> {
                pendingBatches = outcome.batches
                val files = outcome.batches.sumOf { it.ids.size }
                state.update { it.copy(running = false, step = SetupStep.CONSENT, consent = ConsentUi(files, 1, outcome.batches.size)) }
            }
        }
    }

    private fun openSession(): Session {
        val runId = activeRun() ?: throw IllegalStateException("No setup is running.")
        val dir = File(migrationRoot, runId)
        val p = plan ?: MigrationPlan.fromJson(File(dir, "plan.json").readText())
        val inv = inventory ?: LegacyInventories.read(File(dir, "backup/library.json").readText(), SpaceLogic.decode(JSONObject(File(dir, "backup/library-settings.json").readText())))
        // Settings > Storage: the app already runs on this folder, and a second store on it would fight the first, so the run uses the live one
        val live = (engine as? app.keepfin.MediaEngine)?.folderLibrary
        val repo = live ?: LibrarySelector.create(context, QuietHost(inv.settings), profiles, forMigration = true) ?: throw IllegalStateException("The Keepfin folder is missing.")
        if (live == null) {
            repo.start()
            repo.awaitReady()
        }
        val journal = MigrationJournal.open(dir)
        val runner = MigrationRunner(
            p, inv, MediaStoreFiles(context), RepositoryTarget(repo, repo.folderAccess()), journal, shouldStop = { stopFlag.get() },
            onProgress = { pr -> state.update { it.copy(progress = ProgressUi(pr.phase, pr.done, pr.total, pr.bytesDone, pr.bytesTotal, pr.current)) } },
            consentBatch = Consent.batchSize,
            relocated = { old, uri, path -> engine.relocateFile(old, uri, path) },
        )
        return Session(runId, p, inv, repo, journal, runner, shared = live != null)
    }

    /** The user pressed the button on the consent screen: the request to show (its batch is journaled first). */
    override fun consentRequest(): ConsentRequest? {
        val s = session ?: return null
        val batch = pendingBatches.firstOrNull() ?: return null
        currentBatch = s.runner.beginConsent(batch.ids)
        return Consent.request(context, batch.ids.map { it })
    }

    override fun consentResult(granted: Boolean) {
        val s = session ?: return
        state.update { it.copy(busy = true) }
        worker.execute {
            try {
                if (currentBatch >= 0) s.runner.consentResult(currentBatch, granted)
                // "Keep the old copies" answers without asking Android, so no batch was begun: every waiting batch keeps its originals
                else if (!granted) for (batch in pendingBatches) s.runner.consentResult(s.runner.beginConsent(batch.ids), false)
                currentBatch = -1
                apply(s.runner.run(), s)
                state.update { it.copy(busy = false) }
            } catch (e: Throwable) {
                state.update { it.copy(busy = false, step = SetupStep.STOPPED, stopped = "Setup stopped: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    override fun finish() {
        state.update { it.copy(busy = true) }
        worker.execute {
            try {
                session?.let { if (!it.shared) it.repo.shutdown() }
                session = null
                earlierMode = false
                firstStartEarlier = false
                activeFile.delete()
                repairHistory()
                attach()
            } catch (e: Exception) {
                state.update { it.copy(busy = false, error = "Keepfin could not switch to the new library: ${e.message}. Restart the app to finish.") }
            }
        }
    }
    // ------------------------------------------------------------------------------------------------ Settings > Storage

    /** Checks the Keepfin folder now (blocking; call off the main thread). Errors map to states as in spec section 11.1. */
    fun checkStorage(): StorageStatus {
        val profile = profiles.current() ?: return StorageStatus.NoFolder
        val tree = Uri.parse(profile.folderTreeUri)
        val label = FolderPlaces.label(DocumentsContract.getTreeDocumentId(tree))
        return try {
            val access = SafFolderAccess.open(context.contentResolver, tree)
            when (KeepfinFolders.open(access)) {
                is OpenFolderResult.Ok -> StorageStatus.Ok(label, access.freeBytes(), access.list("").entries.size)
                OpenFolderResult.Corrupt -> StorageStatus.Problem(label, "The folder's Keepfin data could not be read.")
                else -> StorageStatus.Problem(label, "This place no longer holds a Keepfin folder. Nothing is deleted; choose the folder again.")
            }
        } catch (e: FolderError.NeedsAccess) {
            StorageStatus.NeedsAccess(label)
        } catch (e: FolderError.Unavailable) {
            StorageStatus.Unavailable(label)
        } catch (e: Exception) {
            StorageStatus.Problem(label, friendly(e))
        }
    }

    /** "Needs access again": the user chose the folder once more. Only the same Keepfin library is accepted. Returns an error text or null. */
    fun repick(uri: Uri): String? {
        val profile = profiles.current() ?: return "No Keepfin folder is set up."
        return try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val access = SafFolderAccess.open(context.contentResolver, uri)
            val opened = KeepfinFolders.open(access) as? OpenFolderResult.Ok ?: return "That folder is not a Keepfin folder."
            if (opened.folder.libraryId != profile.libraryId) return "That is a different Keepfin library. Choose the folder you set up."
            profiles.add(profile.copy(folderTreeUri = uri.toString()))
            null
        } catch (e: Exception) {
            friendly(e)
        }
    }
}

internal sealed class StorageStatus {
    data object NoFolder : StorageStatus()
    class Ok(val label: String, val freeBytes: Long?, val entries: Int) : StorageStatus()
    class NeedsAccess(val label: String) : StorageStatus()
    class Unavailable(val label: String) : StorageStatus()
    class Problem(val label: String, val message: String) : StorageStatus()
}