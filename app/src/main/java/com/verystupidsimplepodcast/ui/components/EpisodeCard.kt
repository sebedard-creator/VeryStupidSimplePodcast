package com.verystupidsimplepodcast.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.verystupidsimplepodcast.data.db.Episode
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EpisodeCard(
    episode: Episode,
    podcastName: String,
    sourceType: String,
    isUnsubscribed: Boolean,
    onClick: () -> Unit,
    onResetProgress: () -> Unit,
    onMarkAsCompleted: () -> Unit
) {
    val alpha = if (isUnsubscribed) 0.5f else 1.0f
    val baseColor = if (episode.isCompleted) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
    var showMenu by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                ),
            colors = CardDefaults.cardColors(
                containerColor = baseColor.copy(alpha = alpha)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Line 1: Publication Date
                val dateStr = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(episode.pubDate))
                Text(text = dateStr, style = MaterialTheme.typography.bodySmall, color = Color.Gray.copy(alpha = alpha))
                
                // Line 2: Podcast Name & Source Tag
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = podcastName, style = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)))
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                    ) {
                        Text(
                            text = sourceType,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)
                        )
                    }
                }
                
                // Line 3: Episode Title
                Text(text = episode.title, style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)), maxLines = 2)
                
                // Line 4: Time Remaining / Duration
                val remainingMs = if (episode.progressMs > 0) (episode.durationMs - episode.progressMs) else episode.durationMs
                val remainingText = if (episode.isCompleted) "Complété" else "${formatDuration(remainingMs)} Restant"
                val remainingColor = when {
                    episode.isCompleted -> MaterialTheme.colorScheme.primary
                    episode.progressMs > 0L -> Color(0xFFE5C07B) // Soft yellow for "started but not finished"
                    else -> Color.Gray // Default for "not started"
                }
                Text(text = remainingText, style = MaterialTheme.typography.bodySmall, color = remainingColor.copy(alpha = alpha))
            }
        }
        
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false }
        ) {
            DropdownMenuItem(
                text = { Text("Rétablir au Début") },
                onClick = {
                    showMenu = false
                    onResetProgress()
                }
            )
            DropdownMenuItem(
                text = { Text("Marquer Comme Entendu") },
                onClick = {
                    showMenu = false
                    onMarkAsCompleted()
                }
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "Unknown Duration"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    
    return if (hours > 0) {
        "${hours}Hr ${minutes}Min"
    } else {
        "${minutes}Min ${seconds}Sec"
    }
}
