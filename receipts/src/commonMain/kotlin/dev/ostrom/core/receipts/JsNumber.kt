package dev.ostrom.core.receipts

/**
 * Numbers as ECMAScript `Number.prototype.toString` writes them (what `JSON.stringify` writes): the shortest decimal
 * that reads back as the same double, closest to it on a tie of length; integers below `1e21` in full, exponents as
 * `1e+21` and `1e-7`, `-0` as `0`. Kotlin's own `Double.toString()` differs (`1.0E21`), hence this exact
 * implementation: the double is expanded to its exact decimal value with integer arithmetic, and a candidate is kept
 * when it lies inside the double's rounding interval (round half to even), with no reliance on the platform's float
 * parser.
 */
internal object JsNumber {

    private const val TWO_POW_53 = 9_007_199_254_740_992.0

    /** `n` (the decimal exponent of ECMAScript's `Number::toString`) up to which a number is written without `e`. */
    private const val MAX_FIXED_N = 21

    /** `n` from which a number below 1 is written without `e`: `0.000001` has `n = -5`, `1e-7` has `n = -6`. */
    private const val MIN_FIXED_N = -5

    /**
     * Formats [value] as ECMAScript does.
     *
     * @param value The number.
     *
     * @return Its ECMAScript text; `null` for NaN and infinities, as `JSON.stringify` writes them.
     */
    fun format(value: Double): String = when {
        !value.isFinite() -> "null"
        value == 0.0 -> "0"
        value < 0 -> "-" + formatPositive(-value)
        else -> formatPositive(value)
    }

    private fun formatPositive(abs: Double): String =
        if (abs < TWO_POW_53 && abs == abs.toLong().toDouble()) {
            abs.toLong().toString()
        } else {
            val decimal = shortest(abs)
            layout(decimal.digits, decimal.pos)
        }

    /** Writes `0.digits × 10^n` as ECMAScript's `Number::toString` steps 6 to 10 do. */
    private fun layout(digits: String, n: Int): String {
        val k = digits.length
        return when {
            n in k..MAX_FIXED_N -> digits + "0".repeat(n - k)
            n in 1..MAX_FIXED_N -> digits.substring(0, n) + "." + digits.substring(n)
            n in MIN_FIXED_N..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val exponent = if (e >= 0) "+$e" else "$e"
                val mantissa = if (k == 1) digits else "${digits[0]}.${digits.substring(1)}"
                "${mantissa}e$exponent"
            }
        }
    }

    /** Returns the shortest decimal of [abs] (> 0, finite) that reads back as [abs]. */
    private fun shortest(abs: Double): Decimal {
        val interval = RoundingInterval.of(abs)
        return (1 until interval.exact.digits.length).firstNotNullOfOrNull(interval::candidate) ?: interval.exact
    }
}

/** A positive decimal `0.digits × 10^pos`, digits without leading or trailing zeros. */
private data class Decimal(val digits: String, val pos: Int) : Comparable<Decimal> {
    override fun compareTo(other: Decimal): Int =
        if (pos != other.pos) {
            pos.compareTo(other.pos)
        } else {
            // same exponent: equal-length digit strings compare as the numbers they write
            val length = maxOf(digits.length, other.digits.length)
            digits.padEnd(length, '0').compareTo(other.digits.padEnd(length, '0'))
        }
}

/**
 * The decimals that read back as one double: strictly between [low] and [high], or on a bound when the mantissa is
 * even ([inclusive], round half to even).
 *
 * @property exact The exact decimal value of the double.
 */
