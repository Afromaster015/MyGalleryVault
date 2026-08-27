package id.bayu.mygalleryvault.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.bayu.mygalleryvault.domain.model.TransferKind
import id.bayu.mygalleryvault.domain.model.TransferProgress
import kotlin.math.roundToInt

/**
 * Realtime transfer progress: current item as "filename (n/total)" with a live
 * determinate bar, plus an overall items-completed indicator and percentage.
 * Non-dismissible by back-press/tap-outside so state cannot desync mid-transfer.
 */
@Composable
fun TransferProgressDialog(
    progress: TransferProgress,
    onCancel: () -> Unit,
) {
    val title = when (progress.kind) {
        TransferKind.IMPORT -> "Mengimpor ke vault..."
        TransferKind.EXPORT -> "Mengekspor dari vault..."
        TransferKind.BACKUP -> "Membuat backup terenkripsi..."
        TransferKind.RESTORE -> "Memulihkan backup..."
    }
    val itemFraction = progress.itemFraction

    AlertDialog(
        onDismissRequest = {},
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${progress.currentItemName} (${progress.currentItemIndex}/${progress.totalItems})",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (itemFraction != null) {
                    val animatedFraction by animateFloatAsState(
                        targetValue = itemFraction.coerceIn(0f, 1f),
                        label = "item_progress",
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(
                            progress = { animatedFraction },
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            " ${(animatedFraction * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    // Provider did not report a size; show indeterminate movement.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(modifier = Modifier.weight(1f))
                        Text(" ...", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Keseluruhan",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "(${progress.completedItems}/${progress.totalItems}) " +
                                "${(progress.overallFraction * 100).roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.overallFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Batalkan") }
        },
    )
}
