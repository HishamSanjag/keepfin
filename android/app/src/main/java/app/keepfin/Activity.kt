package app.keepfin

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The in-app Activity centre (dev-docs/architecture/ACTIVITY.md): what happened while the user was elsewhere. Everything here is pure; recording
 * happens in [ActivityStore] from the places where the events happen (engine, follow check, app updater), never from UI code.
 */
enum class ActivityKind(val wire: String) {
    SYNC_FOUND("sync-found"), SYNC_REVIEW("sync-review"), BATCH_DONE("batch-done"), FAILED("failed"),
    APP_UPDATED("app-updated"), UPDATE_READY("update-ready"), SIGN_IN("sign-in");

    /** Entries that need the user (the Problems filter). */
    val problem: Boolean get() = this == FAILED || this == SIGN_IN

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } }
}

/** What an entry is about; actions are derived from the kind and what is set here. */
data class ActivityTarget(
    val collectionId: String? = null,
    val jobIds: List<String> = emptyList(),
    val groupId: String? = null,
    val version: String? = null,
    val provider: String? = null,
)

/**
 * One line of the Activity list. [title] and [detail] are display text built when the entry was recorded. [open] is true for a `sync-found`
 * entry whose downloads are still running (it is updated in place until they finish). [away] marks entries created while the app was not in
 * the foreground, for the "N things happened while you were away" notice.
 */
data class ActivityEntry(
    val id: String,
    val kind: ActivityKind,
    val at: Long,
    val title: String,
    val detail: String? = null,
    val read: Boolean = false,
    val target: ActivityTarget = ActivityTarget(),
    val count: Int? = null,
    val thumbnail: String? = null,
    val open: Boolean = false,
    val away: Boolean = false,
)

enum class ActivityAction(val label: String) {
    OPEN("Open"), REVIEW("Review"), RETRY("Retry"), DETAILS("Details"), WHATS_NEW("What's new"), INSTALL("Install"), SIGN_IN("Sign in")
}

enum class ActivityFilter(val label: String) { ALL("All"), UNREAD("Unread"), PROBLEMS("Problems") }

data class ActivityDay(val label: String, val entries: List<ActivityEntry>)

/** What became of one download of a sync batch. */
enum class SyncOutcome { PENDING, SAVED, FAILED, CANCELLED, GONE }

/** What the store knows about the world when a download ends. */
class ActivityContext(
    val now: Long,
    val foreground: Boolean,
    /** Display name of a space, or null while the library is off. */
    val spaceName: (String?) -> String? = { null },
    val isMusicSpace: (String?) -> Boolean = { false },
    /** Ids of downloads that belong to a still-open `sync-found` entry. */
    val syncJobIds: Set<String> = emptySet(),
)

object ActivityLogic {
    const val MAX_ENTRIES = 200
    const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    // ---- storage rules -----------------------------------------------------------------------------

    /** Newest first; nothing older than 30 days and at most 200 entries. */
    fun prune(entries: List<ActivityEntry>, now: Long): List<ActivityEntry> =
        entries.filter { now - it.at <= MAX_AGE_MS }.sortedByDescending { it.at }.take(MAX_ENTRIES)

    /** Adds [entry], or replaces the entry with the same id: [refresh] makes it new again (unread, current time), otherwise the old one stays untouched. */
    fun upsert(entries: List<ActivityEntry>, entry: ActivityEntry, refresh: Boolean = false): List<ActivityEntry> {
        val existing = entries.firstOrNull { it.id == entry.id } ?: return entries + entry
        if (!refresh) return entries
        return entries.map { if (it.id == entry.id) entry.copy(read = false, away = existing.away || entry.away) else it }
    }

    // ---- counts, filters, grouping -------------------------------------------------------------------

    fun unreadCount(entries: List<ActivityEntry>) = entries.count { !it.read }
    fun problemCount(entries: List<ActivityEntry>) = entries.count { it.kind.problem }

    fun filter(entries: List<ActivityEntry>, filter: ActivityFilter): List<ActivityEntry> = when (filter) {
        ActivityFilter.ALL -> entries
        ActivityFilter.UNREAD -> entries.filter { !it.read }
        ActivityFilter.PROBLEMS -> entries.filter { it.kind.problem }
    }

