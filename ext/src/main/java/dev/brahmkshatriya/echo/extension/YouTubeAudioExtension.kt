package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.*
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.IOException

// For more information on which clients to use
// visit https://brahmkshatriya.github.io/echo/common/dev.brahmkshatriya.echo.common/
class YouTubeAudioExtension : ExtensionClient, SearchFeedClient, TrackClient, HomeFeedClient {

    // Initialize NewPipe with YouTube service
    private val youtubeService = ServiceList.YouTube
    private val searchExtractor: SearchExtractor = youtubeService.searchExtractor

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

    // Simple HTTP client usage example
    override suspend fun getSettingItems(): List<Setting> {
        return emptyList()
    }

    // ===== SEARCH FEED CLIENT IMPLEMENTATION =====

    override suspend fun loadSearchFeed(query: String): Feed {
        return Feed(
            tabs = listOf("Videos", "Playlists", "Channels"),
            context = query
        )
    }

    override suspend fun loadSearchFeed(query: String, tab: String?): PagedData<MediaItems> {
        return object : PagedData<MediaItems> {
            override suspend fun loadPage(page: Page?): Page<MediaItems> {
                return safeExecute("loadSearchFeed", {
                    val searchQuery = if (tab == null) query else "$query $tab"
                    logInfo("loadSearchFeed", "Searching for: $searchQuery")
                    
                    val extractor = youtubeService.getSearchExtractor(searchQuery, listOf("videos"), "")
                    extractor.fetchPage()
                    
                    val items = extractor.relatedItems.mapNotNull { infoItem ->
                        when (infoItem) {
                            is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                                MediaItems.Item.Track(
                                    Track(
                                        id = infoItem.url,
                                        title = infoItem.name,
                                        cover = infoItem.thumbnailUrl,
                                        duration = infoItem.duration,
                                        artists = listOf(Artist(infoItem.uploaderName, infoItem.uploaderUrl)),
                                        album = null,
                                        releaseDate = null,
                                        isExplicit = false,
                                        isPlayable = true
                                    )
                                )
                            }
                            else -> null
                        }
                    }
                    
                    logInfo("loadSearchFeed", "Found ${items.size} results")
                    Page(items, if (extractor.hasNextPage()) Page(extractor.nextPage) else null)
                }, Page(emptyList(), null))
            }
        }
    }

    // ===== TRACK CLIENT IMPLEMENTATION =====

    override suspend fun loadTrack(track: Track, refresh: Boolean): Track {
        return safeExecute("loadTrack", {
            val streamUrl = track.id
            logInfo("loadTrack", "Loading track: ${track.title}")
            
            val extractor = youtubeService.getStreamExtractor(streamUrl)
            extractor.fetchPage()
            
            val audioStreams = extractor.audioStreams
            logInfo("loadTrack", "Found ${audioStreams.size} audio streams")
            
            track.copy(
                servers = audioStreams.map { audioStream ->
                    Streamable(
                        id = audioStream.url,
                        quality = audioStream.getFormatText(),
                        format = audioStream.mimeType ?: "audio/*",
                        bitrate = audioStream.averageBitrate,
                        size = audioStream.contentLength,
                        headers = mapOf()
                    )
                }
            )
        }, track)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, refresh: Boolean): StreamableMedia {
        return StreamableMedia(
            url = streamable.id,
            format = streamable.format,
            headers = streamable.headers,
            bitrate = streamable.bitrate,
            size = streamable.size
        )
    }

    // ===== HOME FEED CLIENT IMPLEMENTATION =====

    override suspend fun loadHomeFeed(): Feed {
        return Feed(
            tabs = listOf("Trending", "Music", "Recommended"),
            context = "home"
        )
    }

    override suspend fun loadHomeFeed(tab: String?): PagedData<MediaItems> {
        return object : PagedData<MediaItems> {
            override suspend fun loadPage(page: Page?): Page<MediaItems> {
                return safeExecute("loadHomeFeed", {
                    val feedType = when (tab) {
                        "Music" -> "music"
                        "Trending" -> "trending"
                        else -> "default"
                    }
                    
                    logInfo("loadHomeFeed", "Loading feed type: $feedType")
                    
                    // For now, we'll simulate home feed content
                    // In a real implementation, you'd extract from YouTube's actual feeds
                    val items = listOf(
                        MediaItems.Item.Track(
                            Track(
                                id = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                                title = "Never Gonna Give You Up",
                                cover = "https://i.ytimg.com/vi/dQw4w9WgXcQ/default.jpg",
                                duration = 213,
                                artists = listOf(Artist("Rick Astley", "https://www.youtube.com/@RickAstley")),
                                album = null,
                                releaseDate = null,
                                isExplicit = false,
                                isPlayable = true
                            )
                        ),
                        MediaItems.Item.Track(
                            Track(
                                id = "https://www.youtube.com/watch?v=9bZkp7q19f0",
                                title = "Gangnam Style",
                                cover = "https://i.ytimg.com/vi/9bZkp7q19f0/default.jpg",
                                duration = 252,
                                artists = listOf(Artist("PSY", "https://www.youtube.com/@officialpsy")),
                                album = null,
                                releaseDate = null,
                                isExplicit = false,
                                isPlayable = true
                            )
                        )
                    )
                    
                    logInfo("loadHomeFeed", "Loaded ${items.size} items")
                    Page(items, null) // No pagination for now
                }, Page(emptyList(), null))
            }
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