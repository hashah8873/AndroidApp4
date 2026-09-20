@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.androidapp4

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.androidapp4.ui.theme.SuperPodcastTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/** Represents a podcast returned by Apple's iTunes Search API. */
data class Podcast(
    val id: Long,
    val name: String,
    val artist: String,
    val genre: String,
    val artworkUrl: String,
    val feedUrl: String,
    val podcastUrl: String,
    val episodeCount: Int,
    val reviewCount: Int
)

/** Represents one episode parsed from a podcast RSS feed. */
data class Episode(
    val guid: String,
    val title: String,
    val description: String,
    val audioUrl: String,
    val publishedDate: String,
    val duration: String
)

/** Contains podcast information plus its recent RSS episodes. */
data class PodcastDetails(
    val title: String,
    val description: String,
    val imageUrl: String,
    val episodes: List<Episode>
)

/** Performs iTunes and RSS network operations away from the main thread. */
object PodcastRepository {

    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"

    /** Searches Apple's iTunes Search API for podcasts. */
    suspend fun searchPodcasts(searchTerm: String): List<Podcast> =
        withContext(Dispatchers.IO) {
            if (searchTerm.isBlank()) return@withContext emptyList()

            val encodedTerm = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val requestUrl = "$ITUNES_SEARCH_URL?term=$encodedTerm&country=ca&media=podcast&entity=podcast&limit=50"
            val connection = URL(requestUrl).openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val results = JSONObject(response).optJSONArray("results") ?: JSONArray()
                val podcasts = mutableListOf<Podcast>()

                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val id = item.optLong("collectionId", 0L)
                    if (id == 0L) continue

                    podcasts.add(
                        Podcast(
                            id = id,
                            name = item.optString("collectionName", "Unknown Podcast"),
                            artist = item.optString("artistName", "Unknown Author"),
                            genre = item.optString("primaryGenreName", "Podcast"),
                            artworkUrl = item.optString(
                                "artworkUrl600",
                                item.optString("artworkUrl100", "")
                            ),
                            feedUrl = item.optString("feedUrl", ""),
                            podcastUrl = item.optString("collectionViewUrl", ""),
                            episodeCount = item.optInt("trackCount", 0),
                            reviewCount = item.optInt("userRatingCount", 0)
                        )
                    )
                }
                podcasts
            } finally {
                connection.disconnect()
            }
        }

    /** Downloads and parses a podcast RSS feed. */
    suspend fun getPodcastDetails(podcast: Podcast): PodcastDetails? =
        withContext(Dispatchers.IO) {
            if (podcast.feedUrl.isBlank()) return@withContext null

            val connection = URL(podcast.feedUrl).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val parser = Xml.newPullParser()
                connection.inputStream.use { inputStream ->
                    parser.setInput(inputStream, null)

                    var eventType = parser.eventType
                    var channelTitle = podcast.name
                    var channelDescription = ""
                    var channelImage = podcast.artworkUrl
                    val episodes = mutableListOf<Episode>()
                    var insideItem = false
                    var currentTitle = ""
                    var currentDescription = ""
                    var currentAudioUrl = ""
                    var currentPublishedDate = ""
                    var currentDuration = ""
                    var currentGuid = ""
                    var itemIndex = 0

                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        when (eventType) {
                            XmlPullParser.START_TAG -> {
                                val tag = parser.name.lowercase()

                                when {
                                    tag == "item" -> {
                                        insideItem = true
                                        currentTitle = ""
                                        currentDescription = ""
                                        currentAudioUrl = ""
                                        currentPublishedDate = ""
                                        currentDuration = ""
                                        currentGuid = ""
                                    }

                                    tag == "enclosure" && insideItem -> {
                                        currentAudioUrl = parser.getAttributeValue(null, "url") ?: ""
                                    }

                                    tag == "title" -> {
                                        val value = safeNextText(parser)
                                        if (insideItem) currentTitle = value
                                        else if (value.isNotBlank()) channelTitle = value
                                    }

                                    tag == "description" -> {
                                        val value = cleanHtml(safeNextText(parser))
                                        if (insideItem) currentDescription = value
                                        else if (value.isNotBlank()) channelDescription = value
                                    }

                                    tag == "pubdate" && insideItem -> {
                                        currentPublishedDate = safeNextText(parser)
                                    }

                                    tag == "guid" && insideItem -> {
                                        currentGuid = safeNextText(parser)
                                    }

                                    tag == "duration" && insideItem -> {
                                        currentDuration = safeNextText(parser)
                                    }

                                    tag == "url" && !insideItem -> {
                                        val value = safeNextText(parser)
                                        if (value.isNotBlank()) channelImage = value
                                    }
                                }
                            }

                            XmlPullParser.END_TAG -> {
                                if (parser.name.equals("item", ignoreCase = true)) {
                                    if (currentTitle.isNotBlank() || currentAudioUrl.isNotBlank()) {
                                        episodes.add(
                                            Episode(
                                                guid = currentGuid.ifBlank { "${podcast.id}-$itemIndex" },
                                                title = currentTitle.ifBlank { "Untitled Episode" },
                                                description = currentDescription,
                                                audioUrl = currentAudioUrl,
                                                publishedDate = currentPublishedDate,
                                                duration = currentDuration
                                            )
                                        )
                                        itemIndex++
                                    }
                                    insideItem = false
                                }
                            }
                        }
                        eventType = parser.next()
                    }

                    PodcastDetails(
                        title = channelTitle,
                        description = channelDescription,
                        imageUrl = channelImage.ifBlank { podcast.artworkUrl },
                        episodes = episodes.take(50)
                    )
                }
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

    /** Safely reads text from an XML tag. */
    private fun safeNextText(parser: XmlPullParser): String =
        try {
            parser.nextText().trim()
        } catch (_: Exception) {
            ""
        }

    /** Converts HTML episode descriptions into readable plain text. */
    private fun cleanHtml(value: String): String =
        try {
            Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } catch (_: Exception) {
            value.trim()
        }

    /** Downloads podcast artwork in the background. */
    suspend fun downloadArtwork(url: String): Bitmap? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        try {
            URL(url).openStream().use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}

