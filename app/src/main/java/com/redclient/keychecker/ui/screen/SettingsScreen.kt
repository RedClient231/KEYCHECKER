package com.redclient.keychecker.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.redclient.keychecker.data.KeySlot
import com.redclient.keychecker.ui.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showSnackbar by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = {
            showSnackbar?.let { msg ->
                Snackbar(modifier = Modifier.padding(8.dp)) { Text(msg) }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Stripe API keys", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Find your keys at dashboard.stripe.com -> Developers -> API keys. " +
                            "Each mode (TEST / LIVE) has a publishable key (pk_) and a secret key (sk_). " +
                            "All four are stored on this device only, AES-256-GCM encrypted via Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            KeySlot.values().forEach { slot ->
                KeyField(
                    slot = slot,
                    value = state.keys[slot].orEmpty(),
                    onChange = { viewModel.update(slot, it) },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        showSnackbar = if (viewModel.save()) "Saved" else "One or more keys have an invalid prefix."
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
                OutlinedButton(
                    onClick = {
                        viewModel.clearAll()
                        showSnackbar = "Cleared all keys."
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Clear all") }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Security warning", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Holding a Stripe SECRET key on a mobile device is not a production architecture. " +
                            "If this device is rooted, lost, or its install package is extracted, the key " +
                            "can be exfiltrated. For real apps, keep secret keys on a server and ship only " +
                            "the publishable key to the client.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun KeyField(slot: KeySlot, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(slot.isPublishable) }
    val labelText = when (slot) {
        KeySlot.PK_TEST -> "Publishable test key  (pk_test_...)"
        KeySlot.SK_TEST -> "Secret test key  (sk_test_...)"
        KeySlot.PK_LIVE -> "Publishable live key  (pk_live_...)"
        KeySlot.SK_LIVE -> "Secret live key  (sk_live_...)"
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(labelText) },
        singleLine = true,
        visualTransformation = if (visible || slot.isPublishable) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            if (!slot.isPublishable) {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (visible) "Hide" else "Show",
                    )
                }
            }
        },
        supportingText = {
            val v = value.trim()
            val ok = v.isEmpty() || v.startsWith(slot.expectedPrefix)
            Text(
                if (v.isEmpty()) "Optional"
                else if (ok) "Looks valid"
                else "Must start with ${slot.expectedPrefix}",
                color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        },
        isError = value.isNotBlank() && !value.startsWith(slot.expectedPrefix),
        modifier = Modifier.fillMaxWidth(),
    )
}
