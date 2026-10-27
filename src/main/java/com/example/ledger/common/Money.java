package com.example.ledger.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact decimal handling for two-decimal currencies (spec 4.2, 5.2: NUMERIC(19,2)).
 * Amounts are never rounded: a value with more than two fractional digits is rejected.
 */
public final class Money {

    public static final int SCALE = 2;
    /** NUMERIC(19,2) allows at most 17 integer digits. */
    public static final int MAX_INTEGER_DIGITS = 17;

    private Money() {
    }

    /** True when the amount fits NUMERIC(19,2) without rounding. */
    public static boolean isRepresentable(BigDecimal amount) {
        if (amount == null) {
            return false;
        }
        BigDecimal stripped = amount.stripTrailingZeros();
        int scale = Math.max(stripped.scale(), 0);
        int integerDigits = stripped.precision() - stripped.scale();
        return scale <= SCALE && integerDigits <= MAX_INTEGER_DIGITS;
    }

    /** Normalizes to scale 2. Throws if that would require rounding. */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(SCALE, RoundingMode.UNNECESSARY);
    }

    /** Canonical string form, e.g. "150.00". */
    public static String format(BigDecimal amount) {
        return normalize(amount).toPlainString();
    }
}