    /** Unread entries created while the app was not in the foreground. A new app version's own entry is never "while away". */
    fun awayCount(entries: List<ActivityEntry>) = entries.count { !it.read && it.away && it.kind != ActivityKind.APP_UPDATED }

    /** "Today", "Yesterday", the weekday for the rest of the last week, then the date. */
    fun dayLabel(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (ChronoUnit.DAYS.between(day, today)) {
            in Long.MIN_VALUE..0L -> "Today"
            1L -> "Yesterday"
            in 2L..6L -> day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> day.format(DateTimeFormatter.ofPattern(if (day.year == today.year) "d MMM" else "d MMM yyyy", locale))
        }
    }

    /** Entries newest first, split into day groups. */
    fun group(entries: List<ActivityEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): List<ActivityDay> {
        val days = ArrayList<Pair<String, MutableList<ActivityEntry>>>()
        for (entry in entries.sortedByDescending { it.at }) {
            val label = dayLabel(entry.at, now, zone, locale)
            val last = days.lastOrNull()
            if (last != null && last.first == label) last.second.add(entry) else days.add(label to mutableListOf(entry))
        }
        return days.map { ActivityDay(it.first, it.second) }
    }

    /** The buttons of an entry, in order, derived from its kind and target. At most two. */
    fun actions(entry: ActivityEntry): List<ActivityAction> {
        val t = entry.target
        return when (entry.kind) {
            ActivityKind.SYNC_FOUND -> if (t.collectionId != null) listOf(ActivityAction.OPEN) else emptyList()
            ActivityKind.SYNC_REVIEW -> if (t.collectionId != null) listOf(ActivityAction.REVIEW) else emptyList()
            ActivityKind.BATCH_DONE -> if (t.collectionId != null || t.groupId != null || t.jobIds.isNotEmpty()) listOf(ActivityAction.OPEN) else emptyList()
            ActivityKind.FAILED -> if (t.groupId != null || t.jobIds.isNotEmpty()) listOf(ActivityAction.RETRY, ActivityAction.DETAILS) else emptyList()
            ActivityKind.APP_UPDATED -> listOf(ActivityAction.WHATS_NEW)
            ActivityKind.UPDATE_READY -> listOf(ActivityAction.INSTALL)
            ActivityKind.SIGN_IN -> listOf(ActivityAction.SIGN_IN)
        }
    }

    // ---- words -----------------------------------------------------------------------------------

    /** "song"/"songs" for music, "item"/"items" otherwise. */
    fun noun(music: Boolean, n: Int): String = (if (music) "song" else "item") + if (n == 1) "" else "s"

    fun syncFoundTitle(name: String, n: Int, music: Boolean) = "$name got $n new ${noun(music, n)}"
    fun syncReviewTitle(name: String, n: Int, music: Boolean) = "$name has $n new ${noun(music, n)} waiting"
    fun syncReviewDetail(confirmAbove: Int) = "More than $confirmAbove, so they wait for you"
    fun syncRunningDetail(spaceName: String?) = if (spaceName != null) "Downloading to $spaceName" else "Downloading now"

    /** "All 3 saved", or "2 saved, 1 failed". */
    fun syncSettledDetail(saved: Int, failed: Int, cancelled: Int): String =
        if (saved + failed + cancelled == 0) "Done"
        else if (failed == 0 && cancelled == 0) "All $saved saved"
        else listOfNotNull("$saved saved", "$failed failed".takeIf { failed > 0 }, "$cancelled cancelled".takeIf { cancelled > 0 }).joinToString(", ")

    fun batchDoneTitle(name: String) = "$name finished"
    fun batchDoneDetail(saved: Int, total: Int, music: Boolean, spaceName: String?) =
        "$saved of $total ${noun(music, total)} saved" + if (spaceName != null) " to $spaceName" else ""
    fun failedGroupTitle(failed: Int, music: Boolean, group: String) = "$failed ${noun(music, failed)} from $group failed"
    fun appUpdatedTitle(version: String) = "Keepfin updated to $version"
    fun updateReadyTitle(version: String) = "Keepfin $version is ready to install"
    fun signInTitle(provider: String) = "Sign in to $provider again"
    const val SIGN_IN_DETAIL = "Private or members-only downloads need it"

