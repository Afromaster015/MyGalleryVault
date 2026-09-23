package id.bayu.mygalleryvault.ui.screens.viewer

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes as ExoAudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.playback.PlaybackDataSourceFactory
import id.bayu.mygalleryvault.core.playback.vaultUri
import id.bayu.mygalleryvault.data.repository.VaultRepository
import id.bayu.mygalleryvault.domain.model.SubtitleEdge
import id.bayu.mygalleryvault.domain.model.SubtitleStyle
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.ui.screens.viewer.components.SpeedSheet
import id.bayu.mygalleryvault.ui.screens.viewer.components.SubtitleEntry
import id.bayu.mygalleryvault.ui.screens.viewer.components.SubtitleSheet
import id.bayu.mygalleryvault.ui.screens.viewer.components.SubtitleStyleSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Legacy v1 playback decrypts to a plaintext temp; refuse beyond this. */
private const val MAX_LEGACY_DECRYPT_BYTES = 512L * 1024 * 1024

/** Resume positions live for the current app process only (keputusan #1). */
object ResumePositions {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    operator fun get(fileId: Long): Long = map[fileId] ?: 0L
    operator fun set(fileId: Long, positionMs: Long) {
        map[fileId] = positionMs
    }
}

private data class ActiveSubtitle(val id: String, val label: String, val uri: Uri, val mime: String)

