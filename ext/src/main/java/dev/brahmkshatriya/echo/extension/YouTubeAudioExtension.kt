package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.EchoMediaItem.Companion.toMediaItem
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.models.Track.Playable
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.IOException

class YouTubeAudioExtension : ExtensionClient, SearchFeedClient, TrackClient, HomeFeedClient {

    // Initialize NewPipe with YouTube service
    private val youtubeService = ServiceList.YouTube

    // Every extension has its own settings instance
    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) {
        setting = settings
    }

    // HTTP client for additional requests if needed
    private val httpClient = OkHttpClient()

    override suspend fun onInitialize() {
        // Initialize NewPipe extractor
        NewPipe.init(DownloaderImpl(httpClient))
        println("YouTube Audio Extension initialized")
    }

    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    // ===== SEARCH FEED CLIENT IMPLEMENTATION =====

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        return if (query.isBlank()) {
            Feed(listOf()) { getBrowsePage() }
        } else {
            val tabs = listOf(
                Tab("Videos", "videos"),
                Tab("Playlists", "playlists"),
                Tab("Channels", "channels")
            )
            Feed(tabs) { tab ->
                when (tab?.id) {
                    "videos" -> searchVideos(query)
                    "playlists" -> searchPlaylists(query)
                    "channels" -> searchChannels(query)
                    else -> searchVideos(query)
                }.toFeedData()
            }
        }
    }

    private fun getBrowsePage(): PagedData.Single<Shelf> {
        return PagedData.Single {
            listOf(
                Shelf.Lists.Tracks(
                    "trending",
                    "Trending",
                    listOf(
                        Track(
                            id = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                            title = "Never Gonna Give You Up",
                            cover = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg".toImageHolder(),
                            duration = 213,
                            artists = listOf(Artist("Rick Astley", "https://www.youtube.com/@RickAstley")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        ),
                        Track(
                            id = "https://www.youtube.com/watch?v=9bZkp7q19f0",
                            title = "Gangnam Style",
                            cover = "https://i.ytimg.com/vi/9bZkp7q19f0/default.jpg".toImageHolder(),
                            duration = 252,
                            artists = listOf(Artist("PSY", "https://www.youtube.com/@officialpsy")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        )
                    )
                )
            )
        }
    }

    private fun searchVideos(query: String): PagedData<Shelf> {
        return paged { page ->
            safeExecute("searchVideos", {
                val extractor = youtubeService.getSearchExtractor(query, listOf("videos"), "")
                extractor.fetchPage()
                
                val items = extractor.initialPage.items.mapNotNull { infoItem ->
                    when (infoItem) {
                        is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                            Track(
                                id = infoItem.url,
                                title = infoItem.name,
                                cover = infoItem.thumbnailUrl.toImageHolder(),
                                duration = infoItem.duration,
                                artists = listOf(Artist(infoItem.uploaderName, infoItem.uploaderUrl)),
                                album = null,
                                releaseDate = null,
                                isExplicit = false,
                                isPlayable = Playable.TRUE
                            )
                        }
                        else -> null
                    }
                }
                
                val tracks = items.map { it.toMediaItem() }
                val shelf = Shelf.Lists.Tracks("search_videos_$page", "Videos", tracks)
                listOf(shelf) to if (extractor.hasNextPage()) page + 1 else null
            }, emptyList<Shelf>() to null)
        }
    }

    private fun searchPlaylists(query: String): PagedData<Shelf> {
        return paged { page ->
            safeExecute("searchPlaylists", {
                val extractor = youtubeService.getSearchExtractor(query, listOf("playlists"), "")
                extractor.fetchPage()
                
                val items = extractor.initialPage.items.mapNotNull { infoItem ->
                    when (infoItem) {
                        is org.schabi.newpipe.extractor.playlist.PlaylistInfoItem -> {
                            Playlist(
                                id = infoItem.url,
                                title = infoItem.name,
                                cover = infoItem.thumbnailUrl.toImageHolder(),
                                description = infoItem.uploaderName,
                                author = Artist(infoItem.uploaderName, infoItem.uploaderUrl),
                                trackCount = infoItem.streamCount,
                                isEditable = false
                            )
                        }
                        else -> null
                    }
                }
                
                val playlists = items.map { it.toMediaItem() }
                val shelf = Shelf.Lists.Playlists("search_playlists_$page", "Playlists", playlists)
                listOf(shelf) to if (extractor.hasNextPage()) page + 1 else null
            }, emptyList<Shelf>() to null)
        }
    }

    private fun searchChannels(query: String): PagedData<Shelf> {
        return paged { page ->
            safeExecute("searchChannels", {
                val extractor = youtubeService.getSearchExtractor(query, listOf("channels"), "")
                extractor.fetchPage()
                
                val items = extractor.initialPage.items.mapNotNull { infoItem ->
                    when (infoItem) {
                        is org.schabi.newpipe.extractor.channel.ChannelInfoItem -> {
                            Artist(
                                id = infoItem.url,
                                name = infoItem.name,
                                cover = infoItem.thumbnailUrl.toImageHolder(),
                                description = infoItem.description
                            )
                        }
                        else -> null
                    }
                }
                
                val artists = items.map { it.toMediaItem() }
                val shelf = Shelf.Lists.Artists("search_channels_$page", "Channels", artists)
                listOf(shelf) to if (extractor.hasNextPage()) page + 1 else null
            }, emptyList<Shelf>() to null)
        }
    }

    // ===== TRACK CLIENT IMPLEMENTATION =====

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        return safeExecute("loadTrack", {
            val streamUrl = track.id
            logInfo("loadTrack", "Loading track: ${track.title}")
            
            val extractor = youtubeService.getStreamExtractor(streamUrl)
            extractor.fetchPage()
            
            val audioStreams = extractor.audioStreams
            logInfo("loadTrack", "Found ${audioStreams.size} audio streams")
            
            val streamables = audioStreams.map { audioStream ->
                Streamable(
                    id = audioStream.url ?: "",
                    type = Streamable.MediaType.Server,
                    quality = audioStream.getFormatText(),
                    extras = mapOf(
                        "format" to (audioStream.mimeType ?: "audio/*"),
                        "bitrate" to audioStream.averageBitrate.toString(),
                        "size" to audioStream.contentLength?.toString()
                    )
                )
            }
            
            track.copy(
                extras = mapOf("streamables" to streamables)
            )
        }, track)
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable,
        isDownload: Boolean
    ): Streamable.Media {
        return Streamable.Media.Server(
            streamable.id.toGetRequest(),
            headers = mapOf()
        )
    }

    // ===== HOME FEED CLIENT IMPLEMENTATION =====

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("Trending", "trending"),
            Tab("Music", "music"),
            Tab("Recommended", "recommended")
        )
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "trending" -> getTrendingFeed()
                "music" -> getMusicFeed()
                "recommended" -> getRecommendedFeed()
                else -> getTrendingFeed()
            }.toFeedData()
        }
    }

    private fun getTrendingFeed(): PagedData<Shelf> {
        return PagedData.Single {
            listOf(
                Shelf.Lists.Tracks(
                    "trending",
                    "Trending",
                    listOf(
                        Track(
                            id = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                            title = "Never Gonna Give You Up",
                            cover = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg".toImageHolder(),
                            duration = 213,
                            artists = listOf(Artist("Rick Astley", "https://www.youtube.com/@RickAstley")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        ),
                        Track(
                            id = "https://www.youtube.com/watch?v=9bZkp7q19f0",
                            title = "Gangnam Style",
                            cover = "https://i.ytimg.com/vi/9bZkp7q19f0/default.jpg".toImageHolder(),
                            duration = 252,
                            artists = listOf(Artist("PSY", "https://www.youtube.com/@officialpsy")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        )
                    )
                )
            )
        }
    }

    private fun getMusicFeed(): PagedData<Shelf> {
        return PagedData.Single {
            listOf(
                Shelf.Lists.Tracks(
                    "music",
                    "Music",
                    listOf(
                        Track(
                            id = "https://www.youtube.com/watch?v=fJ9rUzIMcZQ",
                            title = "Bohemian Rhapsody",
                            cover = "https://i.ytimg.com/vi/fJ9rUzIMcZQ/default.jpg".toImageHolder(),
                            duration = 354,
                            artists = listOf(Artist("Queen", "https://www.youtube.com/@queenofficial")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        )
                    )
                )
            )
        }
    }

    private fun getRecommendedFeed(): PagedData<Shelf> {
        return PagedData.Single {
            listOf(
                Shelf.Lists.Tracks(
                    "recommended",
                    "Recommended",
                    listOf(
                        Track(
                            id = "https://www.youtube.com/watch?v=hTWKbfoikeg",
                            title = "Smells Like Teen Spirit",
                            cover = "https://i.ytimg.com/vi/hTWKbfoikeg/default.jpg".toImageHolder(),
                            duration = 301,
                            artists = listOf(Artist("Nirvana", "https://www.youtube.com/@nirvana")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false,
                            isPlayable = Playable.TRUE
                        )
                    )
                )
            )
        }
    }

    // ===== HELPER METHODS AND ERROR HANDLING =====

    private fun logError(method: String, error: Exception, additionalInfo: String = "") {
        println("YouTubeAudioExtension.$method error: ${error.message}")
        if (additionalInfo.isNotEmpty()) {
            println("Additional info: $additionalInfo")
        }
        error.printStackTrace()
    }

    private fun logInfo(method: String, message: String) {
        println("YouTubeAudioExtension.$method: $message")
    }

    private fun <T> safeExecute(method: String, operation: () -> T, defaultValue: T): T {
        return try {
            operation()
        } catch (e: Exception) {
            logError(method, e)
            defaultValue
        }
    }

    private fun <T> paged(operation: suspend (Int) -> Pair<List<T>, Int?>): PagedData<T> {
        return object : PagedData<T> {
            private var currentPage = 0
            private var nextPage: Int? = 0

            override suspend fun load(page: Int?): Pair<List<T>, Int?> {
                val pageToLoad = page ?: nextPage ?: 0
                val (items, nextPage) = operation(pageToLoad)
                this.nextPage = nextPage
                return items to nextPage
            }
        }
    }

    // ===== DOWNLOADER IMPLEMENTATION =====

    private class DownloaderImpl(private val client: OkHttpClient) : org.schabi.newpipe.extractor.downloader.Downloader {
        override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
            val okhttpRequest = Request.Builder()
                .url(request.url())
                .headers(request.headers())
                .method(request.method(), request.dataToSend()?.let { it.toRequestBody() })
                .build()

            val response = client.newCall(okhttpRequest).execute()
            return org.schabi.newpipe.extractor.downloader.Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.bytes(),
                response.body?.contentLength()
            )
        }
    }

    private fun okhttp3.Headers.toMultimap(): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until size) {
            val name = name(i)
            val value = value(i)
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun ByteArray.toRequestBody() = okhttp3.RequestBody.create(null, this)
}