/** Main Activity and MediaPlayer controller. */
class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    private var isPlayingState by mutableStateOf(false)
    private var currentEpisodeState by mutableStateOf<Episode?>(null)
    private var currentPositionState by mutableIntStateOf(0)
    private var durationState by mutableIntStateOf(0)

    /** Repeatedly updates the player progress shown in Compose. */
    private val progressRunnable = object : Runnable {
        override fun run() {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    currentPositionState = player.currentPosition
                    handler.postDelayed(this, 500)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SuperPodcastTheme {
                SuperPodcastApp(
                    context = this,
                    onOpenPodcast = ::openPodcast,
                    onPlayEpisode = ::playEpisode,
                    onPauseEpisode = ::pausePlayback,
                    onResumeEpisode = ::resumePlayback,
                    onStopPlayback = ::stopPlayback,
                    onSeekBack = { seekBy(-10_000) },
                    onSeekForward = { seekBy(30_000) },
                    onSeekTo = ::seekTo,
                    isPlaying = isPlayingState,
                    currentEpisode = currentEpisodeState,
                    currentPosition = currentPositionState,
                    duration = durationState
                )
            }
        }
    }

    /** Opens the podcast page in the device browser. */
    private fun openPodcast(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // Prevents a browser error from crashing the app.
        }
    }

    /** Starts playback for the selected episode. */
    private fun playEpisode(episode: Episode) {
        if (episode.audioUrl.isBlank()) return

        stopPlayback()
        currentEpisodeState = episode

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(episode.audioUrl)

                setOnPreparedListener { player ->
                    durationState = player.duration.coerceAtLeast(0)
                    currentPositionState = 0
                    isPlayingState = true
                    player.start()
                    handler.post(progressRunnable)
                }

                setOnCompletionListener {
                    isPlayingState = false
                    currentPositionState = durationState
                    handler.removeCallbacks(progressRunnable)
                }

                setOnErrorListener { _, _, _ ->
                    stopPlayback()
                    true
                }

                prepareAsync()
            }
        } catch (_: Exception) {
            stopPlayback()
        }
    }

    /** Pauses the current episode without resetting its position. */
    private fun pausePlayback() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) {
                    player.pause()
                    isPlayingState = false
                    currentPositionState = player.currentPosition
                    handler.removeCallbacks(progressRunnable)
                }
            } catch (_: Exception) {
                // Safely ignores an invalid player state.
            }
        }
    }

    /** Resumes the paused episode. */
    private fun resumePlayback() {
        mediaPlayer?.let { player ->
            try {
                if (!player.isPlaying) {
                    player.start()
                    isPlayingState = true
                    handler.post(progressRunnable)
                }
            } catch (_: Exception) {
                // Safely ignores an invalid player state.
            }
        }
    }

    /** Seeks by a positive or negative number of milliseconds. */
    private fun seekBy(milliseconds: Int) {
        mediaPlayer?.let { player ->
            try {
                val target = (player.currentPosition + milliseconds)
                    .coerceIn(0, player.duration.coerceAtLeast(0))
                player.seekTo(target)
                currentPositionState = target
            } catch (_: Exception) {
                // Safely ignores an invalid player state.
            }
        }
    }

    /** Moves playback to an exact position selected by the progress slider. */
    private fun seekTo(position: Int) {
        mediaPlayer?.let { player ->
            try {
                val target = position.coerceIn(0, player.duration.coerceAtLeast(0))
                player.seekTo(target)
                currentPositionState = target
            } catch (_: Exception) {
                // Safely ignores an invalid player state.
            }
        }
    }

    /** Stops playback and releases MediaPlayer resources. */
    private fun stopPlayback() {
        handler.removeCallbacks(progressRunnable)
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Exception) {
                // Player may already be stopped or released.
            }
            try {
                player.release()
            } catch (_: Exception) {
                // Resource was already released.
            }
        }
        mediaPlayer = null
        isPlayingState = false
        currentEpisodeState = null
        currentPositionState = 0
        durationState = 0
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }
}

