package com.verystupidsimplepodcast.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.verystupidsimplepodcast.data.worker.FeedRefreshWorker
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.verystupidsimplepodcast.data.db.Subscription
import com.verystupidsimplepodcast.ui.components.MiniPlayer
import com.verystupidsimplepodcast.ui.screens.MainFeedScreen
import com.verystupidsimplepodcast.ui.screens.SearchScreen
import kotlinx.coroutines.launch

private val DeepCharcoal = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val VibrantAccent = Color(0xFF1DB954) // Softer Spotify Green

private val PodcastDarkColorScheme = darkColorScheme(
    primary = VibrantAccent,
    background = DeepCharcoal,
    surface = DarkSurface,
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFB3B3B3)
)

class MainActivity : ComponentActivity() {
    private val viewModel: PodcastViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Schedule background feed refresh
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val refreshWorkRequest = PeriodicWorkRequestBuilder<FeedRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "FeedRefreshWork",
            ExistingPeriodicWorkPolicy.KEEP,
            refreshWorkRequest
        )

        // Set up crash reporting
        val prefs = getSharedPreferences("crash_prefs", Context.MODE_PRIVATE)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            val stackTrace = exception.stackTraceToString()
            prefs.edit().putString("last_crash", stackTrace).commit()
            defaultHandler?.uncaughtException(thread, exception)
        }

        setContent {
            MaterialTheme(colorScheme = PodcastDarkColorScheme) {
                var crashLog by remember { mutableStateOf(prefs.getString("last_crash", null)) }
                
                if (crashLog != null) {
                    AlertDialog(
                        onDismissRequest = { 
                            prefs.edit().remove("last_crash").apply()
                            crashLog = null 
                        },
                        title = { Text("App Crashed!") },
                        text = { 
                            LazyColumn {
                                item { Text(crashLog ?: "") }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { 
                                prefs.edit().remove("last_crash").apply()
                                crashLog = null 
                            }) { Text("Clear") }
                        }
                    )
                }

                MainApp(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: PodcastViewModel) {
    var hasPermissionAnswered by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        hasPermissionAnswered = true
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            if (status == PackageManager.PERMISSION_GRANTED) {
                hasPermissionAnswered = true
            } else {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            hasPermissionAnswered = true
        }
        viewModel.initPlayer(context)
    }

    if (!hasPermissionAnswered) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Waiting for permission...")
        }
        return
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf("FEED") }
    var selectedSubscriptionToUnsubscribe by remember { mutableStateOf<Subscription?>(null) }
    
    val subscriptions by viewModel.subscriptions.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPlayingEpisode by viewModel.currentPlayingEpisode.collectAsState()

    if (selectedSubscriptionToUnsubscribe != null) {
        AlertDialog(
            onDismissRequest = { selectedSubscriptionToUnsubscribe = null },
            title = { Text("Unsubscribe") },
            text = { Text("Are you sure you want to unsubscribe from ${selectedSubscriptionToUnsubscribe?.title}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unsubscribePodcast(selectedSubscriptionToUnsubscribe!!)
                    selectedSubscriptionToUnsubscribe = null
                }) {
                    Text("Unsubscribe")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedSubscriptionToUnsubscribe = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("Subscriptions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                LazyColumn {
                    items(subscriptions) { sub ->
                        Text(
                            text = sub.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedSubscriptionToUnsubscribe = sub }
                                .padding(16.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (currentScreen == "FEED") "Episodes" else "Search") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                if (currentScreen == "FEED") {
                    FloatingActionButton(onClick = { currentScreen = "SEARCH" }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Podcast")
                    }
                }
            },
            bottomBar = {
                val episode = currentPlayingEpisode
                if (episode != null) {
                    val remainingMs = if (episode.progressMs > 0) (episode.durationMs - episode.progressMs) else episode.durationMs
                    val remainingText = if (episode.isCompleted) "Completed" else "${com.verystupidsimplepodcast.ui.components.formatDuration(remainingMs)} Restant"

                    val progressRatio = if (episode.durationMs > 0) {
                        val ratio = episode.progressMs.toFloat() / episode.durationMs.toFloat()
                        if (ratio.isNaN()) 0f else ratio.coerceIn(0f, 1f)
                    } else 0f
                    MiniPlayer(
                        episodeTitle = episode.title,
                        isPlaying = isPlaying,
                        remainingText = remainingText,
                        progressRatio = progressRatio,
                        onPlayPause = { viewModel.togglePlayPause() },
                        onSkipForward = { viewModel.skipForward() },
                        onSkipBackward = { viewModel.skipBackward() }
                    )
                }
            }
        ) { paddingValues ->
            Box(modifier = Modifier.padding(paddingValues)) {
                if (currentScreen == "FEED") {
                    MainFeedScreen(viewModel = viewModel, onEpisodeClick = { viewModel.playEpisode(it) })
                } else {
                    SearchScreen(viewModel = viewModel, onResultClicked = { 
                        viewModel.subscribeToPodcast(it)
                        currentScreen = "FEED"
                    })
                }
            }
        }
    }
}
