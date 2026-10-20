package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Generic index-linked coupon formula; it is not an issuer-specific market convention. */
public record IndexLinkedCoupon(BigDecimal annualRate, IndexationRule indexation,
                                ReferenceDate referenceDate, List<DatedPeriod> periods) implements Coupon {
    public IndexLinkedCoupon {
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(indexation, "indexation");
        Objects.requireNonNull(referenceDate, "referenceDate");
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("Index-linked coupon annualRate must be non-negative");
        }
        periods = CouponPeriods.copySortedAndValidate(periods);
    }

    public enum ReferenceDate {
        PERIOD_START,
        PERIOD_END,
        CALCULATION_DATE
    }
}
