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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.regex.Pattern

/**
 * Represents a podcast returned by Apple's iTunes Search API.
 */
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

/**
 * Repository responsible for all online podcast operations.
 *
 * The iTunes Search API is used to search for podcasts.
 * Podcast RSS feeds are used to locate an episode for playback.
 */
object PodcastRepository {

    private const val ITUNES_SEARCH_URL =
        "https://itunes.apple.com/search"

    /**
     * Searches Apple's iTunes Search API for podcasts.
     */
    suspend fun searchPodcasts(
        searchTerm: String
    ): List<Podcast> = withContext(Dispatchers.IO) {

        if (searchTerm.isBlank()) {
            return@withContext emptyList()
        }

        val encodedTerm = URLEncoder.encode(
            searchTerm.trim(),
            "UTF-8"
        )

        val requestUrl =
            "$ITUNES_SEARCH_URL" +
                    "?term=$encodedTerm" +
                    "&country=ca" +
                    "&media=podcast" +
                    "&entity=podcast" +
                    "&limit=50"

        val connection =
            URL(requestUrl).openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val response =
                connection.inputStream.bufferedReader().use {
                    it.readText()
                }

            val json =
                JSONObject(response)

            val results =
                json.optJSONArray("results")
                    ?: JSONArray()

            val podcasts =
                mutableListOf<Podcast>()

            for (index in 0 until results.length()) {

                val item =
                    results.optJSONObject(index)
                        ?: continue

                val id =
                    item.optLong(
                        "collectionId",
                        0L
                    )

                if (id == 0L) {
                    continue
                }

                val name =
                    item.optString(
                        "collectionName",
                        "Unknown Podcast"
                    )

                val artist =
                    item.optString(
                        "artistName",
                        "Unknown Author"
                    )

                val genre =
                    item.optString(
                        "primaryGenreName",
                        "Podcast"
                    )

                val artworkUrl =
                    item.optString(
                        "artworkUrl600",
                        item.optString(
                            "artworkUrl100",
                            ""
                        )
                    )

                val feedUrl =
                    item.optString(
                        "feedUrl",
                        ""
                    )

                val podcastUrl =
                    item.optString(
                        "collectionViewUrl",
                        ""
                    )

                val episodeCount =
                    item.optInt(
                        "trackCount",
                        0
                    )

                /*
                 * iTunes does not always provide a review count
                 * for every podcast result.
                 */
                val reviewCount =
                    item.optInt(
                        "userRatingCount",
                        0
                    )

                podcasts.add(
                    Podcast(
                        id = id,
                        name = name,
                        artist = artist,
                        genre = genre,
                        artworkUrl = artworkUrl,
                        feedUrl = feedUrl,
                        podcastUrl = podcastUrl,
                        episodeCount = episodeCount,
                        reviewCount = reviewCount
                    )
                )
            }

            podcasts

        } finally {

            connection.disconnect()
        }
    }

    /**
     * Reads an RSS feed and finds the first episode audio URL.
     */
    suspend fun findFirstEpisodeUrl(
        feedUrl: String
    ): String? = withContext(Dispatchers.IO) {

        if (feedUrl.isBlank()) {
            return@withContext null
        }

        val connection =
            URL(feedUrl).openConnection() as HttpURLConnection

        try {

            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val parser =
                Xml.newPullParser()

            connection.inputStream.use { inputStream ->

                parser.setInput(
                    inputStream,
                    null
                )

                var eventType =
                    parser.eventType

                while (
                    eventType !=
                    XmlPullParser.END_DOCUMENT
                ) {

                    if (
                        eventType ==
                        XmlPullParser.START_TAG &&
                        parser.name.equals(
                            "enclosure",
                            ignoreCase = true
                        )
                    ) {

                        val audioUrl =
                            parser.getAttributeValue(
                                null,
                                "url"
                            )

                        if (!audioUrl.isNullOrBlank()) {
                            return@withContext audioUrl
                        }
                    }

                    eventType =
                        parser.next()
                }
            }

            null

        } finally {

            connection.disconnect()
        }
    }

    /**
     * Downloads podcast artwork in the background.
     */
    suspend fun downloadArtwork(
        url: String
    ): Bitmap? = withContext(Dispatchers.IO) {

        if (url.isBlank()) {
            return@withContext null
        }

        try {

            URL(url).openStream().use { input ->
                BitmapFactory.decodeStream(input)
            }

        } catch (_: Exception) {

            null
        }
    }
}

