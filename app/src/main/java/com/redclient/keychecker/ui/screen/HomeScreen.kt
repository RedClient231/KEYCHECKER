package com.redclient.keychecker.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.redclient.keychecker.card.CardUtils
import com.redclient.keychecker.data.StripeMode
import com.redclient.keychecker.stripe.StripeOperation
import com.redclient.keychecker.ui.HomeViewModel
import com.redclient.keychecker.ui.OperationResult
import com.redclient.keychecker.ui.ResultColor

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var liveConfirmFor by remember { mutableStateOf<StripeOperation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KeyChecker") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = if (state.mode == StripeMode.LIVE) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
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
            ModeBanner(mode = state.mode, onModeChange = viewModel::setMode)

            CardForm(state = state, vm = viewModel)

            ValidationStrip(state = state)

            TestCardsSection(visible = state.mode == StripeMode.TEST, onPick = viewModel::fillTestCard)

            OperationButtons(
                state = state,
                onClick = { op ->
                    if (state.mode == StripeMode.LIVE && op.touchesBank) {
                        liveConfirmFor = op
                    } else {
                        viewModel.runOperation(op)
                    }
                },
            )

            state.result?.let { ResultPanel(it) }

            state.message?.let { msg ->
                Snackbar(
                    modifier = Modifier.fillMaxWidth(),
                    action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
                ) { Text(msg) }
            }

            DocsBlock()

            Spacer(Modifier.height(48.dp))
        }
    }

    liveConfirmFor?.let { op ->
        AlertDialog(
            onDismissRequest = { liveConfirmFor = null },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Confirm LIVE-mode operation") },
            text = {
                Column {
                    Text("You are about to run this against the real Stripe account:")
                    Spacer(Modifier.height(8.dp))
                    Text(op.title, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(op.explanation)
                    if (op.movesMoney) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This call will MOVE REAL MONEY.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val captured = op
                    liveConfirmFor = null
                    viewModel.runOperation(captured)
                }) { Text("Run anyway", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { liveConfirmFor = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ModeBanner(mode: StripeMode, onModeChange: (StripeMode) -> Unit) {
    val live = mode == StripeMode.LIVE
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (live) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (live) "LIVE MODE - REAL MONEY" else "TEST MODE - sandbox",
                color = if (live) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (live)
                    "Calls will hit real banks. Stripe Radar may flag your account if you submit many distinct cards."
                else
                    "Calls hit Stripe's sandbox. Use the test card buttons below.",
                color = if (live) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Test",
                    color = if (live) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = if (!live) FontWeight.Bold else FontWeight.Normal,
                )
                Switch(
                    checked = live,
                    onCheckedChange = { onModeChange(if (it) StripeMode.LIVE else StripeMode.TEST) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text(
                    "Live",
                    color = if (live) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = if (live) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun CardForm(state: com.redclient.keychecker.ui.HomeUiState, vm: HomeViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Card", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                AssistChip(onClick = {}, label = { Text(state.brand.displayName) })
            }
            OutlinedTextField(
                value = CardUtils.formatPan(state.card.number),
                onValueChange = { vm.onCardNumberChange(it) },
                label = { Text("Card number") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(
                        when {
                            state.card.number.isEmpty() -> "Enter a card number to begin"
                            state.luhnOk -> "Structurally valid"
                            else -> "Invalid Luhn / length"
                        }
                    )
                },
                isError = state.card.number.isNotEmpty() && !state.luhnOk,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.card.expMonth,
                    onValueChange = vm::onExpMonthChange,
                    label = { Text("MM") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    isError = state.card.expMonth.isNotEmpty() && !state.expiryOk,
                )
                OutlinedTextField(
                    value = state.card.expYear,
                    onValueChange = vm::onExpYearChange,
                    label = { Text("YYYY") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    isError = state.card.expYear.isNotEmpty() && !state.expiryOk,
                )
                OutlinedTextField(
                    value = state.card.cvc,
                    onValueChange = vm::onCvcChange,
                    label = { Text("CVC") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.weight(1f),
                    isError = state.card.cvc.isNotEmpty() && !state.cvcOk,
                )
            }
        }
    }
}

@Composable
private fun ValidationStrip(state: com.redclient.keychecker.ui.HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ValidationChip("Luhn", state.luhnOk)
        ValidationChip("Expiry", state.expiryOk)
        ValidationChip("CVC", state.cvcOk)
    }
}

@Composable
private fun ValidationChip(label: String, ok: Boolean) {
    AssistChip(
        onClick = {},
        label = { Text(if (ok) "$label OK" else label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (ok) Color(0xFFC8E6C9) else Color(0xFFFFCDD2),
        ),
    )
}

@Composable
private fun TestCardsSection(visible: Boolean, onPick: (String) -> Unit) {
    if (!visible) return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Stripe test cards", style = MaterialTheme.typography.titleMedium)
            Text(
                "These are scripted to produce specific outcomes. Tap to fill the form.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRowSimple {
                TestCardChip("Visa success", "4242424242424242", onPick)
                TestCardChip("Mastercard", "5555555555554444", onPick)
                TestCardChip("Amex", "378282246310005", onPick)
                TestCardChip("Insufficient funds", "4000000000009995", onPick)
                TestCardChip("Generic decline", "4000000000000002", onPick)
                TestCardChip("Stolen card", "4000000000009979", onPick)
                TestCardChip("Expired card", "4000000000000069", onPick)
                TestCardChip("3DS required", "4000002500003155", onPick)
            }
        }
    }
}

@Composable
private fun TestCardChip(label: String, pan: String, onPick: (String) -> Unit) {
    AssistChip(onClick = { onPick(pan) }, label = { Text(label) })
}

@Composable
private fun FlowRowSimple(content: @Composable () -> Unit) {
    // Simple two-column flow without depending on accompanist.
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun OperationButtons(state: com.redclient.keychecker.ui.HomeUiState, onClick: (StripeOperation) -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Operations", style = MaterialTheme.typography.titleMedium)
            StripeOperation.values().forEach { op ->
                OperationButton(op = op, state = state, onClick = onClick)
            }
        }
    }
}

@Composable
private fun OperationButton(op: StripeOperation, state: com.redclient.keychecker.ui.HomeUiState, onClick: (StripeOperation) -> Unit) {
    val running = state.isLoading && state.activeOperation == op
    Column {
        Button(
            onClick = { onClick(op) },
            enabled = (state.canSubmit || running) && !state.isLoading,
            modifier = Modifier.fillMaxWidth(),
            colors = if (state.mode == StripeMode.LIVE && op.movesMoney)
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            else ButtonDefaults.buttonColors(),
        ) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(op.title)
        }
        Text(op.explanation, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))
    }
}

@Composable
private fun ResultPanel(result: OperationResult) {
    val bg = when (result.statusColor) {
        ResultColor.SUCCESS -> Color(0xFFC8E6C9)
        ResultColor.WARNING -> Color(0xFFFFE0B2)
        ResultColor.ERROR -> Color(0xFFFFCDD2)
        ResultColor.NEUTRAL -> Color(0xFFE0E0E0)
    }
    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
        Column(Modifier.padding(16.dp)) {
            Text("Result: ${result.operation.title}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("HTTP ${result.httpStatus ?: "-"}  •  ${result.statusLabel}", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(result.summary)
            Spacer(Modifier.height(12.dp))
            Text("Raw response", style = MaterialTheme.typography.labelMedium)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF263238), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                Text(
                    result.rawJson,
                    color = Color(0xFFEEFFEE),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DocsBlock() {
    Card {
        Column(Modifier.padding(16.dp)) {
            Text("How to read results", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "• Tokenize success only proves the number is structurally valid.\n" +
                    "• SetupIntent succeeded = bank acknowledged the card.\n" +
                    "• PaymentIntent succeeded = funds were available.\n" +
                    "• status=requires_action means 3DS is needed (this minimal app does not handle 3DS).\n" +
                    "• decline_code reveals the bank's reason.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
