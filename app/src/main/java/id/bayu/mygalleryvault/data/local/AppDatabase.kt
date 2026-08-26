package id.bayu.mygalleryvault.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        VaultFileEntity::class,
        FolderEntity::class,
        SecurityEventEntity::class,
        SettingEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun vaultFileDao(): VaultFileDao
    abstract fun folderDao(): FolderDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        // Real vault keeps the historical file name; decoy uses a neutral one so the
        // file list does not advertise which DB holds which vault (PRD §26).
        const val REAL_DB_NAME = "secure_vault.db"
        const val DECOY_DB_NAME = "sv_sys.db"

        @Volatile
        private var realInstance: AppDatabase? = null

        @Volatile
        private var decoyInstance: AppDatabase? = null

        fun get(context: Context): AppDatabase = getReal(context)

        fun getReal(context: Context): AppDatabase =
            realInstance ?: synchronized(this) {
                realInstance ?: build(context, REAL_DB_NAME).also { realInstance = it }
            }

        fun getDecoy(context: Context): AppDatabase =
            decoyInstance ?: synchronized(this) {
                decoyInstance ?: build(context, DECOY_DB_NAME).also { decoyInstance = it }
            }

        private fun build(context: Context, name: String): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, name)
                .fallbackToDestructiveMigration()
                .build()
    }
}
