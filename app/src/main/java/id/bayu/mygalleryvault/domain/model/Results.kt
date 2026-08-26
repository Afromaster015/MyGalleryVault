package id.bayu.mygalleryvault.domain.model

sealed class UnlockResult {
    data class Success(val slot: VaultSlot) : UnlockResult()
    data class Failed(
        val attemptCount: Int,
        val backoffMillis: Long,
        val breakInDetected: Boolean = false,
    ) : UnlockResult()
}

data class ImportOutcome(
    val total: Int,
    val succeeded: Int,
    val copiedNotMoved: List<String>,
    val failed: List<String>,
)

data class VaultStats(
    val totalSizeBytes: Long,
    val fileCount: Int,
    val folderCount: Int,
)

data class SecurityEventUi(
    val id: Long,
    val eventType: String,
    val timestamp: Long,
)
