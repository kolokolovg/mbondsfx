package ru.onlychill.mbondsfx.core.bond;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record FixedCoupon(BigDecimal annualRate, List<DatedPeriod> periods) implements Coupon {
    public FixedCoupon {
        Objects.requireNonNull(annualRate, "annualRate");
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("Fixed coupon annualRate must be non-negative");
        }
        periods = CouponPeriods.copySortedAndValidate(periods);
    }
}
