package id.bayu.mygalleryvault.ui.screens.settings

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.backup.BackupCodec
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.lock.AutoLockManager
import id.bayu.mygalleryvault.core.security.BreakInCapturer
import id.bayu.mygalleryvault.core.security.LauncherIconController
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.domain.model.AutoLockOption
import id.bayu.mygalleryvault.domain.model.SearchEngine
import id.bayu.mygalleryvault.domain.model.ShakeSensitivity
import id.bayu.mygalleryvault.domain.model.TransferCancelledException
import id.bayu.mygalleryvault.domain.model.TransferProgress
import id.bayu.mygalleryvault.domain.model.VaultSlot
import id.bayu.mygalleryvault.ui.components.BiometricHelper
import id.bayu.mygalleryvault.ui.components.FormatUtil
import id.bayu.mygalleryvault.ui.theme.sanityColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    activity: FragmentActivity,
    onBack: (() -> Unit)? = null,
) {
    val app = activity.application as SecureVaultApp
    val container = app.container
    val scope = rememberCoroutineScope()
    val isRealSession = (VaultSession.slot ?: VaultSlot.REAL) == VaultSlot.REAL

    val autoLock by container.settingsRepository.autoLock.collectAsStateWithLifecycle(
        initialValue = AutoLockOption.IMMEDIATE
    )
    val screenshotProtection by container.settingsRepository.screenshotProtection.collectAsStateWithLifecycle(
        initialValue = true
    )
    val biometricEnabled by container.settingsRepository.biometricEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val shakeEnabled by container.settingsRepository.shakeEnabled.collectAsStateWithLifecycle(
        initialValue = false
    )
    val shakeSensitivity by container.settingsRepository.shakeSensitivity.collectAsStateWithLifecycle(
        initialValue = ShakeSensitivity.MEDIUM
    )
    val searchEngine by container.settingsRepository.searchEngine.collectAsStateWithLifecycle(
        initialValue = SearchEngine.DUCKDUCKGO
    )
    val shieldsDefaultOn by container.settingsRepository.shieldsDefaultOn.collectAsStateWithLifecycle(
        initialValue = true
    )

    var statsText by rememberSaveable { mutableStateOf("memuat...") }
    var threshold by rememberSaveable { mutableIntStateOf(AuthRepository.DEFAULT_THRESHOLD) }
    var breakInEnabled by rememberSaveable { mutableStateOf(true) }
    var breakInPhotos by rememberSaveable { mutableStateOf(false) }
    var decoyCreated by rememberSaveable { mutableStateOf(false) }
    var iconHidden by rememberSaveable { mutableStateOf(false) }

    var showChangePin by rememberSaveable { mutableStateOf(false) }
    var showAutoLockPicker by rememberSaveable { mutableStateOf(false) }
    var showShakePicker by rememberSaveable { mutableStateOf(false) }
    var showSecurityLog by rememberSaveable { mutableStateOf(false) }
    var showDecoySetup by rememberSaveable { mutableStateOf(false) }
    var showDecoyDisable by rememberSaveable { mutableStateOf(false) }
    var showHideIconConfirm by rememberSaveable { mutableStateOf(false) }

    // ---- backup / restore flow state (PRD §36-37a) ----
    var pendingBackupUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePin by remember { mutableStateOf("") }

    // Realtime backup/restore progress (same dialog as import/export) + cancel.
    var backupTransfer by remember { mutableStateOf<TransferProgress?>(null) }
    val backupCancel = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var restoreRenameConflicts by remember { mutableStateOf(false) }
    var showRestorePin by remember { mutableStateOf(false) }
    var showRestoreMerge by remember { mutableStateOf(false) }

    // ---- v1 -> v2 optimization state ----
    var legacyCount by remember { mutableStateOf(0) }
    var showOptimizeConfirm by remember { mutableStateOf(false) }
    var optimizing by remember { mutableStateOf(false) }
    var optimizeProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // ---- browser prefs ----
    var showSearchEnginePicker by remember { mutableStateOf(false) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showImportUrlDialog by remember { mutableStateOf(false) }
    val blocklistImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val added = withContext(Dispatchers.IO) {
                        val target =
                            id.bayu.mygalleryvault.core.browser.ShieldBlocker.userBlocklistFile(activity)
                        target.parentFile?.mkdirs()
                        val incoming = activity.contentResolver.openInputStream(uri)?.use { input ->
                            input.bufferedReader().readLines()
                        } ?: emptyList()
                        val merged =
                            id.bayu.mygalleryvault.core.browser.ShieldBlocker.parseAndMerge(
                                incoming.asSequence()
                            )
                        // persist raw lines for future sessions
                                        target.bufferedWriter().use { w ->
                            incoming.forEach { w.appendLine(it) }
                        }
                        id.bayu.mygalleryvault.core.browser.ShieldBlocker.reload(activity)
                        merged
                    }
                    Toast.makeText(activity, "$added domain baru diblokir", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(activity, "Impor gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupCodec.MIME_TYPE)
    ) { uri ->
        if (uri != null) pendingBackupUri = uri
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            restorePin = ""
            restoreRenameConflicts = false
            showRestorePin = true
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        scope.launch {
            container.settingsRepository.setBreakInPhotosEnabled(granted)
            if (!granted) {
                Toast.makeText(
                    activity,
                    "Tanpa izin kamera: alert tetap dicatat tanpa foto",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        try {
            threshold = container.authRepository.failedThreshold()
            breakInEnabled = container.authRepository.breakInAlertEnabled()
            breakInPhotos = container.authRepository.breakInPhotosEnabled()
            decoyCreated = container.authRepository.isDecoyCreated
            iconHidden = LauncherIconController.isHidden(activity)
            legacyCount = runCatching { container.currentStack().repository.countLegacy() }.getOrDefault(0)
        } catch (_: Exception) {
        }
        statsText = try {
            val s = container.currentStack().repository.stats()
            "${FormatUtil.fileSize(s.totalSizeBytes)} • ${s.fileCount} file • ${s.folderCount} folder"
        } catch (_: Exception) {
            "tidak tersedia"
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                title = {
                    Text(
                        "Pengaturan",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Kembali")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Keamanan")

            SettingsRow(
                title = "Ganti PIN",
                subtitle = if (isRealSession) "Ubah PIN pembuka vault"
                else "Ubah PIN vault ini",
                onClick = { showChangePin = true },
            )

            if (isRealSession) {
                if (biometricEnabled) {
                    BiometricRow(
                        enabled = biometricEnabled,
                        activity = activity,
                        onEnableRequest = { /* already enabled, no-op */ },
                        onDisable = {
                            scope.launch {
                                container.authRepository.disableBiometric()
                                container.authRepository.setBiometricAllowed(false)
                            }
                        },
                    )
                } else {
                    BiometricEnableRow(
                        activity = activity,
                        onEnableRequest = {
                            scope.launch {
                                try {
                                    val request = container.authRepository.prepareBiometricEnable()
                                    withContext(Dispatchers.Main) {
                                        BiometricHelper.authenticate(
                                            activity = activity,
                                            title = "Aktifkan biometrik",
                                            subtitle = "Kunci vault akan diikat ke biometrik Anda",
                                            negativeText = "Batal",
                                            cryptoObject = BiometricPrompt.CryptoObject(request.cipher),
                                            onSuccess = {
                                                scope.launch {
                                                    try {
                                                        container.authRepository.completeBiometricEnable(request)
                                                        container.authRepository.setBiometricAllowed(true)
                                                    } catch (e: Exception) {
                                                        Toast.makeText(
                                                            activity,
                                                            "Gagal: ${e.message}",
                                                            Toast.LENGTH_SHORT,
                                                        ).show()
                                                    }
                                                }
                                            },
                                            onFailure = { msg ->
                                                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                                            },
                                        )
                                    }
                                } catch (_: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(activity, "Biometrik tidak tersedia", Toast.LENGTH_SHORT)
                                            .show()
                                    }
                                }
                            }
                        },
                    )
                }
            }

            SettingsRow(
                title = "Kunci otomatis",
                subtitle = "${autoLock.label} setelah aplikasi keluar dari layar",
                onClick = { showAutoLockPicker = true },
            )

            SwitchRow(
                title = "Guncang untuk mengunci",
                subtitle = "Kunci vault saat perangkat diguncang (PRD §30)",
                checked = shakeEnabled,
                onChecked = { checked ->
                    scope.launch { container.settingsRepository.setShakeEnabled(checked) }
                },
            )

            if (shakeEnabled) {
                SettingsRow(
                    title = "Sensitivitas guncangan",
                    subtitle = shakeSensitivity.label,
                    onClick = { showShakePicker = true },
                )
            }

            if (isRealSession) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Break-in Alert")

                ThresholdRow(threshold = threshold, onChange = { new ->
                    threshold = new
                    scope.launch { container.authRepository.setFailedThreshold(new) }
                })

                SwitchRow(
                    title = "Break-in alert",
                    subtitle = "Catat percobaan PIN yang gagal berulang (PRD §27)",
                    checked = breakInEnabled,
                    onChecked = { checked ->
                        breakInEnabled = checked
                        scope.launch { container.authRepository.setBreakInAlertEnabled(checked) }
                    },
                )

                SwitchRow(
                    title = "Foto penyusup",
                    subtitle = if (breakInPhotos)
                        "Kamera depan otomatis mengambil foto saat ambang terlampaui"
                    else
                        "Butuh izin kamera; alert tanpa foto jika ditolak",
                    checked = breakInPhotos,
                    onChecked = { checked ->
                        if (checked && !BreakInCapturer.hasCameraPermission(activity)) {
                            AutoLockManager.launchWithoutAutoLock {
                                cameraPermissionLauncher.launch(BreakInCapturer.PERMISSION_CAMERA)
                            }
                        } else {
                            breakInPhotos = checked
                            scope.launch { container.settingsRepository.setBreakInPhotosEnabled(checked) }
                        }
                    },
                    enabled = breakInEnabled,
                )

                SettingsRow(
                    title = "Laporan keamanan",
                    subtitle = "Riwayat percobaan gagal & break-in alert",
                    onClick = { showSecurityLog = true },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Vault Decoy")

                SettingsRow(
                    title = if (decoyCreated) "Nonaktifkan vault decoy" else "Buat vault decoy",
                    subtitle = if (decoyCreated)
                        "Hapus vault palsu beserta seluruh isinya"
                    else
                        "PIN kedua membuka vault palsu yang terpisah total (PRD §25)",
                    onClick = {
                        if (decoyCreated) showDecoyDisable = true else showDecoySetup = true
                    },
                )

                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                SectionTitle("Privasi")

                SettingsRow(
                    title = if (iconHidden) "Tampilkan ikon aplikasi" else "Sembunyikan ikon aplikasi",
                    subtitle = "Ikon hilang dari launcher; buka kembali lewat instruksi di dalam dialog",
                    onClick = {
                        if (iconHidden) {
                            LauncherIconController.setHidden(activity, false)
                            iconHidden = false
                        } else {
                            showHideIconConfirm = true
                        }
                    },
                )

                SettingsRow(
                    title = "Bersihkan data browser",
                    subtitle = "Hapus cookie, cache, dan data form WebView sekarang (PRD §43.5)",
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                android.webkit.CookieManager.getInstance().apply {
                                    removeAllCookies(null)
                                    flush()
                                }
                                android.webkit.WebStorage.getInstance().deleteAllData()
                                activity.cacheDir.resolve("WebView").deleteRecursively()
                            }
                            launch(Dispatchers.Main) {
                                Toast.makeText(activity, "Data browser dibersihkan", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Browser")

            SwitchRow(
                title = "Shields blokir iklan",
                subtitle = "Iklan & tracker populer diblokir otomatis di browser privat",
                checked = shieldsDefaultOn,
                onChecked = { checked ->
                    scope.launch { container.settingsRepository.setShieldsDefaultOn(checked) }
                },
            )

            SettingsRow(
                title = "Mesin pencari",
                subtitle = searchEngine.label,
                onClick = { showSearchEnginePicker = true },
            )

            SettingsRow(
                title = "Impor blocklist kustom",
                subtitle = "File .txt format hosts; digabung dengan daftar bawaan",
                onClick = {
                    AutoLockManager.launchWithoutAutoLock {
                        blocklistImportLauncher.launch(arrayOf("text/*", "application/octet-stream"))
                    }
                },
            )

            SettingsRow(
                title = "Impor blocklist dari URL",
                subtitle = "AdGuard DNS, StevenBlack, OISD, AdAway & lainnya (ratusan ribu domain)",
                onClick = { showImportUrlDialog = true },
            )

            SettingsRow(
                title = "Tambah rule blokir manual",
                subtitle = "Domain (contoh: ads.example.com) atau potongan URL (/pagead/)",
                onClick = { showAddRuleDialog = true },
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionTitle("Penyimpanan")
            Text(statsText, style = MaterialTheme.typography.bodyMedium)

            if (isRealSession) {
                SettingsRow(
                    title = "Backup vault (.svbackup)",
                    subtitle = "Ekspor seluruh isi vault sebagai file terenkripsi portabel",
                    onClick = {
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.getDefault())
                            .format(java.util.Date())
                        AutoLockManager.launchWithoutAutoLock {
                            exportLauncher.launch("securevault_backup_$stamp.${BackupCodec.FILE_EXTENSION}")
                        }
                    },
                )
                SettingsRow(
                    title = "Restore dari backup",
                    subtitle = "Impor file .svbackup hasil backup dari perangkat lain",
                    onClick = {
                        AutoLockManager.launchWithoutAutoLock { importLauncher.launch(arrayOf("*/*")) }
                    },
                )

                if (legacyCount > 0) {
                    SettingsRow(
                        title = "Optimalkan video lama ($legacyCount)",
                        subtitle = "Konversi ke format baru: putar instan & seek cepat",
                        onClick = { showOptimizeConfirm = true },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Tentang")
            Text(
                "MyGalleryVault: vault terenkripsi AES-256-GCM. Kunci hanya ada di " +
                    "perangkat Anda. Aplikasi tidak mengirim data ke server manapun.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(id.bayu.mygalleryvault.R.string.icon_credit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(32.dp))
            TextButton(onClick = {
                VaultSession.lockNow()
                onBack?.invoke()
            }) { Text("Kunci vault sekarang") }
        }
    }

    if (showChangePin) {
        ChangePinDialog(
            onDismiss = { showChangePin = false },
            onConfirm = { old, new, onError ->
                scope.launch {
                    val ok =
                        container.authRepository.changePin(old.toCharArray(), new.toCharArray())
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            showChangePin = false
                            Toast.makeText(activity, "PIN diganti", Toast.LENGTH_SHORT).show()
                        } else {
                            onError("PIN lama salah")
                        }
                    }
                }
            },
        )
    }

    if (showAutoLockPicker) {
        SingleChoiceDialog(
            title = "Kunci otomatis",
            options = AutoLockOption.entries.map { it.label },
            selectedLabel = autoLock.label,
            onSelect = { idx ->
                scope.launch {
                    container.settingsRepository.setAutoLock(AutoLockOption.entries[idx])
                    showAutoLockPicker = false
                }
            },
            onDismiss = { showAutoLockPicker = false },
        )
    }

    if (showShakePicker) {
        SingleChoiceDialog(
            title = "Sensitivitas guncangan",
            options = ShakeSensitivity.entries.map { it.label },
            selectedLabel = shakeSensitivity.label,
            onSelect = { idx ->
                scope.launch {
                    container.settingsRepository.setShakeSensitivity(ShakeSensitivity.entries[idx])
                    showShakePicker = false
                }
            },
            onDismiss = { showShakePicker = false },
        )
    }

    if (showSearchEnginePicker) {
        SingleChoiceDialog(
            title = "Mesin pencari",
            options = SearchEngine.entries.map { it.label },
            selectedLabel = searchEngine.label,
            onSelect = { idx ->
                scope.launch {
                    container.settingsRepository.setSearchEngine(SearchEngine.entries[idx])
                    showSearchEnginePicker = false
                }
            },
            onDismiss = { showSearchEnginePicker = false },
        )
    }

    if (showAddRuleDialog) {
        var ruleText by rememberSaveable { mutableStateOf("") }
        var ruleError by rememberSaveable { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showAddRuleDialog = false },
            title = { Text("Tambah rule blokir") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Masukkan domain (ads.example.com) untuk memblokir domain + " +
                            "semua subdomainnya, ATAU potongan URL seperti /pagead/ " +
                            "untuk memblokir berdasarkan pola.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = ruleText,
                        onValueChange = { ruleText = it },
                        singleLine = true,
                        label = { Text("Domain atau /pola/") },
                    )
                    ruleError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val ok = id.bayu.mygalleryvault.core.browser.ShieldBlocker.addUserRule(
                            activity, ruleText
                        )
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                showAddRuleDialog = false
                                Toast.makeText(activity, "Rule ditambahkan", Toast.LENGTH_SHORT).show()
                            } else {
                                ruleError = "Format tidak valid"
                            }
                        }
                    }
                }, enabled = ruleText.isNotBlank()) { Text("Tambah") }
            },
            dismissButton = { TextButton(onClick = { showAddRuleDialog = false }) { Text("Batal") } },
        )
    }

    if (showImportUrlDialog) {
        var customUrl by rememberSaveable { mutableStateOf("") }
        var importingUrl by remember { mutableStateOf(false) }

        fun runImport(urlStr: String) {
            val trimmed = urlStr.trim()
            if (!trimmed.startsWith("https://", ignoreCase = true)) {
                Toast.makeText(activity, "URL harus https://", Toast.LENGTH_SHORT).show()
                return
            }
            importingUrl = true
            scope.launch {
                try {
                    val added = withContext(Dispatchers.IO) {
                        id.bayu.mygalleryvault.core.browser.ShieldBlocker.importFromUrl(
                            activity, trimmed
                        )
                    }
                    Toast.makeText(activity, "$added domain baru diblokir", Toast.LENGTH_LONG).show()
                    showImportUrlDialog = false
                } catch (e: Exception) {
                    Toast.makeText(activity, "Impor gagal: ${e.message}", Toast.LENGTH_SHORT).show()
                } finally {
                    importingUrl = false
                }
            }
        }

        AlertDialog(
            onDismissRequest = { if (!importingUrl) showImportUrlDialog = false },
            title = { Text("Impor blocklist dari URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Unduh daftar blokir publik (format hosts / AdGuard ||domain^). " +
                            "Daftar besar memakan waktu beberapa saat.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    BLOCKLIST_SOURCES.forEach { source ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !importingUrl) { runImport(source.url) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(source.label, style = MaterialTheme.typography.titleSmall)
                            Text(
                                source.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it },
                        singleLine = true,
                        enabled = !importingUrl,
                        label = { Text("Atau URL kustom (https://...)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (importingUrl) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Mengunduh...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { runImport(customUrl) },
                    enabled = !importingUrl && customUrl.isNotBlank(),
                ) { Text("Impor URL kustom") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportUrlDialog = false },
                    enabled = !importingUrl,
                ) { Text("Tutup") }
            },
        )
    }

    if (showSecurityLog) {
        SecurityLogDialog(
            auth = container.authRepository,
            onDismiss = { showSecurityLog = false },
            onClear = {
                scope.launch {
                    runCatching { container.authRepository.clearSecurityLog() }
                    showSecurityLog = false
                }
            },
        )
    }

    if (showDecoySetup) {
        DecoySetupDialog(
            onDismiss = { showDecoySetup = false },
            onConfirm = { pin, onError ->
                scope.launch {
                    val result = container.authRepository.createDecoyVault(pin.toCharArray())
                    withContext(Dispatchers.Main) {
                        result.fold(
                            onSuccess = {
                                decoyCreated = true
                                showDecoySetup = false
                                Toast.makeText(activity, "Vault decoy dibuat", Toast.LENGTH_SHORT).show()
                            },
                            onFailure = { onError(it.message ?: "Gagal membuat decoy") },
                        )
                    }
                }
            },
        )
    }

    if (showDecoyDisable) {
        AlertDialog(
            onDismissRequest = { showDecoyDisable = false },
            title = { Text("Hapus vault decoy?") },
            text = { Text("Seluruh isi vault decoy akan dihapus permanen dan tidak dapat dipulihkan.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { container.wipeDecoyData() }
                        container.authRepository.disableDecoyVault()
                        decoyCreated = false
                        showDecoyDisable = false
                    }
                }) { Text("Hapus", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDecoyDisable = false }) { Text("Batal") }
            },
        )
    }

    if (showHideIconConfirm) {
        AlertDialog(
            onDismissRequest = { showHideIconConfirm = false },
            title = { Text("Sembunyikan ikon aplikasi?") },
            text = {
                Text(
                    "Ikon akan hilang dari launcher. Aplikasi masih bisa dilihat lewat " +
                        "Pengaturan sistem.\n\nUntuk membuka lagi tanpa ikon, jalankan dari komputer:\n" +
                        "adb shell pm enable ${activity.packageName}/${activity.packageName}.Launcher\n\n" +
                        "Fitur ini hanya lapisan privasi, bukan perlindungan absolut (PRD §29).",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    try {
                        LauncherIconController.setHidden(activity, true)
                        iconHidden = true
                    } catch (_: Exception) {
                    }
                    Toast.makeText(activity, "Ikon disembunyikan", Toast.LENGTH_SHORT).show()
                    showHideIconConfirm = false
                }) { Text("Sembunyikan") }
            },
            dismissButton = {
                TextButton(onClick = { showHideIconConfirm = false }) { Text("Batal") }
            },
        )
    }

    // ---- backup / restore dialogs ----

    pendingBackupUri?.let { uri ->
        PinEntryDialog(
            title = "PIN untuk backup",
            subtitle = "Backup dienkripsi dengan PIN vault Anda. PIN ini juga dibutuhkan " +
                "untuk restore di perangkat lain.",
            onDismiss = { pendingBackupUri = null },
            onConfirm = { pin ->
                pendingBackupUri = null
                backupCancel.set(false)
                scope.launch {
                    try {
                        val slot = VaultSession.slot ?: VaultSlot.REAL
                        val summary = withContext(Dispatchers.IO) {
                            activity.contentResolver.openOutputStream(uri)?.use { out ->
                                container.backupRepository.exportBackup(
                                    dest = out,
                                    pin = pin.toCharArray(),
                                    slot = slot,
                                    stack = container.currentStack(),
                                    keyManager = container.keyManager,
                                    onProgress = { p -> backupTransfer = p },
                                    isCancelled = { backupCancel.get() },
                                )
                            } ?: throw IllegalStateException("Tidak dapat membuka tujuan backup")
                        }
                        Toast.makeText(
                            activity,
                            "Backup selesai: ${summary.fileCount} file • " +
                                FormatUtil.fileSize(summary.totalBytes),
                            Toast.LENGTH_LONG,
                        ).show()
                    } catch (e: TransferCancelledException) {
                        // Never leave a partial .svbackup in the public location.
                        runCatching { activity.contentResolver.delete(uri, null, null) }
                        Toast.makeText(activity, e.message ?: "Backup dibatalkan", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        runCatching { activity.contentResolver.delete(uri, null, null) }
                        Toast.makeText(activity, "Backup gagal: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        backupTransfer = null
                    }
                }
            },
        )
    }

    if (showRestorePin) {
        PinEntryDialog(
            title = "PIN vault asli",
            subtitle = "Masukkan PIN vault dari perangkat PERTAMA tempat backup dibuat " +
                "(bukan PIN vault di perangkat ini).",
            onDismiss = {
                showRestorePin = false
                pendingRestoreUri = null
            },
            onConfirm = { pin ->
                restorePin = pin
                showRestorePin = false
                showRestoreMerge = true
            },
        )
    }

    if (showRestoreMerge) {
        AlertDialog(
            onDismissRequest = { showRestoreMerge = false },
            title = { Text("Mode penggabungan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Seluruh isi backup akan dienkripsi ulang dengan kunci vault di " +
                            "perangkat ini. File yang besar dapat memakan waktu.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreRenameConflicts = false },
                    ) {
                        RadioButton(
                            selected = !restoreRenameConflicts,
                            onClick = { restoreRenameConflicts = false },
                        )
                        Text("Lewati file yang sudah ada")
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { restoreRenameConflicts = true },
                    ) {
                        RadioButton(
                            selected = restoreRenameConflicts,
                            onClick = { restoreRenameConflicts = true },
                        )
                        Text("Duplikat dengan nama baru")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreMerge = false
                    val targetUri = pendingRestoreUri
                    if (targetUri != null) {
                        backupCancel.set(false)
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    activity.contentResolver.openInputStream(targetUri)?.use { input ->
                                        container.backupRepository.restoreBackup(
                                            source = input,
                                            originalPin = restorePin.toCharArray(),
                                            renameConflicts = restoreRenameConflicts,
                                            stack = container.currentStack(),
                                            onProgress = { p -> backupTransfer = p },
                                            isCancelled = { backupCancel.get() },
                                        )
                                    } ?: throw IllegalStateException("Tidak dapat membaca file backup")
                                }
                                Toast.makeText(
                                    activity,
                                    "Restore selesai: ${result.imported} file diimpor" +
                                        (if (result.skipped > 0) ", ${result.skipped} dilewati" else "") +
                                        (if (result.foldersCreated > 0) ", ${result.foldersCreated} folder baru" else ""),
                                    Toast.LENGTH_LONG,
                                ).show()
                                statsText = try {
                                    val s = container.currentStack().repository.stats()
                                    "${FormatUtil.fileSize(s.totalSizeBytes)} • ${s.fileCount} file • ${s.folderCount} folder"
                                } catch (_: Exception) {
                                    statsText
                                }
                            } catch (e: TransferCancelledException) {
                                Toast.makeText(activity, e.message ?: "Restore dibatalkan", Toast.LENGTH_LONG)
                                    .show()
                            } catch (e: Exception) {
                                Toast.makeText(activity, "Restore gagal: ${e.message}", Toast.LENGTH_LONG)
                                    .show()
                            } finally {
                                backupTransfer = null
                                pendingRestoreUri = null
                            }
                        }
                    }
                }) { Text("Mulai restore") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreMerge = false
                    pendingRestoreUri = null
                }) { Text("Batal") }
            },
        )
    }

    // ---- optimize (v1 -> v2) dialogs ----

    if (showOptimizeConfirm && !optimizing) {
        AlertDialog(
            onDismissRequest = { showOptimizeConfirm = false },
            title = { Text("Optimalkan $legacyCount file lama?") },
            text = {
                Text(
                    "Setiap file akan didekripsi lalu dienkripsi ulang ke format baru " +
                        "(putar instan, seek cepat, tanpa file sementara). Waktu proses " +
                        "bergantung ukuran total. File gagal dikonversi tetap bisa diputar."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOptimizeConfirm = false
                    optimizing = true
                    scope.launch {
                        try {
                            val summary = withContext(Dispatchers.IO) {
                                container.currentStack().repository.migrateAllLegacy(
                                    onProgress = { done, total -> optimizeProgress = done to total },
                                    onItemError = { _, _ -> },
                                )
                            }
                            Toast.makeText(
                                activity,
                                buildString {
                                    append("${summary.migrated} file dioptimalkan")
                                    if (summary.failed.isNotEmpty()) append(", ${summary.failed.size} gagal")
                                },
                                Toast.LENGTH_LONG,
                            ).show()
                            legacyCount = runCatching {
                                container.currentStack().repository.countLegacy()
                            }.getOrDefault(0)
                        } finally {
                            optimizing = false
                            optimizeProgress = null
                        }
                    }
                }) { Text("Mulai") }
            },
            dismissButton = { TextButton(onClick = { showOptimizeConfirm = false }) { Text("Batal") } },
        )
    }

    if (optimizing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Mengoptimalkan...") },
            text = {
                val p = optimizeProgress
                val animatedOptimizeProgress by animateFloatAsState(
                    targetValue = if (p != null && p.second > 0) p.first.toFloat() / p.second else 0f,
                    label = "optimizeProgress",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { animatedOptimizeProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (p != null) "${p.first}/${p.second} item" else "menyiapkan…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }

    // Realtime backup/restore progress overlay (nama file + (n/total) + bar).
    backupTransfer?.let { tp ->
        id.bayu.mygalleryvault.ui.components.TransferProgressDialog(
            progress = tp,
            onCancel = { backupCancel.set(true) },
        )
    }
}

@Composable
private fun PinEntryDialog(
    title: String,
    subtitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pin) },
                enabled = pin.length >= AuthRepository.MIN_PIN_LENGTH,
            ) { Text("Lanjut") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun BiometricRow(
    enabled: Boolean,
    activity: FragmentActivity,
    onEnableRequest: () -> Unit,
    onDisable: () -> Unit,
) {
    val available by produceState(initialValue = false) {
        value = BiometricHelper.canUseBiometrics(activity)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Buka dengan biometrik", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Gunakan sidik jari sebagai alternatif PIN",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { checked -> if (checked) onEnableRequest() else onDisable() },
                enabled = true,
                colors = vaultSwitchColors(),
            )
        }
    }
}