/**
 * Main activity of the SuperPodcast application.
 */
class MainActivity : ComponentActivity() {

    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        setContent {

            SuperPodcastTheme {

                SuperPodcastApp(
                    context = this,

                    onOpenPodcast = { url ->
                        openPodcast(url)
                    },

                    onPlayPodcast = { podcast ->
                        playPodcast(podcast)
                    },

                    onStopPlayback = {
                        stopPlayback()
                    }
                )
            }
        }
    }

    /**
     * Opens the podcast page in the device browser.
     */
    private fun openPodcast(
        url: String
    ) {

        if (url.isBlank()) {
            return
        }

        try {

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )

            startActivity(intent)

        } catch (_: Exception) {

            // Prevents the application from crashing.
        }
    }

    /**
     * Finds the first podcast episode and starts playback.
     */
    private fun playPodcast(
        podcast: Podcast
    ) {

        if (podcast.feedUrl.isBlank()) {
            return
        }

        Thread {

            try {

                val episodeUrl =
                    runBlocking {

                        PodcastRepository
                            .findFirstEpisodeUrl(
                                podcast.feedUrl
                            )
                    }

                if (episodeUrl.isNullOrBlank()) {
                    return@Thread
                }

                runOnUiThread {

                    stopPlayback()

                    mediaPlayer =
                        MediaPlayer().apply {

                            setAudioAttributes(
                                AudioAttributes
                                    .Builder()
                                    .setContentType(
                                        AudioAttributes
                                            .CONTENT_TYPE_MUSIC
                                    )
                                    .setUsage(
                                        AudioAttributes
                                            .USAGE_MEDIA
                                    )
                                    .build()
                            )

                            setDataSource(
                                episodeUrl
                            )

                            setOnPreparedListener { player ->
                                player.start()
                            }

                            setOnCompletionListener {
                                stopPlayback()
                            }

                            prepareAsync()
                        }
                }

            } catch (_: Exception) {

                // Playback errors are handled safely.
            }

        }.start()
    }

    /**
     * Stops the current podcast and releases MediaPlayer resources.
     */
    private fun stopPlayback() {

        mediaPlayer?.let { player ->

            try {

                if (player.isPlaying) {
                    player.stop()
                }

            } catch (_: Exception) {
            }

            player.release()
        }

        mediaPlayer = null
    }

    override fun onDestroy() {

        stopPlayback()

        super.onDestroy()
    }
}

/**
 * Main screen of SuperPodcast.
 */
