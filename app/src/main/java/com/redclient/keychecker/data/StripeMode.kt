package com.redclient.keychecker.data

enum class StripeMode { TEST, LIVE }

enum class KeySlot(val prefKey: String, val expectedPrefix: String, val mode: StripeMode, val isPublishable: Boolean) {
    PK_TEST("pk_test", "pk_test_", StripeMode.TEST, true),
    SK_TEST("sk_test", "sk_test_", StripeMode.TEST, false),
    PK_LIVE("pk_live", "pk_live_", StripeMode.LIVE, true),
    SK_LIVE("sk_live", "sk_live_", StripeMode.LIVE, false);

    companion object {
        fun publishable(mode: StripeMode): KeySlot =
            if (mode == StripeMode.TEST) PK_TEST else PK_LIVE

        fun secret(mode: StripeMode): KeySlot =
            if (mode == StripeMode.TEST) SK_TEST else SK_LIVE
    }
}
