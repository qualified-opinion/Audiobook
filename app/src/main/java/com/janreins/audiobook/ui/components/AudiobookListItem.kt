package com.janreins.audiobook.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.audiobook.data.model.Audiobook

/**
 * List item card representing an individual audiobook in the Clean Minimalism library.
 */
@Composable
fun AudiobookListItem(
    audiobook: Audiobook,
    savedPositionMs: Long,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    finished: Boolean = false,
    onMarkFinished: () -> Unit = {},
    onMarkUnplayed: () -> Unit = {}
) {
    var showMenu by remember { mutableStateOf(false) }
    val progress = remember(savedPositionMs, audiobook.durationMs, finished) {
        if (finished) 1f else if (audiobook.durationMs > 0) {
            (savedPositionMs.toFloat() / audiobook.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
    }

    val progressPercent = (progress * 100).toInt()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Play", onClick = onClick)
            .testTag("audiobook_item_${audiobook.id.hashCode()}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCurrentlyPlaying) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookCover(
                book = audiobook,
                modifier = Modifier.size(54.dp),
                cornerRadius = 14.dp,
                showPlayingOverlay = isCurrentlyPlaying
            )

            Spacer(modifier = Modifier.width(14.dp))

            // Book Details & Progress
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = audiobook.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(3.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (audiobook.formattedDuration.isNotEmpty()) {
                        Text(
                            text = audiobook.formattedDuration,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                }

                if (finished || (savedPositionMs > 0 && audiobook.durationMs > 0)) {
                    Text(
                        text = if (finished) "Finished" else {
                            val remaining = (audiobook.durationMs - savedPositionMs).coerceAtLeast(0L)
                            "$progressPercent% · ${formatRemainingDuration(remaining)} left"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Progress Bar if started
                if (progress > 0f) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options for ${audiobook.title}")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    // Only offer what would change something: a finished book can't be
                    // finished again, and a book never started is already unplayed.
                    if (!finished) {
                        DropdownMenuItem(
                            text = { Text("Mark as finished") },
                            onClick = { showMenu = false; onMarkFinished() }
                        )
                    }
                    if (finished || savedPositionMs > 0) {
                        DropdownMenuItem(
                            text = { Text("Mark as unplayed") },
                            onClick = { showMenu = false; onMarkUnplayed() }
                        )
                    }
                }
            }

            // Play / Resume Button
            Surface(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = CircleShape
            ) {
                Box(
                    modifier = Modifier.size(38.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        // Decorative: tapping anywhere on the card plays the book.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

private fun formatRemainingDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}
