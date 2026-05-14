package com.redclient.keychecker.card

/**
 * Pure-Kotlin card structural validation. No network, no Stripe.
 * Use this BEFORE any Stripe call to avoid wasting auth attempts on typos.
 */
object CardUtils {

    enum class Brand(val displayName: String, val validLengths: IntRange, val cvcLength: IntRange) {
        VISA("Visa", 13..19, 3..3),
        MASTERCARD("Mastercard", 16..16, 3..3),
        AMEX("American Express", 15..15, 4..4),
        DISCOVER("Discover", 16..19, 3..3),
        JCB("JCB", 16..19, 3..3),
        DINERS("Diners Club", 14..19, 3..3),
        UNIONPAY("UnionPay", 16..19, 3..3),
        UNKNOWN("Unknown", 12..19, 3..4),
    }

    fun digitsOnly(input: String): String = input.filter { it.isDigit() }

    fun detectBrand(pan: String): Brand {
        val n = digitsOnly(pan)
        if (n.isEmpty()) return Brand.UNKNOWN
        val first6 = n.take(6).padEnd(6, '0').toIntOrNull() ?: 0
        val first4 = n.take(4).padEnd(4, '0').toIntOrNull() ?: 0
        val first2 = n.take(2).padEnd(2, '0').toIntOrNull() ?: 0

        return when {
            n.startsWith("4") -> Brand.VISA
            // Mastercard: 51-55 OR 2221-2720
            first2 in 51..55 -> Brand.MASTERCARD
            first4 in 2221..2720 -> Brand.MASTERCARD
            // Amex: 34, 37
            first2 == 34 || first2 == 37 -> Brand.AMEX
            // Discover: 6011, 65, 644-649, 622126-622925
            n.startsWith("6011") -> Brand.DISCOVER
            first2 == 65 -> Brand.DISCOVER
            first4 in 6440..6499 -> Brand.DISCOVER
            first6 in 622126..622925 -> Brand.DISCOVER
            // JCB: 3528-3589
            first4 in 3528..3589 -> Brand.JCB
            // Diners Club: 300-305, 36, 38, 39
            first4 in 3000..3059 -> Brand.DINERS
            first2 == 36 || first2 == 38 || first2 == 39 -> Brand.DINERS
            // UnionPay: 62
            first2 == 62 -> Brand.UNIONPAY
            else -> Brand.UNKNOWN
        }
    }

    /** Standard Luhn / mod-10 checksum. */
    fun luhnValid(pan: String): Boolean {
        val digits = digitsOnly(pan)
        if (digits.length < 12) return false
        var sum = 0
        var alternate = false
        for (i in digits.length - 1 downTo 0) {
            var d = digits[i] - '0'
            if (alternate) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            alternate = !alternate
        }
        return sum % 10 == 0
    }

    fun isLengthValidForBrand(pan: String, brand: Brand = detectBrand(pan)): Boolean =
        digitsOnly(pan).length in brand.validLengths

    fun isCvcValidForBrand(cvc: String, brand: Brand): Boolean {
        val n = digitsOnly(cvc)
        return n.length in brand.cvcLength
    }

    /** Expiry must be a future month (or current month). */
    fun isExpiryValid(month: Int, year: Int): Boolean {
        if (month !in 1..12) return false
        val fullYear = if (year < 100) 2000 + year else year
        val cal = java.util.Calendar.getInstance()
        val curYear = cal.get(java.util.Calendar.YEAR)
        val curMonth = cal.get(java.util.Calendar.MONTH) + 1
        return when {
            fullYear < curYear -> false
            fullYear == curYear && month < curMonth -> false
            fullYear > curYear + 30 -> false
            else -> true
        }
    }

    fun formatPan(pan: String): String {
        val digits = digitsOnly(pan)
        val brand = detectBrand(digits)
        // Amex: 4-6-5 grouping, otherwise groups of 4.
        val groups = if (brand == Brand.AMEX) listOf(4, 6, 5) else List(5) { 4 }
        val sb = StringBuilder()
        var idx = 0
        for (g in groups) {
            if (idx >= digits.length) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(digits.substring(idx, (idx + g).coerceAtMost(digits.length)))
            idx += g
        }
        return sb.toString()
    }
}
