package ru.onlychill.mbondsfx.core.bond;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class CouponPeriods {
    private CouponPeriods() {
    }

    static List<DatedPeriod> copySortedAndValidate(List<DatedPeriod> periods) {
        Objects.requireNonNull(periods, "periods");
        if (periods.isEmpty()) {
            throw new IllegalArgumentException("A coupon must have at least one period");
        }
        List<DatedPeriod> copy = new ArrayList<>(periods);
        copy.forEach(period -> Objects.requireNonNull(period, "period"));
        copy.sort(Comparator.comparing(DatedPeriod::accrualStart)
                .thenComparing(DatedPeriod::accrualEnd));
        for (int i = 1; i < copy.size(); i++) {
            if (copy.get(i - 1).accrualEnd().isAfter(copy.get(i).accrualStart())) {
                throw new IllegalArgumentException("Coupon accrual periods must not overlap");
            }
        }
        return List.copyOf(copy);
    }
}
