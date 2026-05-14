package com.redclient.keychecker.stripe

import com.redclient.keychecker.card.CardUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Thin wrapper over Stripe's REST API.
 *
 * Architecture decision (and trade-off):
 *  - This client uses the SECRET key directly from the Android app. That is the
 *    architecture the user explicitly asked for ("manually add the stripe key
 *    inside the app"). It is documented in README as NOT a production pattern.
 *  - Real production apps put the secret key on a backend and only ship the
 *    publishable key to the device.
 *
 * Stripe note on raw card data:
 *  - By default, /v1/payment_methods rejects raw card data when the request is
 *    made with a secret key, unless the account has "raw card data API access"
 *    enabled (filed via Stripe support). So tokenize() uses /v1/tokens instead,
 *    which accepts raw card data with the publishable key. The token is then
 *    exchanged for a PaymentMethod or used directly on a PaymentIntent.
 */
class StripeClient(
    private val publishableKey: String,
    private val secretKey: String,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .build()

    data class CardInput(
        val number: String,
        val expMonth: Int,
        val expYear: Int,
        val cvc: String,
    )

    sealed class Result {
        data class Success(val httpStatus: Int, val body: JsonObject) : Result()
        data class StripeError(val httpStatus: Int, val body: JsonObject) : Result()
        data class Network(val message: String) : Result()
    }

    /** Tokenize raw card data using the PUBLISHABLE key against /v1/tokens. */
    suspend fun tokenize(card: CardInput): Result = withContext(Dispatchers.IO) {
        require(publishableKey.isNotBlank()) { "Publishable key is required for tokenization." }
        val form = FormBody.Builder()
            .add("card[number]", CardUtils.digitsOnly(card.number))
            .add("card[exp_month]", card.expMonth.toString())
            .add("card[exp_year]", card.expYear.toString())
            .add("card[cvc]", card.cvc)
            .build()
        execute("https://api.stripe.com/v1/tokens", form, publishableKey)
    }

    /**
     * Create a SetupIntent and confirm it with the given token.
     * SetupIntent confirmation triggers a $0 verification with the issuing bank.
     */
    suspend fun setupIntentVerify(card: CardInput): Result = withContext(Dispatchers.IO) {
        val token = tokenize(card)
        if (token !is Result.Success) return@withContext token
        val tokenId = token.body["id"]?.jsonPrimitive?.content
            ?: return@withContext Result.StripeError(token.httpStatus, token.body)

        val pmRes = createPaymentMethodFromToken(tokenId)
        if (pmRes !is Result.Success) return@withContext pmRes
        val pmId = pmRes.body["id"]?.jsonPrimitive?.content
            ?: return@withContext Result.StripeError(pmRes.httpStatus, pmRes.body)

        val form = FormBody.Builder()
            .add("payment_method", pmId)
            .add("confirm", "true")
            .add("usage", "off_session")
            .add("payment_method_types[]", "card")
            .build()
        execute("https://api.stripe.com/v1/setup_intents", form, secretKey)
    }

    /**
     * Create a PaymentIntent and confirm it with the given token.
     *
     * @param amountCents amount in the smallest currency unit (e.g. 100 = $1.00).
     * @param manualCapture if true, only authorize (place a hold). If false, auto-capture (real charge in live mode).
     * @param currency 3-letter ISO currency code.
     */
    suspend fun paymentIntent(
        card: CardInput,
        amountCents: Long,
        manualCapture: Boolean,
        currency: String = "usd",
    ): Result = withContext(Dispatchers.IO) {
        val token = tokenize(card)
        if (token !is Result.Success) return@withContext token
        val tokenId = token.body["id"]?.jsonPrimitive?.content
            ?: return@withContext Result.StripeError(token.httpStatus, token.body)

        val pmRes = createPaymentMethodFromToken(tokenId)
        if (pmRes !is Result.Success) return@withContext pmRes
        val pmId = pmRes.body["id"]?.jsonPrimitive?.content
            ?: return@withContext Result.StripeError(pmRes.httpStatus, pmRes.body)

        val form = FormBody.Builder().apply {
            add("amount", amountCents.toString())
            add("currency", currency)
            add("payment_method", pmId)
            add("confirm", "true")
            add("payment_method_types[]", "card")
            add("capture_method", if (manualCapture) "manual" else "automatic")
            // Disable redirect-based PMs so 3DS won't bounce out of our minimal app.
            add("automatic_payment_methods[enabled]", "false")
        }.build()
        execute("https://api.stripe.com/v1/payment_intents", form, secretKey)
    }

    private suspend fun createPaymentMethodFromToken(tokenId: String): Result {
        val form = FormBody.Builder()
            .add("type", "card")
            .add("card[token]", tokenId)
            .build()
        return execute("https://api.stripe.com/v1/payment_methods", form, secretKey)
    }

    private fun execute(url: String, body: okhttp3.RequestBody, key: String): Result {
        require(key.isNotBlank()) { "Stripe key is empty. Set it in Settings." }
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", "Bearer $key")
            .header("Stripe-Version", STRIPE_API_VERSION)
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val parsed: JsonElement = if (raw.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(raw)
                val obj = if (parsed is JsonObject) parsed else JsonObject(mapOf("raw" to parsed))
                if (resp.isSuccessful) Result.Success(resp.code, obj) else Result.StripeError(resp.code, obj)
            }
        } catch (t: Throwable) {
            Result.Network(t.message ?: t::class.java.simpleName)
        }
    }

    companion object {
        const val STRIPE_API_VERSION = "2024-12-18.acacia"
    }
}