@Composable
fun SuperPodcastApp(
    context: Context,
    onOpenPodcast: (String) -> Unit,
    onPlayPodcast: (Podcast) -> Unit,
    onStopPlayback: () -> Unit
) {

    var searchText by remember {
        mutableStateOf("")
    }

    var regexText by remember {
        mutableStateOf("")
    }

    var minimumWordsText by remember {
        mutableStateOf("")
    }

    var podcasts by remember {
        mutableStateOf<List<Podcast>>(
            emptyList()
        )
    }

    var subscribedPodcasts by remember {
        mutableStateOf(
            loadSubscriptions(context)
        )
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf("")
    }

    var selectedTab by remember {
        mutableStateOf(0)
    }

    var sortLowestReviews by remember {
        mutableStateOf(false)
    }

    val scope =
        rememberCoroutineScope()

    /**
     * Performs a search using Apple's iTunes Search API.
     */
    fun performSearch() {

        if (searchText.isBlank()) {

            errorMessage =
                "Please enter a podcast to search."

            return
        }

        scope.launch {

            isLoading = true
            errorMessage = ""

            try {

                podcasts =
                    PodcastRepository
                        .searchPodcasts(
                            searchText
                        )

                if (podcasts.isEmpty()) {

                    errorMessage =
                        "No podcasts were found."
                }

            } catch (_: Exception) {

                errorMessage =
                    "Unable to connect to iTunes. Please check your internet connection."

            } finally {

                isLoading = false
            }
        }
    }

    /**
     * Applies the advanced search requirements.
     *
     * The user can filter by:
     * - Regular expression
     * - Minimum number of words
     * - Lowest review count
     */
    val filteredPodcasts =
        remember(
            podcasts,
            regexText,
            minimumWordsText,
            sortLowestReviews
        ) {

            var result =
                podcasts

            /*
             * Regular expression filter.
             *
             * Example:
             * tech|science
             */
            if (regexText.isNotBlank()) {

                try {

                    val pattern =
                        Pattern.compile(
                            regexText,
                            Pattern.CASE_INSENSITIVE
                        )

                    result =
                        result.filter { podcast ->

                            pattern.matcher(
                                podcast.name
                            ).find() ||

                                    pattern.matcher(
                                        podcast.artist
                                    ).find() ||

                                    pattern.matcher(
                                        podcast.genre
                                    ).find()
                        }

                } catch (_: Exception) {

                    /*
                     * Invalid regular expressions are ignored
                     * instead of crashing the application.
                     */
                }
            }

            /*
             * Minimum number of words in the podcast title.
             */
            val minimumWords =
                minimumWordsText.toIntOrNull()

            if (
                minimumWords != null &&
                minimumWords > 0
            ) {

                result =
                    result.filter { podcast ->

                        countWords(
                            podcast.name
                        ) >= minimumWords
                    }
            }

            /*
             * Sorts results from the lowest review count
             * to the highest review count.
             */
            if (sortLowestReviews) {

                result =
                    result.sortedBy {
                        it.reviewCount
                    }
            }

            result
        }

    Scaffold(

        modifier =
            Modifier.fillMaxSize(),

        topBar = {

            TopAppBar(

                title = {

                    Text(
                        text = "SuperPodcast",
                        fontWeight =
                            FontWeight.Bold
                    )
                }
            )
        }

    ) { paddingValues ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            TabRow(
                selectedTabIndex =
                    selectedTab
            ) {

                Tab(

                    selected =
                        selectedTab == 0,

                    onClick = {
                        selectedTab = 0
                    },

                    text = {
                        Text("Search")
                    }
                )

                Tab(

                    selected =
                        selectedTab == 1,

                    onClick = {
                        selectedTab = 1
                    },

                    text = {
                        Text("Subscriptions")
                    }
                )
            }

            if (selectedTab == 0) {

                SearchScreen(

                    searchText =
                        searchText,

                    onSearchTextChange = {
                        searchText = it
                    },

                    regexText =
                        regexText,

                    onRegexChange = {
                        regexText = it
                    },

                    minimumWordsText =
                        minimumWordsText,

                    onMinimumWordsChange = {
                        minimumWordsText = it
                    },

                    sortLowestReviews =
                        sortLowestReviews,

                    onSortLowestReviews = {
                        sortLowestReviews =
                            !sortLowestReviews
                    },

                    onSearch = {
                        performSearch()
                    },

                    isLoading =
                        isLoading,

                    errorMessage =
                        errorMessage,

                    podcasts =
                        filteredPodcasts,

                    subscribedPodcasts =
                        subscribedPodcasts,

                    onSubscribe = { podcast ->

                        val alreadySubscribed =
                            subscribedPodcasts.any {
                                it.id == podcast.id
                            }

                        val updated =
                            if (alreadySubscribed) {

                                subscribedPodcasts.filter {
                                    it.id != podcast.id
                                }

                            } else {

                                subscribedPodcasts +
                                        podcast
                            }

                        subscribedPodcasts =
                            updated

                        saveSubscriptions(
                            context,
                            updated
                        )
                    },

                    onOpenPodcast =
                        onOpenPodcast,

                    onPlayPodcast =
                        onPlayPodcast,

                    onStopPlayback =
                        onStopPlayback
                )

            } else {

                SubscriptionScreen(

                    subscribedPodcasts =
                        subscribedPodcasts,

                    onSubscribe = { podcast ->

                        val updated =
                            subscribedPodcasts.filter {
                                it.id != podcast.id
                            }

                        subscribedPodcasts =
                            updated

                        saveSubscriptions(
                            context,
                            updated
                        )
                    },

                    onOpenPodcast =
                        onOpenPodcast,

                    onPlayPodcast =
                        onPlayPodcast,

                    onStopPlayback =
                        onStopPlayback
                )
            }
        }
    }
}

