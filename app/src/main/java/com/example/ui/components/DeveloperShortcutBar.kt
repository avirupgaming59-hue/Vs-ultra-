package com.example.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun DeveloperShortcutBar(
    onDispatchKey: (key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) -> Unit,
    onHideBar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var ctrlSticky by remember { mutableStateOf(false) }
    var altSticky by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("developer_shortcut_bar"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = ctrlSticky,
                onClick = { ctrlSticky = !ctrlSticky },
                label = { Text("Ctrl", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_key_ctrl"),
            )
            FilterChip(
                selected = altSticky,
                onClick = { altSticky = !altSticky },
                label = { Text("Alt", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_key_alt"),
            )
            listOf(
                "Esc" to "Escape",
                "Tab" to "Tab",
                "P" to "p",
                "S" to "s",
                "↑" to "ArrowUp",
                "↓" to "ArrowDown",
                "←" to "ArrowLeft",
                "→" to "ArrowRight",
            ).forEach { (label, jsKey) ->
                AssistChip(
                    onClick = {
                        onDispatchKey(jsKey, ctrlSticky, altSticky, false)
                        ctrlSticky = false
                        altSticky = false
                    },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.minimumInteractiveComponentSize(),
                )
            }
            AssistChip(
                onClick = onHideBar,
                label = { Text("Hide", style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("dev_key_hide"),
            )
        }
    }
}
