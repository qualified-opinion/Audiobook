package com.janreins.audiobook.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Clean Minimalism Playback controls:
 * - 4 skip buttons with configurable inner intervals, icons, and compact tags
 * - 72dp prominent center Play / Pause button
 */
@Composable
fun PlayerControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipBackward15s: () -> Unit,
    onSkipBackward1m: () -> Unit,
    onSkipForward15s: () -> Unit,
    onSkipForward1m: () -> Unit,
    skipBackSeconds: Int = 15,
    skipForwardSeconds: Int = 30,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Skip -1 Minute
        MinimalSkipButton(
            tag = "-1m",
            spokenLabel = "Back 1 minute",
            isRewind = true,
            isLong = true,
            testTag = "skip_backward_1m_button",
            onClick = onSkipBackward1m
        )

        // Configured skip back
        MinimalSkipButton(
            tag = "-${skipBackSeconds}s",
            spokenLabel = "Back $skipBackSeconds seconds",
            isRewind = true,
            isLong = false,
            testTag = "skip_backward_15s_button",
            onClick = onSkipBackward15s
        )

        // Center 72dp Play / Pause Button. The shadow sits outside the clip so it is actually
        // visible, and the press ripple gives feedback that the tap registered.
        Surface(
            onClick = onTogglePlayPause,
            modifier = Modifier
                .size(72.dp)
                .semantics { contentDescription = if (isPlaying) "Pause" else "Play" }
                .testTag("play_pause_button"),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 6.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Configured skip forward
        MinimalSkipButton(
            tag = "+${skipForwardSeconds}s",
            spokenLabel = "Forward $skipForwardSeconds seconds",
            isRewind = false,
            isLong = false,
            testTag = "skip_forward_15s_button",
            onClick = onSkipForward15s
        )

        // Skip +1 Minute
        MinimalSkipButton(
            tag = "+1m",
            spokenLabel = "Forward 1 minute",
            isRewind = false,
            isLong = true,
            testTag = "skip_forward_1m_button",
            onClick = onSkipForward1m
        )
    }
}

/**
 * Minimalist circular skip button with directional icon and compact label underneath.
 * Screen readers hear one clear label ("Back 15 seconds") instead of the icon and "-15s".
 */
@Composable
private fun MinimalSkipButton(
    tag: String,
    spokenLabel: String,
    isRewind: Boolean,
    isLong: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(52.dp)
            .semantics { contentDescription = spokenLabel }
            .testTag(testTag),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isRewind) {
                    if (isLong) Icons.Default.FastRewind else Icons.Default.Replay
                } else {
                    if (isLong) Icons.Default.FastForward else Icons.Default.Update
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tag,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Already said by the button's label; hide it so it isn't read twice.
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}