/** Main SuperPodcast application state and navigation. */
@Composable
fun SuperPodcastApp(
    context: Context,
    onOpenPodcast: (String) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onPauseEpisode: () -> Unit,
    onResumeEpisode: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit,
    isPlaying: Boolean,
    currentEpisode: Episode?,
    currentPosition: Int,
    duration: Int
) {
    var searchText by remember { mutableStateOf("") }
    var regexText by remember { mutableStateOf("") }
    var minimumWordsText by remember { mutableStateOf("") }
    var podcasts by remember { mutableStateOf<List<Podcast>>(emptyList()) }
    var subscribedPodcasts by remember { mutableStateOf(loadSubscriptions(context)) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    var sortLowestReviews by remember { mutableStateOf(false) }
    var selectedPodcast by remember { mutableStateOf<Podcast?>(null) }
    val scope = rememberCoroutineScope()

    fun toggleSubscription(podcast: Podcast) {
        val alreadySubscribed = subscribedPodcasts.any { it.id == podcast.id }
        val updated = if (alreadySubscribed) {
            subscribedPodcasts.filter { it.id != podcast.id }
        } else {
            subscribedPodcasts + podcast
        }
        subscribedPodcasts = updated
        saveSubscriptions(context, updated)
    }

    fun performSearch() {
        if (searchText.isBlank()) {
            errorMessage = "Please enter a podcast to search."
            return
        }

        scope.launch {
            isLoading = true
            errorMessage = ""
            try {
                podcasts = PodcastRepository.searchPodcasts(searchText)
                if (podcasts.isEmpty()) errorMessage = "No podcasts were found."
            } catch (_: Exception) {
                errorMessage = "Unable to connect to iTunes. Please check your internet connection."
            } finally {
                isLoading = false
            }
        }
    }

    val filteredPodcasts = remember(
        podcasts,
        regexText,
        minimumWordsText,
        sortLowestReviews
    ) {
        var result = podcasts

        if (regexText.isNotBlank()) {
            try {
                val pattern = Pattern.compile(regexText, Pattern.CASE_INSENSITIVE)
                result = result.filter { podcast ->
                    pattern.matcher(podcast.name).find() ||
                            pattern.matcher(podcast.artist).find() ||
                            pattern.matcher(podcast.genre).find()
                }
            } catch (_: Exception) {
                // Invalid regular expressions are ignored safely.
            }
        }

        val minimumWords = minimumWordsText.toIntOrNull()
        if (minimumWords != null && minimumWords > 0) {
            result = result.filter { countWords(it.name) >= minimumWords }
        }

        if (sortLowestReviews) result = result.sortedBy { it.reviewCount }
        result
    }

    if (selectedPodcast != null) {
        PodcastDetailsScreen(
            podcast = selectedPodcast!!,
            subscribedPodcasts = subscribedPodcasts,
            onBack = { selectedPodcast = null },
            onSubscribe = ::toggleSubscription,
            onOpenPodcast = onOpenPodcast,
            onPlayEpisode = onPlayEpisode,
            onStopPlayback = onStopPlayback,
            currentEpisode = currentEpisode,
            isPlaying = isPlaying,
            currentPosition = currentPosition,
            duration = duration,
            onPauseEpisode = onPauseEpisode,
            onResumeEpisode = onResumeEpisode,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward,
            onSeekTo = onSeekTo
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("SuperPodcast", fontWeight = FontWeight.Bold)
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Search") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Subscriptions") }
                )
            }

            if (selectedTab == 0) {
                SearchScreen(
                    searchText = searchText,
                    onSearchTextChange = { searchText = it },
                    regexText = regexText,
                    onRegexChange = { regexText = it },
                    minimumWordsText = minimumWordsText,
                    onMinimumWordsChange = { minimumWordsText = it },
                    sortLowestReviews = sortLowestReviews,
                    onSortLowestReviews = { sortLowestReviews = !sortLowestReviews },
                    onSearch = ::performSearch,
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    podcasts = filteredPodcasts,
                    subscribedPodcasts = subscribedPodcasts,
                    onSubscribe = ::toggleSubscription,
                    onOpenDetails = { selectedPodcast = it }
                )
            } else {
                SubscriptionScreen(
                    subscribedPodcasts = subscribedPodcasts,
                    onUnsubscribe = ::toggleSubscription,
                    onOpenDetails = { selectedPodcast = it }
                )
            }
        }
    }
}

