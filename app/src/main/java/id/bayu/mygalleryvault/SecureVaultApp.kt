package id.bayu.mygalleryvault

import android.app.Application
import id.bayu.mygalleryvault.core.crypto.VaultKeyManager
import id.bayu.mygalleryvault.core.crypto.VaultSession
import id.bayu.mygalleryvault.core.lock.AutoLockManager
import id.bayu.mygalleryvault.core.security.SecurePhotoStore
import id.bayu.mygalleryvault.core.storage.VaultStorage
import id.bayu.mygalleryvault.data.local.AppDatabase
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.data.repository.BackupRepository
import id.bayu.mygalleryvault.data.repository.SettingsRepository
import id.bayu.mygalleryvault.data.repository.ThumbnailService
import id.bayu.mygalleryvault.data.repository.TransferRepository
import id.bayu.mygalleryvault.data.repository.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class SecureVaultApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AutoLockManager.register(this)
        container.settingsRepository.start()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.realStack().repository.reconcileStorage() }
            if (container.authRepository.isDecoyCreated) {
                runCatching { container.decoyStack().repository.reconcileStorage() }
            }
            // One-time fix: correct DB encryptionVersion for files that are actually v2 on disk
            runCatching { container.realStack().repository.fixLegacyDbRecords() }
            if (container.authRepository.isDecoyCreated) {
                runCatching { container.decoyStack().repository.fixLegacyDbRecords() }
            }
        }
    }
}

/**
 * Per-slot bundle of database, storage, and repository (PRD §25-26). The decoy
 * vault is fully isolated from the real one: separate DB file, separate object
 * store, separate master key.
 */
class VaultStack(
    val database: AppDatabase,
    val storage: VaultStorage,
    val repository: VaultRepository,
    val transfers: TransferRepository,
    val thumbnails: ThumbnailService,
)

class AppContainer(app: Application) {

    // Global (device-level) components. Settings & security events always live in
    // the real DB so behavior and alerts are shared regardless of open slot.
    private val context: Application = app
    val keyManager = VaultKeyManager(File(app.filesDir, "keys"))
    val photoStore = SecurePhotoStore(File(app.filesDir, "vault/sec"))
    val settingsRepository = SettingsRepository(AppDatabase.getReal(app).settingsDao())

    val authRepository = AuthRepository(
        keyManager = keyManager,
        settingsDao = AppDatabase.getReal(app).settingsDao(),
        securityEventDao = AppDatabase.getReal(app).securityEventDao(),
        photoStore = photoStore,
    )

    val backupRepository by lazy { BackupRepository(app) }

    private val real by lazy {
        val db = AppDatabase.getReal(app)
        val storage = VaultStorage(app, SLOT_DIR_REAL)
        buildStack(db, storage)
    }

    private val decoy by lazy {
        val db = AppDatabase.getDecoy(app)
        val storage = VaultStorage(app, SLOT_DIR_DECOY)
        buildStack(db, storage)
    }

    private fun buildStack(db: AppDatabase, storage: VaultStorage): VaultStack {
        val fileDao = db.vaultFileDao()
        val thumbnails = ThumbnailService(context, fileDao, storage)
        return VaultStack(
            database = db,
            storage = storage,
            repository = VaultRepository(context, fileDao, db.folderDao(), storage),
            transfers = TransferRepository(context, fileDao, storage, thumbnails),
            thumbnails = thumbnails,
        )
    }

    fun realStack(): VaultStack = real

    fun decoyStack(): VaultStack = decoy

    /** Stack for whatever slot is currently unlocked; real when locked. */
    fun currentStack(): VaultStack =
        if (VaultSession.slot == id.bayu.mygalleryvault.domain.model.VaultSlot.DECOY) decoy else real

    /** Removes all decoy data (keys are wiped separately by AuthRepository callers). */
    suspend fun wipeDecoyData() {
        if (!authRepository.isDecoyCreated) return
        runCatching { AppDatabase.getDecoy(context).close() }
        decoy.storage.wipeAll()
        context.getDatabasePath(AppDatabase.DECOY_DB_NAME).delete()
        File(context.getDatabasePath(AppDatabase.DECOY_DB_NAME).absolutePath + "-wal").delete()
        File(context.getDatabasePath(AppDatabase.DECOY_DB_NAME).absolutePath + "-shm").delete()
    }

    companion object {
        // Neutral directory names so the layout does not reveal which slot is "real" (§26).
        private const val SLOT_DIR_REAL = "w7q1"
        private const val SLOT_DIR_DECOY = "p3n8"
    }
}