/**
 * Search interface and podcast results.
 */
@Composable
fun SearchScreen(
    searchText: String,
    onSearchTextChange:
        (String) -> Unit,

    regexText: String,
    onRegexChange:
        (String) -> Unit,

    minimumWordsText: String,
    onMinimumWordsChange:
        (String) -> Unit,

    sortLowestReviews: Boolean,
    onSortLowestReviews:
        () -> Unit,

    onSearch: () -> Unit,

    isLoading: Boolean,
    errorMessage: String,

    podcasts: List<Podcast>,
    subscribedPodcasts:
    List<Podcast>,

    onSubscribe:
        (Podcast) -> Unit,

    onOpenPodcast:
        (String) -> Unit,

    onPlayPodcast:
        (Podcast) -> Unit,

    onStopPlayback:
        () -> Unit
) {

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
    ) {

        OutlinedTextField(

            value =
                searchText,

            onValueChange =
                onSearchTextChange,

            modifier =
                Modifier.fillMaxWidth(),

            label = {
                Text("Podcast Search")
            },

            placeholder = {
                Text("Example: technology")
            },

            singleLine = true
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        OutlinedTextField(

            value =
                regexText,

            onValueChange =
                onRegexChange,

            modifier =
                Modifier.fillMaxWidth(),

            label = {
                Text(
                    "Regular Expression Filter"
                )
            },

            placeholder = {
                Text(
                    "Example: tech|science"
                )
            },

            singleLine = true
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        OutlinedTextField(

            value =
                minimumWordsText,

            onValueChange =
                onMinimumWordsChange,

            modifier =
                Modifier.fillMaxWidth(),

            label = {
                Text(
                    "Minimum Words in Title"
                )
            },

            placeholder = {
                Text("Example: 3")
            },

            singleLine = true
        )

        Spacer(
            modifier =
                Modifier.height(8.dp)
        )

        Row(

            modifier =
                Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.spacedBy(8.dp)
        ) {

            Button(

                onClick =
                    onSearch,

                modifier =
                    Modifier.weight(1f)
            ) {

                Text("Search")
            }

            OutlinedButton(

                onClick =
                    onSortLowestReviews,

                modifier =
                    Modifier.weight(1f)
            ) {

                Text(

                    if (sortLowestReviews) {
                        "Lowest Reviews ✓"
                    } else {
                        "Lowest Reviews"
                    }
                )
            }
        }

        Spacer(
            modifier =
                Modifier.height(10.dp)
        )

        if (isLoading) {

            Box(

                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),

                contentAlignment =
                    Alignment.Center
            ) {

                CircularProgressIndicator()
            }
        }

        if (errorMessage.isNotBlank()) {

            Text(

                text =
                    errorMessage,

                color =
                    MaterialTheme.colorScheme.error,

                modifier =
                    Modifier.padding(
                        vertical = 8.dp
                    )
            )
        }

        if (
            !isLoading &&
            podcasts.isNotEmpty()
        ) {

            Text(

                text =
                    "${podcasts.size} podcasts found",

                fontWeight =
                    FontWeight.Bold,

                modifier =
                    Modifier.padding(
                        vertical = 6.dp
                    )
            )
        }

        LazyColumn(

            modifier =
                Modifier.fillMaxSize(),

            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            items(

                items =
                    podcasts,

                key = {
                    it.id
                }

            ) { podcast ->

                PodcastCard(

                    podcast =
                        podcast,

                    isSubscribed =
                        subscribedPodcasts.any {
                            it.id == podcast.id
                        },

                    onSubscribe = {
                        onSubscribe(podcast)
                    },

                    onOpenPodcast = {
                        onOpenPodcast(
                            podcast.podcastUrl
                        )
                    },

                    onPlayPodcast = {
                        onPlayPodcast(podcast)
                    },

                    onStopPlayback =
                        onStopPlayback
                )
            }
        }
    }
}

