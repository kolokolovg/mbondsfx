package ru.onlychill.mbondsfx.core.bond;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import ru.onlychill.mbondsfx.core.money.Money;

/** Authoritative per-unit coupon amounts; explicit periods may have no known amount yet. */
public record KnownCoupon(List<DatedPeriod> periods, List<Amount> amounts) implements Coupon {
    public KnownCoupon {
        periods = CouponPeriods.copySortedAndValidate(periods);
        Objects.requireNonNull(amounts, "amounts");
        Set<DatedPeriod> periodSet = new HashSet<>(periods);
        Set<DatedPeriod> seen = new HashSet<>();
        List<Amount> copy = new ArrayList<>(amounts);
        copy.forEach(amount -> {
            Objects.requireNonNull(amount, "amount");
            if (!periodSet.contains(amount.period())) {
                throw new IllegalArgumentException("Known amount period is not declared by this coupon");
            }
            if (!seen.add(amount.period())) {
                throw new IllegalArgumentException("Duplicate known amount for coupon period");
            }
        });
        copy.sort(Comparator.comparing(amount -> amount.period().accrualStart()));
        amounts = List.copyOf(copy);
    }

    /** Convenience form where all periods are already known amounts. */
    public KnownCoupon(List<Amount> amounts) {
        this(periodsFrom(amounts), amounts);
    }

    public record Amount(DatedPeriod period, Money amountPerUnit) {
        public Amount {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(amountPerUnit, "amountPerUnit");
        }
    }

    private static List<DatedPeriod> periodsFrom(List<Amount> amounts) {
        Objects.requireNonNull(amounts, "amounts");
        return amounts.stream().map(amount -> Objects.requireNonNull(amount, "amount").period()).toList();
    }
}
