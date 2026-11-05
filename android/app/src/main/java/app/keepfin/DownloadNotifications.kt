package app.keepfin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import java.io.File

/** Owns preferences, channels and terminal events independently of activity/service polling. */
internal class DownloadNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val defaults = mapOf("enabled" to true, "progress" to true, "completed" to true,
        "failed" to true, "cancelled" to false, "sound" to true, "previews" to true, "private" to true)
    fun enabled(key: String) = prefs.getBoolean(key, defaults.getValue(key))
    fun configure(key: String, value: Boolean) { require(key in defaults); prefs.edit().putBoolean(key,value).apply() }
    fun allowed() = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun blockedCategories(): List<String> {
        channels()
        if(Build.VERSION.SDK_INT >= 28 && manager.getNotificationChannelGroup("keepfin-downloads")?.isBlocked == true) return listOf("Downloads group")
        val channels = linkedMapOf("progress" to ("keepfin-progress-v2" to "Progress"))
        for(type in listOf("completed","failed","cancelled")) channels[type] = "keepfin-$type-${if(enabled("sound")) "sound" else "quiet"}" to type.replaceFirstChar { it.uppercase() }
        return channels.filter { (key,value) -> enabled(key) && manager.getNotificationChannel(value.first)?.importance == NotificationManager.IMPORTANCE_NONE }.values.map { it.second }
    }
    fun test() {
        check(enabled("enabled")) { "Turn on download notifications first." }
        check(allowed()) { "Allow notifications in Android settings first." }
        channels()
        val notice = builder("keepfin-completed-${if(enabled("sound")) "sound" else "quiet"}")
            .setContentTitle("Keepfin").setContentText("Notifications are ready. Android channel settings and quiet modes still apply.")
            .setContentIntent(appIntent("Settings")).setAutoCancel(true).build()
        manager.notify("keepfin-notification-test",2,notice)
    }
    fun channels() {
        manager.createNotificationChannelGroup(android.app.NotificationChannelGroup("keepfin-downloads", "Downloads"))
        fun create(id: String, name: String, importance: Int, sound: Boolean = false) {
            manager.createNotificationChannel(NotificationChannel(id,name,importance).apply {
                group = "keepfin-downloads"
                if (!sound) { setSound(null,null); enableVibration(false) }
                description = "Control this category independently in Android notification settings."
            })
        }
        create("keepfin-progress-v2", "Download progress", NotificationManager.IMPORTANCE_LOW)
        create("keepfin-background-v2", "Background service status", NotificationManager.IMPORTANCE_LOW)
        for (type in listOf("completed", "failed", "cancelled")) {
            val name = when(type) {"completed" -> "Completed downloads"; "failed" -> "Download problems"; else -> "Cancelled downloads"}
            create("keepfin-$type-quiet", "$name · silent", NotificationManager.IMPORTANCE_LOW)
            create("keepfin-$type-sound", "$name · sound", NotificationManager.IMPORTANCE_DEFAULT,true)
        }
    }
    private fun appIntent(page: String, id: String = ""): PendingIntent = PendingIntent.getActivity(context,page.hashCode(),
        Intent(context, MainActivity::class.java).putExtra("notificationPage",page).putExtra("notificationJob",id)
            .setData(Uri.parse("keepfin://downloads/$page/$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun command(action: String, id: String, group: Boolean = false) = PendingIntent.getForegroundService(context,0,
        Intent(context, DownloadService::class.java).setAction(action).putExtra("jobId",id).putExtra("group",group)
            .setData(Uri.parse("keepfin://commands/$action/${if (group) "group/" else ""}$id")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun builder(channel: String) = NotificationCompat.Builder(context,channel)
        .setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt())
        .setVisibility(if(enabled("private")) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
    /**
     * The single foreground notification for all active work. One kept-together collection shows its title, "7 of 12 · 2 failed"
     * and overall progress; several unrelated jobs collapse into "3 downloads · 45%". Never one notification per item.
     * [all] is the full queue (needed to count finished siblings of a collection); it defaults to [active].
     */
    fun progress(active: List<Job>, all: List<Job> = active): Notification {
        val detailed = enabled("enabled") && enabled("progress")
        val notice = builder(if(detailed) "keepfin-progress-v2" else "keepfin-background-v2")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(appIntent("Downloads")).setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (!detailed) return notice.setContentTitle("Keepfin is working").setContentText("Background download service").build()
        val speed = active.mapNotNull { it.speed }.sum().takeIf { it > 0 }?.let { " · ${rate(it)}" }.orEmpty()
        val groupIds = active.map { it.group?.id }.distinct()
        val single = groupIds.singleOrNull()
        if (single != null) {
            val children = all.filter { it.group?.id == single }
            val group = children.first().group!!
            val summary = GroupSummary.of(children)
            val percent = summary.percent
            val current = active.firstOrNull { it.status == JobStatus.DOWNLOADING } ?: active.first()
            notice.setContentTitle(group.title).setContentText(listOf(summary.text(), speed.removePrefix(" · ").ifEmpty { null }).filterNotNull().joinToString(" · "))
                .setSubText(current.phase ?: "Keepfin").setProgress(100, percent, active.none { it.status == JobStatus.DOWNLOADING || it.status == JobStatus.PROCESSING })
            notice.addAction(0, "Pause all", command(DownloadService.ACTION_PAUSE, group.id, group = true))
            notice.addAction(0, "Cancel all", command(DownloadService.ACTION_CANCEL, group.id, group = true))
            return notice.build()
        }
        val current = active.firstOrNull { it.status == JobStatus.DOWNLOADING } ?: active.firstOrNull { it.status == JobStatus.PROCESSING }
            ?: active.firstOrNull { it.status == JobStatus.PREPARING } ?: active.firstOrNull()
        val transferring = active.filter { it.status == JobStatus.DOWNLOADING || it.status == JobStatus.PREPARING || it.status == JobStatus.PROCESSING }
        val percent = if (transferring.isEmpty()) 0 else transferring.map { it.percent }.average().toInt().coerceIn(0, 100)
        val downloading = active.count { it.status == JobStatus.DOWNLOADING }
        val title = when {
            active.size > 1 -> "${active.size} downloads · $percent%$speed"
            current?.status == JobStatus.PROCESSING -> if (downloading > 0) "Downloading $downloading · $percent%$speed" else current.phase ?: "Processing media"
            current?.status == JobStatus.DOWNLOADING -> "Downloading · $percent%$speed"
            current?.status == JobStatus.QUEUED -> retryText(current) ?: current.phase ?: "Waiting to download"
            else -> current?.phase ?: "Preparing download"
        }
        val exact = current?.status == JobStatus.DOWNLOADING || (current?.status == JobStatus.PROCESSING && downloading > 0)
        notice.setContentTitle(title).setContentText(if (active.size > 1) "Keepfin" else current?.title.orEmpty().ifEmpty { "Keepfin" })
            .setSubText(if (active.size > 1) "${active.size} downloads in queue" else "Keepfin").setProgress(100, percent, !exact)
        if (current != null && active.size == 1) {
            if (current.status != JobStatus.PROCESSING) notice.addAction(0, "Pause", command(DownloadService.ACTION_PAUSE, current.id))
            notice.addAction(0, "Cancel", command(DownloadService.ACTION_CANCEL, current.id))
        } else if (current != null) {
            notice.addAction(0, "Open queue", appIntent("Downloads"))
        }
        return notice.build()
    }
    private fun retryText(job: Job): String? {
        val at = job.retryAt ?: return null
        return RetryPolicy.waitingText(((at - System.currentTimeMillis()) + 999) / 1000, job.retryCount, job.phase == MediaEngine.ENGINE_PHASE)
    }
    private fun rate(bytes: Long): String {
        val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytes.toDouble(); var unit = 0
        while (value >= 1000 && unit < units.lastIndex) { value /= 1024; unit++ }
        return if (value >= 100 || unit == 0) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
    }
    /** Foreground notification of a running background check of followed playlists. */
    fun followProgress(text: String): Notification {
        channels()
        return builder("keepfin-background-v2").setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentTitle("Keepfin").setContentText(text).setProgress(0, 0, true).setContentIntent(appIntent("Library")).build()
    }

    /** "3 new in Gym Mix" / "28 new in Gym Mix · Review": one quiet notification per collection, replaced by the next one. */
    @Synchronized fun followNews(collection: LibraryCollection, title: String, text: String) {
        if (!enabled("enabled") || !enabled("completed") || !allowed()) return
        channels()
        val notice = builder("keepfin-completed-quiet").setAutoCancel(true).setContentTitle(title).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS).setContentIntent(appIntent("Library", "collection:${collection.id}"))
        try { manager.notify("follow-${collection.id}", 2, notice.build()) } catch (_: SecurityException) { }
    }

    @Synchronized fun dismiss(id: String) { manager.cancel(id,2) }
    @Synchronized fun dismissGroup(groupId: String) { manager.cancel("group-$groupId",2) }
    private var lastAlertAt = 0L
    /** Completions arriving within this window of an earlier one buzz only once (children go silent, one summary alerts). */
    private val burstWindowMs = 60_000L
    /**
     * Terminal notification. Single downloads post one each (collapsed into a group summary when several finish together);
     * a kept-together collection posts exactly one notification, only once every member is terminal. [jobs] reads the queue.
     */
    @Synchronized fun finished(job: Job, jobs: () -> List<Job> = { listOf(job) }, isCurrent: () -> Boolean = { true }) {
        val status = job.status
        if (status !in setOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED)) return
        val id = job.id
        val stamp = "$id:${job.attempt}:${status.wire}"
        val delivered = prefs.getStringSet("delivered",emptySet()).orEmpty()
        if (stamp in delivered) return
        // Record muted events too: enabling later must never replay history.
        prefs.edit().putStringSet("delivered",(delivered.toList().takeLast(199) + stamp).toSet()).apply()
        val problem = status == JobStatus.FAILED || (status == JobStatus.COMPLETED && !job.saveError.isNullOrEmpty())
        job.group?.let { group -> finishedGroup(group, jobs(), isCurrent); return }
        val category = if(problem) "failed" else status.wire
        if (!enabled("enabled") || !enabled(category) || !allowed() || !isCurrent()) return
        channels()
        val burst = System.currentTimeMillis() - lastAlertAt < burstWindowMs
        val notice = builder("keepfin-$category-${if(enabled("sound")) "sound" else "quiet"}")
            .setGroup(FINISHED_GROUP).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setSilent(burst)
            .setAutoCancel(true).setContentIntent(appIntent(if(status == JobStatus.COMPLETED) "History" else "Downloads",id))
            .setContentTitle(when {
                problem && status == JobStatus.COMPLETED -> "Download ready · save needs attention"; problem -> "Download failed"; status == JobStatus.CANCELLED -> "Download cancelled"
                status == JobStatus.COMPLETED && job.existing != null -> "Already in ${spaceName(job.existing.space) ?: "your library"}"
                status == JobStatus.COMPLETED && job.space != null && job.space != "downloads" -> spaceName(job.space)?.let { "Saved to $it" } ?: "Download complete"
                else -> "Download complete" })
            .setContentText(job.title).setCategory(if(problem) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        val detail = when {
            problem -> (if (status == JobStatus.COMPLETED) job.saveError else job.error).orEmpty()
            status == JobStatus.COMPLETED && job.existing != null -> "This recording is already saved. Open Keepfin to play it or download it anyway."
            status == JobStatus.COMPLETED -> job.publicPath ?: "Ready in your library"
            else -> "You can retry this download from the queue." }
        notice.setStyle(NotificationCompat.BigTextStyle().bigText("${job.title}\n$detail"))
        if (status == JobStatus.COMPLETED && job.existing != null) {
            // Nothing was downloaded: the tap opens the queue, where Download anyway lives.
        } else if (status == JobStatus.COMPLETED) {
            try {
                val file = File(job.file ?: error("missing file"))
                val uri = job.publicUri?.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                    ?: FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
                val pending = PendingIntent.getActivity(context,id.hashCode(),Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                notice.addAction(0,"Open",pending)
                if(enabled("previews")) (ThumbnailStore(context).loadJob(id) ?: LocalMediaPreview.load(context,uri,file,160))
                    ?.let { notice.setLargeIcon(it) }
            } catch (_: Exception) { /* Library tap remains usable when an external file/viewer is unavailable. */ }
        } else notice.addAction(0,"Retry",command(DownloadService.ACTION_RETRY,id))
        try {
            if(isCurrent()) {
                manager.notify(id,2,notice.build())
                if (!burst) lastAlertAt = System.currentTimeMillis()
                postBurstSummary(category)
            }
        } catch (_: SecurityException) { }
    }

    /** Name of a library space, or null when the library is off or the space was removed. */
    private fun spaceName(id: String): String? =
        (context.applicationContext as? KeepfinApplication)?.engineApi?.settings?.value?.library?.takeIf { it.enabled }?.spaces?.firstOrNull { it.id == id }?.name

    /** A second finished download folds the first two into one expandable summary instead of a longer list of alerts. */
    private fun postBurstSummary(category: String) {
        val children = manager.activeNotifications.filter { it.id == 2 && it.notification.group == FINISHED_GROUP && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        if (children.size < 2) return
        val lines = children.mapNotNull { it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() }
        val inbox = NotificationCompat.InboxStyle().also { style -> lines.take(5).forEach(style::addLine) }
            .setBigContentTitle("${children.size} downloads finished")
        val summary = builder("keepfin-$category-${if(enabled("sound")) "sound" else "quiet"}")
            .setGroup(FINISHED_GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY).setSilent(true)
            .setAutoCancel(true).setContentTitle("${children.size} downloads finished").setContentText(lines.firstOrNull().orEmpty())
            .setStyle(inbox).setContentIntent(appIntent("History"))
        manager.notify(SUMMARY_ID, summary.build())
    }

    private fun finishedGroup(group: JobGroup, queue: List<Job>, isCurrent: () -> Boolean) {
        val children = queue.filter { it.group?.id == group.id }
        if (children.isEmpty() || children.any { it.status.active || it.status == JobStatus.PAUSED }) return
        val summary = GroupSummary.of(children)
        val category = when { summary.saved > 0 -> "completed"; summary.failed > 0 -> "failed"; else -> "cancelled" }
        val stamp = "group:${group.id}:${summary.saved}:${summary.failed}:${summary.cancelled}:${children.sumOf { it.attempt }}"
        val delivered = prefs.getStringSet("delivered",emptySet()).orEmpty()
        if (stamp in delivered) return
        prefs.edit().putStringSet("delivered",(delivered.toList().takeLast(199) + stamp).toSet()).apply()
        if (!enabled("enabled") || !enabled(category) || !allowed() || !isCurrent()) return
        channels()
        val title = if (summary.saved == 0 && summary.failed > 0) "${group.title} failed" else if (summary.saved == 0) "${group.title} cancelled" else "${group.title} downloaded"
        val detail = summary.finalText()
        val notice = builder("keepfin-$category-${if(enabled("sound")) "sound" else "quiet"}")
            .setAutoCancel(true).setContentIntent(appIntent(if (summary.saved > 0) "History" else "Downloads", "group:${group.id}"))
            .setContentTitle(title).setContentText(detail).setStyle(NotificationCompat.BigTextStyle().bigText("$title — $detail"))
            .setCategory(if (category == "failed") NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
        if (summary.failed > 0) notice.addAction(0, "Retry failed", command(DownloadService.ACTION_RETRY, group.id, group = true))
        try { if (isCurrent()) manager.notify("group-${group.id}", 2, notice.build()) } catch (_: SecurityException) { }
    }

    companion object {
        const val FINISHED_GROUP = "keepfin-finished"
        const val SUMMARY_ID = 3
    }
}