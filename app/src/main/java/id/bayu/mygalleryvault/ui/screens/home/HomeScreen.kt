package id.bayu.mygalleryvault.ui.screens.home

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavHostController
import id.bayu.mygalleryvault.ui.Routes
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.lock.AutoLockManager
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.domain.model.SortOption
import id.bayu.mygalleryvault.domain.model.ViewMode
import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.domain.model.VaultSlot
import id.bayu.mygalleryvault.ui.components.DecryptedShare
import id.bayu.mygalleryvault.ui.components.FolderTile
import id.bayu.mygalleryvault.ui.components.FormatUtil
import id.bayu.mygalleryvault.ui.components.MoveToFolderDialog
import id.bayu.mygalleryvault.ui.components.PreviewThumb
import id.bayu.mygalleryvault.ui.components.SelectionBrackets
import id.bayu.mygalleryvault.ui.components.ShareWarningDialog
import id.bayu.mygalleryvault.ui.components.TextInputDialog
import id.bayu.mygalleryvault.ui.components.TileLabel
import id.bayu.mygalleryvault.ui.components.TileLabelBand
import id.bayu.mygalleryvault.ui.components.TransferProgressDialog
import id.bayu.mygalleryvault.ui.components.pressScale
import id.bayu.mygalleryvault.ui.theme.AppMotion
import id.bayu.mygalleryvault.ui.theme.AppRadius
import id.bayu.mygalleryvault.ui.theme.settleSpring
import kotlinx.coroutines.launch

/**
 * One page margin for the whole Gallery screen. The grid, the detail list, and the search results
 * all use it, so switching presentation does not shift the header sideways. Before this, the grid
 * sat at 8dp, the list at 16dp, and the search rows at 20dp, and the difference was visible the
 * moment the mode changed.
 */
private val GalleryPageMargin = 16.dp

/** Vertical gap between blocks on the Gallery screen (header, rows). */
private val GalleryBlockGap = 8.dp

/**
 * Gap between grid cells. It stays below [GalleryBlockGap] on purpose: space inside the sheet has
 * to read as smaller than space between blocks. It moved from 2dp to 4dp when tiles gained a name
 * band, because at 2dp the names of neighbouring tiles nearly touched.
 */