private class RoundingInterval(
    val exact: Decimal,
    private val low: Decimal,
    private val high: Decimal,
    private val inclusive: Boolean,
) {

    private fun contains(candidate: Decimal): Boolean {
        val toLow = candidate.compareTo(low)
        val toHigh = candidate.compareTo(high)
        return (toLow > 0 && toHigh < 0) || ((toLow == 0 || toHigh == 0) && inclusive)
    }

    /**
     * The [k]-digit decimal that reads back as the double, the closest to it when both neighbours do; null when
     * neither does.
     *
     * The closest is decided on the digits after the k-th: below `5` the lower neighbour, otherwise the upper one.
     * An exact tie (the rest is exactly `5`) never happens with both neighbours inside: a double whose exact value
     * ends in 5 at the 10^-j place is a multiple of 2^-j, so its half-gap is at most 2^-(j+1), below the
     * 5 × 10^-j between it and either neighbour; and every integer double of 2^53 or more is even.
     */
    fun candidate(k: Int): Decimal? {
        val prefix = exact.digits.substring(0, k)
        val lower = normalize(prefix, exact.pos)
        val upper = increment(prefix, exact.pos)
        val lowerInside = contains(lower)
        val upperInside = contains(upper)
        return when {
            lowerInside && upperInside -> if (exact.digits.substring(k) < "5") lower else upper
            lowerInside -> lower
            upperInside -> upper
            else -> null
        }
    }

    companion object {
        private const val MANTISSA_BITS = 52
        private const val EXPONENT_MASK = 0x7FFL
        private const val FRACTION_MASK = 0xFFFFFFFFFFFFFL
        private const val SUBNORMAL_EXPONENT = -1074
        private const val EXPONENT_BIAS = 1075

        /** Below a power of two the gap is half the gap above: the low bound is `(4m - 1) × 2^(e - 2)`. */
        private const val QUARTER_STEPS = 4L

        fun of(abs: Double): RoundingInterval {
            val bits = abs.toRawBits()
            val exponentField = ((bits ushr MANTISSA_BITS) and EXPONENT_MASK).toInt()
            val fraction = bits and FRACTION_MASK
            val subnormal = exponentField == 0
            val mantissa = if (subnormal) fraction else fraction or (1L shl MANTISSA_BITS)
            val exponent = if (subnormal) SUBNORMAL_EXPONENT else exponentField - EXPONENT_BIAS
            val low = if (fraction == 0L && exponentField > 1) {
                decimalOf(QUARTER_STEPS * mantissa - 1, exponent - 2)
            } else {
                decimalOf(2 * mantissa - 1, exponent - 1)
            }
            return RoundingInterval(
                exact = decimalOf(mantissa, exponent),
                low = low,
                high = decimalOf(2 * mantissa + 1, exponent - 1),
                inclusive = mantissa % 2 == 0L,
            )
        }
    }
}

/** Adds one unit in the last place of `0.digits × 10^pos`. */
private fun increment(digits: String, pos: Int): Decimal {
    val chars = digits.toCharArray()
    var i = chars.size - 1
    while (i >= 0 && chars[i] == '9') {
        chars[i] = '0'
        i--
    }
    return if (i < 0) {
        normalize("1" + chars.concatToString(), pos + 1)
    } else {
        chars[i] = chars[i] + 1
        normalize(chars.concatToString(), pos)
    }
}

/** Strips the zeros around `0.digits × 10^pos`; [digits] always has a non-zero digit here (every value is > 0). */
private fun normalize(digits: String, pos: Int): Decimal {
    val leading = digits.indexOfFirst { it != '0' }
    return Decimal(digits.substring(leading).trimEnd('0'), pos - leading)
}

private const val FIVE = 5

/** The exact decimal value of `m × 2^e`, for `m > 0`. */
private fun decimalOf(m: Long, e: Int): Decimal {
    val big = BigDecimalDigits(m)
    return if (e >= 0) {
        big.multiplyByPowerOf(2, e)
        val text = big.toDecimalString()
        normalize(text, text.length)
    } else {
        // m × 2^e = m × 5^-e / 10^-e
        big.multiplyByPowerOf(FIVE, -e)
        val text = big.toDecimalString()
        normalize(text, text.length + e)
    }
}

/** A non-negative integer in base 10^9 limbs, little-endian; only what [decimalOf] needs. */
private class BigDecimalDigits(value: Long) {
    // 5^1075 and 2^1024 fit in 85 limbs of 9 digits; the array grows if ever needed
    private var limbs = LongArray(INITIAL_LIMBS)
    private var size = 0

    init {
        var v = value
        while (v > 0) {
            push(v % BASE)
            v /= BASE
        }
    }

    private fun push(limb: Long) {
        if (size == limbs.size) limbs = limbs.copyOf(limbs.size * 2)
        limbs[size++] = limb
    }

    fun multiplyByPowerOf(factor: Int, power: Int) {
        var left = power
        // factor^chunk stays below 2^31, so limb × multiplier stays below 2^62
        val chunk = if (factor == 2) TWO_CHUNK else FIVE_CHUNK
        while (left > 0) {
            val step = minOf(chunk, left)
            var multiplier = 1L
            repeat(step) { multiplier *= factor }
            multiplySmall(multiplier)
            left -= step
        }
    }

    private fun multiplySmall(multiplier: Long) {
        var carry = 0L
        for (i in 0 until size) {
            val product = limbs[i] * multiplier + carry
            limbs[i] = product % BASE
            carry = product / BASE
        }
        while (carry > 0) {
            push(carry % BASE)
            carry /= BASE
        }
    }

    // never empty: the value is the mantissa of a non-zero double, times a power of 2 or 5
    fun toDecimalString(): String {
        val out = StringBuilder(size * LIMB_DIGITS)
        out.append(limbs[size - 1].toString())
        for (i in size - 2 downTo 0) out.append(limbs[i].toString().padStart(LIMB_DIGITS, '0'))
        return out.toString()
    }

    private companion object {
        const val BASE = 1_000_000_000L
        const val LIMB_DIGITS = 9
        const val INITIAL_LIMBS = 8
        const val TWO_CHUNK = 30
        const val FIVE_CHUNK = 13
    }
}
