package app.pagefault.hyperllm.console.payment;

import app.pagefault.hyperllm.common.error.ErrorCode;
import app.pagefault.hyperllm.common.error.HyperLlmException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The two conversions between a channel's minor unit and micro-USD.
 *
 * <p>Both round up: a customer never receives less than the paid amount rounded to micro-USD, and
 * the channel never charges less than the SKU price. The rate is first rounded to the column's
 * eight decimal places, so the value used here is exactly the value stored on the order.
 */
public final class PaymentAmounts {

    private static final BigDecimal MICROS_PER_UNIT = BigDecimal.valueOf(10_000L);
    private static final int FX_SCALE = 8;

    private PaymentAmounts() {
    }

    public static BigDecimal canonicalRate(BigDecimal rate) {
        if (rate == null || rate.signum() <= 0) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A rate must be positive");
        }
        return rate.setScale(FX_SCALE, RoundingMode.HALF_UP);
    }

    /** ceil(pay_amount_minor x 10_000 x fx_rate). */
    public static long creditMicros(long payAmountMinor, BigDecimal canonicalRate) {
        if (payAmountMinor <= 0L) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A payment must be positive");
        }
        BigDecimal micros = BigDecimal.valueOf(payAmountMinor)
                .multiply(MICROS_PER_UNIT)
                .multiply(canonicalRate);
        return toLongCeiling(micros);
    }

    /** ceil(sku_price_micros / (10_000 x fx_rate)). */
    public static long payAmountMinor(long priceMicros, BigDecimal canonicalRate) {
        if (priceMicros <= 0L) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "A price must be positive");
        }
        BigDecimal minor = BigDecimal.valueOf(priceMicros)
                .divide(MICROS_PER_UNIT.multiply(canonicalRate), 12, RoundingMode.CEILING);
        return toLongCeiling(minor);
    }

    private static long toLongCeiling(BigDecimal value) {
        try {
            long amount = value.setScale(0, RoundingMode.CEILING).longValueExact();
            if (amount <= 0L || amount > (1L << 53) - 1L) {
                throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Amount is outside the exact range");
            }
            return amount;
        } catch (ArithmeticException ex) {
            throw new HyperLlmException(ErrorCode.BILLING_INVALID_AMOUNT, "Amount is outside the long range");
        }
    }
}
