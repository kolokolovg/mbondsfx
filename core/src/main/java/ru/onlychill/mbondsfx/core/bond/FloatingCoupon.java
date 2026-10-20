package ru.onlychill.mbondsfx.core.bond;

import java.util.List;
import java.util.Objects;

public record FloatingCoupon(FloatingRateFormula formula, List<DatedPeriod> periods) implements Coupon {
    public FloatingCoupon {
        Objects.requireNonNull(formula, "formula");
        periods = CouponPeriods.copySortedAndValidate(periods);
    }
}