/**
 * Displays all podcasts saved by the user.
 */
@Composable
fun SubscriptionScreen(

    subscribedPodcasts:
    List<Podcast>,

    onSubscribe:
        (Podcast) -> Unit,

    onOpenPodcast:
        (String) -> Unit,

    onPlayPodcast:
        (Podcast) -> Unit,

    onStopPlayback:
        () -> Unit
) {

    if (subscribedPodcasts.isEmpty()) {

        Box(

            modifier =
                Modifier.fillMaxSize(),

            contentAlignment =
                Alignment.Center
        ) {

            Column(

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(

                    text =
                        "No subscribed podcasts yet.",

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )

                Text(
                    text =
                        "Search for a podcast and tap Subscribe."
                )
            }
        }

    } else {

        LazyColumn(

            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(16.dp),

            verticalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {

            item {

                Text(

                    text =
                        "My Subscriptions",

                    style =
                        MaterialTheme.typography
                            .headlineSmall,

                    fontWeight =
                        FontWeight.Bold
                )

                Spacer(
                    modifier =
                        Modifier.height(4.dp)
                )

                Text(

                    text =
                        "${subscribedPodcasts.size} subscribed podcasts"
                )

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )
            }

            items(

                items =
                    subscribedPodcasts,

                key = {
                    it.id
                }

            ) { podcast ->

                PodcastCard(

                    podcast =
                        podcast,

                    isSubscribed =
                        true,

                    onSubscribe = {
                        onSubscribe(podcast)
                    },

                    onOpenPodcast = {
                        onOpenPodcast(
                            podcast.podcastUrl
                        )
                    },

                    onPlayPodcast = {
                        onPlayPodcast(podcast)
                    },

                    onStopPlayback =
                        onStopPlayback
                )
            }
        }
    }
}

/**
 * Displays one podcast and its available actions.
 */
@Composable
fun PodcastCard(

    podcast: Podcast,

    isSubscribed: Boolean,

    onSubscribe:
        () -> Unit,

    onOpenPodcast:
        () -> Unit,

    onPlayPodcast:
        () -> Unit,

    onStopPlayback:
        () -> Unit
) {

    Card(

        modifier =
            Modifier.fillMaxWidth(),

        elevation =
            CardDefaults.cardElevation(
                defaultElevation = 3.dp
            )
    ) {

        Column(

            modifier =
                Modifier.padding(12.dp)
        ) {

            Row(

                verticalAlignment =
                    Alignment.Top
            ) {

                PodcastArtwork(
                    url =
                        podcast.artworkUrl
                )

                Spacer(
                    modifier =
                        Modifier.width(12.dp)
                )

                Column(

                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text(

                        text =
                            podcast.name,

                        fontWeight =
                            FontWeight.Bold,

                        maxLines = 2,

                        overflow =
                            TextOverflow.Ellipsis
                    )

                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )

                    Text(

                        text =
                            podcast.artist,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )

                    Text(
                        text =
                            "Genre: ${podcast.genre}"
                    )

                    Text(
                        text =
                            "Episodes: ${podcast.episodeCount}"
                    )

                    Text(
                        text =
                            "Reviews: ${podcast.reviewCount}"
                    )
                }
            }

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Button(

                onClick =
                    onSubscribe,

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(

                    if (isSubscribed) {
                        "Unsubscribe"
                    } else {
                        "Subscribe"
                    }
                )
            }

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            Row(

                modifier =
                    Modifier.fillMaxWidth(),

                horizontalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {

                Button(

                    onClick =
                        onPlayPodcast,

                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text("Play")
                }

                OutlinedButton(

                    onClick =
                        onStopPlayback,

                    modifier =
                        Modifier.weight(1f)
                ) {

                    Text("Stop")
                }
            }

            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )

            OutlinedButton(

                onClick =
                    onOpenPodcast,

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text("Open Podcast")
            }
        }
    }
}

