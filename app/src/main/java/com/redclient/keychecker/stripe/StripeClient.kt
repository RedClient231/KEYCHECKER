package com.redclient.keychecker.stripe

import android.content.Context
import com.redclient.keychecker.card.CardUtils
import com.stripe.android.Stripe
import com.stripe.android.core.exception.StripeException
import com.stripe.android.model.CardParams
import com.stripe.android.model.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/**
 * Wrapper over Stripe's REST API + Stripe Android SDK.
 *
 * Architecture decision (and trade-off):
 *  - This client uses the SECRET key directly from the Android app for
 *    SetupIntent / PaymentIntent creation. That is the architecture the
 *    user explicitly asked for ("manually add the stripe key inside the app").
 *    It is documented in README as NOT a production pattern.
 *
 * Why tokenization goes through the Stripe Android SDK (not raw OkHttp):
 *  - Stripe blocks raw-card POSTs to /v1/tokens from arbitrary HTTP clients
 *    with a publishable key. The error is:
 *      "This integration surface is unsupported for publishable key tokenization."
 *    See: https://support.stripe.com/questions/card-tokenization-restrictions-using-publishable-keys
 *  - The Stripe Android SDK is a recognized integration surface and is
 *    whitelisted, so we use it for the tokenize step. The token is then
 *    exchanged server-side (here, in-app with the secret key) for a
 *    PaymentMethod, which is allowed for any account.
 */
class StripeClient(
    private val context: Context,
    private val publishableKey: String,
    private val secretKey: String,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
        .build()

    /** Lazy because Stripe(context, key) registers the publishable key globally for the SDK session. */
    private val stripeSdk: Stripe by lazy { Stripe(context.applicationContext, publishableKey) }

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

    /**
     * Tokenize raw card data via the Stripe Android SDK.
     *
     * Uses [Stripe.createCardTokenSynchronous] which:
     *   - is recognized as a valid publishable-key integration surface;
     *   - performs the network call itself (must be off main thread, hence Dispatchers.IO);
     *   - throws [StripeException] on Stripe-side errors and any [Throwable] on network errors.
     */
    suspend fun tokenize(card: CardInput): Result = withContext(Dispatchers.IO) {
        require(publishableKey.isNotBlank()) { "Publishable key is required for tokenization." }
        try {
            val params = CardParams(
                number = CardUtils.digitsOnly(card.number),
                expMonth = card.expMonth,
                expYear = card.expYear,
                cvc = card.cvc,
            )
            val token: Token = stripeSdk.createCardTokenSynchronous(params)
                ?: return@withContext Result.Network("Stripe SDK returned a null token (unexpected).")

            val body = buildJsonObject {
                put("id", token.id)
                put("object", "token")
                put("livemode", token.livemode)
                put("used", token.used)
                put("created", token.created.time / 1000)
                token.card?.let { c ->
                    putJsonObject("card") {
                        put("id", c.id ?: "")
                        put("object", "card")
                        put("brand", c.brand.toString())
                        put("last4", c.last4 ?: "")
                        put("exp_month", c.expMonth ?: 0)
                        put("exp_year", c.expYear ?: 0)
                        put("country", c.country ?: "")
                        put("funding", c.funding?.toString() ?: "")
                    }
                }
            }
            Result.Success(200, body)
        } catch (e: StripeException) {
            val errBody = buildJsonObject {
                putJsonObject("error") {
                    put("type", e.stripeError?.type ?: "stripe_exception")
                    put("message", e.localizedMessage ?: e.message ?: "Stripe SDK error")
                    e.stripeError?.code?.let { put("code", it) }
                    e.stripeError?.declineCode?.let { put("decline_code", it) }
                    e.stripeError?.param?.let { put("param", it) }
                    e.requestId?.let { put("request_id", it) }
                }
            }
            Result.StripeError(e.statusCode.coerceAtLeast(400), errBody)
        } catch (t: Throwable) {
            Result.Network(t.message ?: t::class.java.simpleName)
        }
    }

    /**
     * Create a SetupIntent and confirm it with the tokenized card.
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
     * Create a PaymentIntent and confirm it with the tokenized card.
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
        }.build()
        execute("https://api.stripe.com/v1/payment_intents", form, secretKey)
    }

    private fun createPaymentMethodFromToken(tokenId: String): Result {
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
