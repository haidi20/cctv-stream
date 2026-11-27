package id.warung.monitor.feature.auth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import id.warung.monitor.feature.auth.domain.LoginState
import id.warung.monitor.feature.auth.domain.PinPolicy
import id.warung.monitor.feature.auth.domain.ResetPolicy

/**
 * Stateless login surface: all values from [state], all intents as callbacks.
 *
 * Width selects the centred form width; there is no orientation check.
 */
@Composable
fun LoginScreen(
    state: LoginState,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onBiometric: (() -> Unit)?,
    onReset: (String) -> Unit,
    modifier: Modifier = Modifier,
    onStartChangePin: () -> Unit = {},
    onCancelChangePin: () -> Unit = {},
    onStartResetPin: () -> Unit = {},
    onCancelResetPin: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
    ) {
        val wide = maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = if (wide) Arrangement.Center else Arrangement.Top,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Warung Monitor", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (state) {
                        is LoginState.CreatePin -> "Buat PIN 6 digit untuk mengunci aplikasi"
                        is LoginState.ChangePin -> "Ganti PIN"
                        is LoginState.ResetPin -> "Atur ulang PIN"
                        is LoginState.LockedOut -> "Terlalu banyak percobaan"
                        is LoginState.Verifying -> "Memeriksa PIN…"
                        else -> "Masukkan PIN"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                when (state) {
                    LoginState.Loading -> Unit
                    is LoginState.EnterPin -> EnterPinBody(
                        state = state,
                        onDigit = onDigit,
                        onDelete = onDelete,
                        onBiometric = onBiometric,
                        onReset = onReset,
                        onStartChangePin = onStartChangePin,
                        onStartResetPin = onStartResetPin,
                    )
                    is LoginState.Verifying -> VerifyingBody(state)
                    is LoginState.LockedOut -> LockedBody(state)
                    is LoginState.CreatePin -> CreatePinBody(state, onDigit, onDelete)
                    is LoginState.ChangePin -> ChangePinBody(
                        state = state,
                        onDigit = onDigit,
                        onDelete = onDelete,
                        onCancel = onCancelChangePin,
                    )
                    is LoginState.ResetPin -> ResetPinBody(
                        state = state,
                        onDigit = onDigit,
                        onDelete = onDelete,
                        onCancel = onCancelResetPin,
                    )
                }
            }
        }
    }
}

@Composable
private fun EnterPinBody(
    state: LoginState.EnterPin,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onBiometric: (() -> Unit)?,
    onReset: (String) -> Unit,
    onStartChangePin: () -> Unit,
    onStartResetPin: () -> Unit,
) {
    PinDots(filled = state.filled, total = PinPolicy.LENGTH)
    Spacer(Modifier.height(8.dp))
    Text(
        text = state.message.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.message == null) Color.Transparent else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("login-error"),
    )
    Spacer(Modifier.height(16.dp))
    PinKeypad(
        onDigit = onDigit,
        onDelete = onDelete,
        onBiometric = onBiometric,
    )
    Spacer(Modifier.height(16.dp))
    ResetSection(
        onStartChangePin = onStartChangePin,
        onReset = onReset,
        onStartResetPin = onStartResetPin,
    )
}

@Composable
private fun VerifyingBody(state: LoginState.Verifying) {
    PinDots(filled = state.filled, total = PinPolicy.LENGTH)
    Spacer(Modifier.height(24.dp))
    PinKeypad(onDigit = {}, onDelete = {}, enabled = false)
}