    /** True when [message] (a friendly failure text) asks the user to sign in. */
    fun needsSignIn(message: String?): Boolean {
        val text = message.orEmpty()
        return text.startsWith("Sign in required") || text == ErrorMessages.BOT_MESSAGE || text.startsWith("Age-restricted: sign in")
    }

    // ---- recording from download endings --------------------------------------------------------------

    /**
     * Entries for a download that just ended ([job] is in a terminal state; [queue] is the whole queue including it). One entry per batch,
     * never per item; failures only count here because the engine calls this after automatic retries are spent.
     */
    fun forTerminal(job: Job, queue: List<Job>, ctx: ActivityContext): List<ActivityEntry> {
        val group = job.group
        val children = if (group == null) emptyList() else queue.filter { it.group?.id == group.id }
        if (group == null || children.size < 2) return forSingle(job, ctx)
        if (children.any { it.status.active || it.status == JobStatus.PAUSED }) return emptyList()
        val summary = GroupSummary.of(children)
        val music = ctx.isMusicSpace(job.space) || children.all { it.kind == MediaKind.AUDIO }
        val collectionId = children.firstNotNullOfOrNull { it.collectionId }
        val stamp = "${children.sumOf { it.attempt }}:${summary.saved}:${summary.failed}"
        val art = group.thumbnail ?: children.firstNotNullOfOrNull { it.thumbnail }
        val out = ArrayList<ActivityEntry>()
        val syncBatch = children.any { it.id in ctx.syncJobIds }
        if (!syncBatch && summary.saved > 0) out += ActivityEntry(
            id = "batch:${group.id}:$stamp", kind = ActivityKind.BATCH_DONE, at = ctx.now, title = batchDoneTitle(group.title),
            detail = batchDoneDetail(summary.saved, summary.total, music, ctx.spaceName(job.space)),
            target = ActivityTarget(collectionId = collectionId, groupId = group.id), count = summary.total, thumbnail = art,
        )
        val failedJobs = children.filter { it.status == JobStatus.FAILED || (it.status == JobStatus.COMPLETED && it.saveError != null) }
        if (failedJobs.isNotEmpty()) {
            out += ActivityEntry(
                id = "failed:${group.id}:$stamp", kind = ActivityKind.FAILED, at = ctx.now, title = failedGroupTitle(failedJobs.size, music, group.title),
                detail = failureText(failedJobs.first()), target = ActivityTarget(collectionId = collectionId, jobIds = failedJobs.map { it.id }, groupId = group.id),
                count = failedJobs.size, thumbnail = art,
            )
            out += signInEntries(failedJobs, ctx)
        }
        return out
    }

    private fun forSingle(job: Job, ctx: ActivityContext): List<ActivityEntry> {
        val failed = job.status == JobStatus.FAILED || (job.status == JobStatus.COMPLETED && job.saveError != null)
        if (failed) {
            return listOf(ActivityEntry(
                id = "failed:${job.id}:${job.attempt}", kind = ActivityKind.FAILED, at = ctx.now, title = "${job.title} failed", detail = failureText(job),
                target = ActivityTarget(jobIds = listOf(job.id), groupId = job.group?.id, collectionId = job.collectionId), count = 1, thumbnail = job.thumbnail,
            )) + signInEntries(listOf(job), ctx)
        }
        // A single download that finished while the user watched needs no entry; a sync batch is covered by its own entry.
        if (job.status != JobStatus.COMPLETED || ctx.foreground || job.existing != null || job.filesRemoved || job.id in ctx.syncJobIds) return emptyList()
        val space = ctx.spaceName(job.space)
        return listOf(ActivityEntry(
            id = "done:${job.id}:${job.attempt}", kind = ActivityKind.BATCH_DONE, at = ctx.now, title = "${job.title} finished", detail = if (space != null) "Saved to $space" else "Saved",
            target = ActivityTarget(jobIds = listOf(job.id), collectionId = job.collectionId), count = 1, thumbnail = job.thumbnail,
        ))
    }

