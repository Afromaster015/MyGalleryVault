package id.bayu.mygalleryvault.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.ui.screens.home.HomeViewModel
import id.bayu.mygalleryvault.ui.theme.AppRadius

/**
 * Album tile: a bare square cover of the folder's contents with the name on a mono line
 * underneath. The name sits below rather than on the cover so the tile reads as a container
 * of media, which is exactly what separates it from the square photos next to it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderTile(
    vm: HomeViewModel,
    name: String,
    id: Long,
    onClick: (Long) -> Unit,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onLongPress: ((Long, String) -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(id) },
                onLongClick = onLongPress?.let { press -> { press(id, name) } },
            )
            .semantics {
                if (selectionMode) {
                    stateDescription = if (isSelected) "Terpilih" else "Tidak terpilih"
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AppRadius.container)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            val previews by produceState<List<VaultFile>>(initialValue = emptyList(), key1 = id) {
                value = runCatching { vm.folderPreview(id) }.getOrDefault(emptyList())
            }
            if (previews.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(48.dp),
                    )
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PreviewSlot(previews.getOrNull(0), vm)
                        PreviewSlot(previews.getOrNull(1), vm)
                    }
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        PreviewSlot(previews.getOrNull(2), vm)
                        PreviewSlot(previews.getOrNull(3), vm)
                    }
                }
            }
            if (isSelected) {
                SelectionBrackets(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, start = 2.dp, end = 2.dp),
        )
    }
}

/** One cell of a folder's 2x2 cover; empty slots stay visually quiet. */
@Composable
private fun RowScope.PreviewSlot(file: VaultFile?, vm: HomeViewModel) {
    if (file == null) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(AppRadius.media)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                modifier = Modifier.height(20.dp),
            )
        }
    } else {
        PreviewThumb(
            file = file,
            vm = vm,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(AppRadius.media),
            iconHeightDp = 24,
        )
    }
}

/** Decrypted preview or a type-icon fallback inside [modifier]'s bounds. */
@Composable
internal fun PreviewThumb(
    file: VaultFile,
    vm: HomeViewModel,
    modifier: Modifier = Modifier,
    iconHeightDp: Int = 28,
) {
    val thumb by produceState<Bitmap?>(
        initialValue = null, key1 = file.id, key2 = file.hasThumbnail,
    ) {
        value = if (file.isVideo || file.isImage) {
            vm.thumbnailFor(file.id, file.isVideo, file.isImage)
        } else null
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = when {
                    file.isImage -> Icons.Rounded.Image
                    file.isVideo -> Icons.Rounded.VideoFile
                    file.mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
                    else -> Icons.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(iconHeightDp.dp),
            )
        }
    }
}
