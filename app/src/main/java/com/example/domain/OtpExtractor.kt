package com.example.domain

/**
 * Extracts OTP / one-time verification codes from SMS message bodies
 * without ever altering the original SMS content.
 */
object OtpExtractor {

    private val keywordRegex = Regex(
        pattern = """(?i)\b(otp|code|pin|passcode|verify|verification|auth|password|token|2fa|security|one[- ]time|confirm|login|signin)\b"""
    )

    private val googlePrefixRegex = Regex("""\bG-(\d{4,8})\b""")
    private val dashedCodeRegex = Regex("""\b(\d{3}-\d{3})\b""")
    private val contextCodeRegex = Regex(
        """(?i)(?:code|otp|pin|passcode|token|password|is|:)\s*(?:is\s*)?[:\-]?\s*([0-9]{4,8})\b"""
    )
    private val standaloneDigitsRegex = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

    fun extractOtp(body: String): String? {
        if (body.isBlank()) return null

        googlePrefixRegex.find(body)?.let { match ->
            return match.groupValues[1]
        }

        if (keywordRegex.containsMatchIn(body)) {
            contextCodeRegex.find(body)?.let { match ->
                val candidate = match.groupValues[1]
                if (!looksLikeYear(candidate)) return candidate
            }

            dashedCodeRegex.find(body)?.let { match ->
                return match.groupValues[1].replace("-", "")
            }

            val candidates = standaloneDigitsRegex.findAll(body)
                .map { it.groupValues[1] }
                .filterNot { looksLikeYear(it) || looksLikePhoneFragment(body, it) }
                .toList()

            // Prefer 6-digit codes, then 4..8 digit codes
            return candidates.firstOrNull { it.length == 6 } ?: candidates.firstOrNull()
        }

        return null
    }

    private fun looksLikeYear(digits: String): Boolean {
        if (digits.length != 4) return false
        val value = digits.toIntOrNull() ?: return false
        return value in 1990..2035
    }

    private fun looksLikePhoneFragment(body: String, digits: String): Boolean {
        val idx = body.indexOf(digits)
        if (idx <= 0) return false
        val prevChar = body[idx - 1]
        return prevChar == '+' || prevChar == '(' || prevChar == '-'
    }
}