private val GalleryTileGap = 4.dp

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    activity: FragmentActivity,
    navController: NavHostController,
    folderId: Long?,
) {
    val app = activity.application as SecureVaultApp
    val sessionSlot = VaultSession.slot ?: VaultSlot.REAL
    val vm: HomeViewModel = viewModel(
        key = "home_${sessionSlot}_${folderId ?: 0}",
        factory = viewModelFactory {
            initializer {
                val stack = app.container.currentStack()
                HomeViewModel(stack.repository, stack.transfers, stack.thumbnails, folderId)
            }
        },
    )

    val entries by vm.entries.collectAsStateWithLifecycle()
    val entriesLoaded by vm.entriesLoaded.collectAsStateWithLifecycle()
    val entriesError by vm.entriesError.collectAsStateWithLifecycle()

    var mediaFilter by rememberSaveable { mutableStateOf(MediaFilter.ALL) }
    val visibleEntries = remember(entries, mediaFilter) { entries.filterBy(mediaFilter) }

    // Real numbers: the vault's encrypted payload plus the device capacity from the filesystem.
    val storageUsedBytes by produceState(0L, entries) { value = vm.storageUsedBytes() }
    val storageTotalBytes = remember {
        runCatching {
            val stat = android.os.StatFs(activity.filesDir.path)
            stat.blockCountLong * stat.blockSizeLong
        }.getOrDefault(0L)
    }
    // Free space is what the caption reports. The device total is not something the owner can act
    // on, while what is left is what decides whether the next import fits.
    val storageFreeBytes = remember {
        runCatching { android.os.StatFs(activity.filesDir.path).availableBytes }.getOrDefault(0L)
    }

    val galleryHeader: @Composable () -> Unit = {
        GalleryHeader(
            usedBytes = storageUsedBytes,
            totalBytes = storageTotalBytes,
            freeBytes = storageFreeBytes,
            filter = mediaFilter,
            onFilterChange = { mediaFilter = it },
            // Inside a folder the app bar already names the location, so only the chips
            // stay: hiding them would leave an active filter with no visible control.
            showSummary = folderId == null,
        )
    }
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val folderName by vm.folderName.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val currentSort by vm.currentSort.collectAsStateWithLifecycle()
    val transferProgress by vm.transferProgress.collectAsStateWithLifecycle()

    // Grid vs detail-list presentation, persisted across sessions (settings DB).
    val settingsRepo = app.container.settingsRepository
    val viewMode by produceState(ViewMode.GRID) {
        settingsRepo.homeViewMode.collect { value = it }
    }

    var overflowOpen by remember { mutableStateOf(false) }
    var selectionMenuOpen by remember { mutableStateOf(false) }

    // The search field holds focus until something takes it back, which leaves the caret blinking
    // and the outline in its active colour long after the user has moved on.
    val focusManager = LocalFocusManager.current

    // Break-in alerts awaiting owner review (PRD §28), real vault session only.
    var pendingBreakIns by remember { mutableStateOf<List<AuthRepository.BreakInAlertUi>?>(null) }
    val homeScope = rememberCoroutineScope()
    LaunchedEffect(sessionSlot) {
        if (sessionSlot == VaultSlot.REAL) {
            pendingBreakIns = runCatching {
                app.container.authRepository.pendingBreakInAlerts()
            }.getOrNull().orEmpty().ifEmpty { null }
        }
    }

    var pendingImportUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var deleteTarget by remember { mutableStateOf<VaultEntry.File?>(null) }
    var folderDeleteTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }

    // ---- multi-select mode (long-press to enter) ----
    var selection by remember { mutableStateOf(GallerySelection.Empty) }
    val inSelection = !selection.isEmpty
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(GallerySelection.Empty) }
    var pendingExportIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var pendingMove by remember { mutableStateOf(GallerySelection.Empty) }
    var bulkBusy by remember { mutableStateOf(false) }
    // A tracked import/export (progress dialog) also disables destructive actions.
    val busyWithTransfer = bulkBusy || transferProgress != null

    // Back leaves the selection first, the way every other screen in the app treats it, instead of
    // dropping the whole Gallery (and the selection with it). Disabled while nothing is selected,
    // so Back still navigates normally the rest of the time.
    BackHandler(enabled = inSelection) { selection = GallerySelection.Empty }

    var newFolderDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var moveTarget by remember { mutableStateOf<VaultFile?>(null) }
    var openWithTarget by remember { mutableStateOf<VaultFile?>(null) }
    var shareTarget by remember { mutableStateOf<VaultFile?>(null) }
    var exportTarget by remember { mutableStateOf<VaultFile?>(null) }
    var actionSheetFor by remember { mutableStateOf<ActionSheetData?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) pendingImportUris = uris
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val target = exportTarget
        if (uri != null && target != null) {
            vm.exportFile(target.id, uri)
        } else if (target != null) {
            // SAF failed or cancelled - fallback to Downloads via MediaStore
            vm.exportToDownloads(target.id, target.name)
        }
        exportTarget = null
    }

    // Bulk export target: user picks a destination FOLDER, every selected file
    // is decrypted and written there under its original name.
    val bulkExportTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        val ids = pendingExportIds
        pendingExportIds = emptyList()
        if (treeUri != null && ids.isNotEmpty()) {
            runCatching {
                activity.contentResolver.takePersistableUriPermission(
                    treeUri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.exportAllIntoDir(ids, treeUri) { ok, fail ->
                selection = GallerySelection.Empty
                Toast.makeText(
                    activity,
                    "Export: $ok berhasil" + if (fail > 0) ", $fail gagal" else "",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun onFileClicked(file: VaultFile) {
        when {
            file.isImage -> navController.navigate(Routes.viewer(file.id))
            file.isVideo -> navController.navigate(Routes.video(file.id))
            else -> openWithTarget = file
        }
    }

    fun showFileActions(file: VaultFile) {
        actionSheetFor = ActionSheetData(file)
    }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val fileIdSet = remember(entries) {
        entries.filterIsInstance<VaultEntry.File>().map { it.file.id }.toSet()
    }

    /** Files and folders move together; the two lists exist because the repository takes them
     *  separately, and the selection already knows which is which. */
    fun commitMove(targetFolderId: Long?) {
        val pending = pendingMove
        vm.moveSelection(
            fileIds = pending.fileIds.toList(),
            folderIds = pending.folderIds.toList(),
            targetFolderId = targetFolderId,
        )
        selection = GallerySelection.Empty
        pendingMove = GallerySelection.Empty
    }

    /**
     * The single file in the selection, or null when the selection is empty, holds a folder, or
     * holds more than one item. Share and Save to Download both hand one file to something outside
     * the vault, so they are offered only when there is exactly one file, instead of half-working
     * on a multi-selection.
     */
    val singleSelectedFile: VaultFile? =
        if (selection.folderIds.isEmpty()) {
            selection.fileIds.singleOrNull()?.let { id ->
                entries.filterIsInstance<VaultEntry.File>().firstOrNull { it.file.id == id }?.file
            }
        } else {
            null
        }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    Text(
                        text = if (inSelection) "${selection.count} dipilih"
                        else folderName ?: "MyGalleryVault",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                },
                // No brand glyph in the bar: the title is the only thing up here now.
                navigationIcon = {
                    if (inSelection) {
                        IconButton(onClick = { selection = GallerySelection.Empty }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Keluar seleksi")
                        }
                    } else if (folderId != null) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                        }
                    }
                },
                actions = {
                    if (inSelection) {
                        IconButton(
                            onClick = {
                                pendingExportIds = selection.fileIds.toList()
                                AutoLockManager.launchWithoutAutoLock {
                                    bulkExportTreeLauncher.launch(null)
                                }
                            },
                            enabled = !busyWithTransfer && selection.fileIds.isNotEmpty(),
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = "Export terpilih")
                        }
                        IconButton(
                            onClick = { pendingMove = selection },
                            enabled = !busyWithTransfer,
                        ) {
                            Icon(Icons.Rounded.DriveFileMove, contentDescription = "Pindahkan terpilih")
                        }
                        IconButton(
                            onClick = {
                                pendingDelete = selection
                                bulkDeleteConfirm = true
                            },
                            enabled = !busyWithTransfer,
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Hapus terpilih")
                        }
                        Box {
                            IconButton(
                                onClick = { selectionMenuOpen = true },
                                enabled = !busyWithTransfer,
                            ) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Aksi lain")
                            }
                            DropdownMenu(
                                expanded = selectionMenuOpen,
                                onDismissRequest = { selectionMenuOpen = false },
                            ) {
                                // Select-all lives here rather than in the bar: with the three
                                // actions that matter while selecting already in it, a fourth
                                // control left the centred title about 48dp of room on a 360dp
                                // screen, which truncated it.
                                DropdownMenuItem(
                                    text = { Text("Pilih semua") },
                                    leadingIcon = { Icon(Icons.Rounded.Check, null) },
                                    enabled = !busyWithTransfer && fileIdSet.isNotEmpty(),
                                    onClick = {
                                        selectionMenuOpen = false
                                        selection = GallerySelection.allFiles(fileIdSet)
                                    },
                                )
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                // Both of these take a single file: one is handed to another app
                                // and the other writes one copy out. A folder or a multi-selection
                                // leaves them disabled rather than half-working, and the label
                                // above says why instead of leaving the greying unexplained.
                                Text(
                                    "Untuk satu file",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(
                                        start = 12.dp, top = 4.dp, bottom = 4.dp,
                                    ),
                                )
                                DropdownMenuItem(
                                    text = { Text("Bagikan") },
                                    leadingIcon = { Icon(Icons.Rounded.Share, null) },
                                    enabled = singleSelectedFile != null,
                                    onClick = {
                                        selectionMenuOpen = false
                                        shareTarget = singleSelectedFile
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Simpan ke Download") },
                                    leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                                    enabled = singleSelectedFile != null,
                                    onClick = {
                                        selectionMenuOpen = false
                                        singleSelectedFile?.let { file ->
                                            vm.exportToDownloads(
                                                file.id,
                                                id.bayu.mygalleryvault.data.repository
                                                    .TransferRepository.safeExportName(file.name),
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    } else {
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (viewMode == ViewMode.GRID) "Tampilan daftar"
                                            else "Tampilan grid"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (viewMode == ViewMode.GRID) Icons.Rounded.ViewList
                                            else Icons.Rounded.GridView,
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        val next =
                                            if (viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                                        homeScope.launch { settingsRepo.setHomeViewMode(next) }
                                        overflowOpen = false
                                    },
                                )
                                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                                Text(
                                    "Urutkan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                )
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label) },
                                        trailingIcon = {
                                            if (option == currentSort) {
                                                Icon(Icons.Rounded.Check, contentDescription = "Aktif")
                                            }
                                        },
                                        onClick = {
                                            vm.setSort(option)
                                            overflowOpen = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!inSelection) {
                // Both "add" actions share one spot and one shape. The accent stays on the
                // primary action (import): the folder FAB rides a plain surface so the two do
                // not compete for the eye.
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingActionButton(
                        onClick = { newFolderDialog = true },
                        shape = AppRadius.pill,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = "Folder baru")
                    }
                    FloatingActionButton(
                        onClick = {
                            AutoLockManager.launchWithoutAutoLock {
                                importLauncher.launch(arrayOf("*/*"))
                            }
                        },
                        shape = AppRadius.pill,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                trackColor = Color.Transparent,
                            )
                        } else {
                            Icon(Icons.Rounded.Add, contentDescription = "Impor file")
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Search sits under the title as a permanent bar, so looking something up is one
            // tap instead of a mode you enter and leave. It retracts and settles back when
            // selection takes the row over, instead of blinking out of existence.
            AnimatedVisibility(
                visible = !inSelection,
                enter = fadeIn(tween(AppMotion.DETAIL_MS)) + expandVertically(settleSpring()),
                exit = fadeOut(tween(AppMotion.DETAIL_MS)) + shrinkVertically(settleSpring()),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = vm::setSearchQuery,
                    placeholder = { Text("Cari nama file...") },
                    singleLine = true,
                    shape = AppRadius.pill,
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { vm.setSearchQuery("") }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Bersihkan pencarian")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap),
                )
            }
            // Which body the Gallery shows right now. The crossfade keys off this, so moving
            // between the grid and the search results fades instead of cutting. Whether a query
            // found anything is decided one level down, inside SearchArea.
            val bodyKey = when {
                searchQuery.isNotBlank() -> "search"
                !entriesLoaded -> "loading"
                entriesError != null -> "error"
                entries.isEmpty() -> "empty"
                visibleEntries.isEmpty() -> "filtered"
                viewMode == ViewMode.LIST -> "list"
                else -> "grid"
            }
            Crossfade(
                targetState = bodyKey,
                modifier = Modifier
                    .fillMaxSize()
                    // A press anywhere on the content hands focus back to the screen, so the bar
                    // goes idle (no caret, inactive outline) once attention moves elsewhere.
                    // Watched in the initial pass and never consumed, so tiles and scrolling
                    // keep behaving exactly as before.
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Press) focusManager.clearFocus()
                            }
                        }
                    },
                animationSpec = tween(AppMotion.VIEWER_FADE_MS),
                label = "galleryBody",
            ) { body ->
                when (body) {
                    "search" -> SearchArea(
                        results = searchResults,
                        query = searchQuery,
                        onItemClick = ::onFileClicked,
                    )

                    "loading" -> GalleryLoadingGrid()

                    "error" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.ErrorOutline, null, Modifier.height(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Gagal memuat isi vault", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                entriesError.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = vm::retry) { Text("Coba lagi") }
                        }
                    }

                    "empty" -> Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap),
                    ) {
                        galleryHeader()
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GalleryMessage(
                                icon = Icons.Rounded.Folder,
                                title = "Vault kosong",
                                detail = "Tekan tombol + untuk mengimpor file",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    "filtered" -> Column(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap),
                    ) {
                        galleryHeader()
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            GalleryMessage(
                                icon = Icons.Rounded.Image,
                                title = "Tidak ada ${mediaFilter.noun}",
                                detail = "Tampilan ini hanya menampilkan ${mediaFilter.noun}. " +
                                    "Pilih All untuk melihat semuanya.",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    "list" -> EntryDetailsList(
                        entries = visibleEntries,
                        header = galleryHeader,
                        vm = vm,
                        selectionMode = inSelection,
                        isSelected = selection::isSelected,
                        onToggleSelect = { entry -> selection = selection.toggle(entry) },
                        onOpenFolder = { id -> navController.navigate(Routes.folder(id)) },
                        onOpenFile = ::onFileClicked,
                    )

                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = GalleryPageMargin,
                            vertical = GalleryBlockGap,
                        ),
                        verticalArrangement = Arrangement.spacedBy(GalleryTileGap),
                        horizontalArrangement = Arrangement.spacedBy(GalleryTileGap),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) { galleryHeader() }
                        items(visibleEntries, key = { entry ->
                            when (entry) {
                                is VaultEntry.Folder -> "f${entry.folder.id}"
                                is VaultEntry.File -> "c${entry.file.id}"
                            }
                        }) { entry ->
                            // Filtering reflows the sheet: tiles that leave fade out and the rest
                            // glide to their new cell, instead of the grid snapping into a new shape.
                            Box(Modifier.animateItem()) {
                                when (entry) {
                                    is VaultEntry.Folder -> FolderTile(
                                        vm = vm,
                                        name = entry.folder.name,
                                        id = entry.folder.id,
                                        isSelected = selection.isFolderSelected(entry.folder.id),
                                        selectionMode = inSelection,
                                        onClick = { id ->
                                            if (inSelection) selection = selection.toggleFolder(id)
                                            else navController.navigate(Routes.folder(id))
                                        },
                                        onLongPress = { fid, _ -> selection = selection.toggleFolder(fid) },
                                    )

                                    is VaultEntry.File -> FileTile(
                                        file = entry.file,
                                        vm = vm,
                                        isSelected = selection.isFileSelected(entry.file.id),
                                        selectionMode = inSelection,
                                        onClick = { f ->
                                            if (inSelection) selection = selection.toggleFile(f.id)
                                            else onFileClicked(f)
                                        },
                                        onLongPress = { f -> selection = selection.toggleFile(f.id) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- file long-press action sheet ----

    actionSheetFor?.let { data ->
        AlertDialog(
            onDismissRequest = { actionSheetFor = null },
            title = { Text(data.file.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        shareTarget = data.file
                        actionSheetFor = null
                    }) { Text("Bagikan") }
                    TextButton(onClick = {
                        exportTarget = data.file
                        actionSheetFor = null
                        AutoLockManager.launchWithoutAutoLock {
                            exportLauncher.launch(
                                id.bayu.mygalleryvault.data.repository.TransferRepository
                                    .safeExportName(data.file.name)
                            )
                        }
                    }) { Text("Export / Unhide") }
                    TextButton(onClick = {
                        actionSheetFor = null
                        vm.exportToDownloads(
                            data.file.id,
                            id.bayu.mygalleryvault.data.repository.TransferRepository.safeExportName(data.file.name)
                        )
                    }) { Text("Simpan ke Download") }
                    TextButton(onClick = {
                        moveTarget = data.file
                        actionSheetFor = null
                    }) { Text("Pindahkan ke folder") }
                    TextButton(onClick = {
                        deleteTarget = VaultEntry.File(data.file)
                        actionSheetFor = null
                    }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { actionSheetFor = null }) { Text("Tutup") }
            },
        )
    }

    if (pendingImportUris.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingImportUris = emptyList() },
            title = { Text("Pindahkan atau salin?") },
            text = {
                Text(
                    "Pindahkan: original dihapus setelah berhasil dienkripsi.\n" +
                        "Salin: original tetap ada di storage."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.import(pendingImportUris, moveOriginals = true)
                    pendingImportUris = emptyList()
                }) { Text("Pindahkan") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.import(pendingImportUris, moveOriginals = false)
                        pendingImportUris = emptyList()
                    }) { Text("Salin") }
                    TextButton(onClick = { pendingImportUris = emptyList() }) { Text("Batal") }
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Hapus file?") },
            text = { Text("\"${target.file.name}\" akan dihapus permanen dari vault.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFiles(listOf(target.file.id))
                    deleteTarget = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Batal") } },
        )
    }

    if (newFolderDialog) {
        TextInputDialog(
            title = "Nama folder baru",
            initial = "",
            onDismiss = { newFolderDialog = false },
            onConfirm = {
                vm.createFolder(it)
                newFolderDialog = false
            },
        )
    }

    renameTarget?.let { (id, oldName) ->
        TextInputDialog(
            title = "Ganti nama folder",
            initial = oldName,
            onDismiss = { renameTarget = null },
            onConfirm = {
                vm.renameFolder(id, it)
                renameTarget = null
            },
            extraActionLabel = "Hapus folder",
            onExtraAction = {
                folderDeleteTarget = id to oldName
                renameTarget = null
            },
        )
    }

    folderDeleteTarget?.let { (id, name) ->
        AlertDialog(
            onDismissRequest = { folderDeleteTarget = null },
            title = { Text("Hapus folder \"$name\"?") },
            text = { Text("Semua file dan subfolder di dalamnya akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteFolder(id)
                    folderDeleteTarget = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { folderDeleteTarget = null }) { Text("Batal") } },
        )
    }

    if (bulkDeleteConfirm) {
        val fileCount = pendingDelete.fileIds.size
        val folderCount = pendingDelete.folderIds.size
        AlertDialog(
            onDismissRequest = { if (!busyWithTransfer) bulkDeleteConfirm = false },
            title = { Text("Hapus ${pendingDelete.count} item?") },
            text = {
                Text(
                    buildString {
                        append("Terpilih: $fileCount file")
                        if (folderCount > 0) append(", $folderCount folder")
                        append(". Semua akan dihapus permanen dari vault.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busyWithTransfer,
                    onClick = {
                        bulkBusy = true
                        val fileIds = pendingDelete.fileIds.toList()
                        val folderIds = pendingDelete.folderIds.toList()
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            var fail = 0
                            runCatching {
                                app.container.currentStack().repository.deleteFiles(fileIds)
                                folderIds.forEach {
                                    runCatching {
                                        app.container.currentStack().repository.deleteFolderRecursive(it)
                                    }.onFailure { fail++ }
                                }
                            }.onFailure { fail = fileIds.size + folderIds.size }
                            bulkBusy = false
                            bulkDeleteConfirm = false
                            selection = GallerySelection.Empty
                            Toast.makeText(
                                activity,
                                if (fail == 0) "Terhapus" else "Selesai dengan $fail gagal",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                ) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { if (!busyWithTransfer) bulkDeleteConfirm = false }) { Text("Batal") }
            },
        )
    }

    if (!pendingMove.isEmpty) {
        MoveToFolderDialog(
            app = app,
            onDismiss = { pendingMove = GallerySelection.Empty },
            onPickRoot = { commitMove(null) },
            onPick = { target -> commitMove(target) },
            // The folders being moved are not offered as their own destination.
            excludeFolderIds = pendingMove.folderIds,
        )
    }

    moveTarget?.let { file ->
        MoveToFolderDialog(
            app = app,
            onDismiss = { moveTarget = null },
            onPickRoot = {
                vm.moveFile(file.id, null)
                moveTarget = null
            },
            onPick = {
                vm.moveFile(file.id, it)
                moveTarget = null
            },
        )
    }

    openWithTarget?.let { file ->
        AlertDialog(
            onDismissRequest = { openWithTarget = null },
            title = { Text("Buka dengan aplikasi lain?") },
            text = {
                Text(
                    "File akan didekripsi dan diberikan kepada aplikasi lain. " +
                        "Aplikasi tersebut dapat membuat salinan file."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    openWithTarget = null
                    openExternally(vm, activity, file)
                }) { Text("Lanjutkan") }
            },
            dismissButton = { TextButton(onClick = { openWithTarget = null }) { Text("Batal") } },
        )
    }

    shareTarget?.let { file ->
        ShareWarningDialog(
            onDismiss = { shareTarget = null },
            onConfirm = {
                shareTarget = null
                shareExternally(vm, activity, file)
            },
        )
    }

    // Realtime import/export progress (per-item filename + moving bar).
    transferProgress?.let { tp ->
        TransferProgressDialog(progress = tp, onCancel = vm::cancelTransfer)
    }

    pendingBreakIns?.let { alerts ->
        BreakInAlertDialog(
            alerts = alerts,
            onAcknowledge = {
                homeScope.launch {
                    runCatching {
                        app.container.authRepository.acknowledgeSecurityEvents(
                            alerts.map { it.eventId }
                        )
                    }
                }
                pendingBreakIns = null
            },
            onDismiss = { pendingBreakIns = null },
        )
    }
}

@Composable
private fun BreakInAlertDialog(
    alerts: List<AuthRepository.BreakInAlertUi>,
    onAcknowledge: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⚠ Percobaan pembobolan terdeteksi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${alerts.size} peringatan break-in belum dibaca. " +
                        "Foto penyusun diambil dari kamera depan saat PIN salah berkali-kali.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                alerts.take(4).forEach { alert ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        val photo = alert.photo
                        if (photo != null) {
                            Image(
                                bitmap = photo.asImageBitmap(),
                                contentDescription = "Foto penyusup",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .height(64.dp)
                                    .fillMaxWidth(0.35f),
                            )
                        } else {
                            Icon(
                                Icons.Rounded.Folder,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(
                                FormatUtil.dateTime(alert.timestamp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "Percobaan gagal ke-${alert.attempts}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAcknowledge) { Text("Tandai sudah dibaca") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Lihat nanti") }
        },
    )
}

data class ActionSheetData(val file: VaultFile)

private fun openExternally(vm: HomeViewModel, activity: FragmentActivity, file: VaultFile) {
    if (file.isVideo || file.size > 50L * 1024 * 1024) {
        // For large files, use streaming to avoid OOM
        val context = activity
        val dir = DecryptedShare.shareDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        val outFile = java.io.File(dir, safeName)
        vm.streamDecryptedTo(file.id, outFile.outputStream()) { result ->
            result.onSuccess {
                try {
                    context.startActivity(DecryptedShare.openWithIntent(context, outFile, file.mimeType))
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Gagal membuka: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Gagal mendekripsi: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } else {
        vm.decryptedBytes(file.id) { result ->
            result.onSuccess { bytes ->
                try {
                    val context = activity
                    val outFile = DecryptedShare.writeForSharing(context, file.name, bytes)
                    context.startActivity(DecryptedShare.openWithIntent(context, outFile, file.mimeType))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Gagal membuka: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(activity, "Gagal mendekripsi: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

private fun shareExternally(vm: HomeViewModel, activity: FragmentActivity, file: VaultFile) {
    if (file.isVideo || file.size > 50L * 1024 * 1024) {
        // For large files, use streaming to avoid OOM
        val context = activity
        val dir = DecryptedShare.shareDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val safeName = file.name.replace(Regex("[^A-Za-z0-9._ ()-]"), "_")
        val outFile = java.io.File(dir, safeName)
        vm.streamDecryptedTo(file.id, outFile.outputStream()) { result ->
            result.onSuccess {
                try {
                    context.startActivity(DecryptedShare.shareIntent(context, outFile, file.mimeType))
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure {
                activity.runOnUiThread {
                    Toast.makeText(activity, "Gagal mendekripsi: ${it.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } else {
        vm.decryptedBytes(file.id) { result ->
            result.onSuccess { bytes ->
                try {
                    val context = activity
                    val outFile = DecryptedShare.writeForSharing(context, file.name, bytes)
                    context.startActivity(DecryptedShare.shareIntent(context, outFile, file.mimeType))
                } catch (e: Exception) {
                    Toast.makeText(activity, "Gagal membagikan: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(activity, "Gagal mendekripsi: ${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** Scrolls with the grid: section title, real storage summary, and the type filter chips. */
@Composable
private fun GalleryHeader(
    usedBytes: Long,
    totalBytes: Long,
    freeBytes: Long,
    filter: MediaFilter,
    onFilterChange: (MediaFilter) -> Unit,
    showSummary: Boolean = true,
) {
    // The used size arrives from a disk scan, so the bar glides to it instead of snapping.
    val storageFraction by animateFloatAsState(
        targetValue = if (totalBytes > 0L) {
            (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else 0f,
        label = "storageUsed",
    )
    Column(Modifier.fillMaxWidth()) {
        if (showSummary) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Vault ${FormatUtil.fileSize(usedBytes)}, free " +
                            FormatUtil.fileSize(freeBytes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { storageFraction },
                        modifier = Modifier.fillMaxWidth(),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaFilter.entries.forEach { option ->
                val selected = option == filter
                // The marker is drawn, not switched: it grows out of the label and retracts as
                // the chip is left, so the underline hands over instead of blinking.
                val marker by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = settleSpring(),
                    label = "filterMarker",
                )
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Max)
                        .selectable(
                            selected = selected,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                            onClick = { onFilterChange(option) },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(marker)
                            .height(2.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

/** Centered icon + cause + next step, shared by the empty and filtered-to-nothing states. */
@Composable
private fun GalleryMessage(
    icon: ImageVector,
    title: String,
    detail: String,
    tint: Color,
) {
    Column(
        modifier = Modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.height(48.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Static skeleton in the shape of the grid it replaces. Deliberately not animated: the
 * design dial keeps motion at 1, and a looping shimmer would be motion without a purpose.
 */
@Composable
private fun GalleryLoadingGrid() {
    val skeleton = MaterialTheme.colorScheme.surfaceContainerHigh
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = GalleryPageMargin,
            vertical = GalleryBlockGap,
        ),
        verticalArrangement = Arrangement.spacedBy(GalleryTileGap),
        horizontalArrangement = Arrangement.spacedBy(GalleryTileGap),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .fillMaxWidth(0.45f)
                        .height(26.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(skeleton),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .clip(MaterialTheme.shapes.large)
                        .background(skeleton),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) {
                        Box(
                            Modifier
                                .width(76.dp)
                                .height(32.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(skeleton),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        repeat(9) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(skeleton),
                    )
                    // The same band the real tiles reserve, so the grid does not jump when the
                    // data lands.
                    Spacer(Modifier.height(TileLabelBand))
                }
            }
        }
    }
}

/**
 * Grid tile: the media fills its square with no card around it, and the name rides a label band
 * underneath. The band is the same height on every tile, so a row keeps one baseline whether it
 * holds photos, folders, or a mix of the two. Selection is marked with corner brackets, so a
 * selected photo is still the photo.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTile(
    file: VaultFile,
    vm: HomeViewModel,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: (VaultFile) -> Unit,
    onLongPress: (VaultFile) -> Unit,
) {
    val duration by produceState<Long?>(null, file.id, file.isVideo) {
        value = vm.durationFor(file.id, file.isVideo)
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = { onClick(file) },
                onLongClick = { onLongPress(file) },
            )
            .semantics {
                if (selectionMode) {
                    stateDescription = if (isSelected) "Terpilih" else "Tidak terpilih"
                }
            },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(AppRadius.media)
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            PreviewThumb(file = file, vm = vm, modifier = Modifier.fillMaxSize())

            duration?.let { ms ->
                Text(
                    FormatUtil.duration(ms),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.7f),
                            AppRadius.pill,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (isSelected) {
                SelectionBrackets(
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        TileLabel(file.name)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryDetailsList(
    entries: List<VaultEntry>,
    vm: HomeViewModel,
    selectionMode: Boolean,
    isSelected: (VaultEntry) -> Boolean,
    onToggleSelect: (VaultEntry) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onOpenFile: (VaultFile) -> Unit,
    header: @Composable () -> Unit = {},
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(Modifier.padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap)) { header() }
        }
        items(entries, key = { entry ->
            when (entry) {
                is VaultEntry.Folder -> "f${entry.folder.id}"
                is VaultEntry.File -> "c${entry.file.id}"
            }
        }) { entry ->
            val selected = isSelected(entry)
            Row(
                Modifier
                    .fillMaxWidth()
                    .animateItem()
                    .combinedClickable(
                        onClick = {
                            when {
                                selectionMode -> onToggleSelect(entry)
                                entry is VaultEntry.Folder -> onOpenFolder(entry.folder.id)
                                entry is VaultEntry.File -> onOpenFile(entry.file)
                            }
                        },
                        onLongClick = { onToggleSelect(entry) },
                    )
                    .semantics {
                        if (selectionMode) {
                            stateDescription = if (selected) "Terpilih" else "Tidak terpilih"
                        }
                    }
                    .padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (entry) {
                    is VaultEntry.Folder -> Icon(
                        Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(40.dp),
                    )

                    is VaultEntry.File -> MiniPreview(file = entry.file, vm = vm, sizeDp = 44)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when (entry) {
                            is VaultEntry.Folder -> entry.folder.name
                            is VaultEntry.File -> entry.file.name
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when (entry) {
                            is VaultEntry.Folder -> "Folder"
                            is VaultEntry.File ->
                                FormatUtil.dateTime(entry.file.modifiedAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (entry is VaultEntry.File) {
                    Text(
                        FormatUtil.fileSize(entry.file.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (selectionMode) {
                    Spacer(Modifier.width(10.dp))
                    Icon(
                        if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                        contentDescription = null,
                        tint = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

/** Small rounded preview for detail rows; falls back to a type icon. */
@Composable
private fun MiniPreview(file: VaultFile, vm: HomeViewModel, sizeDp: Int) {
    PreviewThumb(
        file = file,
        vm = vm,
        modifier = Modifier
            .height(sizeDp.dp)
            .width(sizeDp.dp)
            .clip(MaterialTheme.shapes.small),
    )
}

/**
 * Search hits plus the "nothing matched" note. The note rides on top of the list instead of
 * replacing it, so hits can fade away under it (animateItem) rather than vanishing the moment
 * the query stops matching. The note only shows for a real query, which keeps clearing the field
 * from flashing the empty message on the way back to the grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchArea(
    results: List<VaultFile>,
    query: String,
    onItemClick: (VaultFile) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { file ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .animateItem()
                        .combinedClickable(onClick = { onItemClick(file) })
                        .padding(horizontal = GalleryPageMargin, vertical = GalleryBlockGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when {
                            file.isVideo -> Icons.Rounded.VideoFile
                            file.isImage -> Icons.Rounded.InsertDriveFile
                            file.mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
                            else -> Icons.Rounded.InsertDriveFile
                        },
                        null,
                    )
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(FormatUtil.fileSize(file.size), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.Center)) {
            AnimatedVisibility(
                visible = query.isNotBlank() && results.isEmpty(),
                enter = fadeIn(tween(AppMotion.VIEWER_FADE_MS)),
                exit = fadeOut(tween(AppMotion.VIEWER_FADE_MS)),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text("Tidak ada file yang cocok", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tidak ada nama yang mengandung \"$query\". Coba kata kunci lain.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