/** Search interface and advanced podcast filtering. */
@Composable
fun SearchScreen(
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    regexText: String,
    onRegexChange: (String) -> Unit,
    minimumWordsText: String,
    onMinimumWordsChange: (String) -> Unit,
    sortLowestReviews: Boolean,
    onSortLowestReviews: () -> Unit,
    onSearch: () -> Unit,
    isLoading: Boolean,
    errorMessage: String,
    podcasts: List<Podcast>,
    subscribedPodcasts: List<Podcast>,
    onSubscribe: (Podcast) -> Unit,
    onOpenDetails: (Podcast) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = searchText,
            onValueChange = onSearchTextChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Podcast Search") },
            placeholder = { Text("Example: technology") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = regexText,
            onValueChange = onRegexChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Regular Expression Filter") },
            placeholder = { Text("Example: tech|science") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = minimumWordsText,
            onValueChange = onMinimumWordsChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Minimum Words in Title") },
            placeholder = { Text("Example: 3") },
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onSearch, modifier = Modifier.weight(1f)) {
                Text("Search")
            }
            OutlinedButton(
                onClick = onSortLowestReviews,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (sortLowestReviews) "Lowest Reviews ✓" else "Lowest Reviews")
            }
        }
        Spacer(Modifier.height(10.dp))

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        if (errorMessage.isNotBlank()) {
            Text(
                text = errorMessage,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        if (!isLoading && podcasts.isNotEmpty()) {
            Text(
                text = "${podcasts.size} podcasts found",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(podcasts, key = { it.id }) { podcast ->
                PodcastCard(
                    podcast = podcast,
                    isSubscribed = subscribedPodcasts.any { it.id == podcast.id },
                    onSubscribe = { onSubscribe(podcast) },
                    onOpenDetails = { onOpenDetails(podcast) }
                )
            }
        }
    }
}

/** Displays saved subscriptions. */
@Composable
fun SubscriptionScreen(
    subscribedPodcasts: List<Podcast>,
    onUnsubscribe: (Podcast) -> Unit,
    onOpenDetails: (Podcast) -> Unit
) {
    if (subscribedPodcasts.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No subscribed podcasts yet.", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Search for a podcast and tap Subscribe.")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "My Subscriptions",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text("${subscribedPodcasts.size} subscribed podcasts")
                Spacer(Modifier.height(8.dp))
            }
            items(subscribedPodcasts, key = { it.id }) { podcast ->
                PodcastCard(
                    podcast = podcast,
                    isSubscribed = true,
                    onSubscribe = { onUnsubscribe(podcast) },
                    onOpenDetails = { onOpenDetails(podcast) }
                )
            }
        }
    }
}

