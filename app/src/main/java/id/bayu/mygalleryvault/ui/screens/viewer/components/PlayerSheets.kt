package id.bayu.mygalleryvault.ui.screens.viewer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Kecepatan putar",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        SPEED_OPTIONS.forEach { speed ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(speed) }
                    .padding(horizontal = 12.dp),
            ) {
                RadioButton(selected = speed == current, onClick = { onSelect(speed) })
                Text(if (speed == 1f) "Normal" else "${speed}x")
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
            Text("Tutup")
        }
    }
}

/** One selectable row inside [SubtitleSheet]. */
data class SubtitleEntry(val id: String, val label: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleSheet(
    vaultEntries: List<SubtitleEntry>,
    externalEntries: List<SubtitleEntry>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onAddExternal: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Subtitle",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(null) }
                .padding(horizontal = 12.dp),
        ) {
            RadioButton(selected = selectedId == null, onClick = { onSelect(null) })
            Text("Nonaktif")
        }

        Text(
            "Dari MyGalleryVault",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (vaultEntries.isEmpty()) {
            Text(
                "Belum ada file subtitle (.srt/.ass/.vtt) di vault — " +
                    "impor dulu lewat tombol + di Gallery.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.height(220.dp)) {
                items(vaultEntries, key = { it.id }) { entry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry.id) }
                            .padding(horizontal = 16.dp),
                    ) {
                        RadioButton(selected = entry.id == selectedId, onClick = { onSelect(entry.id) })
                        Text(entry.label, maxLines = 1)
                    }
                }
            }
        }

        Text(
            "Dari storage HP",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        externalEntries.forEach { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(entry.id) }
                    .padding(horizontal = 12.dp),
            ) {
                RadioButton(selected = entry.id == selectedId, onClick = { onSelect(entry.id) })
                Text(entry.label, maxLines = 1)
            }
        }
        TextButton(
            onClick = onAddExternal,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text("+ Pilih file dari HP…") }
        Spacer(Modifier.height(24.dp))
    }
}
