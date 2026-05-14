package com.redclient.keychecker.stripe

/**
 * The four Stripe operations exposed to the user. Each represents a different
 * level of contact with the cardholder's issuing bank.
 */
enum class StripeOperation(val title: String, val explanation: String, val touchesBank: Boolean, val movesMoney: Boolean) {
    TOKENIZE(
        title = "Tokenize (PaymentMethod.create)",
        explanation = "Structural check only. Stripe validates Luhn + BIN + format. Does NOT contact the issuing bank. Never moves money.",
        touchesBank = false,
        movesMoney = false,
    ),
    VERIFY_ZERO(
        title = "Verify $0 (SetupIntent)",
        explanation = "Sends a $0 verification auth to the issuing bank. Confirms the card exists and is not blocked. No money moves. May briefly appear on the cardholder's statement.",
        touchesBank = true,
        movesMoney = false,
    ),
    AUTH_ONE(
        title = "Auth $1 hold (PaymentIntent, manual capture)",
        explanation = "Places a $1 hold on the card. Verifies funds are available. Hold auto-releases in ~7 days if not captured. Appears as 'pending' on the statement.",
        touchesBank = true,
        movesMoney = false,
    ),
    CHARGE_ONE(
        title = "Charge $1 (PaymentIntent, auto capture)",
        explanation = "Real $1 charge. In test mode this is scripted. In LIVE mode this actually debits the card. Refundable from the Stripe dashboard.",
        touchesBank = true,
        movesMoney = true,
    ),
}
