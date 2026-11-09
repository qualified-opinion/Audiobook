package com.janreins.audiobook.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.janreins.audiobook.ui.theme.AppThemeMode

/**
 * Clean Minimalist Theme Selection Dialog.
 * Allows switching between Light Theme, Dark Theme, and System Default.
 */
@Composable
fun ThemeSelectionDialog(
    currentThemeMode: AppThemeMode,
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "App theme",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppThemeOptionItem(
                    mode = AppThemeMode.LIGHT,
                    icon = Icons.Default.LightMode,
                    title = "Light Theme",
                    subtitle = "Crisp light background with ocean cobalt accents",
                    isSelected = currentThemeMode == AppThemeMode.LIGHT,
                    testTag = "theme_option_light",
                    onClick = {
                        onSelectThemeMode(AppThemeMode.LIGHT)
                        onDismiss()
                    }
                )

                AppThemeOptionItem(
                    mode = AppThemeMode.DARK,
                    icon = Icons.Default.DarkMode,
                    title = "Dark Theme",
                    subtitle = "Deep dark canvas easy on the eyes",
                    isSelected = currentThemeMode == AppThemeMode.DARK,
                    testTag = "theme_option_dark",
                    onClick = {
                        onSelectThemeMode(AppThemeMode.DARK)
                        onDismiss()
                    }
                )

                AppThemeOptionItem(
                    mode = AppThemeMode.SYSTEM,
                    icon = Icons.Default.BrightnessAuto,
                    title = "System Default",
                    subtitle = "Follows your device system appearance",
                    isSelected = currentThemeMode == AppThemeMode.SYSTEM,
                    testTag = "theme_option_system",
                    onClick = {
                        onSelectThemeMode(AppThemeMode.SYSTEM)
                        onDismiss()
                    }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_theme_dialog_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun AppThemeOptionItem(
    mode: AppThemeMode,
    icon: ImageVector,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
            .testTag(testTag),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            }
        ),
        border = if (isSelected) {
            BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(38.dp),
                shape = CircleShape,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}
