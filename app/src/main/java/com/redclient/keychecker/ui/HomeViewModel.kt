package com.redclient.keychecker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.redclient.keychecker.KeyCheckerApp
import com.redclient.keychecker.card.CardUtils
import com.redclient.keychecker.data.KeySlot
import com.redclient.keychecker.data.SecureStore
import com.redclient.keychecker.data.StripeMode
import com.redclient.keychecker.stripe.StripeClient
import com.redclient.keychecker.stripe.StripeOperation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class CardForm(
    val number: String = "",
    val expMonth: String = "",
    val expYear: String = "",
    val cvc: String = "",
)

data class OperationResult(
    val operation: StripeOperation,
    val mode: StripeMode,
    val httpStatus: Int?,
    val statusLabel: String,
    val statusColor: ResultColor,
    val summary: String,
    val rawJson: String,
)

enum class ResultColor { SUCCESS, WARNING, ERROR, NEUTRAL }

data class HomeUiState(
    val mode: StripeMode = StripeMode.TEST,
    val card: CardForm = CardForm(),
    val brand: CardUtils.Brand = CardUtils.Brand.UNKNOWN,
    val luhnOk: Boolean = false,
    val expiryOk: Boolean = false,
    val cvcOk: Boolean = false,
    val canSubmit: Boolean = false,
    val isLoading: Boolean = false,
    val activeOperation: StripeOperation? = null,
    val result: OperationResult? = null,
    val message: String? = null,
)

class HomeViewModel : ViewModel() {

    private val store: SecureStore = KeyCheckerApp.get().secureStore
    private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(HomeUiState(mode = store.getMode()))
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun setMode(mode: StripeMode) {
        store.setMode(mode)
        _state.update { it.copy(mode = mode, message = null) }
    }

    fun onCardChange(transform: (CardForm) -> CardForm) {
        _state.update { current ->
            val newCard = transform(current.card)
            recomputeValidation(current.copy(card = newCard))
        }
    }

    fun onCardNumberChange(value: String) = onCardChange { it.copy(number = CardUtils.digitsOnly(value).take(19)) }
    fun onExpMonthChange(value: String) = onCardChange { it.copy(expMonth = value.filter(Char::isDigit).take(2)) }
    fun onExpYearChange(value: String) = onCardChange { it.copy(expYear = value.filter(Char::isDigit).take(4)) }
    fun onCvcChange(value: String) = onCardChange { it.copy(cvc = value.filter(Char::isDigit).take(4)) }

    fun fillTestCard(pan: String) {
        // Test cards: any future date and any 3-digit CVC are accepted by Stripe in test mode.
        onCardChange { CardForm(number = pan, expMonth = "12", expYear = "2030", cvc = "123") }
    }

    private fun recomputeValidation(s: HomeUiState): HomeUiState {
        val brand = CardUtils.detectBrand(s.card.number)
        val luhn = CardUtils.luhnValid(s.card.number) && CardUtils.isLengthValidForBrand(s.card.number, brand)
        val month = s.card.expMonth.toIntOrNull() ?: 0
        val year = s.card.expYear.toIntOrNull() ?: 0
        val expiry = CardUtils.isExpiryValid(month, year)
        val cvc = CardUtils.isCvcValidForBrand(s.card.cvc, brand)
        return s.copy(
            brand = brand,
            luhnOk = luhn,
            expiryOk = expiry,
            cvcOk = cvc,
            canSubmit = luhn && expiry && cvc && !s.isLoading,
        )
    }