    private fun failureText(job: Job): String? = (if (job.status == JobStatus.COMPLETED) job.saveError else job.error)?.takeIf { it.isNotBlank() }

    private fun signInEntries(failed: List<Job>, ctx: ActivityContext): List<ActivityEntry> =
        failed.filter { needsSignIn(failureText(it)) }.map { it.provider ?: Providers.name(it.url) }.distinct().map { provider ->
            ActivityEntry(
                id = "sign-in:${provider.lowercase()}", kind = ActivityKind.SIGN_IN, at = ctx.now, title = signInTitle(provider), detail = SIGN_IN_DETAIL,
                target = ActivityTarget(provider = provider),
            )
        }

    // ---- sync-found: one entry per collection while its batch runs ---------------------------------------

    /**
     * A follow check queued [jobIds] for [collectionId]. While an earlier batch of the same collection is still running its entry is updated
     * (more items, unread again) instead of adding another.
     */
    fun recordSyncFound(
        entries: List<ActivityEntry>, collectionId: String, name: String, jobIds: List<String>, music: Boolean, spaceName: String?,
        now: Long, away: Boolean, thumbnail: String? = null,
    ): List<ActivityEntry> {
        val running = entries.firstOrNull { it.kind == ActivityKind.SYNC_FOUND && it.open && it.target.collectionId == collectionId }
        if (running == null) {
            val entry = ActivityEntry(
                id = "sync:$collectionId:$now", kind = ActivityKind.SYNC_FOUND, at = now, title = syncFoundTitle(name, jobIds.distinct().size, music),
                detail = syncRunningDetail(spaceName), target = ActivityTarget(collectionId = collectionId, jobIds = jobIds.distinct()),
                count = jobIds.distinct().size, thumbnail = thumbnail, open = true, away = away,
            )
            return entries + entry
        }
        val all = (running.target.jobIds + jobIds).distinct()
        val merged = running.copy(
            at = now, read = false, away = running.away || away, title = syncFoundTitle(name, all.size, music), detail = syncRunningDetail(spaceName),
            target = running.target.copy(jobIds = all), count = all.size,
        )
        return entries.map { if (it.id == running.id) merged else it }
    }

    /** More than the limit was found: one entry per collection; a newer check replaces it. */
    fun syncReview(collectionId: String, name: String, count: Int, confirmAbove: Int, music: Boolean, now: Long, away: Boolean) = ActivityEntry(
        id = "sync-review:$collectionId", kind = ActivityKind.SYNC_REVIEW, at = now, title = syncReviewTitle(name, count, music),
        detail = syncReviewDetail(confirmAbove), target = ActivityTarget(collectionId = collectionId), count = count, away = away,
    )

    /**
     * Closes every running `sync-found` entry whose downloads have all ended: "All 3 saved" or "2 saved, 1 failed". Returns [entries] itself when
     * nothing changed.
     */
    fun settleSync(entries: List<ActivityEntry>, outcome: (String) -> SyncOutcome): List<ActivityEntry> {
        var changed = false
        val next = entries.map { entry ->
            if (entry.kind != ActivityKind.SYNC_FOUND || !entry.open) return@map entry
            val outcomes = entry.target.jobIds.map(outcome)
            if (outcomes.any { it == SyncOutcome.PENDING }) return@map entry
            changed = true
            entry.copy(open = false, detail = syncSettledDetail(outcomes.count { it == SyncOutcome.SAVED }, outcomes.count { it == SyncOutcome.FAILED }, outcomes.count { it == SyncOutcome.CANCELLED }))
        }
        return if (changed) next else entries
    }

    fun outcomeOf(job: Job?): SyncOutcome = when {
        job == null -> SyncOutcome.GONE
        job.status.active || job.status == JobStatus.PAUSED -> SyncOutcome.PENDING
        job.status == JobStatus.FAILED || (job.status == JobStatus.COMPLETED && job.saveError != null) -> SyncOutcome.FAILED
        job.status == JobStatus.CANCELLED -> SyncOutcome.CANCELLED
        else -> SyncOutcome.SAVED
    }
}
