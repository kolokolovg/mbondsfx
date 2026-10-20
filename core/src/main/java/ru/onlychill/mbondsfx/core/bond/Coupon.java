package ru.onlychill.mbondsfx.core.bond;

import java.util.List;

public sealed interface Coupon permits FixedCoupon, FloatingCoupon, KnownCoupon, IndexLinkedCoupon {
    List<DatedPeriod> periods();
}
