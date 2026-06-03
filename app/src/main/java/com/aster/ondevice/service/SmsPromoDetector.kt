package com.aster.ondevice.service

/**
 * Fast rule-based promotional SMS classifier — no LLM needed.
 *
 * Indian DLT regulations require promotional sender IDs to end with "-P".
 * Transactional/Service IDs end with "-S" or "-T".
 * A keyword fallback catches promos from senders that don't follow the DLT format.
 */
object SmsPromoDetector {

    // DLT promotional sender suffix (e.g. AD-PVRVIP-P, AX-HDFCBN-P)
    private val PROMO_SUFFIX = Regex("""-[Pp]${'$'}""")

    // Keywords reliably found in promo bodies but not in transactional SMS
    private val PROMO_KEYWORDS = listOf(
        "% off", "% discount", "upto ", "up to ",
        "cashback", "free gift", "free recharge",
        "click here", "click now", "tap here",
        "redeem now", "redeem today",
        "valid till", "valid till ", "expires soon", "expiring",
        "limited time", "limited offer", "limited period",
        "exclusive offer", "special offer",
        "buy now", "shop now", "order now",
        "hurry!", "hurry,", "last chance",
        "deal of the day",
        "pre-approved loan", "pre-approved offer", "pre-approved",
        "instant loan", "quick loan", "personal loan offer",
        "you've won", "you have won", "congratulations! you",
        "selected for", "you are selected",
        "apply now for",
        "get quick access to funds",
        "born 1980", "born 19",   // age-targeted promos
        "secure your future",
        "download now", "install now",
    )

    /**
     * Returns true if the SMS is promotional and safe to auto-delete.
     */
    fun isPromo(sender: String, body: String): Boolean {
        // 1. DLT sender ID ends with -P → definitely promotional
        if (PROMO_SUFFIX.containsMatchIn(sender)) return true

        // 2. Keyword match — but only for non-bank/non-financial senders
        // Bank senders (HDFCBK, ICICI, SBIINB, etc.) should never be auto-deleted
        // even if body contains a matching word (e.g. "offer" in a CC bill reminder)
        if (isFinancialSender(sender)) return false

        val bodyLower = body.lowercase()
        return PROMO_KEYWORDS.any { bodyLower.contains(it) }
    }

    // Known financial/transactional sender patterns — never auto-delete these
    private val FINANCIAL_PATTERNS = listOf(
        "HDFC", "ICICI", "SBI", "AXIS", "KOTAK", "INDUS", "YES",
        "PAYTM", "PHONEPE", "GPAY", "AMAZON", "FLIPKART",
        "ZERODHA", "GROWW", "AMEX", "CITI",
        "AIRTEL", "JIO", "BSNL", "VODAFONE",   // telecom — bills are transactional
    )

    private fun isFinancialSender(sender: String): Boolean {
        val upper = sender.uppercase()
        return FINANCIAL_PATTERNS.any { upper.contains(it) }
    }
}
