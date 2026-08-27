package id.bayu.mygalleryvault.ui.screens.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.bayu.mygalleryvault.SecureVaultApp
import id.bayu.mygalleryvault.core.security.BreakInCapturer
import id.bayu.mygalleryvault.data.repository.AuthRepository
import id.bayu.mygalleryvault.domain.model.UnlockResult
import id.bayu.mygalleryvault.ui.components.BiometricHelper
import id.bayu.mygalleryvault.ui.components.PinKeypad
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.coroutines.resume

class LockViewModel(private val auth: AuthRepository) : ViewModel() {

    data class UiState(
        val pin: String = "",
        val error: String? = null,
        val attempts: Int = 0,
        val lockedUntilMillis: Long = 0L,
        /** Live remaining lockout, ticked every 250 ms - drives countdown + keypad. */
        val lockRemainingMs: Long = 0L,
        val biometricAvailable: Boolean = false,
        val biometricEnabled: Boolean = false,
        val busy: Boolean = false,
        /** Exact primary-PIN length once known; null for legacy unreconciled vaults. */
        val pinSlots: Int? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init {
        viewModelScope.launch {
            val slots = auth.storedPinLength()
            if (slots != null) {
                _state.value = _state.value.copy(pinSlots = slots)
            }
        }
        // A lockout that survived a process kill (persisted deadline) resumes here.
        viewModelScope.launch {
            val deadline = auth.lockedUntilMillis()
            if (deadline > System.currentTimeMillis()) {
                _state.value = _state.value.copy(lockedUntilMillis = deadline)
            }
        }
        // Ticker: keeps the countdown text live and re-enables the keypad at
        // exactly the right moment without the user leaving/reopening the app.
        viewModelScope.launch {
            while (viewModelScope.isActive) {
                val until = _state.value.lockedUntilMillis
                val remaining =
                    if (until <= 0L) 0L else (until - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remaining != _state.value.lockRemainingMs) {
                    _state.value = _state.value.copy(lockRemainingMs = remaining)
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    fun onDigit(digit: Char) {
        val s = _state.value
        if (s.busy || System.currentTimeMillis() < s.lockedUntilMillis) return
        if (s.pin.length >= MAX_PIN) return
        _state.value = s.copy(pin = s.pin + digit, error = null)
    }

    fun onBackspace() {
        val s = _state.value
        if (s.busy) return
        if (s.pin.isEmpty()) return
        _state.value = s.copy(pin = s.pin.dropLast(1))
    }

    fun submit(activity: FragmentActivity, onUnlocked: () -> Unit) {
        val current = _state.value
        if (current.pin.isEmpty() || current.busy) return
        if (System.currentTimeMillis() < current.lockedUntilMillis) return
        _state.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = auth.unlock(current.pin.toCharArray())) {
                is UnlockResult.Success -> onUnlocked()
                is UnlockResult.Failed -> {
                    _state.value = _state.value.copy(
                        busy = false,
                        pin = "",
                        attempts = result.attemptCount,
                        error = "PIN salah (percobaan ke-${result.attemptCount})",
                        lockedUntilMillis = if (result.lockUntilMillis > 0L) {
                            result.lockUntilMillis
                        } else {
                            System.currentTimeMillis() + result.backoffMillis
                        },
                    )
                    if (result.breakInDetected) {
                        handleBreakInBreach(activity, result.attemptCount)
                    }
                }
            }
        }
    }

    /**
     * Threshold breached: record the break-in alert, attaching a front-camera
     * snapshot when photos are enabled and permission was granted (§27-28).
     * Never crashes when the camera is unavailable.
     */
    private fun handleBreakInBreach(activity: FragmentActivity, attempts: Int) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val photo = if (
                auth.breakInPhotosEnabled() && BreakInCapturer.hasCameraPermission(activity)
            ) {
                suspendCancellableCoroutine<ByteArray?> { cont ->
                    BreakInCapturer.captureFrontPhoto(activity) { bytes -> cont.resume(bytes) }
                }
            } else null
            runCatching { auth.recordBreakin(attempts, photo) }
        }
    }

    fun loadBiometricAvailability(activity: FragmentActivity) {
        viewModelScope.launch {
            val enabled = try {
                auth.biometricEnabled()
            } catch (_: Exception) {
                false
            }
            _state.value = _state.value.copy(
                biometricAvailable = BiometricHelper.canUseBiometrics(activity),
                biometricEnabled = enabled,
            )
        }
    }

    fun startBiometricUnlock(activity: FragmentActivity, onUnlocked: () -> Unit) {
        viewModelScope.launch {
            try {
                val request = auth.prepareBiometricUnlock()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    BiometricHelper.authenticate(
                        activity = activity,
                        title = "Buka Vault",
                        subtitle = "Gunakan sidik jari untuk membuka vault",
                        negativeText = "Gunakan PIN",
                        cryptoObject = androidx.biometric.BiometricPrompt.CryptoObject(request.cipher),
                        onSuccess = { _ ->
                            try {
                                auth.completeBiometricUnlock(request)
                                onUnlocked()
                            } catch (e: Exception) {
                                _state.value = _state.value.copy(error = "Biometrik gagal: ${e.message}")
                            }
                        },
                        onFailure = { msg ->
                            _state.value = _state.value.copy(error = msg)
                        },
                    )
                }
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "Biometrik tidak tersedia")
            }
        }
    }

    companion object {
        /**
         * Typing headroom at the keypad only - kept generous so legacy vaults
         * with a PIN longer than the current 10-digit setup cap can still log
         * in. Creation/change limits live in [AuthRepository].
         */
        const val MAX_PIN = 16
    }
}

@Composable
fun LockScreen(
    activity: FragmentActivity,
    onUnlocked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = activity.application as SecureVaultApp
    val vm: LockViewModel = viewModel(factory = viewModelFactory {
        initializer { LockViewModel(app.container.authRepository) }
    })
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.loadBiometricAvailability(activity) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.Fingerprint,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("MyGalleryVault", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Masukkan PIN Anda", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(24.dp))
        PinDots(pinLength = state.pin.length, slotCount = state.pinSlots)

        val remainSec = ((state.lockRemainingMs + 999) / 1000).toInt()
        if (remainSec > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Coba lagi dalam ${remainSec}s",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Spacer(Modifier.height(16.dp))

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(8.dp))
        PinKeypad(
            enabled = !state.busy && state.lockRemainingMs <= 0L,
            showBiometric = state.biometricAvailable && state.biometricEnabled &&
                state.lockRemainingMs <= 0L,
            onDigit = vm::onDigit,
            onBackspace = vm::onBackspace,
            onSubmit = { vm.submit(activity, onUnlocked) },
            onBiometric = { vm.startBiometricUnlock(activity, onUnlocked) },
        )
    }
}

/**
 * Dot slots for PIN entry. When the stored primary-PIN length is known
 * ([slotCount] != null) exactly that many dots render - e.g. a 9-digit PIN
 * shows 9 slots - laid out as one tidy centered row (10 dots always fit);
 * legacy unreconciled vaults fall back to adaptive length with wrapping.
 */
@Composable
private fun PinDots(pinLength: Int, slotCount: Int?) {
    // Known slots render exactly as stored; an unknown (legacy, pre-reconcile)
    // vault shows only typed dots instead of pretending a false total.
    val total = slotCount ?: pinLength
    val perRow = if (total > 10) 5 else 10
    val dotSize = if (total > 10) 11.dp else 14.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        List(total) { it }.chunked(perRow).forEach { rowIndices ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                rowIndices.forEach { index ->
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = if (index < pinLength) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(dotSize),
                    ) {}
                }
            }
        }
    }
}
