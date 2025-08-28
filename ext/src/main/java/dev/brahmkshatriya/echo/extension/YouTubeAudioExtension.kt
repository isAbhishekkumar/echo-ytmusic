package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.ClientException
import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.*
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.NetworkRequest.Companion.toGetRequest
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toMedia
import dev.brahmkshatriya.echo.common.models.Streamable.Source.Companion.toSource
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.Settings
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
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

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(listOf()) { getBrowsePage() }
        }
        
        val tabs = listOf(
            Tab("VIDEOS", "Videos"),
            Tab("PLAYLISTS", "Playlists"),
            Tab("CHANNELS", "Channels")
        )
        
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "VIDEOS" -> paged { page ->
                    searchYouTube(query, "videos", page)
                }
                "PLAYLISTS" -> paged { page ->
                    searchYouTube(query, "playlists", page)
                }
                "CHANNELS" -> paged { page ->
                    searchYouTube(query, "channels", page)
                }
                else -> emptyList<Shelf>()
            }
        }
    }

    private fun getBrowsePage(): Feed.Data<Shelf> = PagedData.Single {
        emptyList<Shelf>()
    }.toFeedData()

    private suspend fun searchYouTube(query: String, filter: String, page: Int?): Pair<List<Shelf>, Int?> {
        return safeExecute("searchYouTube", {
            val searchQuery = "$query $filter"
            logInfo("searchYouTube", "Searching for: $searchQuery")
            
            val extractor = youtubeService.getSearchExtractor(searchQuery, listOf(filter), "")
            extractor.fetchPage()
            
            val tracks = extractor.initialPage.items.mapNotNull { infoItem ->
                when (infoItem) {
                    is org.schabi.newpipe.extractor.stream.StreamInfoItem -> {
                        Track(
                            id = infoItem.url,
                            title = infoItem.name,
                            cover = null,
                            duration = infoItem.duration,
                            artists = listOf(Artist("Unknown")),
                            album = null,
                            releaseDate = null,
                            isExplicit = false
                        )
                    }
                    else -> null
                }
            }
            
            logInfo("searchYouTube", "Found ${tracks.size} results")
            listOf(Shelf.Lists.Tracks("search_results", "Search Results", tracks)) to null
        }, emptyList<Shelf>() to null)
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
            
            track.copy(
                streamables = audioStreams.map { audioStream ->
                    Streamable(
                        id = audioStream.url,
                        quality = "unknown",
                        type = Streamable.MediaType.Server,
                        extras = mapOf(
                            "format" to "unknown",
                            "bitrate" to "0",
                            "size" to "0"
                        )
                    )
                }
            )
        }, track)
    }

    override suspend fun loadStreamableMedia(
        streamable: Streamable, 
        isDownload: Boolean
    ): Streamable.Media {
        return Streamable.Media.Background(streamable.id.toGetRequest())
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf>? {
        // For YouTube tracks, we can return related videos as recommendations
        return null
    }

    // ===== HOME FEED CLIENT IMPLEMENTATION =====

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        val tabs = listOf(
            Tab("TRENDING", "Trending"),
            Tab("MUSIC", "Music"),
            Tab("RECOMMENDED", "Recommended")
        )
        
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "TRENDING" -> paged { page ->
                    getHomeFeedContent("trending", page)
                }
                "MUSIC" -> paged { page ->
                    getHomeFeedContent("music", page)
                }
                "RECOMMENDED" -> paged { page ->
                    getHomeFeedContent("recommended", page)
                }
                else -> emptyList<Shelf>()
            }
        }
    }

    private suspend fun getHomeFeedContent(feedType: String, page: Int?): Pair<List<Shelf>, Int?> {
        return safeExecute("getHomeFeedContent", {
            logInfo("getHomeFeedContent", "Loading feed type: $feedType")
            
            // For now, we'll simulate home feed content
            // In a real implementation, you'd extract from YouTube's actual feeds
            val tracks = listOf(
                Track(
                    id = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                    title = "Never Gonna Give You Up",
                    cover = null,
                    duration = 213,
                    artists = listOf(Artist("Rick Astley")),
                    album = null,
                    releaseDate = null,
                    isExplicit = false
                ),
                Track(
                    id = "https://www.youtube.com/watch?v=9bZkp7q19f0",
                    title = "Gangnam Style",
                    cover = null,
                    duration = 252,
                    artists = listOf(Artist("PSY")),
                    album = null,
                    releaseDate = null,
                    isExplicit = false
                )
            )
            
            logInfo("getHomeFeedContent", "Loaded ${tracks.size} items")
            listOf(Shelf.Lists.Tracks("${feedType}_tracks", "Popular Tracks", tracks)) to null
        }, emptyList<Shelf>() to null)
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

    // Helper function to create paged data
    private fun <T> paged(loader: suspend (Int?) -> Pair<List<T>, Int?>): Feed.Data<T> {
        return object : PagedData<T> {
            override suspend fun loadListInternal(continuation: String?): Feed.Page<T> {
                val page = continuation?.toIntOrNull()
                val (items, nextPage) = loader(page)
                return Feed.Page(items, nextPage?.toString())
            }
            
            override suspend fun loadAllInternal(): List<T> {
                val (items, _) = loader(null)
                return items
            }
            
            override fun clear() {}
            override fun invalidate(continuation: String?) {}
            override fun <R : Any> map(block: suspend (Result<List<T>>) -> List<R>): PagedData<R> {
                TODO("Not yet implemented")
            }
        }.toFeedData()
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