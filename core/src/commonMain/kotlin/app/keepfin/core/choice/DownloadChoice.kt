package app.keepfin.core.choice

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.DownloadPlan
import app.keepfin.core.formats.Formats
import app.keepfin.core.formats.Size
import app.keepfin.core.providers.Providers

/** The download choice: what a preview offers, opens with and estimates, decided once for both apps (dev-docs/plans/DOWNLOAD-CHOICE.md). */
object DownloadChoice {
    val CONTAINERS = listOf("mp4", "mkv", "webm")

    private val codecNames = mapOf("h264" to "H.264", "hevc" to "HEVC", "vp9" to "VP9", "av1" to "AV1")

    /** The quality a choice stands on: the picked one, else the first (best). */
    fun video(media: ChoiceMedia, choice: Choice): ChoiceVideo? = media.video.firstOrNull { it.id == choice.videoId } ?: media.video.firstOrNull()

    /** MP4 and MKV hold any codec; WebM only VP9 and AV1. */
    private fun allowed(codec: String?, container: String): Boolean = container != "webm" || codec == "vp9" || codec == "av1"

    /** What the preview shows to pick from: every container, with the reason when the chosen quality cannot use it. */
    fun offer(media: ChoiceMedia, choice: Choice): Offer {
        val codec = video(media, choice)?.codec
        val name = codecNames[codec] ?: "another codec"
        return Offer(CONTAINERS.map { if (allowed(codec, it)) ContainerOffer(it, true, null) else ContainerOffer(it, false, "WebM needs VP9 or AV1 video; this quality is $name.") })
    }

    /** The size of what the choice downloads; the same rule gives the expected size of its job (args.Labels). */
    fun size(media: ChoiceMedia, choice: Choice): Size {
        if (choice.mode == "audio") return ChoiceSize.audio(choice.target, choice.quality, ChoiceSize.source(media.audio, choice.audioSource), media.duration)
        val track = media.audio.firstOrNull { it.formatId == choice.audioTrack }
        return ChoiceSize.video(video(media, choice), track, media.audio.firstOrNull())
    }

    private val LOSSY = setOf("mp3", "m4a", "opus")

    /**
     * What the choice sends to the engine. A video carries its height, exact format id, frame rate and container, and its codec
     * when that is one the plan knows (else "auto"; the plan sorts by the codec when the exact id has expired). The chosen audio
     * language goes only with a video that has no audio of its own. Audio sends the quality only for the formats that convert to
     * a bitrate (the others are "best") and a source stream only for Original; null is the best one.
     */
    fun request(media: ChoiceMedia, choice: Choice): DownloadFormat {
        if (choice.mode == "audio") {
            return DownloadFormat.Audio(
                target = choice.target,
                quality = if (choice.target in LOSSY) choice.quality else "best",
                formatId = choice.audioSource.takeIf { choice.target == "original" },
            )
        }
        val option = video(media, choice)
        return DownloadFormat.Video(
            maxHeight = option?.height?.takeIf { it > 0 },
            formatId = option?.formatId,
            audioFormatId = choice.audioTrack.takeIf { option != null && !option.hasAudio },
            fps = option?.fps,
            codec = option?.codec?.takeIf { it in codecNames } ?: "auto",
            container = choice.container,
        )
    }

    /** The frame rate worth showing: from 33 fps up (30, 31 and 32 fps read as plain); null otherwise. */
    fun shownFps(fps: Int?): Int? = fps?.takeIf { it > 32 }

    /** The words on a quality row: the height, then the frame rate when it shows ("1080p60"); "Original" when there is no height. */
    fun videoLabel(height: Int, fps: Int?): String = Formats.heightLabel(height) + (shownFps(fps)?.toString() ?: "")

    /** The modes a link offers: Video when it lists qualities, Audio when something carries audio; a link that lists nothing offers both. */
    fun modes(media: ChoiceMedia): List<String> {
        val modes = listOfNotNull("video".takeIf { media.video.isNotEmpty() }, "audio".takeIf { media.audio.isNotEmpty() || media.video.any { it.hasAudio } })
        return modes.ifEmpty { listOf("video", "audio") }
    }

    /** The best quality within a preset's height (the lowest when none fits); the first (best) for "best". */
    private fun qualityFor(media: ChoiceMedia, preset: String): ChoiceVideo? {
        val limit = preset.toIntOrNull() ?: return media.video.firstOrNull()
        return media.video.firstOrNull { it.height <= limit } ?: media.video.lastOrNull()
    }

    /** MP3 starts at 320; every other audio format at Best. */
    private fun startQuality(target: String): String = if (target == "mp3") DownloadPlan.MP3_QUALITY else "best"

    /**
     * What a preview opens with. A link opens on Audio when the preset is an audio one (unless it is a YouTube video link, which
     * opens as a video whatever the preset says), when it is a song matched from a music service or has no video, and when it is
     * a music link with audio to take; otherwise on Video at the quality the preset names.
     */
    fun opening(media: ChoiceMedia, start: Start): Choice {
        val modes = modes(media)
        val base = Choice(videoId = qualityFor(media, start.preset)?.id, target = "mp3", quality = DownloadPlan.MP3_QUALITY)
        val audioPreset = start.preset.startsWith("audio-") && !Providers.startsAsVideo(start.url, start.ytMusicAudio)
        if ("audio" in modes && audioPreset) {
            val target = start.preset.removePrefix("audio-")
            return base.copy(mode = "audio", target = target, quality = startQuality(target))
        }
        val song = start.music || (media.video.isEmpty() && media.audio.isNotEmpty())
        if (song || ("audio" in modes && Providers.prefersAudio(start.url, start.ytMusicAudio))) return base.copy(mode = "audio")
        return base
    }

    /** The format a list or several links download for a preset: MP3 at 320, the other audio formats at Best. */
    fun forList(preset: String): DownloadFormat = DownloadPlan.preset(preset)

    /** What a preset makes of one link's choice; the choice stays as it is when the link has nothing of that kind. */
    fun applyPreset(media: ChoiceMedia, base: Choice, preset: String): Choice {
        val modes = modes(media)
        if (preset.startsWith("audio-")) {
            if ("audio" !in modes) return base
            val target = preset.removePrefix("audio-")
            return base.copy(mode = "audio", target = target, quality = startQuality(target), audioSource = null)
        }
        if ("video" !in modes) return base
        val video = qualityFor(media, preset) ?: return base.copy(mode = "video")
        return pickVideo(media, base.copy(mode = "video"), video.id)
    }

    /** The choice after picking an audio format: MP3 starts at 320, another format keeps the quality asked for; the picked source stream is cleared. */
    fun pickTarget(choice: Choice, target: String): Choice =
        choice.copy(target = target, quality = if (target == "mp3") DownloadPlan.MP3_QUALITY else choice.quality, audioSource = null)

    /** The choice after picking a quality; the container goes back to MP4 when the new quality cannot use it. */
    fun pickVideo(media: ChoiceMedia, choice: Choice, videoId: String): Choice {
        val codec = media.video.firstOrNull { it.id == videoId }?.codec
        return choice.copy(videoId = videoId, container = if (allowed(codec, choice.container)) choice.container else "mp4")
    }
}
