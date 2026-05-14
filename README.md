# KeyChecker

An experimental Android 13+ app for exploring Stripe's API behavior across both test mode and live mode.

You enter your Stripe keys in Settings, type a card, and run one of four operations to see exactly what Stripe (and the issuing bank, in live mode) returns.

---

## ⚠️ Read this before installing

This app deliberately does something **production apps must not do**: it holds your Stripe **secret key** on the device.

- Anyone with access to the installed app can read the secret key out of `EncryptedSharedPreferences` if the device is rooted or the APK is forensically analyzed.
- Hardcoding or device-storing `sk_live_…` is the #1 way to get a Stripe account drained.
- **Use this app with `sk_test_` only.** If you experiment with `sk_live_`, treat that key as already burned.
- For real applications, keep secret keys on a backend and only ship the publishable key to the client.

The app encrypts keys with AES-256-GCM via Android Keystore and excludes them from cloud backup, but encryption-at-rest is not the same as never-having-the-key-in-the-binary.

This app also includes a **LIVE mode confirmation dialog** before every bank-touching call when in live mode. The first time you flip to LIVE, read the warnings carefully.

---

## What it does

The home screen lets you run four Stripe operations against any card you enter:

| Button | Endpoint | Hits the bank? | Moves money? |
|---|---|---|---|
| **Tokenize** | `POST /v1/tokens` | No | No |
| **Verify $0** | `POST /v1/setup_intents` (confirm) | Yes | No |
| **Auth $1 hold** | `POST /v1/payment_intents` (`capture_method=manual`) | Yes | No (places a hold) |
| **Charge $1** | `POST /v1/payment_intents` (`capture_method=automatic`) | Yes | **Yes (live mode only)** |

Each result panel shows:
- The HTTP status code
- A human-readable status (`succeeded`, `requires_capture`, `Declined: insufficient_funds`, etc.)
- The **raw JSON** Stripe returned, so you can read fields like `last_payment_error.decline_code`, `outcome.network_status`, `outcome.seller_message`.

---

## Test cards (auto-filled by the chips on screen)

In test mode these PANs are scripted by Stripe to produce specific outcomes:

| Card | Behavior |
|---|---|
| `4242 4242 4242 4242` | Successful charge |
| `5555 5555 5555 4444` | Mastercard success |
| `3782 822463 10005` | Amex success |
| `4000 0000 0000 9995` | Insufficient funds decline |
| `4000 0000 0000 0002` | Generic decline |
| `4000 0000 0000 9979` | Stolen card decline |
| `4000 0000 0000 0069` | Expired card decline |
| `4000 0025 0000 3155` | Requires 3DS authentication (this minimal app surfaces `requires_action` and stops; full 3DS handling needs the Stripe SDK PaymentSheet) |

Reference: [docs.stripe.com/testing](https://docs.stripe.com/testing)

---

## Get your Stripe keys

1. Sign in at [dashboard.stripe.com](https://dashboard.stripe.com).
2. Go to **Developers → API keys**.
3. Copy:
   - `pk_test_…` and `sk_test_…` from the **Test mode** view (toggle in the top right).
   - `pk_live_…` and `sk_live_…` from **Live mode** (only if you want to experiment with real charges).
4. In the app, tap the gear icon in the top bar and paste each into its slot.

---

## Build

### Locally

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK with platform 35 installed (the build will download Gradle 8.11.1 itself).

### Via GitHub Actions

Every push to `main` triggers `.github/workflows/android-build.yml`, which produces a debug APK as a workflow artifact named **keychecker-debug-apk**. Download it from the Actions tab.

---

## Stack

| | Version | Why |
|---|---|---|
| Kotlin | 2.1.0 | Stable; ships with the Compose compiler plugin baked in |
| AGP | 8.7.3 | Stable LTS-feel; AGP 9.x stack is bleeding-edge |
| Gradle | 8.11.1 | AGP 8.7 minimum |
| Compose BOM | 2024.12.01 | Latest stable as of build time |
| Material 3 | (BOM-managed) | M3 expressive components |
| Stripe Android SDK | 21.4.1 | Latest stable |
| OkHttp | 4.12.0 | REST client for Stripe API |
| kotlinx.serialization | 1.7.3 | JSON parsing |
| AndroidX Security Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| minSdk | 33 (Android 13) | Per requirement |
| compileSdk / targetSdk | 35 (Android 15) | Latest stable platform |

---

## Architecture

```
KeyCheckerApp (Application)
├── data/
│   ├── SecureStore           AES-256-GCM EncryptedSharedPreferences for 4 keys + mode
│   └── StripeMode, KeySlot   Enums binding mode <-> keys
├── card/
│   └── CardUtils             Pure-Kotlin Luhn + brand detection + expiry validation
├── stripe/
│   ├── StripeOperation       The four operations and their semantics
│   └── StripeClient          OkHttp REST wrapper:
│                              tokenize() (publishable key)
│                              setupIntentVerify() (secret key, $0 verify)
│                              paymentIntent(manual)       (secret key, $1 hold)
│                              paymentIntent(automatic)    (secret key, $1 charge)
└── ui/
    ├── MainActivity          Compose host
    ├── HomeViewModel         Card form + operation runner
    ├── SettingsViewModel     Key entry + persistence
    └── screen/
        ├── HomeScreen        Mode banner, card form, validation chips,
        │                     test-card chips, op buttons, raw-result panel
        └── SettingsScreen    4 key fields with prefix validation + show/hide
```

### Why `/v1/tokens` instead of `/v1/payment_methods` for tokenization

By default, Stripe blocks `/v1/payment_methods` requests that include raw card data when authorized with a secret key (you have to apply for "raw card data API access"). `/v1/tokens` accepts raw cards with a **publishable** key, which every Stripe account has. We then exchange the token for a `PaymentMethod` server-side via `card[token]=tok_...`, which is allowed for any account.

---

## Limitations and honest caveats

- **3DS is not handled.** A real `requires_action` flow needs the Stripe Android SDK's `PaymentLauncher` + a returnURL. This minimal app stops at surfacing the status and the raw JSON.
- **Card-testing detection.** Stripe Radar and the card networks watch for the pattern of "many distinct PANs from one account." If you submit large volumes of cards, expect your account to be flagged or locked. This is a Stripe policy enforcement, independent of this app.
- **No 0-amount auth fallback.** Some banks decline `$0` auths; in that case use the `$1` hold button instead.
- **Currency is hardcoded to USD.** Easy to extend; not a priority for an experiment app.
- **No release keystore.** The release build type signs with the debug key so CI can produce an installable APK; replace before any real distribution.
