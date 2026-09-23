package id.bayu.mygalleryvault.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.domain.model.VaultFolder

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    extraActionLabel: String? = null,
    onExtraAction: (() -> Unit)? = null,
) {
    var value by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) {
                Text("Simpan")
            }
        },
        dismissButton = {
            if (extraActionLabel != null && onExtraAction != null) {
                TextButton(onClick = onExtraAction) {
                    Text(extraActionLabel, color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        },
    )
}

@Composable
fun MoveToFolderDialog(
    app: SecureVaultApp,
    onDismiss: () -> Unit,
    onPickRoot: () -> Unit,
    onPick: (Long) -> Unit,
    /**
     * Folders hidden from the target list. Moving a folder into itself is the mistake this list
     * makes easiest, so the folders being moved are taken out of it up front; checks that need the
     * whole tree stay in the repository, which can see it.
     */
    excludeFolderIds: Set<Long> = emptySet(),
) {
    val folders = rememberFolders(app).filterNot { it.id in excludeFolderIds }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pindahkan ke folder") },
        text = {
            LazyColumn {
                item {
                    ListItem(
                        headlineContent = { Text("Root vault") },
                        leadingContent = { Icon(Icons.Rounded.Home, null) },
                        modifier = Modifier.clickable(onClick = onPickRoot),
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        leadingContent = { Icon(Icons.Rounded.Folder, null) },
                        modifier = Modifier.clickable { onPick(folder.id) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun rememberFolders(app: SecureVaultApp): List<VaultFolder> =
    androidx.compose.runtime.produceState(initialValue = emptyList()) {
        value = try {
            app.container.currentStack().repository.allFoldersOnce().map {
                VaultFolder(
                    id = it.id, parentId = it.parentId, name = it.name,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }.value

@Composable
fun ShareWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bagikan file?") },
        text = {
            Text(
                "File akan didekripsi ke cache sementara dan diberikan kepada " +
                    "aplikasi lain. Aplikasi tersebut dapat membuat salinan file."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Lanjutkan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
