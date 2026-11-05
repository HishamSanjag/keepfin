package app.keepfin.ui.model

import android.app.Application
import app.keepfin.EngineApi
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.MediaKind
import app.keepfin.ui.StudioEngine
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The job actions call the engine through their host and say what went wrong; a fake engine records every call it gets. */
class JobActionsTest {
    private val engine = RecordingEngine()
    private val host = FakeHost(engine.api)
    private val actions = JobActions(host)

    @Before fun fakeEngineSoNoServiceStarts() { StudioEngine.override = engine.api }
    @After fun removeFakeEngine() { StudioEngine.override = null }

    @Test fun cancelAndPauseCallTheEngine() {
        actions.cancel("j1"); host.settle()
        actions.pause("j2"); host.settle()
        assertEquals(listOf("cancel(j1)", "pause(j2)"), engine.calls)
    }

    @Test fun retryAndResumeCallTheEngine() {
        actions.retry("j1"); host.settle()
        actions.resume("j2"); host.settle()
        assertEquals(listOf("retry(j1)", "resume(j2)"), engine.calls)
    }

    @Test fun removeOfAFailedJobOnlyRemovesIt() {
        engine.jobs = listOf(job("j1", JobStatus.FAILED))
        actions.remove("j1"); host.settle()
        assertEquals(listOf("remove(j1)"), engine.calls)
        assertEquals(listOf("Removed from history"), host.said)
    }

    @Test fun removeCancelsAWaitingRetryFirst() {
        engine.jobs = listOf(job("j1", JobStatus.QUEUED, retryAt = 5_000))
        actions.remove("j1"); host.settle()
        assertEquals(listOf("cancel(j1)", "remove(j1)"), engine.calls)
    }

    @Test fun removeOfAQueuedJobWithoutAWaitingRetryDoesNotCancelIt() {
        engine.jobs = listOf(job("j1", JobStatus.QUEUED))
        actions.remove("j1"); host.settle()
        assertEquals(listOf("remove(j1)"), engine.calls)
    }

    @Test fun aFailingActionSaysItCouldNotBeDone() {
        engine.failWith = RuntimeException()
        actions.cancel("j1"); host.settle()
        actions.pause("j1"); host.settle()
        actions.remove("j1"); host.settle()
        assertEquals(listOf("Could not cancel.", "Could not pause.", "Could not remove it from history."), host.said)
    }

    private fun job(id: String, status: JobStatus, retryAt: Long? = null) = Job(
        id = id, url = "https://example.com/$id", title = "Song $id", thumbnail = null, uploader = null, duration = null, provider = null, label = "",
        kind = MediaKind.AUDIO, status = status, percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
        phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, retryAt = retryAt,
    )

    /** Answers every [EngineApi] call by writing it down (`name(arguments)`); its jobs are [jobs]. */
    private class RecordingEngine {
        val calls = mutableListOf<String>()
        var jobs: List<Job> = emptyList()
        var failWith: Throwable? = null
        val api: EngineApi = Proxy.newProxyInstance(EngineApi::class.java.classLoader, arrayOf(EngineApi::class.java)) { _, method, args ->
            if (method.name == "getJobs") return@newProxyInstance MutableStateFlow(jobs)
            calls += method.name + (args?.joinToString(prefix = "(", postfix = ")") ?: "()")
            failWith?.let { throw it }
            null
        } as EngineApi
    }

    private class FakeHost(override val engine: EngineApi) : JobActionsHost {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val said = mutableListOf<String>()

        /** Waits for the work the action launched. */
        fun settle() = runBlocking { scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().joinAll() }

        override fun say(text: String, actionLabel: String?, action: (() -> Unit)?) { said += text }
        override fun sayWithTwo(text: String, actionLabel: String?, action: (() -> Unit)?, secondLabel: String, secondAction: () -> Unit) { said += text }
        override fun groupJobs(groupId: String): List<Job> = emptyList()

        /** The flow only hands it to the engine's service helper, which does nothing while a fake engine is installed. */
        @Suppress("UNCHECKED_CAST")
        override fun <T : Application> getApplication(): T {
            val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            return unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Application::class.java) as T
        }
    }
}