@Composable
private fun LockedBody(state: LoginState.LockedOut) {
    val seconds = (state.remainingMs / 1000).coerceAtLeast(0)
    Text(
        text = "%02d:%02d".format(seconds / 60, seconds % 60),
        style = MaterialTheme.typography.displaySmall,
        modifier = Modifier.testTag("lockout-countdown"),
    )
    Text(
        text = "Coba lagi setelah hitung mundur selesai.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(24.dp))
    PinKeypad(onDigit = {}, onDelete = {}, enabled = false)
}

@Composable
private fun CreatePinBody(
    state: LoginState.CreatePin,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
) {
    val filled = when (state.step) {
        LoginState.CreatePinStep.First -> state.firstFilled
        LoginState.CreatePinStep.Confirm -> state.confirmFilled
    }
    PinDots(filled = filled, total = PinPolicy.LENGTH)
    Spacer(Modifier.height(8.dp))
    Text(
        text = when (state.step) {
            LoginState.CreatePinStep.First -> "Ketik PIN baru"
            LoginState.CreatePinStep.Confirm -> "Ketik ulang PIN"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = state.error.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error == null) Color.Transparent else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    PinKeypad(onDigit = onDigit, onDelete = onDelete)
}

@Composable
private fun ChangePinBody(
    state: LoginState.ChangePin,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    PinDots(filled = state.filled, total = PinPolicy.LENGTH)
    Spacer(Modifier.height(8.dp))
    Text(
        text = when (state.step) {
            LoginState.ChangePinStep.Current -> "Ketik PIN lama"
            LoginState.ChangePinStep.New -> "Ketik PIN baru"
            LoginState.ChangePinStep.Confirm -> "Ketik ulang PIN baru"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("change-pin-step"),
    )
    Text(
        text = state.error.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error == null) Color.Transparent else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("change-pin-error"),
    )
    Spacer(Modifier.height(16.dp))
    PinKeypad(onDigit = onDigit, onDelete = onDelete)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCancel, modifier = Modifier.testTag("change-pin-cancel")) {
        Text("Batal")
    }
}

@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.testTag("pin-dots"),
    ) {
        repeat(total) { index ->
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < filled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}

@Composable
private fun ResetSection(
    onStartChangePin: () -> Unit,
    onReset: (String) -> Unit,
    onStartResetPin: () -> Unit,
) {
    var resetExpanded by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }
    if (!resetExpanded) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onStartChangePin, modifier = Modifier.testTag("start-change-pin")) {
                Text("Ganti PIN")
            }
            TextButton(onClick = { resetExpanded = true }, modifier = Modifier.testTag("start-reset")) {
                Text("Lupa PIN?")
            }
        }
        ResetWordField(onReset = onStartResetPin)
        return
    }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it.take(8) },
        label = { Text("Ketik HAPUS untuk reset") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reset-confirmation"),
    )
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = { onReset(typed) },
        enabled = typed == ResetPolicy.CONFIRMATION,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Hapus semua data aplikasi")
    }
    TextButton(
        onClick = {
            typed = ""
            resetExpanded = false
        },
    ) {
        Text("Batal")
    }
}

/**
 * Word escape hatch from `docs/add-feature.md`: typing `reset` anywhere on the
 * PIN screen opens the two-form PIN reset, so a forgotten PIN does not force a
 * full app wipe. The field is deliberately lowercase-only and off the keypad.
 */
@Composable
private fun ResetWordField(onReset: () -> Unit) {
    var word by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = word,
        onValueChange = { typed ->
            word = typed.take(MAX_RESET_WORD_LENGTH)
            if (word.equals(RESET_WORD, ignoreCase = true)) {
                word = ""
                onReset()
            }
        },
        label = { Text("Atau ketik \"reset\"") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reset-word"),
    )
}

private const val RESET_WORD = "reset"
private const val MAX_RESET_WORD_LENGTH = 8

@Composable
private fun ResetPinBody(
    state: LoginState.ResetPin,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    PinDots(filled = state.filled, total = PinPolicy.LENGTH)
    Spacer(Modifier.height(8.dp))
    Text(
        text = when (state.step) {
            LoginState.ResetPinStep.New -> "Ketik PIN baru"
            LoginState.ResetPinStep.Confirm -> "Ketik ulang PIN baru"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("reset-pin-step"),
    )
    Text(
        text = state.error.orEmpty(),
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.error == null) Color.Transparent else MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().testTag("reset-pin-error"),
    )
    Spacer(Modifier.height(16.dp))
    PinKeypad(onDigit = onDigit, onDelete = onDelete)
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = onCancel, modifier = Modifier.testTag("reset-pin-cancel")) {
        Text("Batal")
    }
}
