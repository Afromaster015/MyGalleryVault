package id.bayu.mygalleryvault.ui.screens.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.bayu.mygalleryvault.R
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.data.repository.AuthRepository
import kotlinx.coroutines.launch

class SetupViewModel(private val auth: AuthRepository) : ViewModel() {

    fun setup(pin: CharArray, confirm: CharArray, onResult: (String?) -> Unit) {
        if (!pin.contentEquals(confirm)) {
            onResult("PIN dan konfirmasi tidak sama")
            return
        }
        if (pin.size < AuthRepository.MIN_PIN_LENGTH) {
            onResult("PIN minimal ${AuthRepository.MIN_PIN_LENGTH} digit")
            return
        }
        if (pin.size > AuthRepository.MAX_PIN_LENGTH) {
            onResult("PIN maksimal ${AuthRepository.MAX_PIN_LENGTH} digit")
            return
        }
        viewModelScope.launch {
            try {
                auth.setupVault(pin)
                onResult(null)
            } catch (e: Exception) {
                onResult(e.message ?: "Gagal membuat vault")
            }
        }
    }
}

@Composable
fun SetupScreen(onSetupComplete: () -> Unit, modifier: Modifier = Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as SecureVaultApp
    val vm: SetupViewModel = viewModel(factory = viewModelFactory {
        initializer { SetupViewModel(app.container.authRepository) }
    })

    var pin by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("MyGalleryVault", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Buat PIN untuk mengenkripsi vault Anda",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { pin = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
            label = { Text(stringResource(R.string.setup_pin_hint)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it.filter(Char::isDigit).take(AuthRepository.MAX_PIN_LENGTH) },
            label = { Text(stringResource(R.string.setup_pin_confirm)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Peringatan penting",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Jika kamu lupa PIN dan tidak menyimpan Recovery Key, " +
                        "seluruh isi vault tidak dapat dipulihkan. Tidak ada backdoor.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = acknowledged,
                        onCheckedChange = { acknowledged = it },
                    )
                    Text("Saya mengerti risikonya", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                if (busy) return@Button
                busy = true
                error = null
                vm.setup(pin.toCharArray(), confirm.toCharArray()) { err ->
                    busy = false
                    if (err != null) error = err else onSetupComplete()
                }
            },
            // Stay enabled-looking while working: the disabled style would grey the coral out
            // and leave the spinner with nothing to sit on. Re-entry is guarded above instead.
            enabled = pin.length >= AuthRepository.MIN_PIN_LENGTH &&
                confirm.length >= AuthRepository.MIN_PIN_LENGTH && acknowledged,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                    // No track ring: on a filled button it only muddies the arc.
                    trackColor = Color.Transparent,
                )
            } else {
                Text("Buat Vault")
            }
        }
    }
}
