package app.keepfin.ui.model

import android.app.Application
import android.net.Uri
import app.keepfin.EngineApi
import app.keepfin.ui.FileActions
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.StudioViewModel.Companion.friendly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [JobActions] uses of the ViewModel: its scope, the engine, the Application and the jobs of a collection's group. */
internal interface JobActionsHost : Notices {
    val scope: CoroutineScope
    val engine: EngineApi
    fun <T : Application> getApplication(): T
    fun groupJobs(groupId: String): List<app.keepfin.Job>
}

/** The actions on download jobs and their history: cancel, pause, resume, retry, remove (with Undo), delete the file, save a copy. */
internal class JobActions(private val host: JobActionsHost) {
    fun jobAction(id: String, label: String, action: EngineApi.(String) -> Unit) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { host.engine.action(id) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not $label.")) }
        }
    }

    fun cancel(id: String) = jobAction(id, "cancel") { cancel(it) }
    fun pause(id: String) = jobAction(id, "pause") { pause(it) }
    fun resume(id: String) = jobAction(id, "resume") { StudioEngine.withServiceWork(host.getApplication()) { resume(it) } }
    fun retry(id: String) = jobAction(id, "retry") { StudioEngine.withServiceWork(host.getApplication()) { retry(it) } }
    fun retrySave(id: String) = jobAction(id, "save again") { retryPublicSave(it) }
    fun downloadAnyway(id: String) = jobAction(id, "download it again") { StudioEngine.withServiceWork(host.getApplication()) { downloadAnyway(it) } }
    /** Takes a record off the list and out of history (a waiting automatic retry is cancelled first). Saved media is never touched. */
    fun remove(id: String) {
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val job = host.engine.jobs.value.firstOrNull { it.id == id }
                    if (job != null && job.status == app.keepfin.JobStatus.QUEUED && job.retryAt != null) host.engine.cancel(id)
                    host.engine.remove(id)
                }
                saidRemovedFromHistory(listOf(id))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove it from history.")) }
        }
    }

    /**
     * The notice after records left the history, with Undo. The engine keeps the records (and their artwork) for a short while, so
     * Undo brings them back as they were; saved media is never touched either way.
     */
    fun saidRemovedFromHistory(ids: List<String>) {
        if (ids.isEmpty()) return
        host.say(if (ids.size == 1) "Removed from history" else "Removed ${ids.size} from history", "Undo") { restoreHistory(ids) }
    }

    private fun restoreHistory(ids: List<String>) {
        host.scope.launch {
            try {
                val back = withContext(Dispatchers.IO) { host.engine.restoreRemoved(ids) }
                if (back.isEmpty()) host.say("Could not undo: the history was cleared for good.")
                else if (back.size < ids.size) host.say("Brought back ${back.size} of ${ids.size}. The others were cleared for good.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not undo: " + friendly(e, "Something went wrong.")) }
        }
    }

    /** "Remove failed": failed, cancelled and not-saved items of a collection, off the list and out of history. Nothing is deleted from storage. */
    fun removeGroupFailed(id: String) {
        host.scope.launch {
            try {
                val gone = withContext(Dispatchers.IO) {
                    host.groupJobs(id).filter { it.status == app.keepfin.JobStatus.FAILED || it.status == app.keepfin.JobStatus.CANCELLED || (it.status == app.keepfin.JobStatus.COMPLETED && it.saveError != null) }
                        .filter { runCatching { host.engine.remove(it.id) }.isSuccess }.map { it.id }
                }
                saidRemovedFromHistory(gone)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove the failed items.")) }
        }
    }
    fun deleteFile(id: String, title: String) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { host.engine.deleteFile(id) }; host.say("Deleted · $title") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not delete this file.")) }
        }
    }
    fun saveCopy(job: app.keepfin.Job, target: Uri) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { FileActions.copyTo(host.getApplication(), host.engine, job, target) }; host.say("Copy saved") }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not save a copy.")) }
        }
    }

    fun clearHistory() {
        host.scope.launch {
            try {
                val ids = withContext(Dispatchers.IO) { host.engine.clearHistory() }
                // Undo as on Windows: the records come back with their artwork for a minute or so
                if (ids.isEmpty()) host.say("History cleared. Saved files remain on your phone.")
                else host.say("History cleared. Saved files remain on your phone.", "Undo") { restoreHistory(ids) }
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not clear history.")) }
        }
    }
}
