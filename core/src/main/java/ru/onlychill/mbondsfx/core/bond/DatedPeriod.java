package ru.onlychill.mbondsfx.core.bond;

import java.time.LocalDate;
import java.util.Objects;

/** Accrual interval [accrualStart, accrualEnd) and its payment date. */
public record DatedPeriod(LocalDate accrualStart, LocalDate accrualEnd, LocalDate paymentDate) {
    public DatedPeriod {
        Objects.requireNonNull(accrualStart, "accrualStart");
        Objects.requireNonNull(accrualEnd, "accrualEnd");
        Objects.requireNonNull(paymentDate, "paymentDate");
        if (!accrualStart.isBefore(accrualEnd)) {
            throw new IllegalArgumentException("accrualStart must be before accrualEnd");
        }
        if (paymentDate.isBefore(accrualEnd)) {
            throw new IllegalArgumentException("paymentDate must not precede accrualEnd");
        }
    }
}