/**
 * Downloads and displays podcast artwork.
 */
@Composable
fun PodcastArtwork(
    url: String
) {

    var bitmap by remember(url) {
        mutableStateOf<Bitmap?>(null)
    }

    LaunchedEffect(url) {

        bitmap =
            PodcastRepository.downloadArtwork(
                url
            )
    }

    if (bitmap != null) {

        Image(

            bitmap =
                bitmap!!.asImageBitmap(),

            contentDescription =
                "Podcast artwork",

            modifier =
                Modifier
                    .size(90.dp)
                    .clip(
                        RoundedCornerShape(12.dp)
                    ),

            contentScale =
                ContentScale.Crop
        )

    } else {

        Box(

            modifier =
                Modifier
                    .size(90.dp)
                    .clip(
                        RoundedCornerShape(12.dp)
                    )
                    .background(
                        MaterialTheme
                            .colorScheme
                            .surfaceVariant
                    ),

            contentAlignment =
                Alignment.Center
        ) {

            Text(
                text = "Podcast"
            )
        }
    }
}

/**
 * Counts the words in a podcast title.
 *
 * This is one of the advanced criteria required by the assignment.
 */
fun countWords(
    text: String
): Int {

    return text
        .trim()
        .split(
            Regex("\\s+")
        )
        .count {
            it.isNotBlank()
        }
}

/**
 * Saves the user's podcast subscriptions locally.
 */
fun saveSubscriptions(

    context: Context,

    podcasts: List<Podcast>
) {

    val jsonArray =
        JSONArray()

    podcasts.forEach { podcast ->

        val jsonObject =
            JSONObject()

        jsonObject.put(
            "id",
            podcast.id
        )

        jsonObject.put(
            "name",
            podcast.name
        )

        jsonObject.put(
            "artist",
            podcast.artist
        )

        jsonObject.put(
            "genre",
            podcast.genre
        )

        jsonObject.put(
            "artworkUrl",
            podcast.artworkUrl
        )

        jsonObject.put(
            "feedUrl",
            podcast.feedUrl
        )

        jsonObject.put(
            "podcastUrl",
            podcast.podcastUrl
        )

        jsonObject.put(
            "episodeCount",
            podcast.episodeCount
        )

        jsonObject.put(
            "reviewCount",
            podcast.reviewCount
        )

        jsonArray.put(
            jsonObject
        )
    }

    context
        .getSharedPreferences(
            "super_podcast_preferences",
            Context.MODE_PRIVATE
        )
        .edit()
        .putString(
            "subscriptions",
            jsonArray.toString()
        )
        .apply()
}

/**
 * Loads saved podcast subscriptions.
 */
fun loadSubscriptions(
    context: Context
): List<Podcast> {

    val preferences =
        context.getSharedPreferences(
            "super_podcast_preferences",
            Context.MODE_PRIVATE
        )

    val savedJson =
        preferences.getString(
            "subscriptions",
            "[]"
        ) ?: "[]"

    return try {

        val jsonArray =
            JSONArray(savedJson)

        val podcasts =
            mutableListOf<Podcast>()

        for (
        index in
        0 until jsonArray.length()
        ) {

            val item =
                jsonArray.getJSONObject(index)

            podcasts.add(

                Podcast(

                    id =
                        item.optLong(
                            "id"
                        ),

                    name =
                        item.optString(
                            "name"
                        ),

                    artist =
                        item.optString(
                            "artist"
                        ),

                    genre =
                        item.optString(
                            "genre"
                        ),

                    artworkUrl =
                        item.optString(
                            "artworkUrl"
                        ),

                    feedUrl =
                        item.optString(
                            "feedUrl"
                        ),

                    podcastUrl =
                        item.optString(
                            "podcastUrl"
                        ),

                    episodeCount =
                        item.optInt(
                            "episodeCount"
                        ),

                    reviewCount =
                        item.optInt(
                            "reviewCount"
                        )
                )
            )
        }

        podcasts

    } catch (_: Exception) {

        emptyList()
    }
}