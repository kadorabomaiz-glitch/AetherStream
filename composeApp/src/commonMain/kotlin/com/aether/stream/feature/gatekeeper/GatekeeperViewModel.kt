package com.aether.stream.feature.gatekeeper

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class GatekeeperUiState(
    val deviceId: String = AetherCryptoEngine.getFormattedDeviceId(),
    val enteredAccessCode: String = "",
    val isUnlocked: Boolean = false,
    val errorMessage: String? = null,
    val isAdminAuthenticated: Boolean = false,
    val enteredAdminPin: String = "",
    val adminPinError: String? = null,
    val activeMasterCode: String = AetherCryptoEngine.DEFAULT_MASTER_CODE,
    val activeAdminPin: String = AetherCryptoEngine.DEFAULT_ADMIN_PIN,
    val friendDeviceIdInput: String = "AETH-",
    val generatedFriendUnlockCode: String? = null,
    val masterCodeStatusMessage: String? = null
)

class GatekeeperViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GatekeeperUiState())
    val uiState: StateFlow<GatekeeperUiState> = _uiState.asStateFlow()

    fun onAccessCodeChanged(newCode: String) {
        _uiState.update {
            it.copy(
                enteredAccessCode = newCode.uppercase(),
                errorMessage = null
            )
        }
    }

    fun submitAccessCode(onSuccessUnlock: () -> Unit) {
        val state = _uiState.value
        val isValid = AetherCryptoEngine.verifyAccessCode(
            inputCode = state.enteredAccessCode,
            currentDeviceId = state.deviceId,
            activeMasterCode = state.activeMasterCode
        )

        if (isValid) {
            _uiState.update {
                it.copy(
                    isUnlocked = true,
                    errorMessage = null
                )
            }
            onSuccessUnlock()
        } else {
            _uiState.update {
                it.copy(
                    errorMessage = "Zugriff verweigert: Ungültiger Freischalt- oder Master-Code."
                )
            }
        }
    }

    fun onAdminPinChanged(pin: String) {
        if (pin.length <= 8) {
            _uiState.update { it.copy(enteredAdminPin = pin, adminPinError = null) }
        }
    }

    fun verifyAdminPin() {
        val state = _uiState.value
        if (state.enteredAdminPin == state.activeAdminPin) {
            _uiState.update {
                it.copy(
                    isAdminAuthenticated = true,
                    enteredAdminPin = "",
                    adminPinError = null
                )
            }
        } else {
            _uiState.update {
                it.copy(adminPinError = "Falsche Admin-PIN.")
            }
        }
    }

    fun onFriendDeviceIdChanged(deviceId: String) {
        _uiState.update {
            it.copy(
                friendDeviceIdInput = deviceId.uppercase(),
                generatedFriendUnlockCode = null
            )
        }
    }

    fun generateKeyForFriend() {
        val rawInput = _uiState.value.friendDeviceIdInput.trim().uppercase()
        if (rawInput.length < 4) return

        val normalizedDeviceId = if (rawInput.startsWith("AETH-")) {
            rawInput
        } else {
            "AETH-$rawInput"
        }

        val generatedKey = AetherCryptoEngine.generateUnlockCodeForDevice(normalizedDeviceId)
        _uiState.update {
            it.copy(
                friendDeviceIdInput = normalizedDeviceId,
                generatedFriendUnlockCode = generatedKey
            )
        }
    }

    fun updateMasterCode(newMasterCode: String) {
        val cleaned = newMasterCode.trim().uppercase()
        if (cleaned.length < 4) {
            _uiState.update {
                it.copy(masterCodeStatusMessage = "Master-Code muss mindestens 4 Zeichen lang sein.")
            }
            return
        }
        _uiState.update {
            it.copy(
                activeMasterCode = cleaned,
                masterCodeStatusMessage = "Neuer Master-Code '$cleaned' aktiv gespeichert."
            )
        }
    }

    fun lockAdminSession() {
        _uiState.update {
            it.copy(
                isAdminAuthenticated = false,
                enteredAdminPin = "",
                adminPinError = null,
                masterCodeStatusMessage = null
            )
        }
    }
}