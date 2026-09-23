package id.bayu.mygalleryvault.ui.screens.viewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import id.bayu.mygalleryvault.domain.model.SubtitleEdge
import id.bayu.mygalleryvault.domain.model.SubtitleStyle

private val SPEED_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)

private val SUBTITLE_SIZES = listOf(12, 14, 16, 18, 20, 22, 24, 28, 32, 36, 40)

private val SUBTITLE_TEXT_COLORS = listOf(
    Color(0xFFFFFFFF) to "Putih",
    Color(0xFFFFF176) to "Kuning",
    Color(0xFFFFB74D) to "Oranye",
    Color(0xFF4DD0E1) to "Cyan",
    Color(0xFFAED581) to "Hijau",
    Color(0xFF64B5F6) to "Biru muda",
    Color(0xFFF06292) to "Pink",
    Color(0xFFBA68C8) to "Ungu",
    Color(0xFFECEFF1) to "Abu terang",
    Color(0xFFE6C35C) to "Emas",
)

private val SUBTITLE_BG_COLORS = listOf(
    Color(0x00000000) to "Transparan",
    Color(0x99000000) to "Hitam 60%",
    Color(0xEE000000) to "Hitam pekat",
    Color(0xDD1B1B1B) to "Abu gelap",
    Color(0xB3FFFFFF) to "Putih semi",
)

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

/** Caption appearance picker: size, text/background colors, edge style. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SubtitleStyleSheet(
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Tampilan subtitle",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            Text(
                "Ukuran font",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SUBTITLE_SIZES.forEach { size ->
                    FilterChip(
                        selected = style.sizeSp == size,
                        onClick = { onStyleChange(style.copy(sizeSp = size)) },
                        label = { Text("${size}sp") },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                "Warna font",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SUBTITLE_TEXT_COLORS.forEach { (color, label) ->
                    ColorSwatch(
                        color = color,
                        label = label,
                        selected = style.textColor == color.toArgb(),
                        onClick = { onStyleChange(style.copy(textColor = color.toArgb())) },
                    )
                }
            }

            Text(
                "Latar belakang font",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SUBTITLE_BG_COLORS.forEach { (color, label) ->
                    ColorSwatch(
                        color = color,
                        label = label,
                        selected = style.bgColor == color.toArgb(),
                        onClick = { onStyleChange(style.copy(bgColor = color.toArgb())) },
                    )
                }
            }

            Text(
                "Garis tepi / bayangan",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                SubtitleEdge.entries.forEach { edge ->
                    FilterChip(
                        selected = style.edge == edge,
                        onClick = { onStyleChange(style.copy(edge = edge)) },
                        label = { Text(edge.label) },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Selesai") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        val borderColor =
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp))
    }
}
