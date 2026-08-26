package id.bayu.mygalleryvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PinKeypad(
    enabled: Boolean,
    showBiometric: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    onBiometric: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                rowDigits.forEach { d ->
                    FilledTonalIconButton(onClick = { onDigit(d) }, enabled = enabled) {
                        Text(d.toString(), style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            if (showBiometric) {
                IconButton(onClick = onBiometric, enabled = enabled) {
                    Icon(Icons.Rounded.Fingerprint, contentDescription = "Buka dengan biometrik", Modifier.size(30.dp))
                }
            } else {
                androidx.compose.foundation.layout.Spacer(Modifier.size(48.dp))
            }
            FilledTonalIconButton(onClick = { onDigit('0') }, enabled = enabled) {
                Text("0", style = MaterialTheme.typography.titleLarge)
            }
            IconButton(onClick = onSubmit, enabled = enabled) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "OK", Modifier.size(30.dp))
            }
        }
        IconButton(onClick = onBackspace, enabled = enabled) {
            Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = "Hapus")
        }
    }
}