@Composable
private fun BiometricEnableRow(
    activity: FragmentActivity,
    onEnableRequest: () -> Unit,
) {
    val available by produceState(initialValue = false) {
        value = BiometricHelper.canUseBiometrics(activity)
    }
    if (available) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEnableRequest)
                    .padding(14.dp),
            ) {
                Text("Aktifkan biometrik", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Gunakan sidik jari sebagai alternatif PIN",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(14.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Switch colours for this dark theme. Material's defaults draw the off state with a surfaceVariant
 * track, an outline border and an outline thumb, and on this card all three land between about 1.1
 * and 1.5 to 1 against the surface, so a switch that is off reads as if it were missing (R-25).
 * Every token here comes from the measured contrast table in DESIGN.md rather than from a guess:
 * stone border and Ash thumb on a card, orange track once it is on.
 */
@Composable
private fun vaultSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    checkedTrackColor = MaterialTheme.colorScheme.primary,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    uncheckedBorderColor = sanityColors().stone,
)

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                enabled = enabled,
                colors = vaultSwitchColors(),
            )
        }
    }
}

@Composable
private fun ThresholdRow(threshold: Int, onChange: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ambang percobaan gagal", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$threshold percobaan sebelum break-in alert dicatat",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { if (threshold > 3) onChange(threshold - 1) }) { Text("-") }
            Text("$threshold")
            TextButton(onClick = { if (threshold < 10) onChange(threshold + 1) }) { Text("+") }
        }
    }
}

