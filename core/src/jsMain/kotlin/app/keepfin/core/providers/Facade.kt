@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.providers

// The JavaScript surface of the providers core. Names and shapes here are the TypeScript API
// (see gui/shared/core/providers.ts): nullable strings, arrays instead of lists, plain classes.

@JsExport
class JsMusicLink(val service: String, val type: String, val id: String, val storefront: String? = null)

/** `href` is set when the link is acceptable, `problem` ("tooLong", "notALink" or "unsupported") when it is not. */
@JsExport
class JsUrlCheck(val href: String?, val problem: String?)

/** The parts of a parsed URL, for the tests; `port` is null for a default or missing port. */
@JsExport
class JsUrl(
    val scheme: String,
    val username: String,
    val password: String,
    val host: String?,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
    val href: String,
)

@JsExport
class JsPathJob(
    val url: String,
    val title: String,
    val uploader: String? = null,
    val createdAt: Double = 0.0,
    val groupTitle: String? = null,
    val musicArtist: String? = null,
    val musicAlbum: String? = null,
    val musicTrack: String? = null,
)

@JsExport
class JsSpaceContext(
    val title: String,
    val uploader: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val url: String = "",
    val groupTitle: String? = null,
    val groupKind: String? = null,
    val audio: Boolean = false,
    val dateMillis: Double = 0.0,
)

@JsExport
fun urlParse(value: String): JsUrl? = Url.parse(value)?.let {
    JsUrl(it.scheme, it.username, it.password, it.host, it.port, it.path, it.query, it.fragment, it.href)
}

@JsExport
fun providersHost(url: String): String = Providers.host(url)

@JsExport
fun providersName(url: String, extractor: String? = null): String = Providers.name(url, extractor)

@JsExport
fun providersFolder(url: String): String = Providers.folder(url)

@JsExport
fun providersIsGallery(url: String): Boolean = Providers.isGallery(url)

@JsExport
fun providersIsYouTube(url: String?): Boolean = Providers.isYouTube(url)

@JsExport
fun providersIsYouTubeMusic(url: String?): Boolean = Providers.isYouTubeMusic(url)

@JsExport
fun providersIsShortMusicLink(url: String): Boolean = Providers.isShortMusicLink(url)

@JsExport
fun providersMusicLink(url: String): JsMusicLink? = Providers.musicLink(url)?.let { JsMusicLink(it.service, it.type, it.id, it.storefront) }

@JsExport
fun providersMusicLinkUrl(service: String, type: String, id: String, storefront: String? = null): String =
    Providers.musicLinkUrl(MusicLink(service, type, id, storefront))

@JsExport
fun providersMusicService(url: String): String? = Providers.musicService(url)

@JsExport
fun providersStartsAsVideo(url: String?, youTubeMusicAudio: Boolean): Boolean = Providers.startsAsVideo(url, youTubeMusicAudio)

@JsExport
fun providersPrefersAudio(url: String?, youTubeMusicAudio: Boolean): Boolean = Providers.prefersAudio(url, youTubeMusicAudio)

@JsExport
fun providersIsCollection(url: String): Boolean = Providers.isCollection(url)

@JsExport
fun providersIsRadioMix(list: String): Boolean = Providers.isRadioMix(list)

@JsExport
fun providersPlaylistUrl(url: String): String? = Providers.playlistUrl(url)

@JsExport
fun providersVideoId(url: String): String? = Providers.videoId(url)

@JsExport
fun providersSafeName(value: String, max: Int = 80): String = Providers.safeName(value, max)

@JsExport
fun providersValidateUrl(value: String): JsUrlCheck = Providers.validateUrl(value).let { JsUrlCheck(it.href, it.problem) }

@JsExport
fun namingIsoDate(millis: Double): String = Naming.isoDate(millis.toLong())

@JsExport
fun namingChannelArtist(uploader: String?): String? = Naming.channelArtist(uploader)

@JsExport
fun namingSubfolders(layout: String, job: JsPathJob): Array<String> = Naming.subfolders(layout, job.toJob()).toTypedArray()

@JsExport
fun namingStem(naming: String, job: JsPathJob): String = Naming.stem(naming, job.toJob())

@JsExport
fun namingSpaceSubfolder(layout: String, ctx: JsSpaceContext): String = Naming.spaceSubfolder(layout, ctx.toContext())

@JsExport
fun namingSpaceStem(naming: String, ctx: JsSpaceContext): String = Naming.spaceStem(naming, ctx.toContext())

private fun JsPathJob.toJob() = PathJob(url, title, uploader, createdAt.toLong(), groupTitle, musicArtist, musicAlbum, musicTrack)

private fun JsSpaceContext.toContext() = SpaceContext(title, uploader, artist, album, url, groupTitle, groupKind, audio, dateMillis.toLong())