@Composable
fun VideoPlayerScreen(
    activity: FragmentActivity,
    fileId: Long,
    onBack: () -> Unit,
) {
    val app = activity.application as SecureVaultApp
    val repo = app.container.currentStack().repository
    val settingsRepo = app.container.settingsRepository
    val scope = rememberCoroutineScope()
    val audioManager = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    // ---------- state ----------
    var fileName by remember { mutableStateOf("memuat…") }
    var ready by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isV2 by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var hasEnded by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var baseSpeed by remember { mutableFloatStateOf(1f) }
    var boosting by remember { mutableStateOf(false) }
    var isMuted by remember { mutableStateOf(false) }

    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSubtitleSheet by remember { mutableStateOf(false) }
    var showSubtitleStyleSheet by remember { mutableStateOf(false) }

    var activeSubs by remember { mutableStateOf<List<ActiveSubtitle>>(emptyList()) }
    var selectedSubId by remember { mutableStateOf<String?>(null) }

    var hudText by remember { mutableStateOf<String?>(null) }
    var seekFlashText by remember { mutableStateOf<String?>(null) }
    var seekFlashTick by remember { mutableIntStateOf(-1) }

    // Cumulative double-tap seek: taps within the window stack on top of the
    // first double-tap instead of restarting from the current position.
    var seekStreak by remember { mutableIntStateOf(0) }
    var seekStreakDir by remember { mutableIntStateOf(0) }
    var streakBaseMs by remember { mutableLongStateOf(0L) }
    var lastStreakAt by remember { mutableLongStateOf(0L) }

    // Horizontal drag scrubbing preview.
    var surfaceWidthPx by remember { mutableIntStateOf(1) }
    var timelineDragging by remember { mutableStateOf(false) }
    var timelineBaseMs by remember { mutableLongStateOf(0L) }
    var timelineDeltaMs by remember { mutableLongStateOf(0L) }

    // Persisted subtitle appearance (PRD-style caption settings).
    var subSizeSp by remember { mutableIntStateOf(22) }
    var subTextColor by remember { mutableIntStateOf(0xFFFFFFFF.toInt()) }
    var subBgColor by remember { mutableIntStateOf(0x00000000) }
    var subEdge by remember { mutableStateOf(SubtitleEdge.OUTLINE) }

    var legacyTemp by remember { mutableStateOf<File?>(null) }
    var legacyReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { settingsRepo.subtitleStyle() }.getOrNull()?.let { style ->
            subSizeSp = style.sizeSp
            subTextColor = style.textColor
            subBgColor = style.bgColor
            subEdge = style.edge
        }
    }
    fun persistSubtitleStyle(style: SubtitleStyle) {
        scope.launch { runCatching { settingsRepo.setSubtitleStyle(style) } }
    }

    // ---------- load metadata / decrypt legacy ----------
    val entity by produceState<VaultFile?>(null, fileId) {
        value = runCatching { repo.getFile(fileId) }.getOrNull()
    }
    LaunchedEffect(entity?.id) {
        val e = entity ?: return@LaunchedEffect
        fileName = e.name
        android.util.Log.d("SV_Player", "open id=${e.id} size=${e.size} verDB=${e.encryptionVersion}")
        try {
            // Auto-detect actual format from file header instead of trusting DB version
            val storage = app.container.currentStack().storage
            val actualVersion = withContext(Dispatchers.IO) {
                storage.detectEncryptionVersion(e.encryptedName)
            }
            isV2 = actualVersion >= 2
            if (isV2) {
                ready = true
            } else if (e.size > MAX_LEGACY_DECRYPT_BYTES) {
                // Refuse to materialize a multi-GB plaintext temp (storage-full /
                // long freeze); v1→v2 optimize in Settings fixes playback.
                errorText =
                    "Video ini (${e.size / (1024 * 1024)} MB) memakai format lama dan " +
                        "terlalu besar untuk didekripsi sementara. " +
                        "Gunakan Pengaturan → Optimalkan file lama, atau export video ini."
            } else {
                val tmp = File(activity.cacheDir, "vplay_${e.id}_${System.currentTimeMillis()}.tmp")
                withContext(Dispatchers.IO) { repo.decryptToFile(e.id, tmp) }
                legacyTemp = tmp
                legacyReady = true
                ready = true
            }
        } catch (ex: Exception) {
            errorText = ex.message ?: "Gagal membuka video"
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { legacyTemp?.delete() } }
    }

    // duration probe (v2 only; legacy reads from temp later)
    var probeDone by remember { mutableStateOf(false) }
    LaunchedEffect(entity?.id, legacyReady, isV2) {
        val e = entity ?: return@LaunchedEffect
        if (isV2) {
            // Any failure here (locked mid-race, retriever quirks) must never
            // kill the process - ExoPlayer timeline provides duration anyway.
            durationMs = runCatching { app.container.currentStack().thumbnails.probeVideoDurationMs(e.id) }.getOrNull() ?: 0L
            probeDone = true
        } else {
            probeDone = true
            if (legacyReady && !ready) ready = true
        }
    }

    var hasStartedPrepare by remember { mutableStateOf(false) }
    var lastSubSignature by remember { mutableStateOf("") }

    // ---------- subtitles ----------
    val vaultSubs by produceState<List<VaultFile>>(emptyList(), entity?.id) {
        value = runCatching { repo.listAllVaultSubtitles() }.getOrNull().orEmpty()
    }

    val vsubTemps = remember { mutableListOf<File>() }
    DisposableEffect(Unit) {
        onDispose { vsubTemps.forEach { runCatching { it.delete() } } }
    }

    LaunchedEffect(vaultSubs) {
        val e = entity ?: return@LaunchedEffect
        if (activeSubs.isNotEmpty()) return@LaunchedEffect
        // Auto-match must never crash the player mid-lock-race.
        runCatching {
            val base = e.name.substringBeforeLast('.', e.name)
            val match = vaultSubs.firstOrNull {
                it.name.substringBeforeLast('.', it.name).equals(base, ignoreCase = true)
            } ?: return@LaunchedEffect
            val encName = repo.encryptedNameOf(match.id) ?: return@LaunchedEffect
            val mime = VaultRepository.subtitleMime(match.name) ?: return@LaunchedEffect
            // Auto-detect subtitle format from file header
            val subIsV2 = withContext(Dispatchers.IO) {
                app.container.currentStack().storage.detectEncryptionVersion(encName) >= 2
            }
            val uri = if (subIsV2) {
                vaultUri(encName)
            } else {
                val tmp = File(activity.cacheDir, "vsub_${match.id}_${System.currentTimeMillis()}.tmp")
                withContext(Dispatchers.IO) { repo.decryptToFile(match.id, tmp) }
                vsubTemps.add(tmp)
                Uri.fromFile(tmp)
            }
            activeSubs = listOf(
                ActiveSubtitle("sub_v_${match.id}", "${match.name}  •", uri, mime)
            )
            selectedSubId = "sub_v_${match.id}"
        }
    }

    val externalUris = remember { mutableListOf<Uri>() }
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            externalUris += uri
            val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "subtitle") + "  (HP)"
            activeSubs = activeSubs + ActiveSubtitle(
                "sub_ext_${externalUris.size - 1}",
                name,
                uri,
                VaultRepository.subtitleMime(name) ?: "application/x-subrip",
            )
            selectedSubId = "sub_ext_${externalUris.size - 1}"
        }
    }

    // ---------- player ----------
    val player = remember(fileId) {
        val renderers = DefaultRenderersFactory(activity).apply {
            // Prefer a working fallback decoder instead of failing the track.
            setEnableDecoderFallback(true)
        }
        android.util.Log.d("SV_Player", "ExoPlayer created")
        ExoPlayer.Builder(activity, renderers).build()
    }
    DisposableEffect(player) {
        player.setAudioAttributes(
            ExoAudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true,
        )
        onDispose {}
    }

    LaunchedEffect(player, isMuted) {
        player.volume = if (isMuted) 0f else 1f
    }

    fun buildAndPrepare(startAtMs: Long) {
        val e = entity ?: return
        val factory = PlaybackDataSourceFactory(activity, app.container.currentStack().storage)
        val videoUri = legacyTemp?.let(Uri::fromFile) ?: vaultUri(e.encryptedName)

        // Sideloaded subtitles ride along via MediaItem config; the default
        // source factory merges them as text tracks automatically.
        val item = MediaItem.Builder()
            .setUri(videoUri)
            .apply {
                if (selectedSubId != null) {
                    setSubtitleConfigurations(
                        activeSubs.filter { it.id == selectedSubId }.map { sub ->
                            MediaItem.SubtitleConfiguration.Builder(sub.uri)
                                .setMimeType(sub.mime)
                                .setLabel(sub.label)
                                .setId(sub.id)
                                .setLanguage("und")
                                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                .build()
                        }
                    )
                }
            }
            .build()

        val source: MediaSource = DefaultMediaSourceFactory(factory).createMediaSource(item)
        player.setMediaSource(source, startAtMs.coerceAtLeast(0L))
        player.prepare()
        applySubtitleSelection(player, selectedSubId)
        player.playWhenReady = true
    }

    // prepare once duration is known / legacy temp ready; rebuild on subtitle changes
    LaunchedEffect(ready, legacyReady, probeDone, activeSubs, selectedSubId, isV2) {
        val e = entity ?: return@LaunchedEffect
        if (!isV2 && !legacyReady) return@LaunchedEffect
        if (isV2 && !probeDone) return@LaunchedEffect

        val sig = "$selectedSubId#${activeSubs.joinToString(",") { it.id }}"
        if (!hasStartedPrepare) {
            hasStartedPrepare = true
            lastSubSignature = sig
            buildAndPrepare(ResumePositions[fileId])
        } else if (sig != lastSubSignature) {
            lastSubSignature = sig
            buildAndPrepare(player.currentPosition.coerceAtLeast(0L))
        }
    }

    val currentSelectedSubId by rememberUpdatedState(selectedSubId)
    DisposableEffect(player) {
        // The listener outlives recompositions that change selectedSubId, so it
        // must read the LATEST choice - not a stale captured value.
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (!value) ResumePositions[fileId] = player.currentPosition.coerceAtLeast(0)
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
                hasEnded = state == Player.STATE_ENDED
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // Selection applied right after prepare() used to miss because
                // text track groups appear only here - re-apply on every change
                // so a picked SRT actually renders.
                applySubtitleSelection(player, currentSelectedSubId)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > videoSize.height) {
                    activity.requestedOrientation =
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.d(
                    "SV_Player",
                    "onPlayerError code=${error.errorCode} cause=${error.cause}",
                )
                errorText = if (error.message?.contains("terkunci", ignoreCase = true) == true ||
                    error.cause?.message?.contains("terkunci", ignoreCase = true) == true
                ) {
                    "Vault terkunci. Tekan kembali lalu buka video lagi."
                } else {
                    error.message ?: "Gagal memutar video"
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // progress ticker
    LaunchedEffect(isPlaying) {
        while (true) {
            if (player.isPlaying && !scrubbing) {
                positionMs = player.currentPosition.coerceAtLeast(0)
            }
            delay(500)
        }
    }

    // auto-hide controls; boost restore safety
    LaunchedEffect(controlsVisible, isPlaying, boosting) {
        if (boosting) return@LaunchedEffect
        if (controlsVisible && isPlaying) {
            delay(3500)
            controlsVisible = false
        }
    }
    LaunchedEffect(boosting) {
        player.playbackParameters = PlaybackParameters(if (boosting) baseSpeed * 2f else baseSpeed)
    }

    // immersive + keep-screen-on + cleanup
    DisposableEffect(Unit) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation =
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ResumePositions[fileId] = player.currentPosition.coerceAtLeast(0)
            runCatching { player.release() }
        }
    }

    // ---------- actions ----------

    fun toggleMute() {
        isMuted = !isMuted
        val msg = if (isMuted) "🔇 Suara mati" else "🔊 Suara nyala"
        hudText = msg
        scope.launch {
            delay(900)
            if (hudText == msg) hudText = null
        }
    }

    fun onDirectionalSeekTap(dir: Int) {
        val now = SystemClock.elapsedRealtime()
        if (seekStreak > 0 && dir == seekStreakDir && now - lastStreakAt <= STREAK_WINDOW_MS) {
            seekStreak += 1
        } else {
            seekStreak = 1
            seekStreakDir = dir
            streakBaseMs = player.currentPosition.coerceAtLeast(0L)
        }
        lastStreakAt = now
        val target = (streakBaseMs + 10_000L * dir * seekStreak)
            .coerceIn(0L, durationMs.coerceAtLeast(0L))
        player.seekTo(target)
        positionMs = target
        seekFlashText = if (dir < 0) "« ${seekStreak * 10}s" else "${seekStreak * 10}s »"
        seekFlashTick += 1
    }

    fun onTapSurface() {
        // A stray tap right after a seek streak must not flip the controls.
        if (seekStreak > 0 && SystemClock.elapsedRealtime() - lastStreakAt <= STREAK_WINDOW_MS) return
        controlsVisible = !controlsVisible
    }

    // Caption style object rebuilt in composition so style edits re-run the
    // PlayerView update below without touching the player instance.
    // Constructor order: foreground, background, window, edge type, edge color, typeface.
    val captionStyle = CaptionStyleCompat(
        subTextColor,
        subBgColor,
        0,
        when (subEdge) {
            SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
            SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        },
        if (subEdge == SubtitleEdge.NONE) 0 else android.graphics.Color.BLACK,
        null,
    )

    // ---------- UI ----------
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (errorText != null) {
            Text(
                errorText.orEmpty(),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else if (!ready) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { useController = false }
                },
                update = { view ->
                    view.player = player
                    view.setShowSubtitleButton(false)
                    view.subtitleView?.apply {
                        setStyle(captionStyle)
                        setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subSizeSp.toFloat())
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // ---- gesture strips (left=brightness, right=volume) + center gestures ----
        if (ready && errorText == null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { surfaceWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(durationMs) {
                        detectHorizontalDragGestures(
                            onDragStart = { _ ->
                                if (durationMs > 0L) {
                                    timelineDragging = true
                                    scrubbing = true
                                    timelineBaseMs = player.currentPosition.coerceAtLeast(0L)
                                    timelineDeltaMs = 0L
                                }
                            },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                if (timelineDragging) {
                                    timelineDeltaMs += (amount * durationMs / surfaceWidthPx).toLong()
                                    positionMs = (timelineBaseMs + timelineDeltaMs)
                                        .coerceIn(0L, durationMs)
                                }
                            },
                            onDragEnd = {
                                if (timelineDragging) {
                                    val target = (timelineBaseMs + timelineDeltaMs)
                                        .coerceIn(0L, durationMs)
                                    player.seekTo(target)
                                    positionMs = target
                                    timelineDragging = false
                                    scrubbing = false
                                }
                            },
                            onDragCancel = {
                                timelineDragging = false
                                scrubbing = false
                            },
                        )
                    },
            ) {
                Row(Modifier.fillMaxSize()) {
                    SideStrip(
                        modifier = Modifier.weight(1f),
                        onTapToggle = ::onTapSurface,
                        onDoubleTap = { onDirectionalSeekTap(-1) },
                        onVerticalDrag = { dy ->
                            val attrs = activity.window.attributes
                            val cur = if (attrs.screenBrightness < 0f) 0.5f else attrs.screenBrightness
                            val next = (cur - dy / 2000f).coerceIn(0.01f, 1f)
                            attrs.screenBrightness = next
                            activity.window.attributes = attrs
                            hudText = "☀ ${(next * 100).toInt()}%"
                        },
                        onDragEnd = { hudText = null },
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { onTapSurface() },
                                    onDoubleTap = { onDirectionalSeekTap(+1) },
                                    onLongPress = { boosting = true },
                                    onPress = {
                                        val released = tryAwaitRelease()
                                        if (released && boosting) boosting = false
                                    },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            enter = fadeIn(),
                            exit = fadeOut(),
                        ) {
                            IconButton(onClick = {
                                if (player.isPlaying) player.pause() else player.play()
                            }) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White.copy(alpha = 0.9f),
                                    modifier = Modifier.size(72.dp),
                                )
                            }
                        }
                        if (boosting) {
                            Text(
                                "▶▶ 2×",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 70.dp)
                                    .background(Color.Black.copy(alpha = 0.55f))
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                            )
                        }
                    }
                    HoldToBoostStrip(
                        modifier = Modifier.weight(1f),
                        onVerticalDrag = { dy ->
                            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            val step = (-dy / 24f).toInt()
                            val next = (cur + step).coerceIn(0, volumeMax)
                            if (next != cur) audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                            hudText = "🔊 ${(next * 100 / volumeMax.coerceAtLeast(1))}%"
                        },
                        onDragEnd = { hudText = null },
                        onSeekTap = { onDirectionalSeekTap(+1) },
                        onSingleTap = ::onTapSurface,
                        onBoostChange = { boosting = it },
                    )
                }

                if (timelineDragging) {
                    val target = (timelineBaseMs + timelineDeltaMs)
                        .coerceIn(0L, durationMs.coerceAtLeast(0L))
                    val delta = target - timelineBaseMs
                    Text(
                        (if (delta >= 0) "+" else "") + "${delta / 1000}s • ${formatTime(target)}",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 90.dp)
                            .background(Color.Black.copy(alpha = 0.65f))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            // cumulative streak flash
            LaunchedEffect(seekFlashTick) {
                if (seekFlashTick >= 0) {
                    delay(700)
                    seekFlashText = null
                }
            }
            seekFlashText?.let { txt ->
                Text(
                    txt,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier
                        .align(if (seekStreakDir < 0) Alignment.CenterStart else Alignment.CenterEnd)
                        .padding(horizontal = 28.dp),
                )
            }

            hudText?.let {
                Text(
                    it,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // ended overlay
            if (hasEnded) {
                TextButton(
                    onClick = {
                        hasEnded = false
                        player.seekTo(0)
                        player.play()
                    },
                    modifier = Modifier.align(Alignment.Center),
                ) { Text("Putar ulang", color = Color.White) }
            }
        }

        // ---- control chrome ----
        AnimatedVisibility(
            visible = controlsVisible && ready && errorText == null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali", tint = Color.White)
                    }
                    Text(
                        fileName,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showSpeedSheet = true }) {
                        Icon(Icons.Rounded.Speed, "Kecepatan", tint = Color.White)
                    }
                }

                Spacer(Modifier.weight(1f))

                Column(Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f))) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(formatTime(positionMs), color = Color.White)
                        Slider(
                            value = positionMs.toFloat(),
                            onValueChange = {
                                scrubbing = true
                                scrubPosition = it.toLong()
                                positionMs = scrubPosition
                            },
                            onValueChangeFinished = {
                                player.seekTo(scrubPosition)
                                scrubbing = false
                            },
                            valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp),
                        )
                        Text(formatTime(durationMs), color = Color.White)
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        IconButton(onClick = ::toggleMute) {
                            Icon(
                                if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                "Bisukan",
                                tint = Color.White,
                            )
                        }
                        IconButton(onClick = { showSubtitleSheet = true }) {
                            Icon(Icons.Rounded.Subtitles, "Subtitle", tint = Color.White)
                        }
                        IconButton(onClick = { showSubtitleStyleSheet = true }) {
                            Icon(Icons.Rounded.FormatSize, "Tampilan subtitle", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (showSpeedSheet) {
        SpeedSheet(
            current = baseSpeed,
            onSelect = { s ->
                baseSpeed = s
                player.playbackParameters = PlaybackParameters(s)
                showSpeedSheet = false
            },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showSubtitleSheet) {
        val vaultEntries = vaultSubs.map {
            SubtitleEntry("sub_v_${it.id}", it.name)
        }
        val externalEntries = activeSubs
            .filter { it.id.startsWith("sub_ext_") || it.id == "sub_auto" }
            .map { SubtitleEntry(it.id, it.label) }

        fun findVaultFile(entryId: String): VaultFile? =
            vaultSubs.firstOrNull { "sub_v_${it.id}" == entryId }

        SubtitleSheet(
            vaultEntries = vaultEntries,
            externalEntries = externalEntries,
            selectedId = selectedSubId,
            onSelect = { id ->
                when {
                    id == null -> selectedSubId = null

                    activeSubs.any { it.id == id } -> selectedSubId = id

                    id.startsWith("sub_v_") -> {
                        val vf = findVaultFile(id)
                        if (vf == null) {
                            selectedSubId = null
                        } else {
                            scope.launch {
                                val encName = repo.encryptedNameOf(vf.id)
                                val mime = VaultRepository.subtitleMime(vf.name)
                                // Auto-detect subtitle format from file header
                                val subIsV2 = if (encName != null) {
                                    withContext(Dispatchers.IO) {
                                        app.container.currentStack().storage.detectEncryptionVersion(encName) >= 2
                                    }
                                } else false
                                val built = if (encName != null && mime != null) {
                                    if (subIsV2) {
                                        ActiveSubtitle(id, vf.name, vaultUri(encName), mime)
                                    } else {
                                        try {
                                            val tmp = File(
                                                activity.cacheDir,
                                                "vsub_${vf.id}_${System.currentTimeMillis()}.tmp"
                                            )
                                            withContext(Dispatchers.IO) {
                                                repo.decryptToFile(vf.id, tmp)
                                            }
                                            vsubTemps.add(tmp)
                                            ActiveSubtitle(id, vf.name, Uri.fromFile(tmp), mime)
                                        } catch (_: Exception) {
                                            null
                                        }
                                    }
                                } else null

                                if (built != null) {
                                    activeSubs = activeSubs
                                        .filterNot { it.id == id } + built
                                    selectedSubId = id
                                }
                                showSubtitleSheet = false
                            }
                        }
                    }

                    else -> selectedSubId = id // already-materialized external
                }
                if (activeSubs.any { it.id == id } || id == null) {
                    applySubtitleSelection(player, id)
                    showSubtitleSheet = false
                }
            },
            onAddExternal = {
                showSubtitleSheet = false
                id.bayu.mygalleryvault.core.lock.AutoLockManager.launchWithoutAutoLock {
                    subPicker.launch(
                        arrayOf(
                            "text/*",
                            "application/x-subrip",
                            "application/x-ssa",
                            "application/ttml+xml",
                        )
                    )
                }
            },
            onDismiss = { showSubtitleSheet = false },
        )
    }

    if (showSubtitleStyleSheet) {
        SubtitleStyleSheet(
            style = SubtitleStyle(subSizeSp, subTextColor, subBgColor, subEdge),
            onStyleChange = { style ->
                subSizeSp = style.sizeSp
                subTextColor = style.textColor
                subBgColor = style.bgColor
                subEdge = style.edge
                persistSubtitleStyle(style)
            },
            onDismiss = { showSubtitleStyleSheet = false },
        )
    }
}

@Composable
private fun SideStrip(
    modifier: Modifier,
    onTapToggle: () -> Unit,
    onDoubleTap: () -> Unit,
    onVerticalDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    Box(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        onVerticalDrag(amount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTapToggle() },
                    onDoubleTap = { onDoubleTap() },
                )
            },
    )
}