/** Displays one podcast with navigation to its details. */
@Composable
fun PodcastCard(
    podcast: Podcast,
    isSubscribed: Boolean,
    onSubscribe: () -> Unit,
    onOpenDetails: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                PodcastArtwork(url = podcast.artworkUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        podcast.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        podcast.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Genre: ${podcast.genre}")
                    Text("Episodes: ${podcast.episodeCount}")
                    Text("Reviews: ${podcast.reviewCount}")
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenDetails,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Podcast Details")
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onSubscribe,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isSubscribed) "Unsubscribe" else "Subscribe")
            }
        }
    }
}

/** Displays RSS details and the episode list for one podcast. */
@Composable
fun PodcastDetailsScreen(
    podcast: Podcast,
    subscribedPodcasts: List<Podcast>,
    onBack: () -> Unit,
    onSubscribe: (Podcast) -> Unit,
    onOpenPodcast: (String) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onStopPlayback: () -> Unit,
    currentEpisode: Episode?,
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    onPauseEpisode: () -> Unit,
    onResumeEpisode: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit
) {
    var details by remember(podcast.id) { mutableStateOf<PodcastDetails?>(null) }
    var isLoading by remember(podcast.id) { mutableStateOf(true) }
    var errorMessage by remember(podcast.id) { mutableStateOf("") }

    LaunchedEffect(podcast.id) {
        isLoading = true
        errorMessage = ""
        val result = PodcastRepository.getPodcastDetails(podcast)
        if (result == null) {
            errorMessage = "Unable to load podcast episodes. Please try again."
        } else {
            details = result
        }
        isLoading = false
    }

    val isSubscribed = subscribedPodcasts.any { it.id == podcast.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Podcast Details", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Back") }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PodcastArtworkLarge(url = details?.imageUrl ?: podcast.artworkUrl)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        details?.title ?: podcast.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(podcast.artist)
                }
            }

            item {
                Button(
                    onClick = { onSubscribe(podcast) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isSubscribed) "Unsubscribe" else "Subscribe")
                }
                OutlinedButton(
                    onClick = { onOpenPodcast(podcast.podcastUrl) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Podcast in Browser")
                }
            }

            item {
                Text(
                    "About this Podcast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    details?.description?.ifBlank { "No description available." }
                        ?: "Loading description..."
                )
            }

            item {
                Text(
                    "Recent Episodes",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            if (isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            if (errorMessage.isNotBlank()) {
                item {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error)
                }
            }

            val episodes = details?.episodes.orEmpty()

            if (!isLoading && episodes.isEmpty() && errorMessage.isBlank()) {
                item { Text("No episodes were found in this podcast RSS feed.") }
            }

            items(episodes, key = { it.guid }) { episode ->
                EpisodeCard(
                    episode = episode,
                    onPlay = { onPlayEpisode(episode) },
                    onStop = onStopPlayback,
                    isCurrentEpisode = currentEpisode?.guid == episode.guid,
                    isPlaying = isPlaying && currentEpisode?.guid == episode.guid,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPause = onPauseEpisode,
                    onResume = onResumeEpisode,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onSeekTo = onSeekTo
                )
            }
        }
    }
}

