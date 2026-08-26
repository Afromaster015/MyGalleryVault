package id.bayu.mygalleryvault.ui.screens.viewer

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.ui.components.DecryptedShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageViewerScreen(
    activity: FragmentActivity,
    fileId: Long,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val app = activity.application as SecureVaultApp
    val repo = app.container.currentStack().repository
    val scope = rememberCoroutineScope()

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = fileId) {
        value = withContext(Dispatchers.IO) {
            try {
                val bytes = repo.readDecryptedBytes(fileId)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        }
    }
    val fileName by produceState<String?>(null, key1 = fileId) {
        value = repo.getFile(fileId)?.name
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var exportTargetName by remember { mutableStateOf<String?>(null) }
    val loading = MutableStateFlow(false)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null && fileId > 0) {
            scope.launch {
                try {
                    repo.exportFile(fileId, uri)
                    Toast.makeText(activity, "File diekspor", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, "Export gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        exportTargetName = null
    }

    fun shareImage() {
        val bmp = bitmap ?: return
        val name = fileName ?: "image.jpg"
        scope.launch(Dispatchers.IO) {
            try {
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos)
                val bytes = bos.toByteArray()
                withContext(Dispatchers.Main) {
                    val outFile = DecryptedShare.writeForSharing(activity, name, bytes)
                    activity.startActivity(DecryptedShare.shareIntent(activity, outFile, "image/jpeg"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val bmp = bitmap
        if (bmp == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            ZoomableImage(bitmap = bmp)
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = Color.White)
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp),
        ) {
            IconButton(onClick = { shareImage() }) {
                Icon(Icons.Rounded.Share, "Bagikan", tint = Color.White)
            }
            IconButton(onClick = {
                id.bayu.mygalleryvault.core.lock.AutoLockManager.launchWithoutAutoLock {
                    exportLauncher.launch(fileName ?: "export.jpg")
                }
            }) {
                Icon(Icons.Rounded.FileDownload, "Export", tint = Color.White)
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Rounded.Delete, "Hapus", tint = Color.White)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Hapus file?") },
            text = { Text("File ini akan dihapus permanen dari vault.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        try {
                            repo.deleteFiles(listOf(fileId))
                            onDeleted()
                        } catch (e: Exception) {
                            Toast.makeText(activity, "Gagal menghapus: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Batal") } },
        )
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}
