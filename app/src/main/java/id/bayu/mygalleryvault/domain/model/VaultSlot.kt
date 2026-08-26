package id.bayu.mygalleryvault.domain.model

/**
 * Logical vault slots (PRD §25-26). Slot identities are internal only;
 * storage/database file names stay neutral so the on-disk layout does not
 * advertise which slot is the "real" one.
 */
enum class VaultSlot {
    REAL,
    DECOY,
}

enum class ShakeSensitivity(val label: String, val threshold: Float) {
    LOW("Lemah", 32f),
    MEDIUM("Sedang", 23f),
    HIGH("Kuat", 16f);

    companion object {
        fun fromName(value: String?): ShakeSensitivity =
            entries.firstOrNull { it.name == value } ?: MEDIUM
    }
}
