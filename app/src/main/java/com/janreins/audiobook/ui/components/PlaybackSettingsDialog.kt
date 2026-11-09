package com.janreins.audiobook.ui.components

import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.janreins.audiobook.player.SkipIntervals

@Composable
fun PlaybackSettingsDialog(
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
    smartRewindEnabled: Boolean,
    sleepFadeOut: Boolean,
    onSetSkipBackSeconds: (Int) -> Unit,
    onSetSkipForwardSeconds: (Int) -> Unit,
    onSetSmartRewindEnabled: (Boolean) -> Unit,
    onSetSleepFadeOut: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text("Playback settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IntervalOptions("Skip back", skipBackSeconds, onSetSkipBackSeconds)
                IntervalOptions("Skip forward", skipForwardSeconds, onSetSkipForwardSeconds)
                PlaybackSwitch("Smart rewind after pause", smartRewindEnabled, onSetSmartRewindEnabled)
                PlaybackSwitch("Sleep timer fade-out", sleepFadeOut, onSetSleepFadeOut)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun IntervalOptions(label: String, seconds: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.titleSmall)
    SkipIntervals.allowedValues.chunked(3).forEach { values ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEach { value ->
                FilterChip(selected = seconds == value, onClick = { onSelect(value) }, label = { Text("${value}s") })
            }
        }
    }
}

@Composable
internal fun PlaybackSwitch(label: String, enabled: Boolean, onChange: (Boolean) -> Unit) {
    // The whole row is the switch, so tapping the text works too and screen readers hear one control.
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = null)
    }
}