/**
 * Right-side gesture strip: tap toggles controls (after the double-tap
 * window), double tap seeks +10s cumulatively, and holding the SECOND tap of
 * a double (double-tap-hold) runs 2x speed until release. A held first tap
 * does nothing, and a vertical volume drag cancels the hold.
 */
@Composable
private fun HoldToBoostStrip(
    modifier: Modifier,
    onVerticalDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onSeekTap: () -> Unit,
    onSingleTap: () -> Unit,
    onBoostChange: (Boolean) -> Unit,
) {
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var toggleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        onVerticalDrag(amount)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    val downTime = SystemClock.elapsedRealtime()
                    val pendingDouble = lastTapAt > 0 &&
                        downTime - lastTapAt <= viewConfiguration.doubleTapTimeoutMillis
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        waitForUpOrCancellation()
                    }
                    if (up == null) {
                        val stolenByDrag = currentEvent.changes.any { it.isConsumed }
                        if (!stolenByDrag && pendingDouble) {
                            // Held second tap: land its seek, then run 2x
                            // until every pointer is released.
                            onSeekTap()
                            onBoostChange(true)
                            var allUp = currentEvent.changes.none { it.pressed }
                            while (!allUp) {
                                allUp = awaitPointerEvent().changes.none { it.pressed }
                            }
                            onBoostChange(false)
                        }
                        lastTapAt = 0L
                    } else {
                        lastTapAt = SystemClock.elapsedRealtime()
                        if (pendingDouble) {
                            onSeekTap()
                        } else {
                            toggleJob?.cancel()
                            toggleJob = scope.launch {
                                delay(viewConfiguration.doubleTapTimeoutMillis.toLong())
                                if (SystemClock.elapsedRealtime() - lastTapAt >=
                                    viewConfiguration.doubleTapTimeoutMillis
                                ) {
                                    onSingleTap()
                                }
                            }
                        }
                    }
                }
            },
    )
}

private fun applySubtitleSelection(player: ExoPlayer, selectedId: String?) {
    val builder = player.trackSelectionParameters.buildUpon()
    if (selectedId == null) {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
    } else {
        builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        player.currentTracks.groups.forEach { group ->
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    if (group.getTrackFormat(i).id == selectedId) {
                        builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, i))
                    }
                }
            }
        }
    }
    player.trackSelectionParameters = builder.build()
}

private const val STREAK_WINDOW_MS = 1200L

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
