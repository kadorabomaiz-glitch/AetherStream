package com.aether.stream.feature.gatekeeper

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aether.stream.core.theme.AetherColors
import com.aether.stream.core.ui.AetherFocusableGlassCard
import com.aether.stream.core.ui.AetherPrismaLogo

@Composable
fun GatekeeperLockScreen(
    viewModel: GatekeeperViewModel,
    onUnlockedSuccess: () -> Unit,
    onSecretAdminTriggered: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
    ) {
        AetherPrismaLogo(
            onSecretAdminTrigger = onSecretAdminTriggered,
            modifier = Modifier.align(Alignment.TopStart)
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .width(520.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.GlassSurface)
                .border(1.5.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(32.dp)
        ) {
            Text(
                text = "SYSTEM-AUTORISIERUNG ERFORDERLICH",
                style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Bitte gib deinen persönlichen Freischaltcode oder Master-Code ein.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(AetherColors.ObsidianBlack)
                    .border(1.dp, AetherColors.DeepCyanBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "DEINE GERÄTE-ID:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = state.deviceId,
                    style = MaterialTheme.typography.titleLarge.copy(
                        color = AetherColors.CyberCyan
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            OutlinedTextField(
                value = state.enteredAccessCode,
                onValueChange = viewModel::onAccessCodeChanged,
                label = { Text("Freischaltcode / Master-Code") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AetherColors.CyberCyan,
                    unfocusedBorderColor = AetherColors.DeepCyanBorder,
                    focusedTextColor = AetherColors.TextPrimary,
                    unfocusedTextColor = AetherColors.TextPrimary,
                    focusedLabelColor = AetherColors.CyberCyan,
                    unfocusedLabelColor = AetherColors.TextSecondary
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (state.errorMessage != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.errorMessage!!,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = AetherColors.CrimsonAlert
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AetherFocusableGlassCard(
                onClick = { viewModel.submitAccessCode(onUnlockedSuccess) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp)
                ) {
                    Text(
                        text = "TERMINAL FREISCHALTEN",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = AetherColors.CyberCyan
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun SecretAdminPanelModal(
    viewModel: GatekeeperViewModel,
    onDismiss: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    var customMasterInput by remember(state.activeMasterCode) {
        mutableStateOf(state.activeMasterCode)
    }

    Dialog(onDismissRequest = {
        viewModel.lockAdminSession()
        onDismiss()
    }) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(AetherColors.DeepSpaceNavy)
                .border(2.dp, AetherColors.CyberCyan, RoundedCornerShape(24.dp))
                .padding(28.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚡ AETHER ADMIN-KEYGEN TRESOR",
                    style = MaterialTheme.typography.titleLarge.copy(color = AetherColors.CyberCyan)
                )
                AetherFocusableGlassCard(
                    onClick = {
                        viewModel.lockAdminSession()
                        onDismiss()
                    },
                    cornerRadius = 10.dp
                ) {
                    Text(
                        text = "SCHLIESSEN ✕",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (!state.isAdminAuthenticated) {
                Text(
                    text = "Bitte gib deine geheime Admin-PIN ein, um den KeyGenerator zu entsperren:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = state.enteredAdminPin,
                    onValueChange = viewModel::onAdminPinChanged,
                    label = { Text("Geheime Admin-PIN") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AetherColors.CyberCyan,
                        unfocusedBorderColor = AetherColors.DeepCyanBorder,
                        focusedTextColor = AetherColors.TextPrimary,
                        unfocusedTextColor = AetherColors.TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.adminPinError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.adminPinError!!,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = AetherColors.CrimsonAlert
                        )
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                AetherFocusableGlassCard(
                    onClick = viewModel::verifyAdminPin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp)
                    ) {
                        Text(
                            text = "ADMIN-COCKPIT ENTSPERREN",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = AetherColors.CyberCyan
                            )
                        )
                    }
                }
            } else {
                Text(
                    text = "1. FREISCHALTCODE FÜR FREUNDE GENERIEREN",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = state.friendDeviceIdInput,
                        onValueChange = viewModel::onFriendDeviceIdChanged,
                        label = { Text("Geräte-ID des Freundes (z.B. AETH-7492)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    AetherFocusableGlassCard(
                        onClick = viewModel::generateKeyForFriend
                    ) {
                        Text(
                            text = "CODE BERECHNEN",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = AetherColors.CyberCyan
                            ),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                        )
                    }
                }

                if (state.generatedFriendUnlockCode != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AetherColors.ObsidianBlack)
                            .border(1.dp, AetherColors.NeonEmerald, RoundedCornerShape(12.dp))
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "Gültiger Code für ${state.friendDeviceIdInput}:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = state.generatedFriendUnlockCode!!,
                            style = MaterialTheme.typography.titleLarge.copy(
                                color = AetherColors.NeonEmerald
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "2. PERSÖNLICHEN MASTER-CODE VERWALTEN",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = customMasterInput,
                        onValueChange = { customMasterInput = it.uppercase() },
                        label = { Text("Aktiver Master-Code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    AetherFocusableGlassCard(
                        onClick = { viewModel.updateMasterCode(customMasterInput) }
                    ) {
                        Text(
                            text = "SPEICHERN",
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = AetherColors.IceBlue
                            ),
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)
                        )
                    }
                }

                if (state.masterCodeStatusMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.masterCodeStatusMessage!!,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = AetherColors.NeonEmerald
                        )
                    )
                }
            }
        }
    }
}