/** Displays one episode and its playback controls. */
@Composable
fun EpisodeCard(
    episode: Episode,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    isCurrentEpisode: Boolean,
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(episode.title, fontWeight = FontWeight.Bold)

            if (episode.publishedDate.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Published: ${episode.publishedDate}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (episode.duration.isNotBlank()) {
                Text(
                    "Duration: ${episode.duration}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (episode.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    episode.description,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(8.dp))

            if (isCurrentEpisode) {
                PlayerControls(
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlay = onPlay,
                    onPause = onPause,
                    onResume = onResume,
                    onStop = onStop,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onSeekTo = onSeekTo
                )
            } else {
                Button(
                    onClick = onPlay,
                    enabled = episode.audioUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Play Episode")
                }
            }
        }
    }
}

/** Complete playback controls for the selected episode. */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    currentPosition: Int,
    duration: Int,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekTo: (Int) -> Unit
) {
    Text(
        "Now Playing",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))

    val safeDuration = duration.coerceAtLeast(1)
    var sliderPosition by remember(currentPosition, duration) {
        mutableFloatStateOf(currentPosition.coerceIn(0, safeDuration).toFloat())
    }

    Slider(
        value = sliderPosition,
        onValueChange = { sliderPosition = it },
        onValueChangeFinished = { onSeekTo(sliderPosition.toInt()) },
        valueRange = 0f..safeDuration.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(formatTime(currentPosition))
        Text(formatTime(duration))
    }

    Spacer(Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OutlinedButton(
            onClick = onSeekBack,
            modifier = Modifier.weight(1f)
        ) {
            Text("-10 sec")
        }

        Button(
            onClick = if (isPlaying) onPause else onResume,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (isPlaying) "Pause" else "Resume")
        }

        OutlinedButton(
            onClick = onSeekForward,
            modifier = Modifier.weight(1f)
        ) {
            Text("+30 sec")
        }
    }

    Spacer(Modifier.height(6.dp))

    OutlinedButton(
        onClick = onStop,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Stop")
    }
}

/** Formats milliseconds as minutes and seconds. */
fun formatTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** Downloads and displays podcast artwork. */
@Composable
fun PodcastArtwork(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = PodcastRepository.downloadArtwork(url)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Podcast artwork",
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Podcast")
        }
    }
}

/** Larger artwork used on the details screen. */
@Composable
fun PodcastArtworkLarge(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(url) {
        bitmap = PodcastRepository.downloadArtwork(url)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "Podcast artwork",
            modifier = Modifier
                .size(190.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(190.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text("Podcast")
        }
    }
}

/** Counts words in a podcast title for the advanced assignment filter. */
fun countWords(text: String): Int =
    text.trim()
        .split(Regex("\\s+"))
        .count { it.isNotBlank() }

/** Saves podcast subscriptions locally as JSON. */
fun saveSubscriptions(context: Context, podcasts: List<Podcast>) {
    val jsonArray = JSONArray()

    podcasts.forEach { podcast ->
        val jsonObject = JSONObject()
        jsonObject.put("id", podcast.id)
        jsonObject.put("name", podcast.name)
        jsonObject.put("artist", podcast.artist)
        jsonObject.put("genre", podcast.genre)
        jsonObject.put("artworkUrl", podcast.artworkUrl)
        jsonObject.put("feedUrl", podcast.feedUrl)
        jsonObject.put("podcastUrl", podcast.podcastUrl)
        jsonObject.put("episodeCount", podcast.episodeCount)
        jsonObject.put("reviewCount", podcast.reviewCount)
        jsonArray.put(jsonObject)
    }

    context.getSharedPreferences(
        "super_podcast_preferences",
        Context.MODE_PRIVATE
    ).edit()
        .putString("subscriptions", jsonArray.toString())
        .apply()
}

/** Loads saved subscriptions from SharedPreferences. */
fun loadSubscriptions(context: Context): List<Podcast> {
    val preferences = context.getSharedPreferences(
        "super_podcast_preferences",
        Context.MODE_PRIVATE
    )

    val savedJson = preferences.getString("subscriptions", "[]") ?: "[]"

    return try {
        val jsonArray = JSONArray(savedJson)
        val podcasts = mutableListOf<Podcast>()

        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            podcasts.add(
                Podcast(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    artist = item.optString("artist"),
                    genre = item.optString("genre"),
                    artworkUrl = item.optString("artworkUrl"),
                    feedUrl = item.optString("feedUrl"),
                    podcastUrl = item.optString("podcastUrl"),
                    episodeCount = item.optInt("episodeCount"),
                    reviewCount = item.optInt("reviewCount")
                )
            )
        }

        podcasts
    } catch (_: Exception) {
        emptyList()
    }
}