@Composable
private fun SingleChoiceDialog(
    title: String,
    options: List<String>,
    selectedLabel: String,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { index, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(index) },
                    ) {
                        RadioButton(selected = label == selectedLabel, onClick = null)
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
private fun SecurityLogDialog(
    auth: AuthRepository,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
) {
    val entries by produceState<List<AuthRepository.SecurityLogEntry>?>(initialValue = null) {
        value = try {
            auth.recentSecurityEvents()
        } catch (_: Exception) {
            emptyList()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Laporan keamanan") },
        text = {
            val list = entries
            when {
                list == null -> Text("Memuat...")
                list.isEmpty() -> Text("Belum ada kejadian keamanan tercatat.")
                else -> LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.timestamp.toString() + it.eventType }) { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val bmp = entry.thumbnail
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    modifier = Modifier.size(52.dp),
                                )
                            } else {
                                Text(
                                    if (entry.eventType == AuthRepository.EVENT_BREAKIN_ALERT) "⚠"
                                    else "•",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(
                                    if (entry.eventType == AuthRepository.EVENT_BREAKIN_ALERT)
                                        "Break-in alert"
                                    else "PIN salah",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${FormatUtil.dateTime(entry.timestamp)}" +
                                        (if (entry.attemptCount > 0) " • ke-${entry.attemptCount}" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Hapus log") }
                TextButton(onClick = onDismiss) { Text("Tutup") }
            }
        },
    )
}

@Composable
private fun DecoySetupDialog(
    onDismiss: () -> Unit,
    onConfirm: (pin: String, onError: (String) -> Unit) -> Unit,
) {
    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Buat PIN decoy") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pilih PIN berbeda dari PIN utama. Memasukkan PIN ini akan membuka " +
                        "vault palsu yang benar-benar terpisah.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("PIN decoy") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("Konfirmasi PIN decoy") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    pin.length < AuthRepository.MIN_PIN_LENGTH ->
                        error = "PIN minimal ${AuthRepository.MIN_PIN_LENGTH} digit"
                    pin != confirm -> error = "PIN dan konfirmasi tidak sama"
                    else -> onConfirm(pin) { err -> error = err }
                }
            }) { Text("Buat") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun ChangePinDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, (String) -> Unit) -> Unit,
) {
    var old by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ganti PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("PIN lama") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("PIN baru") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
                    label = { Text("Konfirmasi PIN baru") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    new != confirm -> error = "PIN baru dan konfirmasi tidak sama"
                    new.length < AuthRepository.MIN_PIN_LENGTH ->
                        error = "PIN minimal ${AuthRepository.MIN_PIN_LENGTH} digit"
                    else -> onConfirm(old, new) { error = it }
                }
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

/** Curated public ad/tracker blocklists importable over HTTPS. */
private data class BlocklistSource(val label: String, val desc: String, val url: String)

private val BLOCKLIST_SOURCES = listOf(
    BlocklistSource(
        "AdGuard DNS filter",
        "~150rb domain; sintaks AdGuard ||domain^ (didukung penuh)",
        "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
    ),
    BlocklistSource(
        "StevenBlack hosts",
        "Gabungan 40+ sumber populer (~130rb domain)",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
    ),
    BlocklistSource(
        "OISD small",
        "Kurasi anti-iklan ringan, minim false positive",
        "https://small.oisd.nl/",
    ),
    BlocklistSource(
        "AdAway default",
        "Daftar standar AdAway (~12rb domain)",
        "https://adaway.org/hosts.txt",
    ),
    BlocklistSource(
        "Peter Lowe's list",
        "Blocklist klasik adservers & trackers",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=0&mimetype=plaintext",
    ),
    BlocklistSource(
        "anudeepND adservers",
        "Ad servers & trackers terkurasi (~15rb domain)",
        "https://raw.githubusercontent.com/anudeepND/blacklist/master/adservers.txt",
    ),
    BlocklistSource(
        "URLhaus malicious",
        "Domain malware/penyebar iklan berbahaya (online)",
        "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts-online.txt",
    ),
)
