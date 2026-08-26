package id.bayu.mygalleryvault.ui.screens.home

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Circle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import id.bayu.mygalleryvault.domain.model.VaultEntry
import id.bayu.mygalleryvault.domain.model.VaultFile
import id.bayu.mygalleryvault.domain.model.VaultSlot
import id.bayu.mygalleryvault.ui.components.DecryptedShare
import id.bayu.mygalleryvault.ui.components.FormatUtil
import id.bayu.mygalleryvault.ui.components.MoveToFolderDialog
import id.bayu.mygalleryvault.ui.components.ShareWarningDialog
import id.bayu.mygalleryvault.ui.components.TextInputDialog
import kotlinx.coroutines.launch

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
            initializer { HomeViewModel(app.container.currentStack().repository, folderId) }
        },
    )

    val entries by vm.entries.collectAsStateWithLifecycle()
    val searchQuery by vm.searchQuery.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val folderName by vm.folderName.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val currentSort by vm.currentSort.collectAsStateWithLifecycle()

    var searchMode by rememberSaveable { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

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
    val selectedIds = remember { mutableStateListOf<Long>() }
    val inSelection = selectedIds.isNotEmpty()
    var bulkDeleteConfirm by remember { mutableStateOf(false) }
    var pendingDeleteIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var pendingExportIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var bulkBusy by remember { mutableStateOf(false) }

    fun toggleSelect(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
    }

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
            bulkBusy = true
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                var ok = 0
                var fail = 0
                runCatching {
                    activity.contentResolver.takePersistableUriPermission(
                        treeUri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                for (id in ids) {
                    try {
                        if (app.container.currentStack().repository.exportIntoDir(id, treeUri)) ok++ else fail++
                    } catch (_: Exception) {
                        fail++
                    }
                }
                bulkBusy = false
                selectedIds.clear()
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (inSelection) "${selectedIds.size} dipilih"
                        else folderName ?: "MyGalleryVault",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    when {
                        inSelection -> IconButton(onClick = { selectedIds.clear() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Keluar seleksi")
                        }

                        folderId != null -> IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Kembali")
                        }

                        searchMode -> IconButton(onClick = { searchMode = false; vm.setSearchQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Tutup pencarian")
                        }
                    }
                },
                actions = {
                    if (inSelection) {
                        TextButton(
                            onClick = { selectedIds.clear(); selectedIds.addAll(fileIdSet) },
                            enabled = !bulkBusy,
                        ) { Text("Semua") }
                        IconButton(
                            onClick = {
                                pendingExportIds = selectedIds.filter { fileIdSet.contains(it) }
                                AutoLockManager.launchWithoutAutoLock {
                                    bulkExportTreeLauncher.launch(null)
                                }
                            },
                            enabled = !bulkBusy &&
                                selectedIds.any { fileIdSet.contains(it) },
                        ) {
                            Icon(Icons.Rounded.FileDownload, contentDescription = "Export terpilih")
                        }
                        IconButton(
                            onClick = {
                                pendingDeleteIds = selectedIds.toList()
                                bulkDeleteConfirm = true
                            },
                            enabled = !bulkBusy,
                        ) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Hapus terpilih")
                        }
                    } else {
                        IconButton(onClick = {
                            searchMode = !searchMode
                            if (!searchMode) vm.setSearchQuery("")
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Cari")
                        }
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = "Urutkan")
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                SortOption.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option.label + if (option == currentSort) " ✓" else "") },
                                        onClick = {
                                            vm.setSort(option)
                                            sortMenuOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowOpen = true }) {
                                Icon(Icons.Rounded.MoreVert, contentDescription = "Menu")
                            }
                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Folder baru") },
                                leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                                onClick = {
                                    newFolderDialog = true
                                    overflowOpen = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Pengaturan") },
                                leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                onClick = {
                                    overflowOpen = false
                                    navController.navigate(Routes.SETTINGS)
                                },
                            )
                        }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!inSelection) {
                FloatingActionButton(
                    onClick = {
                        AutoLockManager.launchWithoutAutoLock { importLauncher.launch(arrayOf("*/*")) }
                    },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    if (importing) CircularProgressIndicator(Modifier.height(20.dp))
                    else Icon(Icons.Rounded.Add, contentDescription = "Impor file")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (searchMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = vm::setSearchQuery,
                    placeholder = { Text("Cari nama file...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                SearchResultsList(results = searchResults, onItemClick = ::onFileClicked)
            } else if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Folder, null, Modifier.height(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("Vault kosong", style = MaterialTheme.typography.titleMedium)
                        Text("Tekan tombol + untuk mengimpor file", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(entries, key = { entry ->
                        when (entry) {
                            is VaultEntry.Folder -> "f${entry.folder.id}"
                            is VaultEntry.File -> "c${entry.file.id}"
                        }
                    }) { entry ->
                        when (entry) {
                            is VaultEntry.Folder -> FolderTile(
                                name = entry.folder.name,
                                id = entry.folder.id,
                                isSelected = selectedIds.contains(entry.folder.id),
                                selectionMode = inSelection,
                                onClick = { id ->
                                    if (inSelection) toggleSelect(id)
                                    else navController.navigate(Routes.folder(id))
                                },
                                onLongPress = { fid, _ -> toggleSelect(fid) },
                            )
                            is VaultEntry.File -> FileTile(
                                file = entry.file,
                                vm = vm,
                                isSelected = selectedIds.contains(entry.file.id),
                                selectionMode = inSelection,
                                onClick = { f ->
                                    if (inSelection) toggleSelect(f.id) else onFileClicked(f)
                                },
                                onLongPress = { f -> toggleSelect(f.id) },
                            )
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
                                id.bayu.mygalleryvault.data.repository.VaultRepository
                                    .safeExportName(data.file.name)
                            )
                        }
                    }) { Text("Export / Unhide") }
                    TextButton(onClick = {
                        actionSheetFor = null
                        vm.exportToDownloads(
                            data.file.id,
                            id.bayu.mygalleryvault.data.repository.VaultRepository.safeExportName(data.file.name)
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
        val fileCount = pendingDeleteIds.count { fileIdSet.contains(it) }
        val folderCount = pendingDeleteIds.size - fileCount
        AlertDialog(
            onDismissRequest = { if (!bulkBusy) bulkDeleteConfirm = false },
            title = { Text("Hapus ${pendingDeleteIds.size} item?") },
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
                    enabled = !bulkBusy,
                    onClick = {
                        bulkBusy = true
                        val targets = pendingDeleteIds.toList()
                        val fileIds = targets.filter { fileIdSet.contains(it) }
                        val folderIds = targets.filterNot { fileIdSet.contains(it) }
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            var fail = 0
                            runCatching {
                                app.container.currentStack().repository.deleteFiles(fileIds)
                                folderIds.forEach {
                                    runCatching {
                                        app.container.currentStack().repository.deleteFolderRecursive(it)
                                    }.onFailure { fail++ }
                                }
                            }.onFailure { fail = targets.size }
                            bulkBusy = false
                            bulkDeleteConfirm = false
                            selectedIds.clear()
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
                TextButton(onClick = { if (!bulkBusy) bulkDeleteConfirm = false }) { Text("Batal") }
            },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    name: String,
    id: Long,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: (Long) -> Unit,
    onLongPress: (Long, String) -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(id) },
                        onLongClick = { onLongPress(id, name) },
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Rounded.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(56.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
    }
}

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
    val thumb by produceState<Bitmap?>(initialValue = null, key1 = file.id) {
        value = vm.thumbnailFor(file.id, file.hasThumbnail)
    }
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onClick(file) },
                        onLongClick = { onLongPress(file) },
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                    contentAlignment = Alignment.Center,
                ) {
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
                                file.isVideo -> Icons.Rounded.VideoFile
                                file.mimeType.startsWith("audio/") -> Icons.Rounded.AudioFile
                                else -> Icons.Rounded.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.height(48.dp),
                        )
                    }
                }
                Column(Modifier.padding(10.dp)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(FormatUtil.fileSize(file.size), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (selectionMode) {
                Icon(
                    if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchResultsList(results: List<VaultFile>, onItemClick: (VaultFile) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { it.id }) { file ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onItemClick(file) })
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
}
