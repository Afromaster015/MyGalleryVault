package id.bayu.mygalleryvault.ui.screens.viewer

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferKind
import id.bayu.mygalleryvault.domain.model.TransferProgress
import id.bayu.mygalleryvault.ui.components.DecryptedShare
import id.bayu.mygalleryvault.ui.components.TransferProgressDialog
import id.bayu.mygalleryvault.ui.theme.AppMotion
import id.bayu.mygalleryvault.ui.theme.settleSpring
import kotlinx.coroutines.Dispatchers
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
    val stack = app.container.currentStack()
    val repo = stack.repository
    val transfers = stack.transfers
    val scope = rememberCoroutineScope()

    var retryToken by remember { mutableStateOf(0) }

    val loadState by produceState<ImageLoadState>(
        initialValue = ImageLoadState.Loading,
        key1 = fileId,
        key2 = retryToken,
    ) {
        value = ImageLoadState.Loading
        value = withContext(Dispatchers.IO) {
            try {
                val bytes = repo.readDecryptedBytes(fileId)
                val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (decoded == null) {
                    ImageLoadState.Failed("Format gambar tidak dikenali atau file rusak.")
                } else {
                    ImageLoadState.Success(decoded)
                }
            } catch (e: Exception) {
                ImageLoadState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }
    val bitmap = (loadState as? ImageLoadState.Success)?.bitmap

    val fileName by produceState<String?>(null, key1 = fileId) {
        value = repo.getFile(fileId)?.name
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var exportTargetName by remember { mutableStateOf<String?>(null) }

    // Realtime export progress (single item).
    var transferState by remember { mutableStateOf<TransferProgress?>(null) }
    val transferCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    fun startTrackedExport(toast: String, block: suspend () -> Unit) {
        if (transferState != null) return
        transferCancel.set(false)
        scope.launch {
            try {
                block()
                Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show()
            } catch (e: TransferCancelledException) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(activity, "Dibatalkan", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        activity,
                        "Export gagal (${e.javaClass.simpleName}): ${e.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                transferState = null
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val name = fileName ?: "export.jpg"
        if (uri != null && fileId > 0) {
            startTrackedExport("File diekspor") {
                transfers.exportFile(
                    fileId, uri,
                    onItemProgress = { done, total ->
                        transferState =
                            TransferProgress.single(TransferKind.EXPORT, name, done, total)
                    },
                    isCancelled = { transferCancel.get() },
                )
            }
        } else if (fileId > 0) {
            startTrackedExport("File disimpan ke folder Downloads") {
                transfers.exportToDownloads(
                    fileId, name,
                    onItemProgress = { done, total ->
                        transferState =
                            TransferProgress.single(TransferKind.EXPORT, name, done, total)
                    },
                    isCancelled = { transferCancel.get() },
                )
            }
        }
        exportTargetName = null
    }

    fun exportToDownloads() {
        val name = fileName ?: "export.jpg"
        startTrackedExport("Tersimpan di folder Download") {
            transfers.exportToDownloads(
                fileId, name,
                onItemProgress = { done, total ->
                    transferState =
                        TransferProgress.single(TransferKind.EXPORT, name, done, total)
                },
                isCancelled = { transferCancel.get() },
            )
        }
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

    var chromeVisible by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AnimatedContent(
            targetState = loadState,
            transitionSpec = {
                fadeIn(tween(AppMotion.VIEWER_FADE_MS)) togetherWith
                    fadeOut(tween(AppMotion.VIEWER_FADE_MS))
            },
            modifier = Modifier.fillMaxSize(),
            label = "imageLoad",
        ) { state ->
            when (state) {
                is ImageLoadState.Loading -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is ImageLoadState.Failed -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(48.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Gambar tidak bisa dibuka",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { retryToken++ }) { Text("Coba lagi") }
                }

                is ImageLoadState.Success -> ZoomableImage(
                    bitmap = state.bitmap,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                )
            }
        }

        // Chrome hides on tap so the picture owns the screen, and slides back in the way it left.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 3 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = Color.White)
                }
                Spacer(Modifier.weight(1f))
                if (bitmap != null) {
                    IconButton(onClick = { shareImage() }) {
                        Icon(Icons.Rounded.Share, "Bagikan", tint = Color.White)
                    }
                }
                IconButton(onClick = { exportToDownloads() }) {
                    Icon(Icons.Rounded.SaveAlt, "Simpan ke Download", tint = Color.White)
                }
                IconButton(onClick = {
                    id.bayu.mygalleryvault.core.lock.AutoLockManager.launchWithoutAutoLock {
                        exportLauncher.launch(
                            id.bayu.mygalleryvault.data.repository.TransferRepository
                                .safeExportName(fileName ?: "export.jpg")
                        )
                    }
                }) {
                    Icon(Icons.Rounded.FileDownload, "Export pilih lokasi", tint = Color.White)
                }
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(Icons.Rounded.Delete, "Hapus", tint = Color.White)
                }
            }
        }
    }

    // Realtime export progress overlay.
    transferState?.let { tp ->
        TransferProgressDialog(progress = tp, onCancel = { transferCancel.set(true) })
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

private sealed interface ImageLoadState {
    object Loading : ImageLoadState
    data class Success(val bitmap: Bitmap) : ImageLoadState
    data class Failed(val reason: String) : ImageLoadState
}

private const val MAX_SCALE = 8f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Pinch and pan with a hard bound and a spring settle. The bound matters: without it the image
 * can be dragged off screen and simply stays there, which reads as a broken screen rather than
 * a photo. Double tap zooms toward the point you tapped, so the detail you aimed at stays under
 * your finger instead of drifting to the middle.
 */
@Composable
private fun ZoomableImage(
    bitmap: Bitmap,
    onToggleChrome: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var container by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()

    // The image arrives a touch small and settles to full size, so it lands instead of appearing.
    var landed by remember(bitmap) { mutableStateOf(false) }
    LaunchedEffect(bitmap) { landed = true }
    val entryScale by animateFloatAsState(
        targetValue = if (landed) 1f else 0.94f,
        animationSpec = settleSpring(),
        label = "imageEntry",
    )

    // Half the overflow of the scaled image past the container: how far the centre may travel.
    fun boundsFor(s: Float): Offset {
        if (container == IntSize.Zero) return Offset.Zero
        val fit = minOf(
            container.width.toFloat() / bitmap.width,
            container.height.toFloat() / bitmap.height,
        )
        return Offset(
            (((bitmap.width * fit * s) - container.width).coerceAtLeast(0f)) / 2f,
            (((bitmap.height * fit * s) - container.height).coerceAtLeast(0f)) / 2f,
        )
    }

    fun clamp(candidate: Offset, s: Float): Offset {
        val bound = boundsFor(s)
        return Offset(
            candidate.x.coerceIn(-bound.x, bound.x),
            candidate.y.coerceIn(-bound.y, bound.y),
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { container = it }
            .pointerInput(bitmap, container) {
                // awaitEachGesture is itself a restricted pointer scope, so the spring settle
                // has to run after it returns, not inside the gesture loop.
                while (true) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        while (true) {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (zoom != 1f || pan != Offset.Zero) {
                                val next = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                scale = next
                                offset = clamp(offset + pan, next)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                    // Released: spring the zoom and the pan back to legal values.
                    val fromScale = scale
                    val fromOffset = offset
                    val toScale = fromScale.coerceAtLeast(1f)
                    val toOffset = clamp(fromOffset, toScale)
                    if (toScale != fromScale || toOffset != fromOffset) {
                        animate(0f, 1f, animationSpec = settleSpring()) { t, _ ->
                            scale = fromScale + (toScale - fromScale) * t
                            offset = Offset(
                                fromOffset.x + (toOffset.x - fromOffset.x) * t,
                                fromOffset.y + (toOffset.y - fromOffset.y) * t,
                            )
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleChrome() },
                    onDoubleTap = { position ->
                        val fromScale = scale
                        val fromOffset = offset
                        val toScale = if (fromScale > 1.01f) 1f else DOUBLE_TAP_SCALE
                        // Keep the tapped point pinned while the zoom changes.
                        val pivot = position - Offset(
                            container.width / 2f,
                            container.height / 2f,
                        )
                        val ratio = toScale / fromScale
                        val toOffset = clamp(pivot - (pivot - fromOffset) * ratio, toScale)
                        scope.launch {
                            animate(0f, 1f, animationSpec = settleSpring()) { t, _ ->
                                scale = fromScale + (toScale - fromScale) * t
                                offset = Offset(
                                    fromOffset.x + (toOffset.x - fromOffset.x) * t,
                                    fromOffset.y + (toOffset.y - fromOffset.y) * t,
                                )
                            }
                        }
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale * entryScale,
                    scaleY = scale * entryScale,
                    translationX = offset.x,
                    translationY = offset.y,
                ),
        )
    }
}