    fun runOperation(op: StripeOperation) {
        val s = _state.value
        if (!s.canSubmit) {
            _state.update { it.copy(message = "Card data is not yet valid (Luhn / expiry / CVC).") }
            return
        }
        val pk = store.getKey(KeySlot.publishable(s.mode))
        val sk = store.getKey(KeySlot.secret(s.mode))
        if (pk.isBlank() || sk.isBlank()) {
            _state.update { it.copy(message = "Missing ${s.mode.name} keys. Open Settings and paste both pk_ and sk_.") }
            return
        }
        if (!pk.startsWith(KeySlot.publishable(s.mode).expectedPrefix) ||
            !sk.startsWith(KeySlot.secret(s.mode).expectedPrefix)
        ) {
            _state.update { it.copy(message = "Key prefix mismatch for ${s.mode.name} mode. Check Settings.") }
            return
        }

        _state.update { it.copy(isLoading = true, activeOperation = op, result = null, message = null, canSubmit = false) }

        val appContext = KeyCheckerApp.get().applicationContext
        viewModelScope.launch {
            val client = StripeClient(context = appContext, publishableKey = pk, secretKey = sk)
            val cardInput = StripeClient.CardInput(
                number = s.card.number,
                expMonth = s.card.expMonth.toInt(),
                expYear = s.card.expYear.toInt().let { if (it < 100) 2000 + it else it },
                cvc = s.card.cvc,
            )
            val raw = when (op) {
                StripeOperation.TOKENIZE -> client.tokenize(cardInput)
                StripeOperation.VERIFY_ZERO -> client.setupIntentVerify(cardInput)
                StripeOperation.AUTH_ONE -> client.paymentIntent(cardInput, amountCents = 100, manualCapture = true)
                StripeOperation.CHARGE_ONE -> client.paymentIntent(cardInput, amountCents = 100, manualCapture = false)
            }
            val result = interpret(op, s.mode, raw)
            _state.update { recomputeValidation(it.copy(isLoading = false, result = result)) }
        }
    }

    private fun interpret(op: StripeOperation, mode: StripeMode, raw: StripeClient.Result): OperationResult = when (raw) {
        is StripeClient.Result.Network -> OperationResult(
            operation = op,
            mode = mode,
            httpStatus = null,
            statusLabel = "Network error",
            statusColor = ResultColor.ERROR,
            summary = raw.message,
            rawJson = "(no body)",
        )
        is StripeClient.Result.StripeError -> {
            val err = raw.body["error"]?.let { it as? JsonObject }
            val errType = err?.get("type")?.jsonPrimitive?.contentOrNullSafe() ?: "stripe_error"
            val errMsg = err?.get("message")?.jsonPrimitive?.contentOrNullSafe() ?: "Stripe returned an error."
            val declineCode = err?.get("decline_code")?.jsonPrimitive?.contentOrNullSafe()
            OperationResult(
                operation = op,
                mode = mode,
                httpStatus = raw.httpStatus,
                statusLabel = if (declineCode != null) "Declined: $declineCode" else "Error: $errType",
                statusColor = ResultColor.ERROR,
                summary = errMsg,
                rawJson = prettyJson.encodeToString(JsonElement.serializer(), raw.body),
            )
        }
        is StripeClient.Result.Success -> {
            val obj = raw.body
            val status = obj["status"]?.jsonPrimitive?.contentOrNullSafe()
            val outcome = (obj["charges"] as? JsonObject)
                ?.get("data")
                // PaymentIntent shape uses `latest_charge` in modern Stripe; safest to look at `status` + outcome on intents.
            val color = when {
                status == null && obj.containsKey("id") -> ResultColor.SUCCESS // tokens.create
                status == "succeeded" || status == "requires_capture" -> ResultColor.SUCCESS
                status == "requires_action" || status == "requires_payment_method" -> ResultColor.WARNING
                else -> ResultColor.NEUTRAL
            }
            val label = status ?: "ok"
            val id = obj["id"]?.jsonPrimitive?.contentOrNullSafe() ?: ""
            OperationResult(
                operation = op,
                mode = mode,
                httpStatus = raw.httpStatus,
                statusLabel = label,
                statusColor = color,
                summary = if (id.isNotBlank()) "id = $id" else "OK",
                rawJson = prettyJson.encodeToString(JsonElement.serializer(), obj),
            )
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
    if (isString) content else content.takeIf { it.isNotBlank() && it != "null" }
